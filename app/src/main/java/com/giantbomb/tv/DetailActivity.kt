package com.giantbomb.tv

import android.content.Intent
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import android.widget.Toast
import android.app.AlertDialog
import androidx.activity.enableEdgeToEdge
import com.bumptech.glide.Glide
import com.giantbomb.tv.data.GiantBombApi
import com.giantbomb.tv.data.PrefsManager
import com.giantbomb.tv.model.Mp4Source
import com.giantbomb.tv.model.ProgressEntry
import com.giantbomb.tv.model.Video
import com.giantbomb.tv.playback.DownloadStatus
import com.giantbomb.tv.playback.Downloads
import com.giantbomb.tv.util.DateFormat
import com.giantbomb.tv.util.DeviceUtil
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.*

class DetailActivity : FragmentActivity(), CoroutineScope by MainScope() {

    companion object {
        const val EXTRA_VIDEO = "extra_video"
    }

    // Cached resume position in seconds, populated when the playback / progress
    // load completes. The watch button's click handler reads this so it can
    // pass an explicit resume position to PlaybackActivity instead of forcing
    // PlaybackActivity to refetch progress (which can race with playback start).
    private var resumeSeconds: Double = 0.0

    // Refs needed by onResume to refresh the Resume label/position after
    // returning from playback, so the button reflects the new watch time
    // instead of replaying from the stale offset captured at onCreate.
    private var detailVideo: Video? = null
    private var detailApi: GiantBombApi? = null
    private var watchButtonRef: Button? = null
    private var restartButtonRef: Button? = null
    // The in-flight progress fetch. onResume cancels and replaces it so a slow
    // onCreate request that returns *after* the refresh doesn't paint stale
    // resume offsets back onto the buttons.
    private var progressRefreshJob: Job? = null
    // True only while a post-playback refresh (withRetry=true) is running. The
    // Watch click waits on the job for *that* case so it doesn't pass a stale
    // resumeSeconds; on cold start the click shouldn't be delayed by the
    // initial fetch at all.
    private var awaitingPostPlaybackRefresh = false
    // Prevents a double-tap during the click-time wait from queueing two
    // PlaybackActivity launches.
    private var watchLaunchInFlight = false

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
    private val density by lazy { resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val isTv = DeviceUtil.isTv(this)
        if (!isTv) {
            enableEdgeToEdge()
        }

        @Suppress("DEPRECATION")
        val video = intent.getSerializableExtra(EXTRA_VIDEO) as? Video ?: run {
            finish()
            return
        }

        val prefs = PrefsManager(this)
        val apiKey = prefs.apiKey ?: ""
        val api = GiantBombApi(apiKey)
        detailVideo = video
        detailApi = api

        val root = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.bg_ambient_gradient)
            clipChildren = false
        }

        // Background image
        val backdrop = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.35f
            contentDescription = null // decorative
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val imageUrl = video.thumbnailUrl ?: video.posterUrl
        if (!imageUrl.isNullOrEmpty()) {
            Glide.with(this)
                .load(imageUrl)
                .override(480, 270)
                .into(backdrop)
        }
        root.addView(backdrop)

        // Multi-layer gradient overlays
        val gradientBottom = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xE81A1A20.toInt(), 0x801A1A20.toInt(), 0x001A1A20)
            )
        }
        root.addView(gradientBottom)

        val gradientLeft = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xDD1A1A20.toInt(), 0x551A1A20.toInt(), 0x001A1A20)
            )
        }
        root.addView(gradientLeft)

        val gradientTop = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                120.dp()
            )
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x991A1A20.toInt(), 0x001A1A20)
            )
        }
        root.addView(gradientTop)

        // Content
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (isTv) Gravity.BOTTOM else Gravity.TOP
            if (isTv) {
                setPadding(48.dp(), 30.dp(), 200.dp(), 40.dp())
            } else {
                setPadding(16.dp(), 16.dp(), 16.dp(), 24.dp())
            }
            clipChildren = false
            clipToPadding = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val animViews = mutableListOf<View>()

        // Show tag
        if (!video.showTitle.isNullOrEmpty()) {
            val showTag = TextView(this@DetailActivity).apply {
                text = video.showTitle.uppercase()
                textSize = 13f
                setTextColor(0xFFE3192C.toInt())
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.1f
                setPadding(0, 0, 0, 6.dp())
            }
            content.addView(showTag)
            animViews.add(showTag)
        }

        // Premium badge — frosted gold pill
        if (video.premium) {
            val premiumTag = TextView(this).apply {
                text = "  PREMIUM  "
                textSize = 11f
                setTextColor(0xFF1A1A1A.toInt())
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.1f
                val pillFill = GradientDrawable().apply {
                    setColor(0xBBFFD700.toInt())
                    cornerRadius = 4f * density
                }
                val pillBorder = GradientDrawable().apply {
                    setColor(0x00000000)
                    setStroke((0.5f * density).toInt(), 0x40FFFFFF)
                    cornerRadius = 4f * density
                }
                background = LayerDrawable(arrayOf(pillFill, pillBorder))
                setPadding(6.dp(), 2.dp(), 6.dp(), 2.dp())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 6.dp() }
            }
            content.addView(premiumTag)
            animViews.add(premiumTag)
        }

        val titleView = TextView(this).apply {
            text = video.title
            textSize = 32f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 8.dp())
            maxLines = 2
        }
        content.addView(titleView)
        animViews.add(titleView)

        // Meta
        val metaView = TextView(this).apply {
            val parts = mutableListOf<String>()
            if (video.publishDate.isNotEmpty()) parts.add(DateFormat.formatPublishDate(video.publishDate))
            if (!video.author.isNullOrEmpty()) parts.add(video.author)
            text = parts.joinToString("  \u2022  ")
            textSize = 15f
            setTextColor(0xFFA0A0A0.toInt())
            setPadding(0, 0, 0, 12.dp())
        }
        content.addView(metaView)
        animViews.add(metaView)

        // Description
        if (!video.description.isNullOrEmpty()) {
            val descView = TextView(this).apply {
                text = video.description.replace(Regex("<[^>]*>"), "")
                textSize = 15f
                setTextColor(0xFF999999.toInt())
                setPadding(0, 0, 0, 20.dp())
                maxLines = 4
                setLineSpacing(0f, 1.3f)
            }
            content.addView(descView)
            animViews.add(descView)
        }

        // Buttons - vertical on phone for better fit, horizontal on TV
        val buttonLayout = LinearLayout(this).apply {
            orientation = if (isTv) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.START
        }

        val watchButton = createGlassButton(
            text = getString(R.string.watch),
            fillColor = 0x55E3192C,
            focusFillColor = 0x88E3192C.toInt(),
            borderColor = 0x40E3192C,
            focusBorderColor = 0x66E3192C,
            textColor = Color.WHITE,
            bold = true
        ).apply {
            contentDescription = "Watch ${video.title}"
            if (isTv) {
                setPadding(32.dp(), 10.dp(), 32.dp(), 10.dp())
            } else {
                setPadding(24.dp(), 12.dp(), 24.dp(), 12.dp())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            setOnClickListener {
                if (watchLaunchInFlight) return@setOnClickListener
                watchLaunchInFlight = true
                isEnabled = false
                launch {
                    // Only the post-playback refresh has the flush race to wait
                    // on. The cold-start fetch is informational; don't make
                    // Watch feel frozen for it on a slow network. Cap any wait
                    // so an offline retry can't lock the primary action.
                    if (awaitingPostPlaybackRefresh) {
                        withTimeoutOrNull(2_000L) { progressRefreshJob?.join() }
                    }
                    val intent = Intent(this@DetailActivity, PlaybackActivity::class.java).apply {
                        putExtra(PlaybackActivity.EXTRA_VIDEO, video)
                        if (resumeSeconds > 0) {
                            putExtra(PlaybackActivity.EXTRA_RESUME_SECONDS, resumeSeconds)
                        }
                    }
                    startActivity(intent)
                }
            }
        }
        buttonLayout.addView(watchButton)

        val watchlistButton = createGlassButton(
            text = "+ Watchlist",
            fillColor = 0x1AFFFFFF,
            focusFillColor = 0x2AFFFFFF,
            borderColor = 0x18FFFFFF,
            focusBorderColor = 0x30FFFFFF,
            textColor = 0xFFCCCCCC.toInt(),
            bold = false
        ).apply {
            contentDescription = "Add to watchlist"
            setPadding(24.dp(), 10.dp(), 24.dp(), 10.dp())
            layoutParams = if (isTv) {
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = 10.dp() }
            } else {
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 8.dp() }
            }
            setOnClickListener {
                launch {
                    val onWatchlist = tag == true
                    if (onWatchlist) {
                        val result = api.removeFromWatchlist(video.id)
                        result.onSuccess {
                            text = "+ Watchlist"
                            tag = false
                            Toast.makeText(this@DetailActivity, "Removed from watchlist", Toast.LENGTH_SHORT).show()
                        }
                        result.onFailure {
                            Toast.makeText(this@DetailActivity, "Failed to remove", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        val result = api.addToWatchlist(video.id)
                        result.onSuccess {
                            text = "\u2713 Watchlist"
                            tag = true
                            Toast.makeText(this@DetailActivity, "Added to watchlist", Toast.LENGTH_SHORT).show()
                        }
                        result.onFailure {
                            Toast.makeText(this@DetailActivity, "Failed to add", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
        val restartButton = createGlassButton(
            text = "Watch from Start",
            fillColor = 0x1AFFFFFF,
            focusFillColor = 0x2AFFFFFF,
            borderColor = 0x18FFFFFF,
            focusBorderColor = 0x30FFFFFF,
            textColor = 0xFFCCCCCC.toInt(),
            bold = false
        ).apply {
            contentDescription = "Watch from start"
            setPadding(24.dp(), 10.dp(), 24.dp(), 10.dp())
            layoutParams = if (isTv) {
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = 10.dp() }
            } else {
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 8.dp() }
            }
            visibility = View.GONE  // shown only when there's progress
            setOnClickListener {
                // Reset progress then play
                launch {
                    api.saveProgress(video.id, 0.0, 1.0)
                }
                val intent = Intent(this@DetailActivity, PlaybackActivity::class.java).apply {
                    putExtra(PlaybackActivity.EXTRA_VIDEO, video)
                    putExtra("start_from_beginning", true)
                }
                startActivity(intent)
            }
        }
        buttonLayout.addView(restartButton)

        buttonLayout.addView(watchlistButton)

        watchButtonRef = watchButton
        restartButtonRef = restartButton

        // Download (offline) button. Hidden until we know an MP4 source is
        // available (or the video is already downloaded / in flight). Tapping
        // toggles: start -> cancel while downloading -> remove once complete.
        Downloads.ensureLoaded(this)
        var downloadSource: Mp4Source? = null
        val downloadButton = createGlassButton(
            text = "Download",
            fillColor = 0x1AFFFFFF,
            focusFillColor = 0x2AFFFFFF,
            borderColor = 0x18FFFFFF,
            focusBorderColor = 0x30FFFFFF,
            textColor = 0xFFCCCCCC.toInt(),
            bold = false
        ).apply {
            contentDescription = "Download for offline viewing"
            setPadding(24.dp(), 10.dp(), 24.dp(), 10.dp())
            visibility = View.GONE
            layoutParams = if (isTv) {
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = 10.dp() }
            } else {
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 8.dp() }
            }
        }
        buttonLayout.addView(downloadButton)

        fun renderDownloadButton() {
            val d = Downloads.get(video.id)
            downloadButton.visibility =
                if (d != null || downloadSource != null) View.VISIBLE else View.GONE
            downloadButton.text = when (d?.status) {
                DownloadStatus.COMPLETED -> "✓ Downloaded"
                DownloadStatus.DOWNLOADING -> "Downloading ${d.progressPercent}%"
                DownloadStatus.QUEUED -> "Queued…"
                DownloadStatus.FAILED -> "Retry Download"
                else -> "Download"
            }
        }
        renderDownloadButton()

        downloadButton.setOnClickListener {
            val current = Downloads.get(video.id)
            when (current?.status) {
                DownloadStatus.COMPLETED -> confirmDeleteDownload(video) { renderDownloadButton() }
                DownloadStatus.DOWNLOADING, DownloadStatus.QUEUED ->
                    Downloads.cancel(this@DetailActivity, video.id)
                else -> {
                    val src = downloadSource
                    if (src == null) {
                        Toast.makeText(this@DetailActivity,
                            "Download isn't available for this video", Toast.LENGTH_SHORT).show()
                    } else {
                        val label = src.label.ifEmpty { if (src.height > 0) "${src.height}p" else "MP4" }
                        Downloads.enqueue(this@DetailActivity, video, src.url, label)
                        Toast.makeText(this@DetailActivity,
                            "Download started", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // Stream live download state into the button while the screen is open.
        launch {
            Downloads.state.collect { renderDownloadButton() }
        }

        content.addView(buttonLayout)
        animViews.add(buttonLayout)

        if (isTv) {
            root.addView(content)
        } else {
            val scrollView = ScrollView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                isFillViewport = true
            }
            scrollView.addView(content)
            root.addView(scrollView)
        }
        setContentView(root)

        // Fetch duration + watchlist state. Progress is fetched in a separate
        // coroutine (see refreshProgress below) so onResume can cancel/replace
        // it without affecting the duration/watchlist loads.
        launch {
            val playbackDeferred = async { api.getPlayback(video.id) }
            val watchlistDeferred = async { api.getWatchlist() }

            val playback = playbackDeferred.await().getOrNull()
            // Pick the MP4 to offer for offline download and reveal the button.
            downloadSource = chooseDownloadSource(playback?.mp4s, prefs.preferredQuality)
            renderDownloadButton()
            val watchlist = watchlistDeferred.await().getOrNull()
            val isOnWatchlist = watchlist?.any { it.id == video.id } == true

            // Update duration in meta
            if (playback != null && playback.duration > 0) {
                val totalSec = playback.duration.toInt()
                val h = totalSec / 3600
                val m = (totalSec % 3600) / 60
                val durationStr = if (h > 0) "${h}h ${m}m" else "${m}m"
                val parts = mutableListOf(durationStr)
                if (video.publishDate.isNotEmpty()) parts.add(DateFormat.formatPublishDate(video.publishDate))
                if (!video.author.isNullOrEmpty()) parts.add(video.author)
                metaView.text = parts.joinToString("  \u2022  ")
            }

            // Update watchlist button state
            if (isOnWatchlist) {
                watchlistButton.text = "\u2713 Watchlist"
                watchlistButton.tag = true
            }
        }
        refreshProgress(video, api, watchButton, restartButton, withRetry = false)

        // Staggered entrance animation
        animViews.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = 30f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(100L + index * 60L)
                .setDuration(350)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        watchButton.requestFocus()
    }

    // Track whether we've completed the first lifecycle pass — the very first
    // onResume fires right after onCreate's initial coroutine launches, and we
    // don't want to double-fetch progress on cold start.
    private var hasResumedOnce = false

    override fun onResume() {
        super.onResume()
        // Re-enable the Watch button after returning from playback so the
        // user can launch again.
        watchLaunchInFlight = false
        watchButtonRef?.isEnabled = true
        if (!hasResumedOnce) {
            hasResumedOnce = true
            return
        }
        // Returning from PlaybackActivity: re-fetch progress so the Resume label
        // and the cached resumeSeconds reflect the new watched position. Without
        // this, hitting Resume a second time replays from the stale offset that
        // was captured at first open.
        val video = detailVideo ?: return
        val api = detailApi ?: return
        val watch = watchButtonRef ?: return
        val restart = restartButtonRef ?: return
        refreshProgress(video, api, watch, restart, withRetry = true)
    }

    private fun refreshProgress(
        video: Video,
        api: GiantBombApi,
        watch: Button,
        restart: Button,
        withRetry: Boolean
    ) {
        // Cancel any earlier load so its result can't land after this one's.
        progressRefreshJob?.cancel()
        awaitingPostPlaybackRefresh = withRetry
        lateinit var thisJob: Job
        thisJob = launch {
            try {
                // Immediate fetch — correct on cold-open and on returns where
                // the playback service finished flushing before we resumed.
                fetchAndApply(video, api, watch, restart)
                // Only the return-from-playback path needs the second fetch:
                // PlaybackService's final saveCurrentProgress() runs on its own
                // scope and may still be in flight as DetailActivity resumes —
                // Android can resurface this activity before the finishing one
                // reaches onStop. cold-open never has that race, so skip the
                // extra API call.
                if (withRetry) {
                    delay(1500L)
                    fetchAndApply(video, api, watch, restart)
                }
            } finally {
                // Identity-check before clearing: a cancelled previous job's
                // finally block can land after a fresh refreshProgress() has
                // already installed a new job + set the flag. Clearing
                // unconditionally would wipe the new job's flag mid-flight.
                if (progressRefreshJob === thisJob) {
                    awaitingPostPlaybackRefresh = false
                }
            }
        }
        progressRefreshJob = thisJob
    }

    private suspend fun fetchAndApply(
        video: Video,
        api: GiantBombApi,
        watch: Button,
        restart: Button
    ) {
        // Only repaint on success. A transient network error would otherwise
        // collapse to null and wipe a still-valid Resume label/offset.
        api.getProgress().onSuccess { entries ->
            val progress = entries.find { it.videoId == video.id }
            applyProgressState(progress, watch, restart)
        }
    }

    private fun applyProgressState(
        progress: ProgressEntry?,
        watch: Button,
        restart: Button
    ) {
        val videoTitle = detailVideo?.title.orEmpty()
        when {
            progress != null && progress.percentComplete in 1..94 && progress.currentTime > 0 -> {
                resumeSeconds = progress.currentTime
                val resumeMin = (progress.currentTime / 60).toInt()
                val label = "${getString(R.string.resume)} (${resumeMin}m in)"
                watch.text = label
                // Keep TalkBack in sync so screen-reader users hear the resume
                // action, not the stale "Watch <title>" set at view creation.
                watch.contentDescription = "$label $videoTitle".trim()
                restart.visibility = View.VISIBLE
            }
            else -> {
                // Covers three reset cases that all need the same UI:
                //   - progress is null (server has no entry yet, or it was cleared)
                //   - percentComplete >= 95 (watched, marked-complete)
                //   - currentTime <= 0 (Watch-from-Start path saved 0.0/1.0)
                // Without this branch the previous Resume label/offset would
                // linger and a later click would replay from a stale position.
                resumeSeconds = 0.0
                val label = getString(R.string.watch)
                watch.text = label
                watch.contentDescription = "$label $videoTitle".trim()
                restart.visibility = View.GONE
            }
        }
    }

    /**
     * Picks which MP4 rendition to download: the user's preferred quality if
     * present, otherwise the highest resolution available. Null when the video
     * has no progressive MP4 (e.g. HLS-only), in which case download is hidden.
     */
    private fun chooseDownloadSource(mp4s: List<Mp4Source>?, preferred: String): Mp4Source? {
        if (mp4s.isNullOrEmpty()) return null
        if (preferred != "auto") {
            mp4s.firstOrNull {
                "${it.height}p".equals(preferred, ignoreCase = true) ||
                    it.label.equals(preferred, ignoreCase = true)
            }?.let { return it }
        }
        return mp4s.maxByOrNull { it.height }
    }

    private fun confirmDeleteDownload(video: Video, onChanged: () -> Unit) {
        AlertDialog.Builder(this, R.style.GbDialogTheme)
            .setTitle(video.title)
            .setMessage("Remove this download from your device?")
            .setPositiveButton("Remove") { _, _ ->
                Downloads.delete(this, video.id)
                onChanged()
            }
            .setNegativeButton("Keep", null)
            .show()
    }

    private fun createGlassButton(
        text: String, fillColor: Int, focusFillColor: Int,
        borderColor: Int, focusBorderColor: Int,
        textColor: Int, bold: Boolean
    ): Button {
        val cornerRadius = 8f * density
        return Button(this).apply {
            this.text = text
            textSize = 16f
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setTextColor(textColor)
            isAllCaps = false
            isFocusable = true

            fun glassDrawable(fill: Int, border: Int): LayerDrawable {
                val f = GradientDrawable().apply {
                    setColor(fill)
                    setCornerRadius(cornerRadius)
                }
                val b = GradientDrawable().apply {
                    setColor(0x00000000)
                    setStroke((1f * density).toInt(), border)
                    setCornerRadius(cornerRadius)
                }
                return LayerDrawable(arrayOf(f, b))
            }

            background = glassDrawable(fillColor, borderColor)

            setOnFocusChangeListener { v, hasFocus ->
                val scale = if (hasFocus) 1.05f else 1.0f
                v.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
                v.background = glassDrawable(
                    if (hasFocus) focusFillColor else fillColor,
                    if (hasFocus) focusBorderColor else borderColor
                )
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Use OnBackPressedDispatcher")
    override fun onBackPressed() {
        super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancel()
    }
}
