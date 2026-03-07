package com.giantbomb.tv.ui

import android.graphics.drawable.Drawable
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.leanback.widget.Presenter
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.giantbomb.tv.R
import com.giantbomb.tv.model.Video

class CardPresenter : Presenter() {

    private var defaultCardImage: Drawable? = null

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        defaultCardImage = ContextCompat.getDrawable(parent.context, R.drawable.default_card)
        val cardView = VideoCardView(parent.context)
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val video = item as Video
        val cardView = viewHolder.view as VideoCardView

        cardView.setTitle(video.title)

        val meta = buildList {
            if (video.publishDate.isNotEmpty()) add(video.publishDate.take(10))
            if (!video.showTitle.isNullOrEmpty()) add(video.showTitle)
        }.joinToString(" \u2022 ")
        cardView.setMeta(meta)

        cardView.setPremium(video.premium)
        cardView.setProgress(video.progressPercent)
        cardView.setWatched(video.watched)

        val imageUrl = video.thumbnailUrl ?: video.posterUrl
        if (!imageUrl.isNullOrEmpty()) {
            if (video.isFallbackThumb) {
                // Show poster fallback — center without cropping
                cardView.thumbnail.scaleType = ImageView.ScaleType.FIT_CENTER
                cardView.thumbnail.setBackgroundColor(0x10FFFFFF)
                Glide.with(viewHolder.view.context)
                    .load(imageUrl)
                    .fitCenter()
                    .error(defaultCardImage)
                    .into(cardView.thumbnail)
            } else {
                cardView.thumbnail.scaleType = ImageView.ScaleType.CENTER_CROP
                cardView.thumbnail.setBackgroundColor(0x00000000)
                Glide.with(viewHolder.view.context)
                    .load(imageUrl)
                    .centerCrop()
                    .error(defaultCardImage)
                    .into(cardView.thumbnail)
            }
        } else {
            cardView.thumbnail.setImageDrawable(defaultCardImage)
        }

    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val cardView = viewHolder.view as VideoCardView
        cardView.thumbnail.setImageDrawable(null)
    }
}
