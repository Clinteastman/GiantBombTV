package com.giantbomb.tv.ui

import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.giantbomb.tv.R
import com.giantbomb.tv.model.SettingsItem

class SettingsCardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setMainImageDimensions(160, 160)
            setMainImageScaleType(ImageView.ScaleType.CENTER_INSIDE)

            GlassSurface.applyState(this, GlassSurface.Emphasis.CARD, focused = false)
            setInfoAreaBackgroundColor(0x00000000)
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
        cardView.mainImage = ContextCompat.getDrawable(viewHolder.view.context, settings.iconResId)
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val cardView = viewHolder.view as ImageCardView
        cardView.mainImage = null
    }
}
