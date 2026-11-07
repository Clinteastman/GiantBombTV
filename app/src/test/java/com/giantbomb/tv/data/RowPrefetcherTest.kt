package com.giantbomb.tv.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RowPrefetcherTest {

    /** Records fetch order and the peak number of fetches running at once. */
    private class Recorder {
        val started = mutableListOf<Int>()
        val startedAt = mutableListOf<Long>()
        var running = 0
        var peak = 0
    }

    private fun TestScope.prefetcher(
        recorder: Recorder,
        maxConcurrent: Int = 3,
        fetchMs: Long = 100L,
        burst: Int = 4,
        minIntervalMs: Long = 0L,
        urgentBurst: Int = 1,
        urgentIntervalMs: Long = 0L,
        cached: Set<Int> = emptySet(),
        outcome: (key: Int, attempt: Int) -> RowPrefetcher.Outcome = { _, _ -> RowPrefetcher.Outcome.DONE }
    ): RowPrefetcher<Int> = RowPrefetcher(
        scope = this,
        maxConcurrent = maxConcurrent,
        maxAttempts = 2,
        initialBackoffMs = 1_000L,
        maxBackoffMs = 4_000L,
        burst = burst,
        minIntervalMs = minIntervalMs,
        urgentBurst = urgentBurst,
        urgentIntervalMs = urgentIntervalMs,
        nowMs = { testScheduler.currentTime },
        isCached = { it in cached }
    ) { key ->
        recorder.started.add(key)
        recorder.startedAt.add(currentTime)
        recorder.running++
        recorder.peak = maxOf(recorder.peak, recorder.running)
        try {
            delay(fetchMs)
            outcome(key, recorder.started.count { it == key })
        } finally {
            recorder.running--
        }
    }

    @Test
    fun backgroundFillRunsInRowOrder() = runTest {
        val r = Recorder()
        val p = prefetcher(r, maxConcurrent = 1)
        p.enqueue(listOf(1, 2, 3, 4, 5))
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 3, 4, 5), r.started)
    }

    @Test
    fun neverRunsMoreThanTheCap() = runTest {
        val r = Recorder()
        val p = prefetcher(r, maxConcurrent = 3)
        p.enqueue((1..20).toList())
        runCurrent()
        assertEquals(3, p.inFlightCount)
        advanceUntilIdle()
        assertEquals(3, r.peak)
        assertEquals((1..20).toList(), r.started)
    }

    @Test
    fun noDuplicateFetches() = runTest {
        val r = Recorder()
        val p = prefetcher(r, maxConcurrent = 2)
        p.enqueue(listOf(1, 2, 3))
        runCurrent()
        // 1 and 2 are in flight, 3 is queued: none of these may start again.
        p.enqueue(listOf(1, 2, 3))
        p.prioritize(listOf(1, 2, 3))
        advanceUntilIdle()
        // Done keys stay done.
        p.enqueue(listOf(1, 2, 3))
        p.prioritize(listOf(3, 2, 1))
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 3), r.started)
        assertTrue(p.isDone(1) && p.isDone(2) && p.isDone(3))
    }

    @Test
    fun prioritizeJumpsTheQueueInGivenOrder() = runTest {
        val r = Recorder()
        val p = prefetcher(r, maxConcurrent = 1)
        p.enqueue(listOf(1, 2, 3, 4, 5, 6))
        runCurrent()
        // The user lands on row 5 while row 1 is loading: 5 and the next
        // row ahead go first, then the background fill carries on.
        p.prioritize(listOf(5, 6))
        assertEquals(listOf(5, 6, 2, 3, 4), p.pendingKeys)
        advanceUntilIdle()
        assertEquals(listOf(1, 5, 6, 2, 3, 4), r.started)
    }

    @Test
    fun rateLimitPausesThenRetriesWithBackoff() = runTest {
        val r = Recorder()
        val p = prefetcher(r, maxConcurrent = 1) { key, attempt ->
            if (key == 2 && attempt <= 2) RowPrefetcher.Outcome.RATE_LIMITED else RowPrefetcher.Outcome.DONE
        }
        p.enqueue(listOf(1, 2, 3))
        advanceTimeBy(250)
        runCurrent()
        // 1 done at 100 ms, 2 hit 429 at 200 ms: nothing runs during the pause.
        assertEquals(listOf(1, 2), r.started)
        advanceTimeBy(900)
        runCurrent()
        assertEquals(listOf(1, 2), r.started)
        // First pause 1 s, second pause doubles to 2 s.
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 2, 2, 3), r.started)
        // 200 (1, 2) + 1000 + 100 (2 again) + 2000 + 100 (2 done) + 100 (3).
        assertEquals(3_500L, currentTime)
    }

    @Test
    fun failedKeyRetriesOnlyWhenAskedAndOnlyUpToTheLimit() = runTest {
        val r = Recorder()
        val p = prefetcher(r, maxConcurrent = 1) { key, _ ->
            if (key == 1) RowPrefetcher.Outcome.FAILED else RowPrefetcher.Outcome.DONE
        }
        p.enqueue(listOf(1, 2))
        advanceUntilIdle()
        p.enqueue(listOf(1))
        advanceUntilIdle()
        assertEquals(listOf(1, 2), r.started)
        repeat(3) {
            p.prioritize(listOf(1))
            advanceUntilIdle()
        }
        // maxAttempts = 2 in all.
        assertEquals(listOf(1, 2, 1), r.started)
    }

    @Test
    fun cancelStopsTheQueue() = runTest {
        val r = Recorder()
        val p = prefetcher(r, maxConcurrent = 2)
        p.enqueue((1..10).toList())
        runCurrent()
        p.cancel()
        advanceUntilIdle()
        assertEquals(listOf(1, 2), r.started)
        assertEquals(0, r.running)
        p.enqueue(listOf(11))
        advanceUntilIdle()
        assertEquals(listOf(1, 2), r.started)
    }

    @Test
    fun networkFetchesArePacedAfterABurst() = runTest {
        val r = Recorder()
        val p = prefetcher(r, maxConcurrent = 5, fetchMs = 10L, burst = 2, minIntervalMs = 1_000L)
        p.enqueue(listOf(1, 2, 3, 4, 5))
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 3, 4, 5), r.started)
        assertEquals(listOf(0L, 0L, 1_000L, 2_000L, 3_000L), r.startedAt)
    }

    @Test
    fun cachedKeysSkipThePacing() = runTest {
        val r = Recorder()
        val p = prefetcher(
            r, maxConcurrent = 5, fetchMs = 10L, burst = 1, minIntervalMs = 1_000L,
            cached = setOf(2, 3, 4)
        )
        p.enqueue(listOf(1, 2, 3, 4, 5))
        advanceUntilIdle()
        // 1 takes the only token; 2-4 come from cache at once; 5 waits a slot.
        assertEquals(listOf(0L, 0L, 0L, 0L, 1_000L), r.startedAt)
    }

    @Test
    fun prioritizedKeysUseTheFasterLane() = runTest {
        val r = Recorder()
        val p = prefetcher(
            r, maxConcurrent = 5, fetchMs = 10L,
            burst = 1, minIntervalMs = 5_000L,
            urgentBurst = 1, urgentIntervalMs = 500L
        )
        p.enqueue(listOf(1, 2, 3, 4, 5))
        runCurrent()
        advanceTimeBy(100)
        // The user reaches rows 4 and 5: 4 starts at once from its own lane,
        // 5 half a second later, while the background lane is still waiting.
        p.prioritize(listOf(4, 5))
        runCurrent()
        advanceUntilIdle()
        assertEquals(listOf(1, 4, 5, 2, 3), r.started)
        assertEquals(listOf(0L, 100L, 600L, 5_000L, 10_000L), r.startedAt)
    }

    @Test
    fun aheadSkipsRowsWithoutKeys() {
        val rows = listOf("live", null, "a", null, "b", "c", "d")
        assertEquals(listOf("a", "b"), RowPrefetcher.ahead(rows, 1, 2))
        assertEquals(listOf("c", "d"), RowPrefetcher.ahead(rows, 4, 5))
        assertEquals(emptyList<String>(), RowPrefetcher.ahead(rows, 6, 2))
    }

    @Test
    fun only429CountsAsRateLimited() {
        val limited = ApiException(429, "/api/public/videos", "", "slow down")
        val forbidden = ApiException(403, "/api/public/videos", "", "no")
        assertTrue(RowPrefetcher.isRateLimited(limited))
        assertTrue(!RowPrefetcher.isRateLimited(forbidden))
        assertTrue(!RowPrefetcher.isRateLimited(java.io.IOException("x")))
    }
}
