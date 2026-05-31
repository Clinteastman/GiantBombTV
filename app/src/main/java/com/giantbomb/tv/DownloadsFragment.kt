package com.giantbomb.tv

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.giantbomb.tv.playback.Download
import com.giantbomb.tv.playback.DownloadStatus
import com.giantbomb.tv.playback.Downloads
import com.giantbomb.tv.util.DeviceUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Lists offline downloads — completed ones (tap to play offline), plus any
 * currently queued / downloading with live progress. Hosted both as the mobile
 * bottom-nav "Downloads" tab and (via [DownloadsActivity]) from the TV settings
 * row. State is observed from [Downloads] so progress updates stream in while
 * the screen is open.
 */
class DownloadsFragment : Fragment(), CoroutineScope by MainScope() {

    private lateinit var adapter: DownloadAdapter
    private lateinit var emptyView: TextView
    private val items = mutableListOf<Download>()

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        Downloads.ensureLoaded(ctx)

        val isTv = DeviceUtil.isTv(ctx)

        val root = FrameLayout(ctx).apply {
            setBackgroundResource(R.drawable.bg_ambient_gradient)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val h = if (isTv) 48.dp() else 16.dp()
            setPadding(h, 24.dp(), h, 16.dp())
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val heading = TextView(ctx).apply {
            text = "Downloads"
            textSize = 28f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 16.dp())
        }
        column.addView(heading)

        emptyView = TextView(ctx).apply {
            text = "No downloads yet.\nOpen a video and tap Download to save it for offline viewing."
            textSize = 15f
            setTextColor(0xFFA0A0A0.toInt())
            setLineSpacing(0f, 1.3f)
            visibility = View.GONE
        }
        column.addView(emptyView)

        adapter = DownloadAdapter()
        val recycler = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            adapter = this@DownloadsFragment.adapter
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        column.addView(recycler)

        root.addView(column)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        launch {
            Downloads.state.collect { map ->
                val sorted = map.values.sortedWith(
                    compareBy({ it.status.sortRank() }, { -(it.video.id) })
                )
                items.clear()
                items.addAll(sorted)
                adapter.notifyDataSetChanged()
                emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun DownloadStatus.sortRank(): Int = when (this) {
        DownloadStatus.DOWNLOADING -> 0
        DownloadStatus.QUEUED -> 1
        DownloadStatus.FAILED -> 2
        DownloadStatus.COMPLETED -> 3
    }

    private fun play(download: Download) {
        if (download.status != DownloadStatus.COMPLETED) return
        val intent = Intent(requireContext(), PlaybackActivity::class.java)
            .putExtra(PlaybackActivity.EXTRA_VIDEO, download.video)
        startActivity(intent)
    }

    private fun confirmRemove(download: Download) {
        val downloading = download.status == DownloadStatus.DOWNLOADING ||
            download.status == DownloadStatus.QUEUED
        AlertDialog.Builder(requireContext(), R.style.GbDialogTheme)
            .setTitle(download.video.title)
            .setMessage(if (downloading) "Cancel this download?" else "Remove this download?")
            .setPositiveButton(if (downloading) "Cancel download" else "Remove") { _, _ ->
                if (downloading) Downloads.cancel(requireContext(), download.videoId)
                else Downloads.delete(requireContext(), download.videoId)
            }
            .setNegativeButton("Keep", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancel()
    }

    private inner class DownloadAdapter : RecyclerView.Adapter<DownloadAdapter.VH>() {

        inner class VH(val view: View) : RecyclerView.ViewHolder(view) {
            val thumb: ImageView = view.findViewById(ID_THUMB)
            val title: TextView = view.findViewById(ID_TITLE)
            val meta: TextView = view.findViewById(ID_META)
            val progress: ProgressBar = view.findViewById(ID_PROGRESS)
            val remove: TextView = view.findViewById(ID_REMOVE)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(buildRow(parent))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val d = items[position]
            holder.title.text = d.video.title

            val thumbUrl = d.video.thumbnailUrl ?: d.video.posterUrl
            if (!thumbUrl.isNullOrEmpty()) {
                Glide.with(holder.thumb.context)
                    .load(thumbUrl)
                    .centerCrop()
                    .placeholder(R.drawable.default_card)
                    .error(R.drawable.default_card)
                    .into(holder.thumb)
            } else {
                holder.thumb.setImageResource(R.drawable.default_card)
            }

            when (d.status) {
                DownloadStatus.COMPLETED -> {
                    holder.meta.text = buildString {
                        d.video.showTitle?.let { append(it); append("  •  ") }
                        append(d.qualityLabel.ifEmpty { "Downloaded" })
                    }
                    holder.progress.visibility = View.GONE
                    holder.remove.text = "Remove"
                }
                DownloadStatus.DOWNLOADING -> {
                    holder.meta.text = "Downloading… ${d.progressPercent}%"
                    holder.progress.visibility = View.VISIBLE
                    holder.progress.isIndeterminate = d.totalBytes <= 0
                    holder.progress.progress = d.progressPercent
                    holder.remove.text = "Cancel"
                }
                DownloadStatus.QUEUED -> {
                    holder.meta.text = "Queued"
                    holder.progress.visibility = View.VISIBLE
                    holder.progress.isIndeterminate = true
                    holder.remove.text = "Cancel"
                }
                DownloadStatus.FAILED -> {
                    holder.meta.text = "Failed — tap Remove to clear"
                    holder.progress.visibility = View.GONE
                    holder.remove.text = "Remove"
                }
            }

            holder.view.isClickable = true
            holder.view.isFocusable = true
            holder.view.setOnClickListener { play(d) }
            holder.remove.setOnClickListener { confirmRemove(d) }
        }
    }

    /** Builds a download row programmatically (the app builds list rows in code). */
    private fun buildRow(parent: ViewGroup): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp(), 10.dp(), 12.dp(), 10.dp())
            isFocusable = true
            isFocusableInTouchMode = false
            background = focusableRowBackground()
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8.dp() }
        }

