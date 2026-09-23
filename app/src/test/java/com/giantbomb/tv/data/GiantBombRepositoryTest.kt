package com.giantbomb.tv.data

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class GiantBombRepositoryTest {
    private lateinit var server: MockWebServer
    private var clockMs = 1_000L

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun repository(): GiantBombRepository = GiantBombRepository.createForTest(
        api = GiantBombApi("secret", server.url("/").toString().removeSuffix("/")),
        nowMs = { clockMs }
    )

    @Test
    fun repeatedShowsReadUsesCache() = runTest {
        server.enqueue(MockResponse().setBody("{\"results\":[]}"))
        val repository = repository()

        repository.getShows().getOrThrow()
        repository.getShows().getOrThrow()

        assertEquals(1, server.requestCount)
    }

    @Test
    fun simultaneousShowsReadsAreCoalesced() = runTest {
        server.enqueue(MockResponse().setBody("{\"results\":[]}"))
        val repository = repository()

        val first = async { repository.getShows().getOrThrow() }
        val second = async { repository.getShows().getOrThrow() }
        first.await()
        second.await()

        assertEquals(1, server.requestCount)
    }

    @Test
    fun oldestPageIsEvictedButRecentPageIsReused() = runTest {
        repeat(130) { server.enqueue(MockResponse().setBody("{\"results\":[]}")) }
        val repository = repository()
        repeat(129) { page ->
            clockMs++
            repository.getShowVideos(1, 10, page * 10).getOrThrow()
        }
        repository.getShowVideos(1, 10, 1280).getOrThrow()
        assertEquals(129, server.requestCount)
        repository.getShowVideos(1, 10, 0).getOrThrow()
        assertEquals(130, server.requestCount)
    }

    @Test
    fun forcedRefreshBypassesFreshCache() = runTest {
        server.enqueue(MockResponse().setBody("{\"results\":[]}"))
        server.enqueue(MockResponse().setBody("{\"results\":[]}"))
        val repository = repository()

        repository.getShows().getOrThrow()
        repository.getShows(force = true).getOrThrow()

        assertEquals(2, server.requestCount)
    }
}
