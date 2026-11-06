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
import com.giantbomb.tv.util.DateFormat

/**
 * @param showShowName false for rows and pages that already name the show
 *   (a show's own page or row), so the meta line is not spent repeating it.
 */
class CardPresenter(private val showShowName: Boolean = true) : Presenter() {

    companion object {
        private const val TAG = "CardPresenter"
        /** A date such as 11/02/2026 or 5/8/26 inside an episode title. */
        private val TITLE_DATE = Regex("""\b\d{1,2}/\d{1,2}/\d{2,4}\b""")
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

        // Length first: with the date it is what a viewer chooses by.
        val meta = buildList {
            DateFormat.formatLength(video.durationSeconds).takeIf { it.isNotEmpty() }?.let(::add)
            // On a show's own page the show name is dropped, and so is the
            // date when the episode title already carries one ("| 11/02/2026").
            val dateInTitle = !showShowName && TITLE_DATE.containsMatchIn(video.title)
            if (video.publishDate.isNotEmpty() && !dateInTitle) add(DateFormat.formatPublishDate(video.publishDate))
            if (showShowName && !video.showTitle.isNullOrEmpty()) add(video.showTitle)
        }.joinToString(" \u2022 ")
        cardView.setMeta(meta)

        cardView.setTier(TierBadge.kindOf(video))
        cardView.setProgress(video.progressPercent)
        cardView.setWatched(video.watched)

        val imageUrl = video.thumbnailUrl ?: video.posterUrl
        val listener = if (com.giantbomb.tv.BuildConfig.DEBUG) object : RequestListener<Drawable> {
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
        } else null

        if (!imageUrl.isNullOrEmpty()) {
            if (video.isFallbackThumb) {
                cardView.thumbnail.scaleType = ImageView.ScaleType.FIT_CENTER
                cardView.thumbnail.setBackgroundColor(0x10FFFFFF)
                Glide.with(viewHolder.view.context)
                    .load(imageUrl)
                    .fitCenter()
                    .placeholder(defaultCardImage)
                    .error(defaultCardImage)
                    .apply { listener?.let { listener(it) } }
                    .into(cardView.thumbnail)
            } else {
                cardView.thumbnail.scaleType = ImageView.ScaleType.CENTER_CROP
                cardView.thumbnail.setBackgroundColor(0x00000000)
                Glide.with(viewHolder.view.context)
                    .load(imageUrl)
                    .centerCrop()
                    .placeholder(defaultCardImage)
                    .error(defaultCardImage)
                    .apply { listener?.let { listener(it) } }
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
