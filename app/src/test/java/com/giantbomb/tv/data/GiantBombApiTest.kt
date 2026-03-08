package com.giantbomb.tv.data

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GiantBombApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: GiantBombApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = GiantBombApi("test-api-key", server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // --- validateKey ---

    @Test
    fun `validateKey - premium user`() = runTest {
        server.enqueue(MockResponse()
            .setBody("""{"is_premium": true}""")
            .setResponseCode(200))

        val result = api.validateKey()
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow())
    }

    @Test
    fun `validateKey - free user`() = runTest {
        server.enqueue(MockResponse()
            .setBody("""{"is_premium": false}""")
            .setResponseCode(200))

        val result = api.validateKey()
        assertTrue(result.isSuccess)
        assertFalse(result.getOrThrow())
    }

    @Test
    fun `validateKey - server error returns failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))

        val result = api.validateKey()
        assertTrue(result.isFailure)
    }

    // --- getVideos ---

    @Test
    fun `getVideos - parses video list`() = runTest {
        val video = JSONObject().apply {
            put("id", 42)
            put("slug", "test-video")
            put("title", "Test Video")
            put("description", "A test video")
            put("publish_date", "2024-01-15")
            put("poster_url", "https://example.com/poster.jpg")
            put("premium", true)
            put("length_seconds", 3600)
            put("thumbnail", JSONObject().apply {
                put("url", "https://example.com/thumb.jpg")
            })
            put("show", JSONObject().apply {
                put("id", 5)
                put("title", "Test Show")
            })
        }
        val body = JSONObject().apply {
            put("results", JSONArray().put(video))
        }

        server.enqueue(MockResponse().setBody(body.toString()).setResponseCode(200))

        val result = api.getVideos()
        assertTrue(result.isSuccess)

        val videos = result.getOrThrow()
        assertEquals(1, videos.size)
        assertEquals(42, videos[0].id)
        assertEquals("test-video", videos[0].slug)
        assertEquals("Test Video", videos[0].title)
        assertEquals("A test video", videos[0].description)
        assertTrue(videos[0].premium)
        assertEquals(5, videos[0].showId)
        assertEquals("Test Show", videos[0].showTitle)
        assertEquals(3600, videos[0].durationSeconds)
        assertEquals("https://example.com/thumb.jpg", videos[0].thumbnailUrl)
    }

    @Test
    fun `getVideos - empty results`() = runTest {
        val body = JSONObject().apply {
            put("results", JSONArray())
        }
        server.enqueue(MockResponse().setBody(body.toString()).setResponseCode(200))

        val result = api.getVideos()
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isEmpty())
    }

    @Test
    fun `getVideos - sends correct query params`() = runTest {
        val body = JSONObject().apply { put("results", JSONArray()) }
        server.enqueue(MockResponse().setBody(body.toString()).setResponseCode(200))

        api.getVideos(limit = 10, offset = 20, showId = 5, premium = true, query = "fire")

        val request = server.takeRequest()
        val path = request.path!!
        assertTrue(path.contains("api_key=test-api-key"))
        assertTrue(path.contains("limit=10"))
        assertTrue(path.contains("offset=20"))
        assertTrue(path.contains("video_show=5"))
        assertTrue(path.contains("premium=true"))
        assertTrue(path.contains("q=fire"))
    }

    @Test
    fun `getVideos - thumbnail fallback chain`() = runTest {
        // No thumbnail, but has image.medium_url
        val video = JSONObject().apply {
            put("id", 1)
            put("slug", "v")
            put("title", "V")
            put("publish_date", "2024-01-01")
            put("premium", false)
            put("image", JSONObject().apply {
                put("medium_url", "https://example.com/image_medium.jpg")
            })
        }
        val body = JSONObject().apply { put("results", JSONArray().put(video)) }
        server.enqueue(MockResponse().setBody(body.toString()).setResponseCode(200))

        val result = api.getVideos()
        assertEquals("https://example.com/image_medium.jpg", result.getOrThrow()[0].thumbnailUrl)
    }

    @Test
    fun `getVideos - null description handled`() = runTest {
        val video = JSONObject().apply {
            put("id", 1)
            put("slug", "v")
            put("title", "V")
            put("publish_date", "2024-01-01")
            put("premium", false)
            // description is absent
        }
        val body = JSONObject().apply { put("results", JSONArray().put(video)) }
        server.enqueue(MockResponse().setBody(body.toString()).setResponseCode(200))

        val result = api.getVideos()
        assertNull(result.getOrThrow()[0].description)
    }

    @Test
    fun `getVideos - server error returns failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))

        val result = api.getVideos()
        assertTrue(result.isFailure)
    }

    // --- getShows ---

    @Test
    fun `getShows - parses show list`() = runTest {
        val show = JSONObject().apply {
            put("id", 10)
            put("slug", "test-show")
            put("title", "Test Show")
            put("deck", "A test show")
            put("active", true)
            put("poster_image", JSONObject().apply {
                put("url", "https://example.com/poster.jpg")
            })
            put("logo_image", JSONObject().apply {
                put("url", "https://example.com/logo.jpg")
            })
        }
        val body = JSONObject().apply { put("results", JSONArray().put(show)) }
        server.enqueue(MockResponse().setBody(body.toString()).setResponseCode(200))

        val result = api.getShows()
        assertTrue(result.isSuccess)

        val shows = result.getOrThrow()
        assertEquals(1, shows.size)
        assertEquals(10, shows[0].id)
        assertEquals("Test Show", shows[0].title)
        assertEquals("A test show", shows[0].deck)
        assertTrue(shows[0].active)
        assertEquals("https://example.com/poster.jpg", shows[0].posterUrl)
        assertEquals("https://example.com/logo.jpg", shows[0].logoUrl)
    }

    // --- getPlayback ---

    @Test
    fun `getPlayback - parses playback info with mp4s`() = runTest {
        val body = JSONObject().apply {
            put("title", "Video Title")
            put("premium", JSONObject().apply {
                put("hls_url", "https://example.com/stream.m3u8")
                put("duration", 1800.5)
                put("poster", "https://example.com/poster.jpg")
                put("mp4s", JSONArray().apply {
                    put(JSONObject().apply {
                        put("url", "https://example.com/1080p.mp4")
                        put("width", 1920)
                        put("height", 1080)
                        put("label", "1080p")
                    })
                    put(JSONObject().apply {
                        put("url", "https://example.com/720p.mp4")
                        put("width", 1280)
                        put("height", 720)
                        put("label", "720p")
                    })
                })
            })
        }
        server.enqueue(MockResponse().setBody(body.toString()).setResponseCode(200))

        val result = api.getPlayback(123)
        assertTrue(result.isSuccess)

        val info = result.getOrThrow()
        assertEquals(123, info.videoId)
        assertEquals("Video Title", info.title)
        assertEquals("https://example.com/stream.m3u8", info.hlsUrl)
        assertEquals(1800.5, info.duration, 0.01)
        assertEquals(2, info.mp4s.size)
        assertEquals("1080p", info.mp4s[0].label)
        assertEquals(1920, info.mp4s[0].width)
    }

    @Test
    fun `getPlayback - falls back to free sources`() = runTest {
        val body = JSONObject().apply {
            put("title", "Free Video")
            put("free", JSONObject().apply {
                put("hls_url", "https://example.com/free.m3u8")
                put("duration", 600.0)
            })
        }
        server.enqueue(MockResponse().setBody(body.toString()).setResponseCode(200))

        val result = api.getPlayback(456)
        assertTrue(result.isSuccess)
        assertEquals("https://example.com/free.m3u8", result.getOrThrow().hlsUrl)
    }

    @Test
    fun `getPlayback - parses youtube URL`() = runTest {
        val body = JSONObject().apply {
            put("title", "YT Video")
            put("youtube_url", "https://www.youtube.com/watch?v=abc123def45")
            put("free", JSONObject().apply {
                put("duration", 300.0)
            })
        }
        server.enqueue(MockResponse().setBody(body.toString()).setResponseCode(200))

        val result = api.getPlayback(789)
        assertEquals("https://www.youtube.com/watch?v=abc123def45", result.getOrThrow().youtubeUrl)
    }

    @Test
    fun `getPlayback - filters out invalid youtube URLs`() = runTest {
        val body = JSONObject().apply {
            put("title", "No YT")
            put("youtube_url", "some-random-garbage")
            put("free", JSONObject().apply {
                put("duration", 300.0)
            })
        }
        server.enqueue(MockResponse().setBody(body.toString()).setResponseCode(200))

        val result = api.getPlayback(101)
        assertNull(result.getOrThrow().youtubeUrl)
    }

    // --- getProgress ---

    @Test
    fun `getProgress - parses progress entries`() = runTest {
        val body = JSONObject().apply {
            put("results", JSONArray().apply {
                put(JSONObject().apply {
                    put("video_id", 42)
                    put("current_time", 120.5)
                    put("duration", 3600.0)
                    put("percent_complete", 3)
                })
            })
        }
        server.enqueue(MockResponse().setBody(body.toString()).setResponseCode(200))

        val result = api.getProgress()
        assertTrue(result.isSuccess)

        val entries = result.getOrThrow()
        assertEquals(1, entries.size)
        assertEquals(42, entries[0].videoId)
        assertEquals(120.5, entries[0].currentTime, 0.01)
        assertEquals(3600.0, entries[0].duration, 0.01)
        assertEquals(3, entries[0].percentComplete)
    }

    // --- saveProgress ---

    @Test
    fun `saveProgress - sends correct JSON body`() = runTest {
        server.enqueue(MockResponse().setBody("{}").setResponseCode(200))

        val result = api.saveProgress(42, 120.5, 3600.0)
        assertTrue(result.isSuccess)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        val body = JSONObject(request.body.readUtf8())
        assertEquals(42, body.getInt("video_id"))
        assertEquals(120.5, body.getDouble("current_time"), 0.01)
        assertEquals(3600.0, body.getDouble("duration"), 0.01)
    }

    // --- addToWatchlist / removeFromWatchlist ---

    @Test
    fun `addToWatchlist - sends POST`() = runTest {
        server.enqueue(MockResponse().setBody("{}").setResponseCode(200))

        val result = api.addToWatchlist(42)
        assertTrue(result.isSuccess)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
    }

    @Test
    fun `removeFromWatchlist - sends DELETE`() = runTest {
        server.enqueue(MockResponse().setBody("{}").setResponseCode(200))

        val result = api.removeFromWatchlist(42)
        assertTrue(result.isSuccess)

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
    }

    // --- Request headers ---

    @Test
    fun `requests include required headers`() = runTest {
        val body = JSONObject().apply { put("results", JSONArray()) }
        server.enqueue(MockResponse().setBody(body.toString()).setResponseCode(200))

        api.getVideos()

        val request = server.takeRequest()
        assertNotNull(request.getHeader("User-Agent"))
        assertEquals("application/json", request.getHeader("Accept"))
    }
}
