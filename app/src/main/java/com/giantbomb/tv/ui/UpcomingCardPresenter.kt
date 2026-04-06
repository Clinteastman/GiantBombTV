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

        if (stream.isLive) {
            // Load Twitch preview thumbnail for live streams
            Glide.with(viewHolder.view.context)
                .load("https://static-cdn.jtvnw.net/previews-ttv/live_user_giantbomb-640x360.jpg")
                .centerCrop()
                .into(cardView.imageView)
        } else if (!stream.image.isNullOrEmpty()) {
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
