package com.giantbomb.tv.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.giantbomb.tv.PlaybackActivity
import com.giantbomb.tv.data.GiantBombApi
import com.giantbomb.tv.data.PrefsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Most media3.exoplayer + DefaultAudioSink APIs are still marked @UnstableApi.
// We rely on them deliberately (custom renderer, PCM buffer sizing, load control),
// so opt in at the class level rather than annotating every call site.
// Uses androidx.annotation.OptIn — Kotlin's stdlib OptIn doesn't satisfy
// media3's androidx-flavoured @RequiresOptIn marker.
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var api: GiantBombApi
    private var progressJob: Job? = null
    private var playerListener: Player.Listener? = null

    override fun onCreate() {
        super.onCreate()
        api = GiantBombApi(PrefsManager(this).apiKey ?: "")

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

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
            ): AudioSink = audioSink
        }
            .forceEnableMediaCodecAsynchronousQueueing()
            .setEnableDecoderFallback(true)

        val player = ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        val sessionActivityIntent = Intent(this, PlaybackActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val sessionActivityPi = PendingIntent.getActivity(
            this,
            0,
            sessionActivityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityPi)
            .build()

        // Periodic progress save + watched marking live on the service so they
        // keep firing when the activity is backgrounded, in PiP, or destroyed.
        // Reads the current MediaItem at callback time, so a media swap on the
        // same controller can't fire against a stale video id.
        playerListener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    val videoId = currentVodId() ?: return
                    serviceScope.launch {
                        saveCurrentProgress()
                        api.markWatched(videoId)
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    startProgressSaving()
                } else {
                    stopProgressSaving()
                    // Flush a final position when playback pauses so resumption
                    // is accurate without waiting for the periodic tick.
                    serviceScope.launch { saveCurrentProgress() }
                }
            }
        }.also { player.addListener(it) }
    }

    private fun currentVodId(): Int? {
        val mediaId = mediaSession?.player?.currentMediaItem?.mediaId ?: return null
        return mediaId.removePrefix("vod:").toIntOrNull()
    }

    private fun startProgressSaving() {
        if (progressJob?.isActive == true) return
        progressJob = serviceScope.launch {
            while (isActive) {
                delay(PROGRESS_SAVE_INTERVAL_MS)
                saveCurrentProgress()
            }
        }
    }

    private fun stopProgressSaving() {
        progressJob?.cancel()
        progressJob = null
    }

    private suspend fun saveCurrentProgress() {
        val player = mediaSession?.player ?: return
        val videoId = currentVodId() ?: return
        val pos = player.currentPosition / 1000.0
        val dur = player.duration / 1000.0
        if (pos > 0 && dur > 0) {
            api.saveProgress(videoId, pos, dur)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    // Keep the stream going when the user swipes the app from recents; only
    // stop if there is nothing queued / playback is paused.
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        playerListener?.let { mediaSession?.player?.removeListener(it) }
        playerListener = null
        progressJob?.cancel()
        progressJob = null
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        private const val PROGRESS_SAVE_INTERVAL_MS = 30_000L
    }
}
