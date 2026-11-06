package com.giantbomb.tv.ui

import android.text.TextUtils
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.giantbomb.tv.model.SettingsItem

class SettingsCardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val density = parent.resources.displayMetrics.density
        val cardView = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            // Wide enough for "Customize Browse" and "Privacy Policy" on one
            // line; titles and descriptions may still wrap to a second line.
            // At 200 dp the last whole card on a 1080p screen also ends clear
            // of the right overscan band.
            setMainImageDimensions((CARD_WIDTH_DP * density).toInt(), (ICON_AREA_DP * density).toInt())
            setMainImageScaleType(ImageView.ScaleType.CENTER_INSIDE)

            GlassSurface.applyState(this, GlassSurface.Emphasis.CARD, focused = false)
            setInfoAreaBackgroundColor(0x00000000)
        }

        // ImageCardView keeps the title and description to one ellipsized
        // line each. Every settings title fits one line at this width, but a
        // long one may wrap; the description always gets a fixed two lines so
        // the cards in the row stay the same height.
        cardView.findViewById<TextView>(androidx.leanback.R.id.title_text)?.apply {
            isSingleLine = false
            minLines = 1
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            VectorFont.applyIfNeon(this)
        }
        cardView.findViewById<TextView>(androidx.leanback.R.id.content_text)?.apply {
            isSingleLine = false
            maxLines = 2
            setLines(2)
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(DESCRIPTION_COLOUR)
        }

        // Focus: lift, ring and brighten glass
        // A smaller lift than the video cards: these are wider, and the full
        // 1.10 lift pushed the first card into the left overscan band.
        val focusRing = CardFocus.install(cardView, focusedScale = FOCUSED_SCALE)
        cardView.setOnFocusChangeListener { v, hasFocus ->
            focusRing.update(hasFocus)
            GlassSurface.applyState(v, GlassSurface.Emphasis.CARD, hasFocus)
        }

        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val settings = item as SettingsItem
        val cardView = viewHolder.view as ImageCardView
        cardView.titleText = settings.title
        cardView.contentText = settings.description
        // One icon colour across the row (the source icons mix red and white).
        cardView.mainImage = ContextCompat.getDrawable(viewHolder.view.context, settings.iconResId)
            ?.mutate()
            ?.apply { setTint(ICON_COLOUR) }
        cardView.mainImageView?.apply {
            val pad = ((ICON_AREA_DP - ICON_DP) / 2f * resources.displayMetrics.density).toInt()
            setPadding(0, pad, 0, pad)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val cardView = viewHolder.view as ImageCardView
        cardView.mainImage = null
    }

    private companion object {
        const val CARD_WIDTH_DP = 200f
        const val ICON_AREA_DP = 72f
        const val ICON_DP = 40f
        const val FOCUSED_SCALE = 1.06f
        const val ICON_COLOUR = 0xFFFFFFFF.toInt()
        const val DESCRIPTION_COLOUR = 0xFFC4C4C4.toInt()
    }
}
