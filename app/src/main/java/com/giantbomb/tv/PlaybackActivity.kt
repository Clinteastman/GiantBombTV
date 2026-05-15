package com.giantbomb.tv

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import android.content.ComponentName
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.giantbomb.tv.playback.PlaybackService
import com.google.common.util.concurrent.ListenableFuture
import androidx.mediarouter.app.MediaRouteButton
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.giantbomb.tv.data.GiantBombApi
import com.giantbomb.tv.data.PrefsManager
import com.giantbomb.tv.model.Video
import com.giantbomb.tv.util.DateFormat
import com.giantbomb.tv.util.DeviceUtil
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import kotlinx.coroutines.*

class PlaybackActivity : FragmentActivity(), CoroutineScope by MainScope() {

    companion object {
        const val EXTRA_VIDEO = "extra_video"
        const val EXTRA_LIVE_HLS_URL = "extra_live_hls_url"
        const val EXTRA_LIVE_TITLE = "extra_live_title"
        // Twitch channel login for the read-only chat WebView shown alongside
        // a live stream. Optional — if null, no chat panel is rendered.
        const val EXTRA_LIVE_TWITCH_CHANNEL = "extra_live_twitch_channel"
        // Default Giant Bomb live channel. Single source of truth for callers
        // that launch live playback so the slug doesn't drift across surfaces.
        const val DEFAULT_TWITCH_CHANNEL = "giantbomb"
        // Twitch login charset: 4-25 alphanumeric or underscore. Reject anything
        // outside that before we interpolate the value into HTML / URL strings.
        private val TWITCH_LOGIN_REGEX = Regex("^[a-zA-Z0-9_]{4,25}$")
        // Optional resume position in seconds, passed by DetailActivity so
        // PlaybackActivity doesn't have to re-fetch progress (which can race
        // with playback start or fail through Cloudflare).
        const val EXTRA_RESUME_SECONDS = "extra_resume_seconds"
    }

    private lateinit var prefs: PrefsManager
    private var player: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    // Single Player.Listener instance so we can remove on disconnect / re-attach.
    // Avoids stacking duplicates on PiP-return paths through onControllerReady().
    private var playbackListener: Player.Listener? = null
    private lateinit var playerView: PlayerView
    private lateinit var rootLayout: FrameLayout
    private lateinit var api: GiantBombApi
    private var video: Video? = null
    private var videoDuration: Double = 0.0
    private var isTv = false

    // Android 13+ runtime permission for the foreground-service media notification.
    // Must be registered before STARTED, so it lives at field-init time.
    // Result is intentionally ignored: denial just means no media notification —
    // playback still works through the service.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* ignore */ }

    // Chromecast
    private var castContext: CastContext? = null
    private var castPlayer: CastPlayer? = null
    private var isCasting = false
    // High-perf wifi lock held while a Chromecast session is active so the
    // sender↔receiver link doesn't downshift when the screen turns off.
    // Released when we leave the cast or finish the activity.
    private var castWifiLock: android.net.wifi.WifiManager.WifiLock? = null

    // Mobile portrait layout views
    private var mobileContentScroll: ScrollView? = null
    private var playerContainer: FrameLayout? = null
    private var relatedRecycler: RecyclerView? = null

    // Held so onDestroy can stopLoading + destroy the WebView. Otherwise the
    // WebView's native context and Twitch's JS timers leak past activity death.
    private var chatWebView: WebView? = null
    // Container that wraps the player + chat panel on live. Tracked so cleanup
    // can detach the whole thing instead of leaving the wrapper as an orphan
    // when later code paths only target playerView's parent.
    private var splitLayout: LinearLayout? = null

    // Quality selection
    private data class QualityOption(val label: String, val url: String, val isHls: Boolean = false)
    private var qualityOptions = mutableListOf<QualityOption>()
    private var currentQualityIndex = 0
    private var qualityOverlay: View? = null
    private var isLiveStream = false

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        isTv = DeviceUtil.isTv(this)

        if (!isTv) {
            enableEdgeToEdge()
        }

        prefs = PrefsManager(this)
        api = GiantBombApi(prefs.apiKey ?: "")

        requestNotificationPermissionIfNeeded()

