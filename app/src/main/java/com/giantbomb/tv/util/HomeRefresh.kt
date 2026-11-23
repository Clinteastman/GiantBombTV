package com.giantbomb.tv.util

/**
 * Coming back to the TV home screen: update the rows on screen where they
 * are, or build them again.
 */
object HomeRefresh {
    /**
     * True when the rows must be built again: a forced refresh, nothing on
     * screen yet, or a change to what decides the set of rows ([shown] and
     * [fresh] are signatures of the API key, theme, section order, hidden
     * sections, pinned shows and show list).
     */
    fun mustRebuild(forceRefresh: Boolean, shown: Any?, fresh: Any): Boolean =
        forceRefresh || shown == null || shown != fresh

    /**
     * For each fresh row, the index of the row on screen it continues, or
     * null for a row that is new. Rows match by key (title and card kind);
     * each row on screen is continued at most once.
     */
    fun <K> matchRows(shown: List<K>, fresh: List<K>): List<Int?> {
        val used = BooleanArray(shown.size)
        return fresh.map { key ->
            shown.indices.firstOrNull { !used[it] && shown[it] == key }?.also { used[it] = true }
        }
    }
}
