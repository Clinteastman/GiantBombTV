package com.giantbomb.tv.ui

import android.view.ViewGroup
import androidx.leanback.widget.Presenter
import com.bumptech.glide.Glide
import com.giantbomb.tv.R
import com.giantbomb.tv.model.UpcomingStream

class UpcomingCardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = UpcomingCardView(parent.context)
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val stream = item as UpcomingStream
        val cardView = viewHolder.view as UpcomingCardView

        cardView.setUpcomingData(
            title = stream.title,
            dateStr = stream.date,
            premium = stream.premium,
            liveNow = stream.isLive
        )

        // For live streams the API layer has already populated stream.image with
        // Twitch's 1280x720 live preview frame, cache-busted per minute so Glide
        // refetches as the show progresses. Same Glide path handles upcoming
        // entries' static promo art.
        if (!stream.image.isNullOrEmpty()) {
            Glide.with(viewHolder.view.context)
                .load(stream.image)
                .centerCrop()
                .into(cardView.imageView)
        } else {
            cardView.imageView.setImageResource(R.drawable.banner)
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val cardView = viewHolder.view as UpcomingCardView
        cardView.stopCountdown()
        cardView.imageView.setImageDrawable(null)
    }
}
