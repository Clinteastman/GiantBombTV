package com.giantbomb.tv.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
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
import com.giantbomb.tv.MainActivity
import com.giantbomb.tv.PlaybackActivity
import com.giantbomb.tv.data.GiantBombRepository
import com.giantbomb.tv.data.PrefsManager
import com.giantbomb.tv.model.Video
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
    private lateinit var repository: GiantBombRepository
    private var progressJob: Job? = null
    private var playerListener: Player.Listener? = null
    private var stoppingForExit = false
    // Identifies the current exit attempt. Each deferred stop only fires if
    // its own attempt is still current, so an older save finishing late can't
    // stop the service before a newer exit's save has landed.
    private var exitGeneration = 0

    override fun onCreate() {
        super.onCreate()
        repository = GiantBombRepository.get(PrefsManager(this).apiKey ?: "")

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

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(buildSessionActivity(null))
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
                        repository.markWatched(videoId)
                    }
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                mediaSession?.setSessionActivity(buildSessionActivity(mediaItem))
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    // Playback restarted (e.g. the player was reopened) while an
                    // exit save was in flight: keep the service alive.
                    cancelPendingExit()
                    startProgressSaving()
                } else {
                    stopProgressSaving()
                    // Flush a final position when playback pauses so resumption
                    // is accurate without waiting for the periodic tick.
                    if (!stoppingForExit) {
                        serviceScope.launch { saveCurrentProgress() }
                    }
                }
            }
        }.also { player.addListener(it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SAVE_PROGRESS_AND_STOP) {
            saveProgressAndStop()
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun saveProgressAndStop() {
        if (stoppingForExit) return
        stoppingForExit = true
        val token = ++exitGeneration
        stopProgressSaving()

        val player = mediaSession?.player
        val videoId = currentVodId()
        val positionSeconds = player?.currentPosition?.div(1000.0) ?: 0.0
        val durationSeconds = player?.duration?.div(1000.0) ?: 0.0
        player?.pause()

        serviceScope.launch {
            if (videoId != null && positionSeconds > 0 && durationSeconds > 0) {
                repository.saveProgress(videoId, positionSeconds, durationSeconds)
            }
            // Skip the stop if the player was reopened during the save.
            if (stoppingForExit && exitGeneration == token) stopSelf()
        }
    }

    private fun cancelPendingExit() {
        stoppingForExit = false
        exitGeneration++
    }

    private fun buildSessionActivity(mediaItem: MediaItem?): PendingIntent {
        val metadata = mediaItem?.mediaMetadata?.extras
        val intent = when {
            mediaItem?.mediaId?.startsWith("vod:") == true -> {
                videoFromMetadata(metadata)?.let { video ->
                    Intent(this, PlaybackActivity::class.java)
                        .putExtra(PlaybackActivity.EXTRA_VIDEO, video)
                } ?: Intent(this, MainActivity::class.java)
            }
            mediaItem?.mediaId?.startsWith("live:") == true -> {
                val hlsUrl = metadata?.getString(PlaybackActivity.METADATA_LIVE_HLS_URL)
                if (hlsUrl.isNullOrBlank()) {
                    Intent(this, MainActivity::class.java)
                } else {
                    Intent(this, PlaybackActivity::class.java)
                        .putExtra(PlaybackActivity.EXTRA_LIVE_HLS_URL, hlsUrl)
                        .putExtra(
                            PlaybackActivity.EXTRA_LIVE_TITLE,
                            metadata.getString(PlaybackActivity.METADATA_LIVE_TITLE)
                        )
                        .putExtra(
                            PlaybackActivity.EXTRA_LIVE_TWITCH_CHANNEL,
                            metadata.getString(PlaybackActivity.METADATA_LIVE_CHANNEL)
                        )
                }
            }
            else -> Intent(this, MainActivity::class.java)
        }.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun videoFromMetadata(extras: Bundle?): Video? {
        if (extras == null || !extras.containsKey(PlaybackActivity.METADATA_VIDEO_ID)) return null
        return Video(
            id = extras.getInt(PlaybackActivity.METADATA_VIDEO_ID),
            slug = extras.getString(PlaybackActivity.METADATA_VIDEO_SLUG).orEmpty(),
            title = extras.getString(PlaybackActivity.METADATA_VIDEO_TITLE).orEmpty(),
            description = extras.getString(PlaybackActivity.METADATA_VIDEO_DESCRIPTION),
            publishDate = extras.getString(PlaybackActivity.METADATA_VIDEO_PUBLISH_DATE).orEmpty(),
            posterUrl = extras.getString(PlaybackActivity.METADATA_VIDEO_POSTER_URL),
            premium = extras.getBoolean(PlaybackActivity.METADATA_VIDEO_PREMIUM),
            showId = extras.takeIf { it.containsKey(PlaybackActivity.METADATA_VIDEO_SHOW_ID) }
                ?.getInt(PlaybackActivity.METADATA_VIDEO_SHOW_ID),
            showTitle = extras.getString(PlaybackActivity.METADATA_VIDEO_SHOW_TITLE),
            author = extras.getString(PlaybackActivity.METADATA_VIDEO_AUTHOR),
            thumbnailUrl = extras.getString(PlaybackActivity.METADATA_VIDEO_THUMBNAIL_URL),
            durationSeconds = extras.getInt(PlaybackActivity.METADATA_VIDEO_DURATION)
        )
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
            repository.saveProgress(videoId, pos, dur)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        // Our own player reconnecting means the user reopened playback, so a
        // pending exit stop must not tear the new session down.
        if (controllerInfo.packageName == packageName) cancelPendingExit()
        return mediaSession
    }

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
        const val ACTION_SAVE_PROGRESS_AND_STOP =
            "com.giantbomb.tv.action.SAVE_PROGRESS_AND_STOP"
        private const val PROGRESS_SAVE_INTERVAL_MS = 30_000L
    }
}
