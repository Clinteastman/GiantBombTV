package com.giantbomb.tv.util

/**
 * Coming back to the TV home screen after a show's cache expired: a fresh
 * first page of the show tells which videos are new since the row loaded.
 */
object RowTopUp {
    /**
     * What to do with a loaded show row. [insert] goes at the front of the
     * row, or replaces every card when [replace] is true; [offset] is how far
     * the row then reaches in the server's order (see [RowPaging.next]).
     */
    data class Plan<T>(val insert: List<T>, val offset: Int, val replace: Boolean = false)

    /**
     * Reads a fresh first [page] against the keys of the cards a row
     * [held], in row order. Returns null when nothing changes.
     *
     * The videos ahead of the first held one are new and go to the front.
     * The offset is measured from the last held video in the page: it now
     * sits at its page index, and the row reaches as many places past it as
     * it holds cards after it. That stays right when the row's old offset
     * already counted a new video's place, or a held video was deleted.
     *
     * A page with nothing held may have more new videos behind it than it
     * shows, so the row starts again from the page.
     */
    fun <T, K> plan(held: List<K>, page: List<T>, key: (T) -> K): Plan<T>? {
        if (page.isEmpty()) return null
        val heldAt = held.withIndex().associate { (i, k) -> k to i }
        val anchor = page.indexOfLast { key(it) in heldAt }
        if (anchor < 0) return Plan(page, page.size, replace = true)
        val head = page.takeWhile { key(it) !in heldAt }
        if (head.isEmpty()) return null
        val anchorHeld = heldAt.getValue(key(page[anchor]))
        return Plan(head, anchor + (held.size - anchorHeld))
    }
}
