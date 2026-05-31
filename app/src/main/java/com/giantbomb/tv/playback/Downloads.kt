package com.giantbomb.tv.playback

import android.content.Context
import com.giantbomb.tv.model.Video
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Collections

/**
 * App-wide registry of offline downloads. Holds the live state (queued /
 * downloading entries plus the set of already-completed ones loaded from disk)
 * and is the single thing the UI observes via [state]. [VideoDownloadService]
 * does the actual byte-pushing and reports back through the internal mutators.
 *
 * Not to be confused with Media3's DownloadManager — this is a plain
 * OkHttp-backed file downloader so the existing ExoPlayer/cast paths stay
 * untouched (a finished download is just an MP4 played via a file:// URI).
 */
object Downloads {

    private val _state = MutableStateFlow<Map<Int, Download>>(emptyMap())
    val state: StateFlow<Map<Int, Download>> = _state

    // Video ids the user has asked to cancel mid-flight. The service checks this
    // between chunks and aborts. Synchronised because it's touched from the
    // download IO thread and the main thread.
    private val cancelled: MutableSet<Int> = Collections.synchronizedSet(mutableSetOf())

    @Volatile private var loaded = false

    /** Loads completed downloads from disk once, lazily. */
    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        val completed = DownloadStore.listCompleted(context.applicationContext)
            .associateBy { it.videoId }
        _state.value = completed
    }

    fun get(videoId: Int): Download? = _state.value[videoId]

    fun isActive(videoId: Int): Boolean =
        _state.value[videoId]?.status.let {
            it == DownloadStatus.QUEUED || it == DownloadStatus.DOWNLOADING
        }

    /**
     * Queue a download. No-op if the video is already downloaded or in flight.
     * Starts the foreground service which drains the queue.
     */
    fun enqueue(context: Context, video: Video, url: String, qualityLabel: String) {
        ensureLoaded(context)
        val existing = _state.value[video.id]
        if (existing != null &&
            (existing.status == DownloadStatus.COMPLETED ||
                existing.status == DownloadStatus.DOWNLOADING ||
                existing.status == DownloadStatus.QUEUED)
        ) return
        cancelled.remove(video.id)
        put(Download(video, url, qualityLabel, DownloadStatus.QUEUED))
        VideoDownloadService.start(context, video.id)
    }

    /** Cancel an in-flight or queued download and drop its partial file. */
    fun cancel(context: Context, videoId: Int) {
        cancelled.add(videoId)
        remove(videoId)
        DownloadStore.partFile(context.applicationContext, videoId).delete()
    }

    /** Remove a finished download (file + metadata) and forget it. */
    fun delete(context: Context, videoId: Int) {
        cancelled.add(videoId)
        DownloadStore.delete(context.applicationContext, videoId)
        remove(videoId)
    }

    // --- service-facing internals -----------------------------------------

    internal fun isCancelled(videoId: Int): Boolean = cancelled.contains(videoId)

    internal fun clearCancelled(videoId: Int) {
        cancelled.remove(videoId)
    }

    internal fun nextQueued(): Download? =
        _state.value.values.firstOrNull { it.status == DownloadStatus.QUEUED }

    internal fun put(download: Download) {
        _state.value = _state.value + (download.videoId to download)
    }

    internal fun remove(videoId: Int) {
        _state.value = _state.value - videoId
    }
}
