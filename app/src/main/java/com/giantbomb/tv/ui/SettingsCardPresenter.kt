package com.giantbomb.tv.ui

import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.giantbomb.tv.R
import com.giantbomb.tv.model.SettingsItem

class SettingsCardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val density = parent.resources.displayMetrics.density
        val cornerRadius = 12f * density

        val cardView = ImageCardView(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setMainImageDimensions(160, 160)
            setMainImageScaleType(ImageView.ScaleType.CENTER_INSIDE)

            // Glass background — no stroke to avoid dark fringe artifacts
            background = GradientDrawable().apply {
                setColor(0x18FFFFFF)
                setCornerRadius(cornerRadius)
            }
            setInfoAreaBackgroundColor(0x0DFFFFFF)
        }

        // Focus: brighten glass
        cardView.setOnFocusChangeListener { v, hasFocus ->
            val scale = if (hasFocus) 1.05f else 1.0f
            v.animate().scaleX(scale).scaleY(scale).setDuration(150).start()

            v.background = GradientDrawable().apply {
                setColor(if (hasFocus) 0x28FFFFFF else 0x18FFFFFF)
                setCornerRadius(cornerRadius)
            }
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
