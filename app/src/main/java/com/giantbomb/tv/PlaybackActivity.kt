package com.giantbomb.tv

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.giantbomb.tv.data.GiantBombApi
import com.giantbomb.tv.data.PrefsManager
import com.giantbomb.tv.data.YouTubeExtractor
import com.giantbomb.tv.model.Video
import com.giantbomb.tv.util.DeviceUtil
import kotlinx.coroutines.*

class PlaybackActivity : FragmentActivity(), CoroutineScope by MainScope() {

    companion object {
        const val EXTRA_VIDEO = "extra_video"
        private const val PROGRESS_SAVE_INTERVAL = 30_000L
    }

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private lateinit var playerView: PlayerView
    private lateinit var rootLayout: FrameLayout
    private lateinit var api: GiantBombApi
    private var video: Video? = null
    private var videoDuration: Double = 0.0
    private var progressJob: Job? = null
    private var saveJob: Job? = null
    private var isTv = false

    // Mobile portrait layout views
    private var mobileContentScroll: ScrollView? = null
    private var playerContainer: FrameLayout? = null
    private var relatedRecycler: RecyclerView? = null

    // Quality selection
    private data class QualityOption(val label: String, val url: String, val isHls: Boolean = false)
    private var qualityOptions = mutableListOf<QualityOption>()
    private var currentQualityIndex = 0
    private var qualityOverlay: View? = null

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        isTv = DeviceUtil.isTv(this)

        // Edge-to-edge + cutout for phones
        if (!isTv) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val prefs = PrefsManager(this)
        api = GiantBombApi(prefs.apiKey ?: "")

        @Suppress("DEPRECATION")
        video = intent.getSerializableExtra(EXTRA_VIDEO) as? Video ?: run {
            finish()
            return
        }