        // Modern Android (gesture nav) routes back through OnBackPressedDispatcher,
        // not onKeyDown(KEYCODE_BACK). Wire PiP entry + quality-picker dismissal here
        // so swipe-back on phones reliably triggers PiP. Falls through to default
        // (finish the activity) when neither applies.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isQualityPickerShowing()) {
                    dismissQualityPicker()
                    return
                }
                if (!isTv && tryEnterPip()) return
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })

        if (!isTv) {
            try {
                castContext = CastContext.getSharedInstance(this)
            } catch (_: Exception) {
                // Cast not available (e.g. no Google Play Services)
            }
        }

        @Suppress("DEPRECATION")
        video = intent.getSerializableExtra(EXTRA_VIDEO) as? Video

        // Allow launching with just an HLS URL (for live streams)
        val liveHlsUrl = intent.getStringExtra(EXTRA_LIVE_HLS_URL)
        if (video == null && liveHlsUrl == null) {
            finish()
            return
        }

        if (isTv) {
            buildTvLayout()
        } else if (liveHlsUrl != null) {
            buildMobileLiveLayout()
        } else {
            buildMobileLayout()
        }
    }

    private fun buildTvLayout() {
        // Force landscape on TV
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        rootLayout = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            useController = true
            controllerShowTimeoutMs = 3000
            controllerAutoShow = true
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setKeepContentOnPlayerReset(false)

            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                if (visibility == View.VISIBLE) {
                    findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.setOnClickListener {
                        showQualityPicker()
                    }
                    enhanceControlFocus(this)
                }
            })
        }

        rootLayout.addView(playerView)
        setContentView(rootLayout)
    }

    private fun buildMobileLiveLayout() {
        // Fullscreen landscape layout for live streams on mobile (no video metadata)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            useController = true
            controllerShowTimeoutMs = 3000
            controllerAutoShow = true
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setKeepContentOnPlayerReset(false)

            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                if (visibility == View.VISIBLE) {
                    findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.setOnClickListener {
                        showQualityPicker()
                    }
                    enhanceControlFocus(this)
                }
            })
        }

        rootLayout.addView(playerView)

        // Cast button overlay for live streams
        if (castContext != null) {
            val castButton = MediaRouteButton(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    48.dp(), 48.dp(),
                    Gravity.TOP or Gravity.END
                ).apply { setMargins(0, 8.dp(), 8.dp(), 0) }
                CastButtonFactory.setUpMediaRouteButton(applicationContext, this)
            }
            rootLayout.addView(castButton)
        }

        setContentView(rootLayout)

        // Hide system bars for immersive playback
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun buildMobileLayout() {
        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(0xFF1A1A20.toInt())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Player container - 16:9 in portrait, fullscreen in landscape
        val screenWidth = resources.displayMetrics.widthPixels
        playerContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (screenWidth * 9) / 16
            )
        }

        playerView = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            useController = true
            controllerShowTimeoutMs = 3000
            controllerAutoShow = true
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setKeepContentOnPlayerReset(false)

            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                if (visibility == View.VISIBLE) {
                    findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.setOnClickListener {
                        showQualityPicker()
                    }
                    enhanceControlFocus(this)
                }
            })
        }

        playerContainer!!.addView(playerView)

        // Cast button overlay on the player
        if (castContext != null) {
            val castButton = MediaRouteButton(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    48.dp(), 48.dp(),
                    Gravity.TOP or Gravity.END
                ).apply { setMargins(0, 8.dp(), 8.dp(), 0) }
                CastButtonFactory.setUpMediaRouteButton(applicationContext, this)
            }
            playerContainer!!.addView(castButton)
        }

        mainLayout.addView(playerContainer)

        // Content below video (only visible in portrait)
        val v = video!!
        mobileContentScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setBackgroundColor(0xFF1A1A20.toInt())
        }

        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(), 12.dp(), 16.dp(), 24.dp())
        }

        // Video title
        val titleView = TextView(this).apply {
            text = v.title
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
        }
        contentLayout.addView(titleView)

        // Meta line
        val metaView = TextView(this).apply {
            val parts = mutableListOf<String>()
            if (!v.showTitle.isNullOrEmpty()) parts.add(v.showTitle)
            if (v.publishDate.isNotEmpty()) parts.add(DateFormat.formatPublishDate(v.publishDate))
            if (!v.author.isNullOrEmpty()) parts.add(v.author)
            text = parts.joinToString("  \u2022  ")
            textSize = 13f
            setTextColor(0xFFA0A0A0.toInt())
            setPadding(0, 4.dp(), 0, 0)
        }
        contentLayout.addView(metaView)

        // Description
        if (!v.description.isNullOrEmpty()) {
            val descView = TextView(this).apply {
                text = v.description.replace(Regex("<[^>]*>"), "")
                textSize = 14f
                setTextColor(0xFF999999.toInt())
                setPadding(0, 12.dp(), 0, 0)
                maxLines = 6
                setLineSpacing(0f, 1.3f)
            }
            contentLayout.addView(descView)
        }

        // Quality button
        val qualityBtn = TextView(this).apply {
            text = "Quality"
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(16.dp(), 8.dp(), 16.dp(), 8.dp())
            val density = resources.displayMetrics.density
            background = GradientDrawable().apply {
                setColor(0x33FFFFFF)
                cornerRadius = 6f * density
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12.dp() }
            setOnClickListener { showQualityPicker() }
        }
        contentLayout.addView(qualityBtn)

        // "Up Next" header
        val upNextHeader = TextView(this).apply {
            text = "Up Next"
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 20.dp(), 0, 8.dp())
        }
        contentLayout.addView(upNextHeader)

        // RecyclerView with nested scrolling disabled to measure all items inside ScrollView
        relatedRecycler = RecyclerView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isNestedScrollingEnabled = false
            layoutManager = LinearLayoutManager(this@PlaybackActivity)
        }
        contentLayout.addView(relatedRecycler)

        mobileContentScroll!!.addView(contentLayout)
        mainLayout.addView(mobileContentScroll)

        rootLayout.addView(mainLayout)
        setContentView(rootLayout)

        // Set initial layout based on current orientation
        updateMobileOrientation(resources.configuration.orientation)

        // Load related videos
        loadRelatedVideos(v)
    }

    private fun loadRelatedVideos(v: Video) {
        val showId = v.showId ?: return
        launch {
            val result = api.getVideos(limit = 20, showId = showId)
            result.onSuccess { videos ->
                val related = videos.filter { it.id != v.id }
                relatedRecycler?.adapter = RelatedVideoAdapter(related)
            }
        }
    }

    private fun updateMobileOrientation(orientation: Int) {
        if (isTv) return
        val isLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)

        if (isLandscape) {
            // Fullscreen landscape
            mobileContentScroll?.visibility = View.GONE
            playerContainer?.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            // Hide system bars
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            // Portrait - video at top 16:9, content below
            mobileContentScroll?.visibility = View.VISIBLE
            playerContainer?.post {
                val width = playerContainer!!.width
                if (width > 0) {
                    playerContainer?.layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (width * 9) / 16
                    )
                }
            }
            // Show system bars
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateMobileOrientation(newConfig.orientation)
    }

    override fun onStart() {
        super.onStart()
        initializeCastPlayer()
        connectController()
    }

    private fun requestNotificationPermissionIfNeeded() {
        // Android 13 (API 33) introduced runtime permission for POST_NOTIFICATIONS.
        // Without it the foreground-service media notification is silently
        // suppressed, defeating the whole point of the background-playback
        // service. We request once on entry and don't gate playback on the
        // result — denial just degrades to no notification.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun connectController() {
        // Already connected (e.g. returning from PiP): just rebind the view.
        player?.let {
            if (!isCasting) playerView.player = it
            onControllerReady(it)
            return
        }
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            // Late-completion guard: if disconnectController() ran while we
            // were waiting (controllerFuture cleared / replaced), don't
            // resurrect the controller — release it and bail.
            if (controllerFuture !== future) {
                if (future.isDone && !future.isCancelled) {
                    runCatching { future.get() }.getOrNull()?.release()
                }
                return@addListener
            }
            if (future.isCancelled) return@addListener
            try {
                val c = future.get()
                player = c
                if (!isCasting) playerView.player = c
                onControllerReady(c)
            } catch (e: Exception) {
                android.util.Log.e("PlaybackActivity", "Controller connect failed", e)
                Toast.makeText(this, "Playback service unavailable", Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onControllerReady(c: MediaController) {
        val v = video
        val liveHlsUrl = intent.getStringExtra(EXTRA_LIVE_HLS_URL)
        val desiredMediaId = when {
            liveHlsUrl != null -> "live:${liveHlsUrl.hashCode()}"
            v != null -> "vod:${v.id}"
            else -> null
        }
        // Service is already playing the requested content: re-attach listener only.
        // attachPlayerListener() removes any prior instance, so PiP-return paths
        // don't stack duplicate listeners that would multi-fire on STATE_ENDED.
        // Progress saving + markWatched live on the service, so we don't restart
        // any activity-side timer here.
        if (desiredMediaId != null && c.currentMediaItem?.mediaId == desiredMediaId) {
            if (v != null) attachPlayerListener(c)
            return
        }
        if (liveHlsUrl != null) initializeLivePlayer(liveHlsUrl)
        else if (v != null) initializePlayer()
    }


    private fun isInPipMode(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
    }

    private fun tryEnterPip(): Boolean {
        if (isTv || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (player == null && castPlayer == null) return false
        enterPictureInPictureMode(
            PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
        )
        return true
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        tryEnterPip()
    }

    private var enteredPip = false

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            enteredPip = true
            playerView.useController = false
        } else {
            playerView.useController = true
            // PiP→fullscreen on phones often leaves playerContainer at stale
            // dimensions until something else triggers a re-layout (rotation
            // works, but onConfigurationChanged isn't reliable across OEMs for
            // a PiP exit). Re-apply the orientation-driven sizing here.
            updateMobileOrientation(newConfig.orientation)
            if (isFinishing) {
                // Tap-X / swipe-away / drag-to-remove: dismisses PiP and finishes
                // the activity. Stop the service here too — Samsung doesn't
                // always fire onStop with isFinishing=true for the PiP-X path,
                // so onStop alone is unreliable as a stop signal.
                stopPlaybackService()
            } else {
                // Tapped the PiP tile to return to fullscreen — no stop wanted,
                // and clear the flag so a later non-PiP onStop doesn't fire it.
                enteredPip = false
            }
        }
    }

    override fun onStop() {
        super.onStop()
        val explicitlyFinishing = isFinishing
        val wasInPip = enteredPip
        // While the activity is just backgrounded (Home press, PiP visible),
        // the service keeps the player alive and the foreground notification
        // shows transport controls. Progress + markWatched are saved
        // service-side via Player.Listener — nothing for us to flush here.
        disconnectController()
        // Cast: always release the CastPlayer here so the next onStart can
        // cleanly re-bind. The WifiLock acquired during switchToCast keeps
        // the underlying cast session alive across the transient stop —
        // releasing the sender-side player wrapper itself is fine.
        releaseCastPlayer()
        enteredPip = false
        if (explicitlyFinishing || wasInPip) {
            // The user dismissed the player UI on purpose — PiP X, PiP swipe-away,
            // drag-to-remove, or Back when PiP couldn't engage. Stop the service
            // so audio doesn't keep playing with no obvious way to silence it.
            // Idempotent: onPictureInPictureModeChanged may have already stopped it.
            stopPlaybackService()
        }
    }

    private fun stopPlaybackService() {
        stopService(Intent(this, PlaybackService::class.java))
    }

    private fun initializeCastPlayer() {
        val ctx = castContext ?: return
        castPlayer = CastPlayer(ctx).apply {
            setSessionAvailabilityListener(object : SessionAvailabilityListener {
                override fun onCastSessionAvailable() {
                    switchToCast()
                }

                override fun onCastSessionUnavailable() {
                    switchToLocal()
                }
            })
            // If a Cast session is already active when we open the activity
            if (isCastSessionAvailable) {
                switchToCast()
            }
        }
    }

    private fun releaseCastPlayer() {
        castPlayer?.setSessionAvailabilityListener(null)
        castPlayer?.release()
        castPlayer = null
        releaseCastWifiLock()
    }

    private fun acquireCastWifiLock() {
        // Defensive try/catch: acquireWifiLock requires WAKE_LOCK which is now
        // declared in the manifest, but a SecurityException here would crash
        // the cast-session-started callback and take the activity down. The
        // wifi lock is a nice-to-have — better to skip it than crash.
        try {
            if (castWifiLock?.isHeld == true) return
            val wm = applicationContext.getSystemService(android.content.Context.WIFI_SERVICE)
                as? android.net.wifi.WifiManager ?: return
            castWifiLock = wm.createWifiLock(
                android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "GiantBombTV:Cast"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            android.util.Log.w("PlaybackActivity", "WifiLock acquire failed: ${e.message}")
        }
    }

    private fun releaseCastWifiLock() {
        try {
            castWifiLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            android.util.Log.w("PlaybackActivity", "WifiLock release failed: ${e.message}")
        }
        castWifiLock = null
    }

    private fun switchToCast() {
        val cp = castPlayer ?: return
        val currentOption = qualityOptions.getOrNull(currentQualityIndex) ?: return
        val currentUrl = currentOption.url
        val exo = player

        // Save current position from local player
        val position = exo?.currentPosition ?: 0L
        val wasPlaying = exo?.isPlaying ?: true

        // Pause and detach local player only after we know we can cast
        exo?.pause()
        playerView.player = cp
        isCasting = true
        // Hold a high-perf wifi lock so the sender↔receiver link survives a
        // screen lock — without this, Wi-Fi downshifts and the receiver drops
        // the cast session within a minute or two of the screen going off.
        acquireCastWifiLock()
        val mimeType = if (currentOption.isHls) MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP4

        val castMediaItem = MediaItem.Builder()
            .setUri(currentUrl)
            .setMimeType(mimeType)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(video?.title ?: intent.getStringExtra(EXTRA_LIVE_TITLE) ?: "Giant Bomb")
                    .setArtworkUri(video?.thumbnailUrl?.let { Uri.parse(it) })
                    .build()
            )
            .build()

        cp.setMediaItem(castMediaItem, position)
        cp.playWhenReady = wasPlaying
        cp.prepare()
    }

    private fun switchToLocal() {
        val cp = castPlayer
        val position = cp?.currentPosition ?: 0L
        val wasPlaying = cp?.isPlaying ?: true

        isCasting = false
        // Cast session ended — drop the high-perf wifi lock.
        releaseCastWifiLock()

        // Re-attach local player
        val exo = player
        if (exo != null) {
            playerView.player = exo
            exo.seekTo(position)
            exo.playWhenReady = wasPlaying
        }
    }

    private fun initializePlayer() {
        // Live stream mode: direct HLS URL, no API call needed
        val liveHlsUrl = intent.getStringExtra(EXTRA_LIVE_HLS_URL)
        if (liveHlsUrl != null) {
            initializeLivePlayer(liveHlsUrl)
            return
        }

        val v = video ?: return

        launch {
            val result = api.getPlayback(v.id)

            result.onSuccess { playback ->
                videoDuration = playback.duration

                // Build quality options list
                qualityOptions.clear()
                if (!playback.hlsUrl.isNullOrEmpty()) {
                    qualityOptions.add(QualityOption("Auto (HLS)", playback.hlsUrl, isHls = true))
                }
                playback.mp4s.sortedByDescending { it.height }.forEach { mp4 ->
                    val label = if (mp4.height > 0) "${mp4.height}p" else mp4.label.ifEmpty { "MP4" }
                    qualityOptions.add(QualityOption(label, mp4.url))
                }

                // YouTube fallback
                if (qualityOptions.isEmpty() && !playback.youtubeUrl.isNullOrEmpty()) {
                    if (BuildConfig.ENABLE_INLINE_YOUTUBE) {
                        // Inline extraction using internal YouTube API (sideload builds only)
                        val ytUrl = playback.youtubeUrl
                        if (ytUrl != null) {
                            val ytResult = com.giantbomb.tv.data.YouTubeExtractor().extract(ytUrl)
                            ytResult.onSuccess { yt ->
                                if (yt.hlsUrl != null) {
                                    qualityOptions.add(QualityOption("YouTube HLS", yt.hlsUrl, isHls = true))
                                }
                                yt.streams
                                    .filter { !it.isAdaptive && it.hasVideo }
                                    .sortedByDescending { it.height }
                                    .forEach { stream ->
                                        val label = "YT ${stream.qualityLabel ?: "${stream.height}p"}"
                                        qualityOptions.add(QualityOption(label, stream.url))
                                    }
                                if (videoDuration == 0.0 && yt.duration > 0) {
                                    videoDuration = yt.duration.toDouble()
                                }
                            }
                            ytResult.onFailure { e ->
                                android.util.Log.w("PlaybackActivity", "YouTube extraction failed: ${e.message}")
                            }
                        }
                    } else {
                        // Store-compliant: open in YouTube app / browser
                        val ytIntent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(playback.youtubeUrl)
                        )
                        startActivity(ytIntent)
                        finish()
                        return@onSuccess
                    }
                }

                if (qualityOptions.isEmpty()) {
                    Toast.makeText(this@PlaybackActivity, "No playback source available", Toast.LENGTH_LONG).show()
                    finish()
                    return@onSuccess
                }

                val preferred = PrefsManager(this@PlaybackActivity).preferredQuality
                currentQualityIndex = if (preferred == "auto") {
                    0
                } else {
                    qualityOptions.indexOfFirst { it.label.equals(preferred, ignoreCase = true) }
                        .takeIf { it >= 0 } ?: 0
                }

                val p = player ?: return@onSuccess
                val mediaItem = MediaItem.Builder()
                    .setUri(qualityOptions[currentQualityIndex].url)
                    .setMediaId("vod:${v.id}")
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(v.title)
                            .setArtist(v.showTitle)
                            .setArtworkUri(v.thumbnailUrl?.let { Uri.parse(it) })
                            .build()
                    )
                    .build()

                val startFromBeginning = intent.getBooleanExtra("start_from_beginning", false)
                val explicitResumeSec = intent.getDoubleExtra(EXTRA_RESUME_SECONDS, 0.0)
                val initialPositionMs = if (!startFromBeginning && explicitResumeSec > 0) {
                    (explicitResumeSec * 1000).toLong()
                } else {
                    0L
                }
                // setMediaItem(item, position) is used (instead of a separate
                // seekTo after prepare) so the player's first ready callback
                // already reports the resume position — no race with prepare()
                // resetting back to 0 before our seek lands.
                p.setMediaItem(mediaItem, initialPositionMs)
                p.prepare()

                // If the caller didn't pass an explicit resume position, fall
                // back to fetching it from the API. The seek issued here is
                // applied as soon as the player has buffered enough to seek.
                if (!startFromBeginning && explicitResumeSec <= 0) {
                    val progressResult = api.getProgress()
                    progressResult.getOrNull()?.find { it.videoId == v.id }?.let { progress ->
                        if (progress.percentComplete < 95 && progress.currentTime > 0) {
                            p.seekTo((progress.currentTime * 1000).toLong())
                        }
                    }
                }

                attachPlayerListener(p)
                p.playWhenReady = true
            }

            result.onFailure { e ->
                Toast.makeText(this@PlaybackActivity,
                    GiantBombApi.friendlyErrorMessage(e), Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun initializeLivePlayer(hlsUrl: String) {
        val liveTitle = intent.getStringExtra(EXTRA_LIVE_TITLE) ?: "Giant Bomb Live"
        // Side-by-side chat only makes sense in landscape — on a portrait phone
        // the 30% panel reduces the player to an unwatchable strip. Gate the
        // split here even though MobileBrowseFragment always passes the extra.
        val isLandscape = resources.configuration.orientation ==
            Configuration.ORIENTATION_LANDSCAPE
        val twitchChannel = intent.getStringExtra(EXTRA_LIVE_TWITCH_CHANNEL)
            ?.takeIf { prefs.showTwitchChat }
            ?.takeIf { isLandscape }
            ?.takeIf { TWITCH_LOGIN_REGEX.matches(it) }

        qualityOptions.clear()
        qualityOptions.add(QualityOption("Live", hlsUrl, isHls = true))
        currentQualityIndex = 0

        // Replace the SurfaceView-based PlayerView with a TextureView-based one.
        // TextureView handles mid-stream resolution changes properly (SurfaceView
        // has a fixed native buffer that causes zoomed-in rendering on some devices).
        val livePlayerView = layoutInflater.inflate(R.layout.view_live_player, null) as PlayerView
        val parent = playerView.parent as ViewGroup
        val index = parent.indexOfChild(playerView)
        val lp = playerView.layoutParams
        parent.removeView(playerView)

        if (twitchChannel != null) {
            // Split the area: player on the left (70%), read-only Twitch chat on the right (30%).
            val split = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = lp
                setBackgroundColor(Color.BLACK)
            }
            livePlayerView.layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 7f
            )
            split.addView(livePlayerView)
            val chat = createTwitchChatWebView(twitchChannel).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 3f
                )
            }
            chatWebView = chat
            split.addView(chat)
            splitLayout = split
            parent.addView(split, index)
        } else {
            parent.addView(livePlayerView, index, lp)
        }
        playerView = livePlayerView

        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            if (visibility == View.VISIBLE) {
                playerView.findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.setOnClickListener {
                    showQualityPicker()
                }
                enhanceControlFocus(playerView)
            }
        })

        isLiveStream = true

        val p = player ?: return
        playerView.player = p

        // Force highest bitrate from the start to prevent adaptive mid-stream
        // resolution switching.
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setForceHighestSupportedBitrate(true)
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri(hlsUrl)
            .setMediaId("live:${hlsUrl.hashCode()}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(liveTitle)
                    .build()
            )
            .build()
        p.setMediaItem(mediaItem)
        p.prepare()
        p.playWhenReady = true

        p.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                if (qualityOptions.size <= 1) {
                    buildLiveQualityOptions(p)
                }
            }
        })
    }

    private fun createTwitchChatWebView(channel: String): WebView {
        require(TWITCH_LOGIN_REGEX.matches(channel)) {
            "Invalid Twitch channel: $channel"
        }
        return WebView(this).apply {
            setBackgroundColor(0xFF18181B.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Make focusable so a TV remote can D-pad into the chat panel.
            isFocusable = true
            isFocusableInTouchMode = true

            webChromeClient = object : WebChromeClient() {
                // Embed shouldn't promote to fullscreen in our context; swallow
                // any attempt rather than letting the WebView surface a custom
                // view we never reparent or dismiss.
                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    callback?.onCustomViewHidden()
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val url = request?.url ?: return false
                    // Let the initial about:blank / data: load through so the
                    // embed can boot. After that, only allow twitch.tv hosts.
                    val scheme = url.scheme?.lowercase()
                    if (scheme == "about" || scheme == "data") return false
                    val host = url.host?.lowercase() ?: return true
                    return host != "twitch.tv" && !host.endsWith(".twitch.tv")
                }
            }

            val html = """
                <!DOCTYPE html>
                <html><head>
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <style>
                  html,body{margin:0;padding:0;background:#18181B;height:100%;overflow:hidden;}
                  iframe{display:block;border:0;width:100%;height:100%;}
                </style>
                </head><body>
                <iframe src="https://www.twitch.tv/embed/$channel/chat?parent=www.twitch.tv&darkpopout" allowfullscreen></iframe>
                </body></html>
            """.trimIndent()

            loadDataWithBaseURL("https://www.twitch.tv", html, "text/html", "UTF-8", null)
        }
    }

    private fun switchQuality(index: Int) {
        if (index == currentQualityIndex) return
        val p = player ?: return
        val option = qualityOptions.getOrNull(index) ?: return

        val position = p.currentPosition
        // playWhenReady captures user intent ("they want to be playing"),
        // unlike isPlaying which is false during buffering — switching quality
        // mid-buffer with isPlaying would leave playback paused on resume.
        val resumePlayback = p.playWhenReady
        currentQualityIndex = index

        val mediaId = p.currentMediaItem?.mediaId
        val metadata = p.currentMediaItem?.mediaMetadata
        val newItem = MediaItem.Builder()
            .setUri(option.url)
            .apply {
                if (mediaId != null) setMediaId(mediaId)
                if (metadata != null) setMediaMetadata(metadata)
            }
            .build()
        p.setMediaItem(newItem, position)
        p.prepare()
        p.playWhenReady = resumePlayback

        Toast.makeText(this, "Quality: ${option.label}", Toast.LENGTH_SHORT).show()
    }

    private fun showQualityPicker() {
        if (qualityOptions.size <= 1) {
            Toast.makeText(this, "No other quality options available", Toast.LENGTH_SHORT).show()
            return
        }

        dismissQualityPicker()
        player?.pause()

        val dp = { value: Int ->
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
        }

        val overlay = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xCC000000.toInt())
            isClickable = true
            isFocusable = true
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                dp(320),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            background = GradientDrawable().apply {
                setColor(0xE6222230.toInt())
                cornerRadius = dp(16).toFloat()
            }
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }

        // Title row with close button
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 0, 0, dp(12))
        }
        val title = TextView(this).apply {
            text = "Stream Quality"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleRow.addView(title)
        val closeBtn = TextView(this).apply {
            text = "\u2715"
            contentDescription = "Close"
            setTextColor(0xAAFFFFFF.toInt())
            textSize = 20f
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(dp(8), 0, dp(4), 0)
            setOnClickListener { dismissQualityPicker() }
            setOnFocusChangeListener { v, hasFocus ->
                (v as TextView).setTextColor(if (hasFocus) Color.WHITE else 0xAAFFFFFF.toInt())
            }
        }
        titleRow.addView(closeBtn)
        panel.addView(titleRow)

        val qualityItems = mutableListOf<TextView>()
        qualityOptions.forEachIndexed { index, option ->
            val isSelected = index == currentQualityIndex
            val item = TextView(this).apply {
                text = if (isSelected) "● ${option.label}" else "○ ${option.label}"
                contentDescription = if (isSelected) "${option.label}, selected" else option.label
                setTextColor(if (isSelected) 0xFFE53935.toInt() else Color.WHITE)
                textSize = 16f
                typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                isFocusable = true
                isFocusableInTouchMode = true
                setPadding(dp(12), dp(10), dp(12), dp(10))

                background = GradientDrawable().apply {
                    setColor(0x00000000)
                    cornerRadius = dp(8).toFloat()
                }

                setOnFocusChangeListener { v, hasFocus ->
                    (v.background as? GradientDrawable)?.setColor(
                        if (hasFocus) 0x33FFFFFF else 0x00000000
                    )
                }

                setOnClickListener {
                    dismissQualityPicker()
                    if (isLiveStream) switchLiveQuality(index) else switchQuality(index)
                }
            }
            qualityItems.add(item)
            panel.addView(item)

            if (isSelected) {
                item.post { item.requestFocus() }
            }
        }

        closeBtn.id = View.generateViewId()
        for (i in qualityItems.indices) {
            val item = qualityItems[i]
            item.id = View.generateViewId()
        }
        // Wire focus: close button <-> first item, items <-> each other
        closeBtn.nextFocusDownId = qualityItems.first().id
        qualityItems.first().nextFocusUpId = closeBtn.id
        for (i in qualityItems.indices) {
            val item = qualityItems[i]
            if (i > 0) item.nextFocusUpId = qualityItems[i - 1].id
            if (i < qualityItems.lastIndex) item.nextFocusDownId = qualityItems[i + 1].id
        }
        qualityItems.last().nextFocusDownId = closeBtn.id
        closeBtn.nextFocusUpId = qualityItems.last().id

        overlay.addView(panel)
        rootLayout.addView(overlay)
        qualityOverlay = overlay
    }

    private fun buildLiveQualityOptions(p: Player) {
        qualityOptions.clear()
        qualityOptions.add(QualityOption("Auto", "", isHls = true))

        val trackGroups = p.currentTracks.groups
        val heights = mutableSetOf<Int>()
        for (group in trackGroups) {
            if (group.type == C.TRACK_TYPE_VIDEO) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    if (format.height > 0) {
                        heights.add(format.height)
                    }
                }
            }
        }

        heights.sortedDescending().forEach { h ->
            qualityOptions.add(QualityOption("${h}p", h.toString(), isHls = true))
        }
    }

    private fun switchLiveQuality(index: Int) {
        val p = player ?: return
        currentQualityIndex = index

        if (index == 0) {
            // Auto - clear track override and allow adaptive bitrate selection
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .clearVideoSizeConstraints()
                .setForceHighestSupportedBitrate(false)
                .build()
        } else {
            // Specific quality - lock to that rendition
            val option = qualityOptions.getOrNull(index) ?: return
            val maxHeight = option.url.toIntOrNull() ?: return
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setMaxVideoSize(Int.MAX_VALUE, maxHeight)
                .setForceHighestSupportedBitrate(true)
                .build()
        }
    }

    private fun dismissQualityPicker() {
        qualityOverlay?.let {
            rootLayout.removeView(it)
            qualityOverlay = null
            player?.play()
        }
    }

    private fun isQualityPickerShowing() = qualityOverlay != null

    private suspend fun playNextEpisode(current: Video) {
        val showId = current.showId ?: run {
            finish()
            return
        }

        val result = api.getVideos(limit = 50, showId = showId)
        val videos = result.getOrNull()
        if (videos != null && videos.size > 1) {
            val currentIndex = videos.indexOfFirst { it.id == current.id }
            val nextVideo = if (currentIndex > 0) videos[currentIndex - 1] else null
            if (nextVideo != null) {
                val intent = Intent(this@PlaybackActivity, PlaybackActivity::class.java).apply {
                    putExtra(EXTRA_VIDEO, nextVideo)
                }
                startActivity(intent)
                finish()
                return
            }
        }
        finish()
    }

    private fun disconnectController() {
        controllerFuture?.let { if (!it.isDone) it.cancel(true) }
        controllerFuture = null
        // Drop the listener reference too — release() makes the player unusable,
        // but a stale field would still be removed-from on the next attach.
        playbackListener = null
        player?.release()
        player = null
    }

    private fun attachPlayerListener(p: Player) {
        // Remove any previously attached instance — PiP return / re-binding
        // can re-enter this path on the same controller, and the old listener
        // would otherwise still be wired up and fire alongside the new one.
        playbackListener?.let { p.removeListener(it) }
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                playerView.requestLayout()
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    // Read the current video at callback time rather than capturing,
                    // so a media-item swap on the same controller can't fire
                    // playNextEpisode against a stale Video. markWatched runs on
                    // the service side so it fires even when the activity is gone.
                    val current = video ?: return
                    val currentMediaId = p.currentMediaItem?.mediaId
                    if (currentMediaId != "vod:${current.id}") return
                    launch { playNextEpisode(current) }
                }
            }
        }
        playbackListener = listener
        p.addListener(listener)
    }

    /**
     * Stronger, on-brand focus state for the media3 controls. Focused buttons
     * get a 2dp white outline ring + slight scale-up; the time bar is always
     * red (YouTube-style) regardless of focus so the played position is
     * unmistakable. Called every time the controls become visible — the
     * operation is idempotent.
     */
    private fun enhanceControlFocus(pv: PlayerView) {
        val red = 0xFFE3192C.toInt()
        val white = 0xFFFFFFFF.toInt()
        val density = resources.displayMetrics.density
        val ringStroke = (2 * density).toInt()

        // Walk the controller subtree and apply the halo treatment to every
        // focusable view — covers play/pause, the rewind/ffwd "with amount"
        // FrameLayouts (which aren't ImageButtons), settings, subtitle, prev/
        // next (when the player advertises those commands), the overflow
        // ▶︎/◀︎ buttons, and anything media3 adds later, without a hardcoded ID
        // list. DefaultTimeBar is excluded — it gets the red-played-colour
        // treatment below instead.
        fun applyHalo(v: View) {
            v.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    view.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0x00000000)
                        setStroke(ringStroke, white)
                    }
                    view.animate().scaleX(1.08f).scaleY(1.08f).setDuration(120).start()
                } else {
                    view.background = null
                    view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }
            }
            // Halo + scale slightly overdraws the view's own bounds; without
            // unclipping ancestors the ring is sliced off by exo_basic_controls
            // and friends. Walk up to the PlayerView root flipping clipping off.
            var p = v.parent
            while (p is android.view.ViewGroup && p !== pv) {
                p.clipChildren = false
                p.clipToPadding = false
                p = p.parent
            }
        }
        fun walk(v: View) {
            if (v is androidx.media3.ui.DefaultTimeBar) return
            if (v !== pv && v.isFocusable) applyHalo(v)
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(pv)

        // Time bar: always red so the played position reads as the YouTube /
        // standard media-app cue. Scrubber thumb stays default; the colour
        // shift alone is plenty obvious.
        val timeBar = pv.findViewById<androidx.media3.ui.DefaultTimeBar>(
            androidx.media3.ui.R.id.exo_progress
        )
        timeBar?.apply {
            setPlayedColor(red)
            setScrubberColor(red)
        }
    }

    /**
     * Intercept DPAD LEFT/RIGHT when the time bar is focused so we can scrub
     * with progressively larger jumps the longer the key is held. Default
     * media3 time-bar scrubbing has a fixed step that's too coarse on long
     * videos and too fine on short ones; this gives a feel similar to
     * holding rewind/forward on a remote — first taps move a few seconds,
     * sustained holds rapidly skim through minutes.
     */
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isLeft = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT
        val isRight = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        if ((isLeft || isRight) && event.action == KeyEvent.ACTION_DOWN) {
            val p = player
            val timeBar = if (::playerView.isInitialized) {
                playerView.findViewById<View>(androidx.media3.ui.R.id.exo_progress)
            } else null
            if (p != null && timeBar != null && timeBar.isFocused) {
                val incrementMs = scrubIncrementMs(event.repeatCount)
                val sign = if (isRight) 1L else -1L
                val newPos = (p.currentPosition + sign * incrementMs).coerceAtLeast(0L)
                p.seekTo(newPos)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun scrubIncrementMs(repeat: Int): Long = when {
        repeat < 5 -> 5_000L          // 0–4: 5 s each
        repeat < 15 -> 15_000L        // 5–14: 15 s each
        repeat < 30 -> 60_000L        // 15–29: 1 min each
        else -> 5 * 60_000L           // 30+: 5 min each
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // BACK is handled by the OnBackPressedDispatcher callback (see onCreate).
        // ESCAPE only fires from hardware keyboards and isn't dispatched there.
        if (isQualityPickerShowing()) {
            if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
                dismissQualityPicker()
                return true
            }
            return super.onKeyDown(keyCode, event)
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                player?.let { if (it.isPlaying) it.pause() else it.play() }
                true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                player?.let { it.seekTo(it.currentPosition + 10000) }
                true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                player?.let { it.seekTo(maxOf(0, it.currentPosition - 10000)) }
                true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                showQualityPicker()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onDestroy() {
        // Disconnect the controller if we still hold one, but do NOT stop the
        // service: it keeps playing (with notification) when the activity dies.
        disconnectController()
        releaseCastPlayer()
        chatWebView?.let { wv ->
            wv.stopLoading()
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        }
        chatWebView = null
        splitLayout?.let { sl -> (sl.parent as? ViewGroup)?.removeView(sl) }
        splitLayout = null
        super.onDestroy()
        cancel()
    }

    // -----------------------------------------------------------------------
    // Related videos adapter for mobile portrait mode
    // -----------------------------------------------------------------------

    private inner class RelatedVideoAdapter(private val videos: List<Video>) :
        RecyclerView.Adapter<RelatedVideoAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val thumbnail: ImageView = view.findViewById(R.id.related_thumbnail)
            val title: TextView = view.findViewById(R.id.related_title)
            val meta: TextView = view.findViewById(R.id.related_meta)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.item_related_video, parent, false)
            return VH(view)
        }

        override fun getItemCount() = videos.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val video = videos[position]
            holder.title.text = video.title
            holder.meta.text = if (video.publishDate.isNotEmpty()) DateFormat.formatPublishDate(video.publishDate) else ""

            if (!video.thumbnailUrl.isNullOrEmpty()) {
                Glide.with(holder.thumbnail)
                    .load(video.thumbnailUrl)
                    .centerCrop()
                    .into(holder.thumbnail)
            }

            holder.itemView.setOnClickListener {
                val intent = Intent(this@PlaybackActivity, PlaybackActivity::class.java).apply {
                    putExtra(EXTRA_VIDEO, video)
                }
                startActivity(intent)
                finish()
            }
        }
    }
}
