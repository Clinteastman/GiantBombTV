package com.giantbomb.tv.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The videos endpoint pages by whole pages: it serves page
 * floor(offset / limit) + 1, so limit=40&offset=7 returns the first page
 * again. TV show rows load a short first screen, then 40-card pages, and
 * repeated their first cards (15 Nov 2026). [RowPaging] picks page-aligned
 * requests and drops what the row already shows.
 */
class RowPagingTest {
    private val page = 40

    /** Mimics the server: the offset snaps down to a whole page. */
    private fun server(total: Int, limit: Int, offset: Int): List<Int> {
        val start = (offset / limit) * limit
        return (start until minOf(start + limit, total)).toList()
    }

    /** First screen of [first] cards, then pages until the row ends. */
    private fun loadRow(total: Int, first: Int): List<Int> {
        val row = server(total, first, 0).toMutableList()
        var offset = row.size
        var hasMore = row.size == first
        var requests = 0
        while (hasMore) {
            check(++requests < 100) { "paging did not end" }
            val request = RowPaging.next(offset, page)
            val step = RowPaging.take(request, server(total, page, request.offset), page, row.toSet()) { it }
            row.addAll(step.items)
            offset = step.offset
            hasMore = step.hasMore
        }
        return row
    }

    @Test
    fun `7 shown asks the first 40 page and drops 7`() {
        assertEquals(RowPaging.Request(offset = 0, drop = 7), RowPaging.next(7, page))
    }

    @Test
    fun `40 shown asks the second page and drops nothing`() {
        assertEquals(RowPaging.Request(offset = 40, drop = 0), RowPaging.next(40, page))
    }

    @Test
    fun `47 shown asks the second page and drops 7`() {
        assertEquals(RowPaging.Request(offset = 40, drop = 7), RowPaging.next(47, page))
    }

    @Test
    fun `8 videos load once each in order`() {
        assertEquals((0 until 8).toList(), loadRow(total = 8, first = 7))
    }

    @Test
    fun `a show with fewer videos than the first screen needs no more pages`() {
        assertEquals((0 until 4).toList(), loadRow(total = 4, first = 7))
    }

    @Test
    fun `a show with exactly the first screen ends on an empty remainder`() {
        val request = RowPaging.next(7, page)
        val step = RowPaging.take(request, server(7, page, request.offset), page, (0 until 7).toSet()) { it }
        assertTrue(step.items.isEmpty())
        assertEquals(7, step.offset)
        assertFalse(step.hasMore)
    }

    @Test
    fun `a show with exactly 40 videos ends after an empty second page`() {
        val first = RowPaging.take(RowPaging.next(7, page), server(40, page, 0), page, (0 until 7).toSet()) { it }
        assertEquals((7 until 40).toList(), first.items)
        assertEquals(40, first.offset)
        assertTrue(first.hasMore)
        assertEquals((0 until 40).toList(), loadRow(total = 40, first = 7))
    }

    @Test
    fun `long shows load every video once`() {
        for (total in listOf(39, 41, 47, 80, 81, 123, 500)) {
            for (first in 6..10) {
                assertEquals("total $total first $first", (0 until total).toList(), loadRow(total, first))
            }
        }
    }

    @Test
    fun `skips videos the row already holds when the order shifts`() {
        // A new episode landed between requests, so the second page starts
        // with the last video of the first page.
        val held = (0 until 40).toSet()
        val shifted = (39 until 79).toList()
        val step = RowPaging.take(RowPaging.next(40, page), shifted, page, held) { it }
        assertEquals((40 until 79).toList(), step.items)
        assertEquals(80, step.offset)
        assertTrue(step.hasMore)
    }
}