        if (isTv) {
            buildTvLayout()
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
                }
            })
        }

        rootLayout.addView(playerView)
        setContentView(rootLayout)
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
        playerContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
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
                }
            })
        }

        playerContainer!!.addView(playerView)
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
            if (v.publishDate.isNotEmpty()) parts.add(v.publishDate.take(10))
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

        // Related videos RecyclerView (nested in ScrollView via NestedScrollView behavior)
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
        initializePlayer()
    }

    override fun onStop() {
        super.onStop()
        saveProgressNow()
        releasePlayer()
    }

    private fun initializePlayer() {
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
                    val ytUrl = playback.youtubeUrl
                    if (ytUrl != null) {
                        val ytResult = YouTubeExtractor().extract(ytUrl)
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

                val loadControl = DefaultLoadControl.Builder()
                    .setBufferDurationsMs(30_000, 60_000, 5_000, 10_000)
                    .build()

                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build()

                val audioBufferSize = DefaultAudioTrackBufferSizeProvider.Builder()
                    .setMinPcmBufferDurationUs(2_500_000)
                    .setMaxPcmBufferDurationUs(5_000_000)
                    .build()

                val audioSink = DefaultAudioSink.Builder(this@PlaybackActivity)
                    .setAudioTrackBufferSizeProvider(audioBufferSize)
                    .build()

                val renderersFactory = object : DefaultRenderersFactory(this@PlaybackActivity) {
                    override fun buildAudioSink(
                        context: android.content.Context,
                        enableFloatOutput: Boolean,
                        enableAudioTrackPlaybackParams: Boolean
                    ): androidx.media3.exoplayer.audio.AudioSink {
                        return audioSink
                    }
                }.forceEnableMediaCodecAsynchronousQueueing()
                    .setEnableDecoderFallback(true)

                player = ExoPlayer.Builder(this@PlaybackActivity)
                    .setRenderersFactory(renderersFactory)
                    .setLoadControl(loadControl)
                    .setAudioAttributes(audioAttributes, true)
                    .build().also { exoPlayer ->
                    playerView.player = exoPlayer

                    mediaSession?.release()
                    mediaSession = MediaSession.Builder(this@PlaybackActivity, exoPlayer).build()

                    exoPlayer.setMediaItem(MediaItem.fromUri(qualityOptions[currentQualityIndex].url))
                    exoPlayer.playWhenReady = false
                    exoPlayer.prepare()

                    val startFromBeginning = intent.getBooleanExtra("start_from_beginning", false)
                    if (!startFromBeginning) {
                        val progressResult = api.getProgress()
                        progressResult.getOrNull()?.find { it.videoId == v.id }?.let { progress ->
                            if (progress.percentComplete < 95 && progress.currentTime > 0) {
                                exoPlayer.seekTo((progress.currentTime * 1000).toLong())
                            }
                        }
                    }

                    exoPlayer.addListener(object : Player.Listener {
                        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                            playerView.requestLayout()
                        }

                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_ENDED) {
                                launch {
                                    api.markWatched(v.id)
                                    playNextEpisode(v)
                                }
                            }
                        }
                    })

                    exoPlayer.playWhenReady = true
                    startProgressSaving()
                }
            }

            result.onFailure { e ->
                Toast.makeText(this@PlaybackActivity,
                    "Failed to load video: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun switchQuality(index: Int) {
        if (index == currentQualityIndex) return
        val p = player ?: return
        val option = qualityOptions.getOrNull(index) ?: return

        val position = p.currentPosition
        val wasPlaying = p.isPlaying

        currentQualityIndex = index

        progressJob?.cancel()
        mediaSession?.release()
        mediaSession = null
        p.release()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 60_000, 5_000, 10_000)
            .build()

        val audioBufferSize = DefaultAudioTrackBufferSizeProvider.Builder()
            .setMinPcmBufferDurationUs(2_500_000)
            .setMaxPcmBufferDurationUs(5_000_000)
            .build()
        val audioSink = DefaultAudioSink.Builder(this)
            .setAudioTrackBufferSizeProvider(audioBufferSize)
            .build()
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): androidx.media3.exoplayer.audio.AudioSink = audioSink
        }.forceEnableMediaCodecAsynchronousQueueing()
            .setEnableDecoderFallback(true)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        player = ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .build().also { newPlayer ->
                playerView.player = newPlayer
                mediaSession = MediaSession.Builder(this@PlaybackActivity, newPlayer).build()
                newPlayer.setMediaItem(MediaItem.fromUri(option.url))
                newPlayer.prepare()
                newPlayer.seekTo(position)
                newPlayer.playWhenReady = wasPlaying

                newPlayer.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) {
                            launch {
                                video?.let { v ->
                                    api.markWatched(v.id)
                                    playNextEpisode(v)
                                }
                            }
                        }
                    }
                })

                startProgressSaving()
            }

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

        val title = TextView(this).apply {
            text = "Stream Quality"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(12))
        }
        panel.addView(title)

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
                    switchQuality(index)
                }
            }
            qualityItems.add(item)
            panel.addView(item)

            if (isSelected) {
                item.post { item.requestFocus() }
            }
        }

        for (i in qualityItems.indices) {
            val item = qualityItems[i]
            item.id = View.generateViewId()
        }
        for (i in qualityItems.indices) {
            val item = qualityItems[i]
            if (i > 0) item.nextFocusUpId = qualityItems[i - 1].id
            if (i < qualityItems.lastIndex) item.nextFocusDownId = qualityItems[i + 1].id
        }

        overlay.addView(panel)
        rootLayout.addView(overlay)
        qualityOverlay = overlay
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

    private fun startProgressSaving() {
        progressJob = launch {
            while (isActive) {
                delay(PROGRESS_SAVE_INTERVAL)
                saveProgressNow()
            }
        }
    }

    private fun saveProgressNow() {
        val v = video ?: return
        val p = player ?: return
        val currentSec = p.currentPosition / 1000.0
        val durationSec = if (videoDuration > 0) videoDuration else p.duration / 1000.0
        if (currentSec > 0 && durationSec > 0) {
            saveJob?.cancel()
            saveJob = launch { api.saveProgress(v.id, currentSec, durationSec) }
        }
    }

    private fun releasePlayer() {
        progressJob?.cancel()
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isQualityPickerShowing()) {
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                dismissQualityPicker()
                return true
            }
            return super.onKeyDown(keyCode, event)
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                saveProgressNow()
                finish()
                true
            }
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
            holder.meta.text = if (video.publishDate.isNotEmpty()) video.publishDate.take(10) else ""

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
