package com.giantbomb.tv.data

import com.giantbomb.tv.model.UpcomingStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function tests for the upcoming/live resolution pipeline. These are
 * the cases that have actually bitten users on this branch:
 *   - Twitch GQL returns null (e.g. PersistedQueryNotFound) → we must NOT
 *     suppress the GB feed's liveNow.
 *   - Twitch live + GB feed claims live → prefer Twitch's title + image.
 *   - GB feed forgets to retire an entry whose scheduled start time is in
 *     the past, or duplicates the live show under "starting soon".
 */
class UpcomingResolverTest {

    private val nowMs = 1_700_000_000_000L  // arbitrary fixed clock
    private val THIRTY_MIN = 30 * 60 * 1000L

    private fun stream(
        title: String,
        dateMs: Long = 0L,
        isLive: Boolean = false
    ) = UpcomingStream(
        type = "Live Show",
        title = title,
        image = null,
        date = "date-$dateMs",  // opaque — tests inject parseDate
        premium = false,
        isLive = isLive
    )

    /** parseDate stub: maps "date-<ms>" back to that ms; everything else → 0L. */
    private val parseDate: (String) -> Long = { s ->
        s.removePrefix("date-").toLongOrNull() ?: 0L
    }

    // -- resolveLiveNow ------------------------------------------------------

    @Test
    fun resolveLiveNow_twitchUnknown_fallsBackToApiLiveNow() {
        val apiLive = stream("Bombcast", isLive = true)
        val result = UpcomingResolver.resolveLiveNow(apiLive, twitchStatus = null)
        assertEquals(apiLive, result)
    }

    @Test
    fun resolveLiveNow_twitchUnknown_apiOfflineYieldsNull() {
        val result = UpcomingResolver.resolveLiveNow(apiLiveNow = null, twitchStatus = null)
        assertNull(result)
    }

    @Test
    fun resolveLiveNow_twitchOffline_dropsApiLiveNow() {
        val apiLive = stream("Bombcast", isLive = true)
        val twitch = TwitchExtractor.LiveStatus(isLive = false, title = null, previewImageUrl = null)
        assertNull(UpcomingResolver.resolveLiveNow(apiLive, twitch))
    }

    @Test
    fun resolveLiveNow_twitchLive_prefersTwitchTitleAndImage() {
        val apiLive = stream("Stale Title").copy(image = "https://old.jpg")
        val twitch = TwitchExtractor.LiveStatus(
            isLive = true,
            title = "DK64 Sub-A-Thon",
            previewImageUrl = "https://twitch/preview.jpg"
        )
        val resolved = UpcomingResolver.resolveLiveNow(apiLive, twitch)
        assertNotNull(resolved)
        assertEquals("DK64 Sub-A-Thon", resolved!!.title)
        assertEquals("https://twitch/preview.jpg", resolved.image)
        assertTrue(resolved.isLive)
    }

    @Test
    fun resolveLiveNow_twitchLive_butApiOffline_synthesizesEntry() {
        // GB feed missed it but Twitch knows — still surface a live card.
        val twitch = TwitchExtractor.LiveStatus(
            isLive = true,
            title = "DK64 Sub-A-Thon",
            previewImageUrl = "https://twitch/preview.jpg"
        )
        val resolved = UpcomingResolver.resolveLiveNow(apiLiveNow = null, twitchStatus = twitch)
        assertNotNull(resolved)
        assertEquals("DK64 Sub-A-Thon", resolved!!.title)
        assertEquals("https://twitch/preview.jpg", resolved.image)
    }

    @Test
    fun resolveLiveNow_twitchLive_emptyTitle_fallsBackToBaseThenDefault() {
        val twitch = TwitchExtractor.LiveStatus(isLive = true, title = "", previewImageUrl = null)
        val resolved = UpcomingResolver.resolveLiveNow(apiLiveNow = null, twitchStatus = twitch)
        assertEquals("Giant Bomb Live", resolved!!.title)
    }

    // -- filterUpcoming ------------------------------------------------------

    @Test
    fun filterUpcoming_whenLive_dropsAnythingScheduledBeforeNow() {
        val past = stream("Past Show", dateMs = nowMs - 60_000)
        val future = stream("Future Show", dateMs = nowMs + 60_000)
        val live = stream("Live", isLive = true)
        val filtered = UpcomingResolver.filterUpcoming(
            listOf(past, future), live, nowMs, parseDate
        )
        assertEquals(listOf(future), filtered)
    }

    @Test
    fun filterUpcoming_whenOffline_keeps30MinGrace() {
        val withinGrace = stream("Just Past", dateMs = nowMs - 10 * 60 * 1000)
        val beforeGrace = stream("Long Past", dateMs = nowMs - 60 * 60 * 1000)
        val filtered = UpcomingResolver.filterUpcoming(
            listOf(withinGrace, beforeGrace), resolvedLiveNow = null,
            nowMs = nowMs, parseDate = parseDate
        )
        assertEquals(listOf(withinGrace), filtered)
    }

    @Test
    fun filterUpcoming_keepsEntriesWithUnparseableDate() {
        // Real /upcoming_json sometimes ships malformed date strings; parseDate
        // returns 0L for those. We mustn't drop them — they're the only signal
        // the user has that the show exists.
        val unparseable = UpcomingStream(
            type = "Live Show", title = "Mystery Show",
            image = null, date = "garbage", premium = false
        )
        val filtered = UpcomingResolver.filterUpcoming(
            listOf(unparseable), resolvedLiveNow = null,
            nowMs = nowMs, parseDate = parseDate
        )
        assertEquals(listOf(unparseable), filtered)
    }

    @Test
    fun filterUpcoming_dedupsLiveTitleEvenWhenScheduledInFuture() {
        // Timezone weirdness: GB feed's scheduled start is in the future but
        // the show's already live on Twitch. Title-based dedup catches it.
        val live = stream("DK64 Sub-A-Thon", isLive = true)
        val dupe = stream("DK64 Sub-A-Thon", dateMs = nowMs + 60_000)
        val keep = stream("Bombcast", dateMs = nowMs + 60_000)
        val filtered = UpcomingResolver.filterUpcoming(
            listOf(dupe, keep), live, nowMs, parseDate
        )
        assertEquals(listOf(keep), filtered)
    }

    @Test
    fun filterUpcoming_titleDedupIsCaseAndWhitespaceInsensitive() {
        val live = stream("  Giant Bombcast  ", isLive = true)
        val dupe = stream("GIANT BOMBCAST", dateMs = nowMs + 60_000)
        val filtered = UpcomingResolver.filterUpcoming(
            listOf(dupe), live, nowMs, parseDate
        )
        assertTrue(filtered.isEmpty())
    }
}