        val thumb = ImageView(ctx).apply {
            id = ID_THUMB
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LinearLayout.LayoutParams(120.dp(), 68.dp())
        }
        row.addView(thumb)

        val textCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp(), 0, 12.dp(), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        textCol.addView(TextView(ctx).apply {
            id = ID_TITLE
            textSize = 16f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
        })
        textCol.addView(TextView(ctx).apply {
            id = ID_META
            textSize = 13f
            setTextColor(0xFFA0A0A0.toInt())
            setPadding(0, 4.dp(), 0, 0)
        })
        textCol.addView(ProgressBar(
            ctx, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            id = ID_PROGRESS
            max = 100
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 6.dp() }
        })
        row.addView(textCol)

        val remove = TextView(ctx).apply {
            id = ID_REMOVE
            text = "Remove"
            textSize = 14f
            setTextColor(0xFFCCCCCC.toInt())
            isFocusable = true
            isClickable = true
            setPadding(16.dp(), 8.dp(), 16.dp(), 8.dp())
            background = GradientDrawable().apply {
                setColor(0x1AFFFFFF)
                cornerRadius = 8f * resources.displayMetrics.density
                setStroke((1 * resources.displayMetrics.density).toInt(), 0x30FFFFFF)
            }
        }
        row.addView(remove)

        return row
    }

    private fun focusableRowBackground(): GradientDrawable = GradientDrawable().apply {
        setColor(0x10FFFFFF)
        cornerRadius = 10f * resources.displayMetrics.density
    }

    companion object {
        // Generated at runtime to avoid colliding with aapt-assigned R ids.
        private val ID_THUMB = View.generateViewId()
        private val ID_TITLE = View.generateViewId()
        private val ID_META = View.generateViewId()
        private val ID_PROGRESS = View.generateViewId()
        private val ID_REMOVE = View.generateViewId()
    }
}
