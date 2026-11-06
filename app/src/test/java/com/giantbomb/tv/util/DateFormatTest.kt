package com.giantbomb.tv.util

import org.junit.Assert.assertEquals
import org.junit.Test

class DateFormatTest {

    @Test
    fun lengthUnderAnHourShowsMinutes() {
        assertEquals("12m", DateFormat.formatLength(12 * 60 + 30))
    }

    @Test
    fun lengthOverAnHourShowsHoursAndMinutes() {
        assertEquals("1h 12m", DateFormat.formatLength(3600 + 12 * 60 + 38))
        assertEquals("3h 0m", DateFormat.formatLength(3 * 3600 + 20))
    }

    @Test
    fun lengthUnderAMinuteRoundsUpToOneMinute() {
        assertEquals("1m", DateFormat.formatLength(45))
    }

    @Test
    fun unknownLengthIsEmpty() {
        assertEquals("", DateFormat.formatLength(0))
        assertEquals("", DateFormat.formatLength(-5))
    }

    @Test
    fun publishDateFormatsBothApiStyles() {
        assertEquals("May 8, 2026", DateFormat.formatPublishDate("2026-05-08 10:00:00"))
        assertEquals("Nov 6, 2026", DateFormat.formatPublishDate("2026-11-06T18:30:00Z"))
    }
}
