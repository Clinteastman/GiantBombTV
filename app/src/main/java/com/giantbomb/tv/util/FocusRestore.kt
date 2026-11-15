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
     */
    fun find(rows: List<Pair<String?, List<Any?>>>, target: Target): Pair<Int, Int>? = null
}
