package com.giantbomb.tv.mobile

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.giantbomb.tv.PlaybackActivity
import com.giantbomb.tv.R
import com.giantbomb.tv.ShowActivity
import com.giantbomb.tv.data.GiantBombApi
import com.giantbomb.tv.data.GiantBombRepository
import com.giantbomb.tv.data.PrefsManager
import com.giantbomb.tv.model.ProgressEntry
import com.giantbomb.tv.model.Show
import com.giantbomb.tv.model.Video
import com.giantbomb.tv.ui.GlassSurface
import com.giantbomb.tv.ui.TierBadge
import com.giantbomb.tv.ui.VectorFont
import com.giantbomb.tv.util.DateFormat
import kotlinx.coroutines.launch

/**
 * The show page on phones and tablets: a solid header with a back button,
 * the show's name and description, then every episode as a full-width card
 * (one column on a phone, more on a tablet). Tapping a card plays it, as on
 * the Home tab. The TV keeps its leanback grid ([com.giantbomb.tv.ShowBrowseFragment]).
 */
class MobileShowFragment : Fragment() {

    private lateinit var repository: GiantBombRepository
    private lateinit var grid: RecyclerView
    private lateinit var loadingView: ProgressBar
    private lateinit var emptyView: TextView
    private val adapter = EpisodeAdapter()
    private var show: Show? = null
    private var progressMap = emptyMap<Int, ProgressEntry>()
    private var isLoading = false
    private var hasMore = true
    private var offset = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_mobile_show, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        repository = GiantBombRepository.get(PrefsManager(requireContext()).apiKey ?: "")
        @Suppress("DEPRECATION")
        show = requireActivity().intent.getSerializableExtra(ShowActivity.EXTRA_SHOW) as? Show

        val header = view.findViewById<View>(R.id.show_header)
        val title = view.findViewById<TextView>(R.id.show_title).also(VectorFont::applyIfNeon)
        val deck = view.findViewById<TextView>(R.id.show_deck)
        grid = view.findViewById(R.id.show_grid)
        loadingView = view.findViewById(R.id.show_loading)
        emptyView = view.findViewById(R.id.show_empty)

