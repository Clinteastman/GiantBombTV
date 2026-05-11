package com.giantbomb.tv.ui

import com.giantbomb.tv.ui.UpcomingCardView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * The `/upcoming_json` feed publishes dates in Giant Bomb's local (Pacific)
 * timezone using a handful of slightly inconsistent format strings — the
 * parser has to tolerate single- vs zero-padded days and 12- vs 1-digit
 * hours. parseDate() returning 0L silently breaks both the countdown and
 * the UpcomingResolver dedup window, so it's worth pinning down.
 */
class UpcomingCardViewParseDateTest {

    /** Convenience: expected epoch ms for a given PT local time. */
    private fun pt(spec: String, pattern: String = "MMM d, yyyy h:mm a"): Long {
        val sdf = SimpleDateFormat(pattern, Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("America/Los_Angeles")
        return sdf.parse(spec)!!.time
    }

    @Test
    fun parsesTwoDigitDayTwoDigitHour() {
        val parsed = UpcomingCardView.parseDate("May 12, 2026 09:00 AM")
        assertEquals(pt("May 12, 2026 9:00 AM"), parsed)
    }

    @Test
    fun parsesSingleDigitDay() {
        val parsed = UpcomingCardView.parseDate("May 5, 2026 09:00 AM")
        assertEquals(pt("May 5, 2026 9:00 AM"), parsed)
    }

    @Test
    fun parsesSingleDigitHour() {
        val parsed = UpcomingCardView.parseDate("May 12, 2026 9:00 AM")
        assertEquals(pt("May 12, 2026 9:00 AM"), parsed)
    }

    @Test
    fun parsesPmHour() {
        val parsed = UpcomingCardView.parseDate("May 12, 2026 12:00 PM")
        assertTrue(parsed > 0L)
        assertEquals(pt("May 12, 2026 12:00 PM"), parsed)
    }

    @Test
    fun emptyStringReturnsZero() {
        assertEquals(0L, UpcomingCardView.parseDate(""))
    }

    @Test
    fun garbageReturnsZero() {
        assertEquals(0L, UpcomingCardView.parseDate("not a date"))
        assertEquals(0L, UpcomingCardView.parseDate("2026-05-12T09:00:00Z"))
    }
}
