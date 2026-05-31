package com.giantbomb.tv.mobile

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.giantbomb.tv.R
import com.giantbomb.tv.model.Show
import com.giantbomb.tv.model.Video

/**
 * Heterogeneous grid adapter for the mobile Shows / Podcasts tabs.
 *
 * Rows mix full-width elements (a podcast hero, section headers) with
 * single-span cards (episodes, show posters). Pair it with a
 * [GridLayoutManager] whose SpanSizeLookup defers to [spanSizeAt].
 */
class MobileGridAdapter(
    private val columns: Int,
    private val onPlayVideo: (Video) -> Unit,
    private val onOpenShow: (Show) -> Unit,
    private val onPinShow: (Show) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Row {
        data class Hero(val video: Video) : Row()
        data class Header(val text: String) : Row()
        data class Episode(val video: Video) : Row()
        data class ShowCard(val show: Show, val pinned: Boolean) : Row()
    }

    private val rows = mutableListOf<Row>()

    fun submit(newRows: List<Row>) {
        rows.clear()
        rows.addAll(newRows)
        notifyDataSetChanged()
    }

    /**
     * Span widths against a 6-unit base grid: heroes/headers fill the row,
     * episodes span 2 units (3 across), and show cards span 3 units (2 across).
     */
    fun spanSizeAt(position: Int): Int = when (rows[position]) {
        is Row.Hero, is Row.Header -> columns
        is Row.Episode -> columns / 3
        is Row.ShowCard -> columns / 2
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Hero -> TYPE_HERO
        is Row.Header -> TYPE_HEADER
        is Row.Episode -> TYPE_EPISODE
        is Row.ShowCard -> TYPE_SHOW
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HERO -> HeroVH(inflater.inflate(R.layout.item_grid_hero, parent, false))
            TYPE_HEADER -> HeaderVH(inflater.inflate(R.layout.item_grid_header, parent, false))
            TYPE_EPISODE -> EpisodeVH(inflater.inflate(R.layout.item_grid_episode, parent, false))
            else -> ShowVH(inflater.inflate(R.layout.item_show_grid, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Hero -> (holder as HeroVH).bind(row.video)
            is Row.Header -> (holder as HeaderVH).bind(row.text)
            is Row.Episode -> (holder as EpisodeVH).bind(row.video)
            is Row.ShowCard -> (holder as ShowVH).bind(row.show, row.pinned)
        }
    }

    inner class HeroVH(view: View) : RecyclerView.ViewHolder(view) {
        private val image: ImageView = view.findViewById(R.id.hero_image)
        private val title: TextView = view.findViewById(R.id.hero_title)
        private val show: TextView = view.findViewById(R.id.hero_show)
        fun bind(video: Video) {
            title.text = video.title
            show.text = video.showTitle ?: ""
            image.setImageDrawable(null)
            val url = video.thumbnailUrl ?: video.posterUrl
            if (!url.isNullOrEmpty()) Glide.with(image).load(url).centerCrop().into(image)
            itemView.findViewById<View>(R.id.hero_card).setOnClickListener { onPlayVideo(video) }
        }
    }

    inner class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        private val text: TextView = view.findViewById(R.id.grid_section_header)
        fun bind(value: String) { text.text = value }
    }

    inner class EpisodeVH(view: View) : RecyclerView.ViewHolder(view) {
        private val thumb: ImageView = view.findViewById(R.id.episode_thumb)
        private val title: TextView = view.findViewById(R.id.episode_title)
        fun bind(video: Video) {
            title.text = video.title
            thumb.setImageDrawable(null)
            val url = video.thumbnailUrl ?: video.posterUrl
            if (!url.isNullOrEmpty()) Glide.with(thumb).load(url).centerCrop().into(thumb)
            itemView.setOnClickListener { onPlayVideo(video) }
        }
    }

    inner class ShowVH(view: View) : RecyclerView.ViewHolder(view) {
        private val poster: ImageView = view.findViewById(R.id.show_poster)
        private val title: TextView = view.findViewById(R.id.show_title)
        private val pinBadge: ImageView = view.findViewById(R.id.show_pin_badge)
        fun bind(show: Show, pinned: Boolean) {
            title.text = show.title
            pinBadge.visibility = if (pinned) View.VISIBLE else View.GONE
            poster.setImageDrawable(null)
            val url = show.posterUrl ?: show.logoUrl
            if (!url.isNullOrEmpty()) Glide.with(poster).load(url).centerCrop().into(poster)
            itemView.setOnClickListener { onOpenShow(show) }
            itemView.setOnLongClickListener { onPinShow(show); true }
        }
    }

    companion object {
        private const val TYPE_HERO = 0
        private const val TYPE_HEADER = 1
        private const val TYPE_EPISODE = 2
        private const val TYPE_SHOW = 3
    }
}