        title.text = show?.title.orEmpty()
        val deckText = show?.deck.orEmpty().trim()
        deck.text = deckText
        deck.visibility = if (deckText.isEmpty()) View.GONE else View.VISIBLE
        view.findViewById<View>(R.id.show_back).setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        // Edge to edge: keep the header below the status bar and the last
        // card above the navigation bar. Base padding is read once so repeat
        // inset passes don't add up.
        val headerTop = header.paddingTop
        val headerStart = header.paddingStart
        val headerEnd = header.paddingEnd
        ViewCompat.setOnApplyWindowInsetsListener(header) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPaddingRelative(headerStart + bars.left, headerTop + bars.top, headerEnd + bars.right, v.paddingBottom)
            insets
        }
        val gridBottom = grid.paddingBottom
        val gridStart = grid.paddingStart
        val gridEnd = grid.paddingEnd
        ViewCompat.setOnApplyWindowInsetsListener(grid) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPaddingRelative(gridStart + bars.left, v.paddingTop, gridEnd + bars.right, gridBottom + bars.bottom)
            insets
        }

        val columns = columnsFor(resources.configuration.screenWidthDp)
        grid.layoutManager = GridLayoutManager(requireContext(), columns)
        grid.adapter = adapter
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as GridLayoutManager
                if (!isLoading && hasMore && lm.findLastVisibleItemPosition() >= adapter.itemCount - LOAD_MORE_THRESHOLD) {
                    loadPage()
                }
            }
        })

        if (show == null) {
            showEmpty("This show could not be opened.")
            return
        }
        loadingView.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            repository.getProgress().getOrNull()?.let { list -> progressMap = list.associateBy { it.videoId } }
            loadPage()
        }
    }

    private fun loadPage() {
        val s = show ?: return
        if (isLoading) return
        isLoading = true
        viewLifecycleOwner.lifecycleScope.launch {
            val result = repository.getShowVideos(s.id, limit = PAGE_SIZE, offset = offset)
            if (!isAdded) return@launch
            loadingView.visibility = View.GONE
            result.onSuccess { videos ->
                if (videos.size < PAGE_SIZE) hasMore = false
                offset += videos.size
                adapter.add(videos.map(::withProgress))
                if (adapter.itemCount == 0) showEmpty("No videos for this show yet.")
            }
            result.onFailure { e ->
                hasMore = false
                if (adapter.itemCount == 0) showEmpty(GiantBombApi.friendlyErrorMessage(e))
            }
            isLoading = false
        }
    }

    private fun withProgress(video: Video): Video {
        val entry = progressMap[video.id] ?: return video
        return when {
            entry.percentComplete >= 95 -> video.copy(watched = true)
            entry.percentComplete in 1..94 -> video.copy(progressPercent = entry.percentComplete)
            else -> video
        }
    }

    private fun showEmpty(message: String) {
        emptyView.text = message
        emptyView.visibility = View.VISIBLE
    }

    private fun playIntent(video: Video): Intent =
        Intent(requireContext(), PlaybackActivity::class.java).apply {
            putExtra(PlaybackActivity.EXTRA_VIDEO, video)
            val entry = progressMap[video.id]
            if (entry != null && entry.percentComplete in 1..94 && entry.currentTime > 0) {
                putExtra(PlaybackActivity.EXTRA_RESUME_SECONDS, entry.currentTime)
            }
        }

    private inner class EpisodeAdapter : RecyclerView.Adapter<EpisodeVH>() {
        private val videos = mutableListOf<Video>()

        fun add(more: List<Video>) {
            val start = videos.size
            videos.addAll(more)
            notifyItemRangeInserted(start, more.size)
        }

        override fun getItemCount() = videos.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            EpisodeVH(LayoutInflater.from(parent.context).inflate(R.layout.item_mobile_video, parent, false))

        override fun onBindViewHolder(holder: EpisodeVH, position: Int) = holder.bind(videos[position])
    }

    private inner class EpisodeVH(view: View) : RecyclerView.ViewHolder(view) {
        private val thumbContainer: FrameLayout = view.findViewById(R.id.thumbnail_container)
        private val thumbnail: ImageView = view.findViewById(R.id.video_thumbnail)
        private val title: TextView = view.findViewById<TextView>(R.id.video_title).also(VectorFont::applyIfNeon)
        private val meta: TextView = view.findViewById(R.id.video_meta)
        private val badge: TextView = view.findViewById(R.id.video_premium_badge)
        private val watched: TextView = view.findViewById(R.id.video_watched)
        private val duration: TextView = view.findViewById(R.id.video_duration)
        private val track: View = view.findViewById(R.id.video_progress_track)
        private val bar: View = view.findViewById(R.id.video_progress_bar)

        init {
            val density = view.resources.displayMetrics.density
            view.findViewById<View>(R.id.video_watchlist_btn).visibility = View.GONE
            watched.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC4CAF50.toInt())
            }
            duration.background = GradientDrawable().apply {
                setColor(0xCC000000.toInt())
                cornerRadius = 3f * density
            }
            track.setBackgroundColor(0x55FFFFFF)
        }

        fun bind(video: Video) {
            val kind = TierBadge.kindOf(video)
            GlassSurface.applyState(
                itemView, GlassSurface.Emphasis.CARD, focused = false,
                cornerRadiusDp = 14f, accentColor = TierBadge.glassTint(kind)
            )
            thumbContainer.post {
                val w = thumbContainer.width
                if (w > 0 && thumbContainer.layoutParams.height != w * 9 / 16) {
                    thumbContainer.layoutParams = thumbContainer.layoutParams.apply { height = w * 9 / 16 }
                }
            }
            title.text = video.title
            // Every card here is from the same show, so the date alone.
            val date = DateFormat.formatPublishDate(video.publishDate)
            meta.text = date
            meta.visibility = if (date.isEmpty()) View.GONE else View.VISIBLE

            TierBadge.styleLabel(badge, kind)
            watched.visibility = if (video.watched) View.VISIBLE else View.GONE
            if (video.durationSeconds > 0) {
                duration.text = formatDuration(video.durationSeconds)
                duration.visibility = View.VISIBLE
            } else {
                duration.visibility = View.GONE
            }
            if (video.progressPercent in 1..99) {
                track.visibility = View.VISIBLE
                bar.visibility = View.VISIBLE
                bar.post {
                    val parentWidth = (bar.parent as? View)?.width ?: return@post
                    bar.layoutParams = bar.layoutParams.apply { width = parentWidth * video.progressPercent / 100 }
                }
            } else {
                track.visibility = View.GONE
                bar.visibility = View.GONE
            }
            val url = video.thumbnailUrl ?: show?.posterUrl
            if (!url.isNullOrEmpty()) {
                Glide.with(thumbnail).load(url).centerCrop()
                    .placeholder(R.drawable.default_card).error(R.drawable.default_card).into(thumbnail)
            } else {
                thumbnail.setImageResource(R.drawable.default_card)
            }
            itemView.setOnClickListener { startActivity(playIntent(video)) }
        }
    }

    companion object {
        private const val PAGE_SIZE = 40
        private const val LOAD_MORE_THRESHOLD = 6
        private const val MIN_CARD_WIDTH_DP = 340

        /** One column on a phone, two or more on a tablet or a phone on its side. */
        fun columnsFor(widthDp: Int): Int = (widthDp / MIN_CARD_WIDTH_DP).coerceIn(1, 4)

        fun formatDuration(seconds: Int): String {
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            val s = seconds % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }
    }
}
