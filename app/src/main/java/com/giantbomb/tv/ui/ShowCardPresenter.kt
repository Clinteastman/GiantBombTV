package com.giantbomb.tv.ui

import android.text.TextUtils
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.bumptech.glide.Glide
import com.giantbomb.tv.R
import com.giantbomb.tv.data.PrefsManager
import com.giantbomb.tv.model.Show

/**
 * @param onLongClick Optional callback fired when the user long-presses
 *   D-pad centre on a focused show card. Used by BrowseFragment to wire
 *   pin/unpin without the fragment having to walk every row's cards.
 */
class ShowCardPresenter(
    private val onLongClick: ((Show) -> Unit)? = null
) : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            // 256 px so the last whole card on screen ends clear of the
            // overscan band instead of inside it.
            setMainImageDimensions(256, 256)
            setMainImageScaleType(ImageView.ScaleType.FIT_CENTER)

            GlassSurface.applyState(this, GlassSurface.Emphasis.CARD, focused = false)
            setInfoAreaBackgroundColor(0x00000000)
        }

        // Shows without artwork are identifiable only by their title, but
        // ImageCardView truncates it to one ellipsized line. Give it two
        // lines (fixed, so every card in the row is the same height).
        val titleView = cardView.findViewById<TextView>(androidx.leanback.R.id.title_text)
        titleView?.let(VectorFont::applyIfNeon)
        titleView?.apply {
            isSingleLine = false
            maxLines = 2
            setLines(2)
            ellipsize = TextUtils.TruncateAt.END
            // Same face as the video card titles (Leanback uses a condensed one).
            if (!VectorFont.isVector(this)) typeface = android.graphics.Typeface.DEFAULT
        }

        val focusRing = CardFocus.install(cardView)
        cardView.setOnFocusChangeListener { v, hasFocus ->
            focusRing.update(hasFocus)
            GlassSurface.applyState(v, GlassSurface.Emphasis.CARD, hasFocus)
        }

        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val show = item as Show
        val cardView = viewHolder.view as ImageCardView
        val prefs = PrefsManager(viewHolder.view.context)
        val isPinned = prefs.isPinnedShow(show.id)
        cardView.titleText = if (isPinned) "\u2605 ${show.title}" else show.title
        cardView.contentText = if (isPinned) "Pinned" else if (show.active) "Active" else ""

        val imageUrl = show.posterUrl ?: show.logoUrl
        if (!imageUrl.isNullOrEmpty()) {
            Glide.with(viewHolder.view.context)
                .load(imageUrl)
                .fitCenter()
                .into(cardView.mainImageView)
        } else {
            cardView.mainImage = ContextCompat.getDrawable(viewHolder.view.context, R.drawable.default_card)
        }

        if (onLongClick != null) {
            cardView.isLongClickable = true
            cardView.setOnLongClickListener {
                onLongClick.invoke(show)
                true
            }
        } else {
            cardView.setOnLongClickListener(null)
            cardView.isLongClickable = false
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val cardView = viewHolder.view as ImageCardView
        cardView.mainImage = null
    }
}
