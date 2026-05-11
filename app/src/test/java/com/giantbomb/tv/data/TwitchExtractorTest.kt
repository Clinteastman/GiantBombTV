package com.giantbomb.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * parseLiveStatusResponse is the heart of the Twitch live-state check.
 * It used to silently return "not live" when Twitch returned a GQL error
 * (PersistedQueryNotFound after Twitch rotated the hash) — and the upstream
 * caller then dropped the GB feed's liveNow, hiding the live card.
 *
 * The contract we're locking in:
 *   - HTTP != 200             → null (unknown)
 *   - body has `errors` field → null (unknown — GQL-level error, schema rotation, etc.)
 *   - body has no stream      → isLive = false
 *   - body has a stream       → isLive = true + title + preview URL
 *   - unparseable JSON        → null (unknown)
 */
class TwitchExtractorTest {

    private val ex = TwitchExtractor()
    private val now = 1_700_000_400_000L  // fixed clock, on a minute boundary

    @Test
    fun http200_streamPresent_returnsLive() {
        val body = """
            {"data":{"user":{"stream":{"id":"123","title":"DK64 Sub-A-Thon","type":"live"}}}}
        """.trimIndent()
        val status = ex.parseLiveStatusResponse("giantbomb", 200, body, now)
        assertNotNull(status)
        assertTrue(status!!.isLive)
        assertEquals("DK64 Sub-A-Thon", status.title)
        assertNotNull(status.previewImageUrl)
        assertTrue(status.previewImageUrl!!.contains("live_user_giantbomb-1280x720.jpg"))
    }

    @Test
    fun http200_streamNull_returnsOffline() {
        val body = """{"data":{"user":{"stream":null}}}"""
        val status = ex.parseLiveStatusResponse("giantbomb", 200, body, now)
        assertNotNull(status)
        assertFalse(status!!.isLive)
        assertNull(status.title)
        assertNull(status.previewImageUrl)
    }

    @Test
    fun http200_userNull_returnsOffline() {
        val body = """{"data":{"user":null}}"""
        val status = ex.parseLiveStatusResponse("giantbomb", 200, body, now)
        assertNotNull(status)
        assertFalse(status!!.isLive)
    }

    @Test
    fun gqlErrors_returnNullSoCallersCanFallBack() {
        // The exact response Twitch was returning when its persisted-query hash
        // for StreamMetadata rotated. Before the fix, the parser would skip the
        // `errors` key, find no `data`, and return offline — silently hiding
        // the live card.
        val body = """
            {"errors":[{"message":"PersistedQueryNotFound"}]}
        """.trimIndent()
        val status = ex.parseLiveStatusResponse("giantbomb", 200, body, now)
        assertNull("GQL errors must be reported as unknown (null), not offline", status)
    }

    @Test
    fun nonOkHttp_returnsNull() {
        val status = ex.parseLiveStatusResponse("giantbomb", 503, "Service Unavailable", now)
        assertNull(status)
    }

    @Test
    fun unparseableBody_returnsNull() {
        val status = ex.parseLiveStatusResponse("giantbomb", 200, "not json", now)
        assertNull(status)
    }

    @Test
    fun blankTitleNormalisedToNull() {
        val body = """{"data":{"user":{"stream":{"id":"1","title":"","type":"live"}}}}"""
        val status = ex.parseLiveStatusResponse("giantbomb", 200, body, now)
        assertNotNull(status)
        assertTrue(status!!.isLive)
        assertNull(status.title)
    }

    @Test
    fun previewUrlIsCacheBustedPerMinute() {
        // Anchor to a minute boundary (60_000_000 is divisible by 60_000) so
        // the "same minute" arm of the assertion is unambiguous — testing at
        // an arbitrary epoch ms can straddle a bucket if the offset is wrong.
        val body = """{"data":{"user":{"stream":{"id":"1","title":"x","type":"live"}}}}"""
        val start = 60_000_000L                  // bucket 1000
        val a = ex.parseLiveStatusResponse("giantbomb", 200, body, start)
        val b = ex.parseLiveStatusResponse("giantbomb", 200, body, start + 60_000L)  // bucket 1001
        val c = ex.parseLiveStatusResponse("giantbomb", 200, body, start + 59_999L)  // still bucket 1000
        assertNotNull(a); assertNotNull(b); assertNotNull(c)
        assertEquals(a!!.previewImageUrl, c!!.previewImageUrl)
        assertTrue("preview URL should change minute-to-minute", a.previewImageUrl != b!!.previewImageUrl)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidLogin_isRejected() {
        // Channel injection: anything outside [a-zA-Z0-9_]{4,25} is refused
        // before we string-interpolate into the GQL query. Run a suspend fn
        // off the test thread via runBlocking-style trampoline isn't needed —
        // the require() fires synchronously before the coroutine launches.
        kotlinx.coroutines.runBlocking {
            ex.getLiveStatus("bad channel\"; mutation { __typename }")
        }
        fail("expected IllegalArgumentException")
    }
}
