package com.giantbomb.tv.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Fills browse rows in the background before the user reaches them.
 *
 * Keys run in queue order with at most [maxConcurrent] fetches at once.
 * [enqueue] adds rows to the back (the startup fill, in the user's row
 * order); [prioritize] moves rows to the front (the focused row and the
 * rows just ahead of it). A key is never fetched twice once it succeeds,
 * and never while it is already in flight.
 *
 * Network fetches are paced in two lanes. The site sits behind Cloudflare,
 * which answered 429 once one address passed about 40 API requests a
 * minute in testing (6 Nov 2026), and a home screen has 30+ show rows.
 * So the background fill starts [burst] at
 * once, then one every [minIntervalMs]; keys the user is about to see
 * (anything passed to [prioritize]) use their own, faster allowance of
 * [urgentBurst] then one every [urgentIntervalMs]. Either way each row costs
 * at most one request, as before. Keys that [isCached] says are already
 * cached skip the pacing, so rebuilding the screen after coming back from a
 * video is instant.
 *
 * When a fetch reports [Outcome.RATE_LIMITED] (HTTP 429) the whole queue
 * pauses, starting at [initialBackoffMs] and doubling up to [maxBackoffMs],
 * and the key goes back to the front. A failed key is not retried by the
 * background fill, only by an explicit [prioritize], and at most
 * [maxAttempts] times in all.
 *
 * Not thread safe: call it from one thread (the main thread in the app).
 * Cancelling [scope], or calling [cancel], stops every fetch.
 */
class RowPrefetcher<K : Any>(
    private val scope: CoroutineScope,
    private val maxConcurrent: Int = 3,
    private val maxAttempts: Int = 3,
    private val initialBackoffMs: Long = 5_000L,
    private val maxBackoffMs: Long = 60_000L,
    private val burst: Int = 6,
    private val minIntervalMs: Long = 4_000L,
    private val urgentBurst: Int = 3,
    private val urgentIntervalMs: Long = 600L,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val isCached: (K) -> Boolean = { false },
    private val fetch: suspend (K) -> Outcome
) {
    enum class Outcome { DONE, FAILED, RATE_LIMITED }

    // Jobs this queue started, so cancel() stops only its own work and the
    // scope stays usable for the rest of the screen.
    private val jobs = mutableSetOf<Job>()
    private var cancelled = false
    private val pending = ArrayDeque<K>()
    private val inFlight = mutableSetOf<K>()
    private val done = mutableSetOf<K>()
    private val failed = mutableSetOf<K>()
    private val attempts = mutableMapOf<K, Int>()
    private var backoffMs = initialBackoffMs
    private var pausedJob: Job? = null
    private var waitJob: Job? = null
    private var waitUntilMs = 0L
    private val urgent = mutableSetOf<K>()

    /** Token bucket: [size] at once, then one more every [intervalMs]. */
    private inner class Lane(private val size: Int, private val intervalMs: Long) {
        private var tokens = size
        private var lastRefillMs = nowMs()

        private fun refill() {
            val now = nowMs()
            val gained = ((now - lastRefillMs) / intervalMs).toInt()
            if (gained > 0) {
                tokens = minOf(size, tokens + gained)
                lastRefillMs += gained * intervalMs
            }
            // A full bucket stores no extra credit.
            if (tokens >= size) lastRefillMs = now
        }

        fun take(): Boolean {
            if (intervalMs <= 0) return true
            refill()
            if (tokens <= 0) return false
            tokens--
            return true
        }

        /** Milliseconds until [take] can succeed again. */
        fun waitMs(): Long = (intervalMs - (nowMs() - lastRefillMs)).coerceIn(1L, intervalMs)
    }

    private val backgroundLane = Lane(burst, minIntervalMs)
    private val urgentLane = Lane(urgentBurst, urgentIntervalMs)

    val inFlightCount: Int get() = inFlight.size
    val pendingKeys: List<K> get() = pending.toList()
    fun isDone(key: K): Boolean = key in done

    /** Adds keys to the back of the queue, in order. Known keys are skipped. */
    fun enqueue(keys: Iterable<K>) {
        for (key in keys) {
            if (key in done || key in inFlight || key in failed || key in pending) continue
            pending.addLast(key)
        }
        pump()
    }

    /**
     * Moves keys to the front of the queue, keeping their given order. Also
     * gives a failed key another try, up to [maxAttempts].
     */
    fun prioritize(keys: List<K>) {
        for (key in keys.asReversed()) {
            if (key in done || key in inFlight) continue
            if (key in failed) {
                if ((attempts[key] ?: 0) >= maxAttempts) continue
                failed.remove(key)
            }
            pending.remove(key)
            pending.addFirst(key)
            urgent.add(key)
        }
        pump()
    }

    fun cancel() {
        cancelled = true
        pending.clear()
        jobs.toList().forEach { it.cancel() }
        jobs.clear()
    }

    private fun start(block: suspend () -> Unit) {
        val launched = scope.launch { block() }
        jobs.add(launched)
        launched.invokeOnCompletion { jobs.remove(launched) }
    }

    private fun pump() {
        if (cancelled || !scope.isActive || pausedJob?.isActive == true) return
        while (inFlight.size < maxConcurrent && pending.isNotEmpty()) {
            val key = pending.first()
            val lane = if (key in urgent) urgentLane else backgroundLane
            if (!isCached(key) && !lane.take()) {
                waitForToken(lane.waitMs())
                return
            }
            pending.removeFirst()
            urgent.remove(key)
            inFlight.add(key)
            start { run(key) }
        }
    }

    private fun waitForToken(wait: Long) {
        val until = nowMs() + wait
        if (waitJob?.isActive == true) {
            // An urgent key may need an earlier wake-up than the one set.
            if (until >= waitUntilMs) return
            waitJob?.cancel()
        }
        waitUntilMs = until
        waitJob = scope.launch {
            delay(wait)
            waitJob = null
            pump()
        }.also { waiting ->
            jobs.add(waiting)
            waiting.invokeOnCompletion { jobs.remove(waiting) }
        }
    }

    private suspend fun run(key: K) {
        val outcome = try {
            fetch(key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.FAILED
        } finally {
            inFlight.remove(key)
        }
        when (outcome) {
            Outcome.DONE -> {
                done.add(key)
                backoffMs = initialBackoffMs
            }
            Outcome.FAILED -> {
                attempts[key] = (attempts[key] ?: 0) + 1
                failed.add(key)
            }
            Outcome.RATE_LIMITED -> {
                if (key !in pending) pending.addFirst(key)
                pause()
                return
            }
        }
        pump()
    }

    private fun pause() {
        if (pausedJob?.isActive == true) return
        val wait = backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(maxBackoffMs)
        pausedJob = scope.launch {
            delay(wait)
            pausedJob = null
            pump()
        }.also { paused ->
            jobs.add(paused)
            paused.invokeOnCompletion { jobs.remove(paused) }
        }
    }

    companion object {
        /**
         * The next [count] keys after position [from] in [ordered], skipping
         * entries without a key (rows that are not prefetchable).
         */
        fun <K : Any> ahead(ordered: List<K?>, from: Int, count: Int): List<K> {
            if (count <= 0) return emptyList()
            val out = ArrayList<K>(count)
            var i = from + 1
            while (i < ordered.size && out.size < count) {
                ordered[i]?.let { out.add(it) }
                i++
            }
            return out
        }

        /** True when [error] is an HTTP 429 from the API. */
        fun isRateLimited(error: Throwable?): Boolean =
            (error as? ApiException)?.httpCode == 429
    }
}
