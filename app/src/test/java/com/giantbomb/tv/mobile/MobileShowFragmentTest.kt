package com.giantbomb.tv.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class MobileShowFragmentTest {

    @Test
    fun phoneGetsOneFullWidthColumn() {
        assertEquals(1, MobileShowFragment.columnsFor(360))
        assertEquals(1, MobileShowFragment.columnsFor(411))
    }

    @Test
    fun tabletAndLandscapeGetMoreColumns() {
        assertEquals(2, MobileShowFragment.columnsFor(800))
        assertEquals(2, MobileShowFragment.columnsFor(915))
        assertEquals(3, MobileShowFragment.columnsFor(1280))
    }

    @Test
    fun columnsAreCapped() {
        assertEquals(4, MobileShowFragment.columnsFor(4000))
    }

    @Test
    fun durationMatchesThePlayerFormat() {
        assertEquals("4:05", MobileShowFragment.formatDuration(245))
        assertEquals("1:12:38", MobileShowFragment.formatDuration(4358))
    }
}
