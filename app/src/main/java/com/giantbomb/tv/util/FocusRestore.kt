package com.giantbomb.tv.util

/**
 * Where focus goes back to after the TV home rows are rebuilt.
 */
object FocusRestore {
    /** The focused card: its row's title, its key, and its position in the row. */
    data class Target(val rowTitle: String, val cardKey: Any?, val cardIndex: Int)

    /**
     * Returns (row index, card index) in [rows], given as each row's title
     * and card keys in order, or null when focus cannot be put back.
     *
     * The row is found by title, as rows above it can come or go (Continue
     * Watching). The card is found by key, as it can move inside its row;
     * if it has gone, focus keeps its old position, clamped to the row.
     */
    fun find(rows: List<Pair<String?, List<Any?>>>, target: Target): Pair<Int, Int>? {
        val row = rows.indexOfFirst { it.first == target.rowTitle }
        if (row < 0) return null
        val cards = rows[row].second
        val byKey = cards.indexOf(target.cardKey)
        val card = if (byKey >= 0) byKey else target.cardIndex.coerceAtMost(cards.size - 1).coerceAtLeast(0)
        return row to card
    }
}
