package com.giantbomb.tv.ui

import android.view.ViewGroup
import android.widget.ImageView
import androidx.leanback.widget.Presenter
import com.giantbomb.tv.R

/** Placeholder item for a TV show row whose first cards are still loading. */
data class SkeletonCard(val slot: Int)

/**
 * Draws a [SkeletonCard] as an empty video card: same size as a real card,
 * so the row keeps its height and position while its videos load.
 */
class SkeletonCardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val card = VideoCardView(parent.context)
        card.setTitle("")
        card.setMeta("")
        card.setTier(TierBadge.Kind.NONE)
        card.setProgress(0)
        card.setWatched(false)
        card.thumbnail.scaleType = ImageView.ScaleType.FIT_XY
        card.thumbnail.setImageResource(R.drawable.bg_loading_skeleton)
        card.contentDescription = parent.context.getString(R.string.loading)
        return ViewHolder(card)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) = Unit

    override fun onUnbindViewHolder(viewHolder: ViewHolder) = Unit
}
