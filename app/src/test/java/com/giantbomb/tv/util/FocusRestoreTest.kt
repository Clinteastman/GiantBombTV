package com.giantbomb.tv.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Back from the detail page rebuilds the TV home rows, which used to put
 * focus on the first card of the row (15 Nov 2026). [FocusRestore.find]
 * says which card of the rebuilt rows should get focus back.
 */
class FocusRestoreTest {
    private val rows = listOf(
        "Continue Watching" to listOf<Any?>(11, 12, 13),
        "Recent Videos" to listOf<Any?>(21, 22, 23, 24, 25, 26, 27, 28),
        "☆ Some Show" to listOf<Any?>(31, 32, 33, 34)
    )

    @Test
    fun `finds the same card in the same row`() {
        val target = FocusRestore.Target("Recent Videos", cardKey = 26, cardIndex = 5)
        assertEquals(1 to 5, FocusRestore.find(rows, target))
    }

    @Test
    fun `finds the row by title when rows above it were added or removed`() {
        // A Continue Watching row appears at the top after watching something.
        val target = FocusRestore.Target("☆ Some Show", cardKey = 33, cardIndex = 2)
        assertEquals(2 to 2, FocusRestore.find(rows, target))
        assertEquals(1 to 2, FocusRestore.find(rows.drop(1), target))
    }

    @Test
    fun `follows the card when it moved inside its row`() {
        // The card was 3rd; after watching it is now 1st in Continue Watching.
        val target = FocusRestore.Target("Continue Watching", cardKey = 13, cardIndex = 2)
        val moved = listOf("Continue Watching" to listOf<Any?>(13, 11, 12))
        assertEquals(0 to 0, FocusRestore.find(moved, target))
    }

    @Test
    fun `keeps the position when the card has left its row`() {
        // A finished video drops out of Continue Watching.
        val target = FocusRestore.Target("Continue Watching", cardKey = 99, cardIndex = 1)
        assertEquals(0 to 1, FocusRestore.find(rows, target))
    }

    @Test
    fun `clamps the position to the end of a shorter row`() {
        val target = FocusRestore.Target("Continue Watching", cardKey = 99, cardIndex = 7)
        assertEquals(0 to 2, FocusRestore.find(rows, target))
    }

    @Test
    fun `gives up when the row is gone`() {
        val target = FocusRestore.Target("Hidden Row", cardKey = 11, cardIndex = 0)
        assertNull(FocusRestore.find(rows, target))
    }

    @Test
    fun `selects the row when it has no cards`() {
        val target = FocusRestore.Target("Empty", cardKey = 1, cardIndex = 3)
        assertEquals(0 to 0, FocusRestore.find(listOf("Empty" to emptyList()), target))
    }
}
