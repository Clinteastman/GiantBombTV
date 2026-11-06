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
            setMainImageDimensions((CARD_WIDTH_DP * density).toInt(), (ICON_AREA_DP * density).toInt())
            setMainImageScaleType(ImageView.ScaleType.CENTER_INSIDE)

            GlassSurface.applyState(this, GlassSurface.Emphasis.CARD, focused = false)
            setInfoAreaBackgroundColor(0x00000000)
        }

        // ImageCardView keeps the title and description to one ellipsized
        // line each. Give both two lines, with fixed heights so every card in
        // the row is the same size.
        cardView.findViewById<TextView>(androidx.leanback.R.id.title_text)?.apply {
            isSingleLine = false
            maxLines = 2
            setLines(2)
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
        val focusRing = CardFocus.install(cardView)
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
        const val ICON_AREA_DP = 96f
        const val ICON_DP = 44f
        const val ICON_COLOUR = 0xFFFFFFFF.toInt()
        const val DESCRIPTION_COLOUR = 0xFFC4C4C4.toInt()
    }
}
