package com.giantbomb.tv.ui

import android.graphics.drawable.Drawable
import android.util.Log
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.leanback.widget.Presenter
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.giantbomb.tv.R
import com.giantbomb.tv.model.Video

class CardPresenter : Presenter() {

    companion object {
        private const val TAG = "CardPresenter"
    }

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
        val listener = object : RequestListener<Drawable> {
            override fun onLoadFailed(
                e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean
            ): Boolean {
                Log.w(TAG, "Thumbnail load failed for '${video.title}' (id=${video.id}): url=$model error=${e?.message}")
                e?.rootCauses?.forEach { cause ->
                    Log.w(TAG, "  Cause: ${cause::class.simpleName}: ${cause.message}")
                }
                return false
            }
            override fun onResourceReady(
                resource: Drawable, model: Any, target: Target<Drawable>,
                dataSource: DataSource, isFirstResource: Boolean
            ): Boolean = false
        }

        if (!imageUrl.isNullOrEmpty()) {
            if (video.isFallbackThumb) {
                cardView.thumbnail.scaleType = ImageView.ScaleType.FIT_CENTER
                cardView.thumbnail.setBackgroundColor(0x10FFFFFF)
                Glide.with(viewHolder.view.context)
                    .load(imageUrl)
                    .fitCenter()
                    .placeholder(defaultCardImage)
                    .error(defaultCardImage)
                    .listener(listener)
                    .into(cardView.thumbnail)
            } else {
                cardView.thumbnail.scaleType = ImageView.ScaleType.CENTER_CROP
                cardView.thumbnail.setBackgroundColor(0x00000000)
                Glide.with(viewHolder.view.context)
                    .load(imageUrl)
                    .centerCrop()
                    .placeholder(defaultCardImage)
                    .error(defaultCardImage)
                    .listener(listener)
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
