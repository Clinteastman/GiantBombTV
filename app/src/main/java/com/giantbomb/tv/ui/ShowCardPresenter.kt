package com.giantbomb.tv.ui

import android.graphics.drawable.GradientDrawable
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
        val density = parent.resources.displayMetrics.density
        val cornerRadius = 12f * density

        val cardView = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setMainImageDimensions(240, 240)
            setMainImageScaleType(ImageView.ScaleType.FIT_CENTER)

            background = GradientDrawable().apply {
                setColor(0x18FFFFFF)
                setCornerRadius(cornerRadius)
            }
            setInfoAreaBackgroundColor(0x0DFFFFFF)
        }

        // Shows without artwork are identifiable only by their title, but
        // ImageCardView truncates it to one ellipsized line. Marquee the
        // title (and content line) so the full name scrolls while focused.
        val titleView = cardView.findViewById<TextView>(androidx.leanback.R.id.title_text)
        titleView?.apply {
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isSingleLine = true
        }

        cardView.setOnFocusChangeListener { v, hasFocus ->
            val scale = if (hasFocus) 1.05f else 1.0f
            v.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
            v.background = GradientDrawable().apply {
                setColor(if (hasFocus) 0x28FFFFFF else 0x18FFFFFF)
                setCornerRadius(cornerRadius)
            }
            // Marquee only animates while the view is "selected"; tie that to
            // focus so the focused card scrolls its full title.
            titleView?.isSelected = hasFocus
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
