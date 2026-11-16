package com.giantbomb.tv.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Back to the TV home screen rebuilt every row, which put every row but
 * the focused one back on its first card and added a new set of Leanback
 * view types each time (15 Nov 2026). [HomeRefresh] says when the rows on
 * screen can be updated where they are, and which of them each fresh row
 * continues.
 */
class HomeRefreshTest {
    private val signature = listOf("key", "simple", listOf("continue", "recent"), setOf<String>(), listOf(7))

    @Test
    fun `same settings and shows update in place`() {
        assertFalse(HomeRefresh.mustRebuild(forceRefresh = false, shown = signature, fresh = signature.toList()))
    }

    @Test
    fun `a forced refresh rebuilds`() {
        assertTrue(HomeRefresh.mustRebuild(forceRefresh = true, shown = signature, fresh = signature))
    }

    @Test
    fun `nothing on screen yet rebuilds`() {
        assertTrue(HomeRefresh.mustRebuild(forceRefresh = false, shown = null, fresh = signature))
    }

    @Test
    fun `a changed setting or show list rebuilds`() {
        val pinnedAnother = signature.dropLast(1) + listOf(listOf(7, 9))
        assertTrue(HomeRefresh.mustRebuild(forceRefresh = false, shown = signature, fresh = pinnedAnother))
    }

    @Test
    fun `unchanged rows each continue the same row`() {
        val rows = listOf("Recent", "☆ A", "☆ B", "Settings")
        assertEquals(listOf(0, 1, 2, 3), HomeRefresh.matchRows(rows, rows.toList()))
    }

    @Test
    fun `a row that appears is new and the rest keep their rows`() {
        // Continue Watching appears at the top after watching part of a video.
        val shown = listOf("Recent", "☆ A", "Settings")
        val fresh = listOf("Continue Watching", "Recent", "☆ A", "Settings")
        assertEquals(listOf(null, 0, 1, 2), HomeRefresh.matchRows(shown, fresh))
    }

    @Test
    fun `a row that goes leaves the rest on their rows`() {
        val shown = listOf("Continue Watching", "Recent", "☆ A")
        val fresh = listOf("Recent", "☆ A")
        assertEquals(listOf(1, 2), HomeRefresh.matchRows(shown, fresh))
    }

    @Test
    fun `a row of another kind under the same title is new`() {
        // The watchlist hint card gives way to real cards: same title, new presenter.
        val shown = listOf("My Watchlist" to "hint", "Recent" to "video")
        val fresh = listOf("My Watchlist" to "video", "Recent" to "video")
        assertEquals(listOf(null, 1), HomeRefresh.matchRows(shown, fresh))
    }

    @Test
    fun `each shown row is continued at most once`() {
        val shown = listOf("A", "B")
        val fresh = listOf("A", "A", "B")
        assertEquals(listOf(0, null, 1), HomeRefresh.matchRows(shown, fresh))
    }

    @Test
    fun `no rows on screen makes every row new`() {
        assertEquals(listOf(null, null), HomeRefresh.matchRows(emptyList(), listOf("A", "B")))
    }
}
