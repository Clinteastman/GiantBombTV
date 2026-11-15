package com.giantbomb.tv.ui

import android.util.TypedValue
import android.widget.TextView
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.RowHeaderPresenter
import androidx.leanback.widget.RowPresenter
import androidx.leanback.widget.removeItemFocusHighlight

/**
 * The row presenter shared by the TV home rows and the search results, so
 * both get the same focus treatment: the cards draw their own lift and ring,
 * so leanback's zoom, shadow and dim are off (with them on, the focused card
 * zoomed twice and sat on a square dark shadow behind its rounded corners).
 */
object TvRowPresenter {
    /** Neon HUD lettering size for the title above each row. */
    const val NEON_ROW_TITLE_SP = 26f

    /** Floor for a row title's fade when its row is not selected (leanback uses 0.5). */
    const val MIN_ROW_TITLE_ALPHA = 0.85f

    /**
     * [extraStartPaddingDp] pushes the first card further in from the left
     * edge, for screens whose rows start closer to it than the home rows do,
     * so the lifted first card stays clear of the TV overscan band.
     */
    fun create(extraStartPaddingDp: Int = 0): ListRowPresenter =
        object : ListRowPresenter(FocusHighlight.ZOOM_FACTOR_NONE) {
            override fun initializeRowViewHolder(holder: RowPresenter.ViewHolder) {
                super.initializeRowViewHolder(holder)
                // ZOOM_FACTOR_NONE alone leaves leanback's focus animator on,
                // writing scale 1.0 over the card's own lift.
                (holder as ViewHolder).bridgeAdapter.removeItemFocusHighlight()
                if (extraStartPaddingDp <= 0) return
                val grid = holder.gridView
                val extra = (extraStartPaddingDp * grid.resources.displayMetrics.density).toInt()
                grid.setPadding(grid.paddingLeft + extra, grid.paddingTop, grid.paddingRight, grid.paddingBottom)
            }
        }.apply {
            shadowEnabled = false
            selectEffectEnabled = false // no dim overlay on unfocused rows
            headerPresenter = RowTitlePresenter()
        }

    /** Row titles: Neon vector lettering, and never faded below readable. */
    private class RowTitlePresenter : RowHeaderPresenter() {
        override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any?) {
            super.onBindViewHolder(viewHolder, item)
            viewHolder.view.findViewById<TextView>(androidx.leanback.R.id.row_header)?.let { title ->
                VectorFont.applyIfNeon(title)
                if (GlassSurface.theme == GlassSurface.Theme.NEON) {
                    title.setTextSize(TypedValue.COMPLEX_UNIT_SP, NEON_ROW_TITLE_SP)
                }
            }
        }

        // Leanback fades the titles of unselected rows to half strength,
        // which drops them below a readable contrast from a sofa. Keep a
        // light fade so the focused row still leads; in Neon the HUD
        // lettering stays at full brightness, like the game.
        override fun onSelectLevelChanged(holder: ViewHolder) {
            super.onSelectLevelChanged(holder)
            holder.view.alpha = if (GlassSurface.theme == GlassSurface.Theme.NEON) 1f
                else maxOf(holder.view.alpha, MIN_ROW_TITLE_ALPHA)
        }
    }
}
