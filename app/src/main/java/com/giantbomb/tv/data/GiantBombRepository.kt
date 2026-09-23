package com.giantbomb.tv.data

import com.giantbomb.tv.model.ProgressEntry
import com.giantbomb.tv.model.Show
import com.giantbomb.tv.model.UpcomingResponse
import com.giantbomb.tv.model.Video
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * App-wide source of Giant Bomb data.
 *
 * Screens used to instantiate their own API client and re-fetch the same five
 * resources whenever they resumed. This repository keeps small, deliberately
 * short-lived caches and coalesces concurrent requests for the same resource.
 */
class GiantBombRepository private constructor(
    private val api: GiantBombApi,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private data class Entry(val value: Any, val expiresAtMs: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val locks = Array(32) { Mutex() }
    private val showRequestSlots = Semaphore(3)

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> cached(
        key: String,
        ttlMs: Long,
        force: Boolean = false,
        fetch: suspend () -> Result<T>
    ): Result<T> {
        fun fresh(): T? {
            val entry = cache[key] ?: return null
            if (entry.expiresAtMs <= nowMs()) {
                cache.remove(key, entry)
                return null
            }
            return entry.value as T
        }

        if (!force) fresh()?.let { return Result.success(it) }
        return locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            if (!force) fresh()?.let { return@withLock Result.success(it) }
            fetch().onSuccess { putCache(key, Entry(it, nowMs() + ttlMs)) }
        }
    }

    @Synchronized
    private fun putCache(key: String, entry: Entry) {
        val now = nowMs()
        cache.entries.forEach { if (it.value.expiresAtMs <= now) cache.remove(it.key, it.value) }
        while (cache.size >= 128 && !cache.containsKey(key)) {
            val oldest = cache.entries.minByOrNull { it.value.expiresAtMs } ?: break
            cache.remove(oldest.key, oldest.value)
        }
        cache[key] = entry
    }

    suspend fun getUpcoming(force: Boolean = false): Result<UpcomingResponse> =
        cached("upcoming", UPCOMING_TTL_MS, force) { api.getUpcoming() }

    suspend fun getWatchlist(force: Boolean = false): Result<List<Video>> =
        cached("watchlist", USER_DATA_TTL_MS, force) { api.getWatchlist() }

    suspend fun getProgress(force: Boolean = false): Result<List<ProgressEntry>> =
        cached("progress", USER_DATA_TTL_MS, force) { api.getProgress() }

    suspend fun getRecentVideos(limit: Int, force: Boolean = false): Result<List<Video>> =
        cached("videos:recent:$limit", RECENT_TTL_MS, force) { api.getVideos(limit = limit) }

    suspend fun getShows(force: Boolean = false): Result<List<Show>> =
        cached("shows", SHOWS_TTL_MS, force) { api.getShows() }

    suspend fun getShowVideos(
        showId: Int,
        limit: Int,
        offset: Int = 0,
        force: Boolean = false
    ): Result<List<Video>> {
        val key = "videos:show:$showId:$limit:$offset"
        return cached(key, SHOW_VIDEOS_TTL_MS, force) {
            showRequestSlots.withPermit { api.getShowVideos(showId, limit, offset) }
        }
    }

    suspend fun searchVideos(query: String, limit: Int = 30): Result<List<Video>> =
        api.getVideos(limit = limit, query = query)

    suspend fun getPlayback(videoId: Int) = api.getPlayback(videoId)

    suspend fun addToWatchlist(video: Video): Result<Unit> =
        api.addToWatchlist(video.id).onSuccess {
            val current = cachedValue<List<Video>>("watchlist")
            if (current != null) {
                cache["watchlist"] = Entry(
                    (listOf(video) + current.filterNot { it.id == video.id }),
                    nowMs() + USER_DATA_TTL_MS
                )
            } else {
                cache.remove("watchlist")
            }
        }

    suspend fun removeFromWatchlist(videoId: Int): Result<Unit> =
        api.removeFromWatchlist(videoId).onSuccess {
            cachedValue<List<Video>>("watchlist")?.let { current ->
                cache["watchlist"] = Entry(
                    current.filterNot { it.id == videoId },
                    nowMs() + USER_DATA_TTL_MS
                )
            }
        }

    suspend fun saveProgress(videoId: Int, currentTime: Double, duration: Double): Result<Unit> =
        api.saveProgress(videoId, currentTime, duration).onSuccess {
            updateProgress(videoId, currentTime, duration)
        }

    suspend fun markWatched(videoId: Int): Result<Unit> =
        api.markWatched(videoId).onSuccess {
            val existing = cachedValue<List<ProgressEntry>>("progress")
                ?.firstOrNull { it.videoId == videoId }
            updateProgress(videoId, existing?.duration ?: 1.0, existing?.duration ?: 1.0)
        }

    fun updateProgress(videoId: Int, currentTime: Double, duration: Double) {
        if (duration <= 0.0) return
        val current = cachedValue<List<ProgressEntry>>("progress") ?: return
        val percent = ((currentTime / duration) * 100).toInt().coerceIn(0, 100)
        val replacement = ProgressEntry(videoId, currentTime, duration, percent)
        cache["progress"] = Entry(
            listOf(replacement) + current.filterNot { it.videoId == videoId },
            nowMs() + USER_DATA_TTL_MS
        )
    }

    fun invalidateUserData() {
        cache.remove("watchlist")
        cache.remove("progress")
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> cachedValue(key: String): T? {
        val entry = cache[key] ?: return null
        if (entry.expiresAtMs <= nowMs()) return null
        return entry.value as? T
    }

    companion object {
        private const val UPCOMING_TTL_MS = 60_000L
        private const val USER_DATA_TTL_MS = 30_000L
        private const val RECENT_TTL_MS = 3 * 60_000L
        private const val SHOW_VIDEOS_TTL_MS = 10 * 60_000L
        private const val SHOWS_TTL_MS = 6 * 60 * 60_000L

        private val instances = ConcurrentHashMap<String, GiantBombRepository>()

        fun get(apiKey: String): GiantBombRepository =
            instances.getOrPut(apiKey) { GiantBombRepository(GiantBombApi(apiKey)) }

        internal fun createForTest(
            api: GiantBombApi,
            nowMs: () -> Long
        ): GiantBombRepository = GiantBombRepository(api, nowMs)

        fun clearAll() = instances.clear()
    }
}
