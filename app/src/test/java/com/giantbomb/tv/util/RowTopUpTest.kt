package com.giantbomb.tv.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Since PR 44 a show row on the TV home screen keeps its cards on Back, so
 * it no longer picked up a new episode once the 10 minute show cache had
 * expired (24 Nov 2026). [RowTopUp] reads a fresh first page against the
 * cards the row holds: new videos go at the front, and the row's paging
 * offset moves so the next page neither repeats nor skips a card.
 */
class RowTopUpTest {
    private val page = 40

    /** Mimics the server: the offset snaps down to a whole page of [order]. */
    private fun server(order: List<Int>, limit: Int, offset: Int): List<Int> {
        val start = (offset / limit) * limit
        return order.subList(minOf(start, order.size), minOf(start + limit, order.size))
    }

    /** A row like the app's: its cards, how far it reaches, whether more may follow. */
    private class Row(val cards: MutableList<Int>, var offset: Int, var hasMore: Boolean)

    private fun firstScreen(order: List<Int>, first: Int): Row {
        val cards = server(order, first, 0).toMutableList()
        return Row(cards, cards.size, cards.size == first)
    }

    /** One loadMoreForRow against [order]: pages on while a page adds nothing. */
    private fun more(row: Row, order: List<Int>) {
        do {
            val request = RowPaging.next(row.offset, page)
            val step = RowPaging.take(request, server(order, page, request.offset), page, row.cards.toSet()) { it }
            row.cards.addAll(step.items)
            row.offset = step.offset
            if (!step.hasMore) row.hasMore = false
        } while (step.items.isEmpty() && row.hasMore)
    }

    private fun pageToEnd(row: Row, order: List<Int>) {
        var requests = 0
        while (row.hasMore) {
            check(++requests < 100) { "paging did not end" }
            more(row, order)
        }
    }

    /** The app's top-up on return to home: a fresh first page of [first] cards. */
    private fun topUp(row: Row, order: List<Int>, first: Int) {
        val fetched = server(order, first, 0)
        val plan = RowTopUp.plan(row.cards, fetched) { it } ?: return
        if (plan.replace) {
            row.cards.clear()
            row.hasMore = fetched.size >= first
        }
        row.cards.addAll(0, plan.insert)
        row.offset = plan.offset
    }

    /** Videos 0 until [total], newest first, with [new] newer ones (ids 1000+) ahead. */
    private fun order(total: Int, new: Int = 0): List<Int> =
        (1000 until 1000 + new).reversed() + (0 until total)

    @Test
    fun `new videos at the head go to the front in server order`() {
        val held = (0 until 7).toList()
        val plan = RowTopUp.plan(held, listOf(1001, 1000, 0, 1, 2, 3, 4)) { it }
        assertEquals(listOf(1001, 1000), plan!!.insert)
        assertFalse(plan.replace)
    }

    @Test
    fun `videos the row already holds are not repeated`() {
        val held = (0 until 7).toList()
        val plan = RowTopUp.plan(held, listOf(1000, 0, 1, 2, 3, 4, 5)) { it }!!
        assertEquals(listOf(1000), plan.insert)
        assertTrue(plan.insert.none { it in held })
    }

    @Test
    fun `no new videos means no change`() {
        val held = (0 until 47).toList()
        assertNull(RowTopUp.plan(held, (0 until 7).toList()) { it })
    }

    @Test
    fun `an empty page changes nothing`() {
        assertNull(RowTopUp.plan((0 until 7).toList(), emptyList<Int>()) { it })
    }

    @Test
    fun `offset stops at the last held video in the page`() {
        // Past the page the row cannot tell what was added or deleted, so it
        // pages again from there and skips the cards it already holds.
        val plan = RowTopUp.plan((0 until 47).toList(), listOf(1002, 1001, 1000, 0, 1, 2, 3)) { it }!!
        assertEquals(7, plan.offset)
    }

    @Test
    fun `next page after a top-up of a first-screen row has no repeats and no gaps`() {
        for (first in 6..10) {
            for (new in 1 until first) {
                val row = firstScreen(order(100), first)
                topUp(row, order(100, new), first)
                pageToEnd(row, order(100, new))
                assertEquals("first $first new $new", order(100, new), row.cards)
            }
        }
    }

    @Test
    fun `next page after a top-up of a paged row has no repeats and no gaps`() {
        for (total in listOf(41, 47, 80, 81, 123)) {
            for (new in 1..5) {
                val row = firstScreen(order(total), 7)
                more(row, order(total))
                topUp(row, order(total, new), 7)
                pageToEnd(row, order(total, new))
                assertEquals("total $total new $new", order(total, new), row.cards)
            }
        }
    }

    @Test
    fun `a video published between the first screen and the next page is recovered`() {
        // The next page already counted the new video's place but dropped it
        // with the first screen, so the top-up must not count it twice.
        val row = firstScreen(order(100), 7)
        more(row, order(100, 1))
        assertFalse(1000 in row.cards)
        topUp(row, order(100, 1), 7)
        pageToEnd(row, order(100, 1))
        assertEquals(order(100, 1), row.cards)
    }

    @Test
    fun `a deleted video in the first page does not skip a card`() {
        val row = firstScreen(order(100), 7)
        more(row, order(100))
        val after = listOf(1000) + (0 until 100).filter { it != 3 }
        topUp(row, after, 7)
        pageToEnd(row, after)
        // The deleted card stays where it was; every other video shows once.
        assertEquals(listOf(1000) + (0 until 100).toList(), row.cards)
    }

    @Test
    fun `a deleted video past the first page does not skip a card`() {
        // Codex review on PR 46: held 0..39, new 1000, 20 deleted.
        val row = firstScreen(order(100), 7)
        more(row, order(100))
        val after = listOf(1000) + (0 until 100).filter { it != 20 }
        topUp(row, after, 7)
        pageToEnd(row, after)
        assertEquals(listOf(1000) + (0 until 100).toList(), row.cards)
    }

    @Test
    fun `a deep row pages on past the cards it holds`() {
        val row = firstScreen(order(200), 7)
        repeat(3) { more(row, order(200)) }
        assertEquals(120, row.cards.size)
        topUp(row, order(200, 2), 7)
        more(row, order(200, 2))
        assertTrue(row.cards.size > 122)
        assertEquals(row.cards.size, row.cards.toSet().size)
        pageToEnd(row, order(200, 2))
        assertEquals(order(200, 2), row.cards)
    }

    @Test
    fun `a full first page of new videos replaces the row`() {
        // The fresh page holds nothing the row has, so more new videos may
        // sit between it and the cards held: start the row again from it.
        val row = firstScreen(order(100), 7)
        more(row, order(100))
        topUp(row, order(100, 9), 7)
        assertEquals(order(100, 9).take(7), row.cards)
        pageToEnd(row, order(100, 9))
        assertEquals(order(100, 9), row.cards)
    }

    @Test
    fun `a full row stays complete after a top-up`() {
        val row = firstScreen(order(5), 7)
        assertFalse(row.hasMore)
        topUp(row, order(5, 2), 7)
        assertEquals(order(5, 2), row.cards)
        assertFalse(row.hasMore)
    }
}
