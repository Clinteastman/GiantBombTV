package com.giantbomb.tv.util

import com.giantbomb.tv.data.GiantBombApi
import com.giantbomb.tv.data.GiantBombRepository
import com.giantbomb.tv.data.RowPrefetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression test for the crash on Back to TV home (13 Nov 2026): Leanback
 * calls the item-selected listener during a layout pass, the listener
 * started a row load on lifecycleScope (Dispatchers.Main.immediate), the
 * page came from the repository cache without suspending, and the row
 * adapter changed inside the layout pass.
 *
 * UnconfinedTestDispatcher stands in for Dispatchers.Main.immediate (it runs
 * a new coroutine inline until it first suspends) and StandardTestDispatcher
 * for Dispatchers.Main (it queues the work for a later turn).
 */
class AfterLayoutTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /**
     * Stands in for a row adapter inside a RecyclerView. RecyclerView throws
     * when an adapter changes during layout; this records it instead.
     */
    private class FakeRow {
        private var inLayout = false
        var changes = 0
        var changesDuringLayout = 0

        fun change() {
            changes++
            if (inLayout) changesDuringLayout++
        }

        /** Runs [listener] the way Leanback runs onItemSelected: mid-layout. */
        fun layoutPass(listener: () -> Unit) {
            inLayout = true
            try {
                listener()
            } finally {
                inLayout = false
            }
        }
    }

    /** A repository whose first page of show 7 is already cached. */
    private suspend fun warmRepository(): GiantBombRepository {
        server.enqueue(MockResponse().setBody("{\"results\":[]}"))
        val repository = GiantBombRepository.createForTest(
            api = GiantBombApi("secret", server.url("/").toString().removeSuffix("/")),
            nowMs = { 1_000L }
        )
        repository.getShowVideos(showId = 7, limit = 3).getOrThrow()
        assertTrue(repository.hasShowVideos(7, 3))
        return repository
    }

    private fun TestScope.immediateParent(): CoroutineScope =
        CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))

    @Test
    fun cachedPageOnImmediateScopeChangesRowInsideLayout() = runTest {
        // The old behaviour, kept so the model above is known to show the bug.
        val repository = warmRepository()
        val parent = immediateParent()
        val row = FakeRow()

        row.layoutPass {
            parent.launch {
                repository.getShowVideos(showId = 7, limit = 3).getOrThrow()
                row.change()
            }
        }

        assertEquals(1, row.changesDuringLayout)
        parent.cancel()
    }

    @Test
    fun loadMoreOnAfterLayoutScopeChangesRowOnlyAfterLayout() = runTest {
        val repository = warmRepository()
        val parent = immediateParent()
        val row = FakeRow()
        var isLoading = false

        row.layoutPass {
            isLoading = true
            afterLayoutScope(parent, StandardTestDispatcher(testScheduler)).launch {
                repository.getShowVideos(showId = 7, limit = 3).getOrThrow()
                row.change()
            }.invokeOnCompletion { isLoading = false }
        }
        assertEquals(0, row.changes)
        assertTrue("still loading, so a second selection does not load again", isLoading)

        runCurrent()
        assertEquals(1, row.changes)
        assertEquals(0, row.changesDuringLayout)
        assertFalse(isLoading)
        parent.cancel()
    }

    @Test
    fun cachedRowFillFromPrioritizeWaitsForLayoutToEnd() = runTest {
        val repository = warmRepository()
        val parent = immediateParent()
        val row = FakeRow()
        val prefetcher = RowPrefetcher<Int>(
            scope = afterLayoutScope(parent, StandardTestDispatcher(testScheduler)),
            isCached = { repository.hasShowVideos(it, 3) }
        ) { showId ->
            repository.getShowVideos(showId = showId, limit = 3).getOrThrow()
            row.change()
            RowPrefetcher.Outcome.DONE
        }

        row.layoutPass { prefetcher.prioritize(listOf(7)) }
        assertEquals(0, row.changes)
        assertEquals("in flight, so it is not started twice", 1, prefetcher.inFlightCount)

        runCurrent()
        assertEquals(1, row.changes)
        assertEquals(0, row.changesDuringLayout)
        assertTrue(prefetcher.isDone(7))
        parent.cancel()
    }

    @Test
    fun workPostedBeforeTheViewGoesNeverRunsAndClearsLoading() = runTest {
        val parent = immediateParent()
        val row = FakeRow()
        var isLoading = true

        afterLayoutScope(parent, StandardTestDispatcher(testScheduler)).launch {
            row.change()
        }.invokeOnCompletion { isLoading = false }
        parent.cancel()
        runCurrent()

        assertEquals(0, row.changes)
        assertFalse(isLoading)
    }
}
