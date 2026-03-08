package com.giantbomb.tv.ui

import android.graphics.drawable.GradientDrawable
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import com.bumptech.glide.Glide
import com.giantbomb.tv.R
import com.giantbomb.tv.data.PrefsManager
import com.giantbomb.tv.model.Show

class ShowCardPresenter : Presenter() {

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
        val show = item as Show
        val cardView = viewHolder.view as ImageCardView
        val prefs = PrefsManager(viewHolder.view.context)
        val isFav = prefs.isFavouriteShow(show.id)
        cardView.titleText = if (isFav) "\u2605 ${show.title}" else show.title
        cardView.contentText = if (isFav) "Pinned" else if (show.active) "Active" else ""

        val imageUrl = show.posterUrl ?: show.logoUrl
        if (!imageUrl.isNullOrEmpty()) {
            Glide.with(viewHolder.view.context)
                .load(imageUrl)
                .fitCenter()
                .into(cardView.mainImageView)
        } else {
            cardView.mainImage = ContextCompat.getDrawable(viewHolder.view.context, R.drawable.default_card)
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val cardView = viewHolder.view as ImageCardView
        cardView.mainImage = null
    }
}
