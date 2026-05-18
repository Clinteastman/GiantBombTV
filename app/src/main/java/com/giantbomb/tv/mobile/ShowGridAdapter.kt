package com.giantbomb.tv.mobile

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.giantbomb.tv.R
import com.giantbomb.tv.model.Show

/**
 * Compact grid adapter for show posters. Used by both the Shows and Podcasts
 * tabs; the only thing that differs between them is the filtered input list.
 */
class ShowGridAdapter(
    private val onClick: (Show) -> Unit
) : RecyclerView.Adapter<ShowGridAdapter.VH>() {

    private val items = mutableListOf<Show>()

    fun submit(newItems: List<Show>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_show_grid, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val show = items[position]
        holder.title.text = show.title
        holder.poster.setImageDrawable(null)
        val url = show.posterUrl ?: show.logoUrl
        if (!url.isNullOrEmpty()) {
            Glide.with(holder.poster).load(url).centerCrop().into(holder.poster)
        }
        holder.itemView.setOnClickListener { onClick(show) }
    }

    class VH(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val poster: ImageView = view.findViewById(R.id.show_poster)
        val title: TextView = view.findViewById(R.id.show_title)
    }
}
