package com.giantbomb.tv

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
import androidx.media3.ui.PlayerView
import com.giantbomb.tv.data.GiantBombApi
import com.giantbomb.tv.data.PrefsManager
import com.giantbomb.tv.data.YouTubeExtractor
import com.giantbomb.tv.model.Mp4Source
import com.giantbomb.tv.model.Video
import kotlinx.coroutines.*

class PlaybackActivity : FragmentActivity(), CoroutineScope by MainScope() {

    companion object {
        const val EXTRA_VIDEO = "extra_video"
        private const val PROGRESS_SAVE_INTERVAL = 30_000L
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var rootLayout: FrameLayout
    private lateinit var api: GiantBombApi
    private var video: Video? = null
    private var videoDuration: Double = 0.0
    private var progressJob: Job? = null
    private var saveJob: Job? = null

    // Quality selection
    private data class QualityOption(val label: String, val url: String, val isHls: Boolean = false)
    private var qualityOptions = mutableListOf<QualityOption>()
    private var currentQualityIndex = 0
    private var qualityOverlay: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val prefs = PrefsManager(this)
        api = GiantBombApi(prefs.apiKey ?: "")

        @Suppress("DEPRECATION")
        video = intent.getSerializableExtra(EXTRA_VIDEO) as? Video ?: run {
            finish()
            return
        }

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
            resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            setKeepContentOnPlayerReset(false)

            // Hook the gear/settings button to open our quality picker
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
                // Sort MP4s by height descending (highest quality first)
                playback.mp4s.sortedByDescending { it.height }.forEach { mp4 ->
                    val label = if (mp4.height > 0) "${mp4.height}p" else mp4.label.ifEmpty { "MP4" }
                    qualityOptions.add(QualityOption(label, mp4.url))
                }

                // If no GB sources, or YouTube URL is available, try YouTube extraction
                if (qualityOptions.isEmpty() || !playback.youtubeUrl.isNullOrEmpty()) {
                    val ytUrl = playback.youtubeUrl
                    if (ytUrl != null) {
                        val ytResult = YouTubeExtractor().extract(ytUrl)
                        ytResult.onSuccess { yt ->
                            // Add YouTube HLS if available
                            if (yt.hlsUrl != null) {
                                qualityOptions.add(QualityOption("YouTube HLS", yt.hlsUrl, isHls = true))
                            }
                            // Add muxed (video+audio) YouTube streams
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

                // Select quality based on user preference
                val preferred = PrefsManager(this@PlaybackActivity).preferredQuality
                currentQualityIndex = if (preferred == "auto") {
                    0
                } else {
                    qualityOptions.indexOfFirst { it.label.equals(preferred, ignoreCase = true) }
                        .takeIf { it >= 0 } ?: 0
                }

                val loadControl = DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        30_000,   // minBufferMs
                        60_000,   // maxBufferMs
                        5_000,    // bufferForPlaybackMs — more data before starting
                        10_000    // bufferForPlaybackAfterRebufferMs
                    )
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

                    exoPlayer.setMediaItem(MediaItem.fromUri(qualityOptions[currentQualityIndex].url))
                    exoPlayer.playWhenReady = false
                    exoPlayer.prepare()

                    // Resume from server-side progress (unless starting from beginning)
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
                            // Force PlayerView to recalculate layout when resolution changes
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

        // Release old player and create a fresh one so video surface resets
        progressJob?.cancel()
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

        // Remove existing overlay if any
        dismissQualityPicker()

        player?.pause()

        val dp = { value: Int ->
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
        }

        // Scrim background
        val overlay = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xCC000000.toInt())
            isClickable = true
            isFocusable = true
        }

        // Panel
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

        // Title
        val title = TextView(this).apply {
            text = "Stream Quality"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(12))
        }
        panel.addView(title)

        // Quality option items
        qualityOptions.forEachIndexed { index, option ->
            val isSelected = index == currentQualityIndex
            val item = TextView(this).apply {
                text = if (isSelected) "● ${option.label}" else "○ ${option.label}"
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
            panel.addView(item)

            // Auto-focus the current quality
            if (isSelected) {
                item.post { item.requestFocus() }
            }
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

        // Fetch show videos and find the one published right before this one
        val result = api.getVideos(limit = 50, showId = showId)
        val videos = result.getOrNull()
        if (videos != null && videos.size > 1) {
            val currentIndex = videos.indexOfFirst { it.id == current.id }
            // Videos are sorted newest first, so "next" is the one before this in the list (older)
            // But for "next episode" UX, we want the one after in chronological order,
            // which is the one at currentIndex - 1 (newer) in the list
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
            // Cancel any in-flight save to avoid piling up network calls
            saveJob?.cancel()
            saveJob = launch { api.saveProgress(v.id, currentSec, durationSec) }
        }
    }

    private fun releasePlayer() {
        progressJob?.cancel()
        player?.release()
        player = null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // If quality picker is showing, handle back to dismiss
        if (isQualityPickerShowing()) {
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                dismissQualityPicker()
                return true
            }
            // Let the overlay handle DPAD navigation
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
            // Menu button opens quality picker
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
}
