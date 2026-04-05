package com.giantbomb.tv.mobile

import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.bumptech.glide.Glide
import com.giantbomb.tv.*
import com.giantbomb.tv.data.GiantBombApi
import com.giantbomb.tv.data.PrefsManager
import com.giantbomb.tv.model.ProgressEntry
import com.giantbomb.tv.model.SettingsItem
import com.giantbomb.tv.data.TwitchExtractor
import com.giantbomb.tv.model.Show
import com.giantbomb.tv.model.UpcomingStream
import com.giantbomb.tv.model.Video
import com.giantbomb.tv.ui.UpcomingCardView
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import androidx.mediarouter.app.MediaRouteButton
import kotlinx.coroutines.*

class MobileBrowseFragment : Fragment(), CoroutineScope by MainScope() {

    private lateinit var prefs: PrefsManager
    private var isLoading = false

    private lateinit var recyclerView: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private var miniPlayerContainer: FrameLayout? = null

    private val browseItems = mutableListOf<BrowseItem>()
    private lateinit var browseAdapter: BrowseAdapter

    companion object {
        private const val SETTINGS_REFRESH = 2
        private const val SETTINGS_SETUP = 3
        private const val SETTINGS_QUALITY = 4
        private const val INITIAL_VIDEO_LIMIT = 100
        private const val ROW_PAGE_SIZE = 40
        private const val RECENT_VERTICAL_COUNT = 5
    }

    // Sealed class representing different row types in the feed
    sealed class BrowseItem {
        data class SectionHeader(val title: String, val showSeeAll: Boolean = false) : BrowseItem()
        data class HorizontalVideoRow(val videos: List<Video>) : BrowseItem()
        data class HorizontalShowRow(val shows: List<Show>) : BrowseItem()
        data class VerticalVideo(val video: Video) : BrowseItem()
        data class SettingRow(val item: SettingsItem) : BrowseItem()
        data class UpcomingRow(val streams: List<UpcomingStream>, val liveNow: UpcomingStream?) : BrowseItem()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_mobile_browse, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefs = PrefsManager(requireContext())

        recyclerView = view.findViewById(R.id.browse_recycler)
        swipeRefresh = view.findViewById(R.id.swipe_refresh)
        miniPlayerContainer = view.findViewById(R.id.mini_player_container)

        // Apply system bar insets to toolbar - status bar top + consistent horizontal padding
        val toolbar = view.findViewById<FrameLayout>(R.id.toolbar_container)
        val basePadStart = toolbar.paddingStart
        val basePadEnd = toolbar.paddingEnd
        val basePadBottom = toolbar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.setPadding(basePadStart, bars.top + (8 * resources.displayMetrics.density).toInt(), basePadEnd, basePadBottom)
            insets
        }

        // Toolbar Cast button
        try {
            val castButton = view.findViewById<MediaRouteButton>(R.id.toolbar_cast)
            CastButtonFactory.setUpMediaRouteButton(requireContext(), castButton)
            CastContext.getSharedInstance(requireContext())
            castButton.visibility = View.VISIBLE
        } catch (_: Exception) {
            // Cast not available
        }

        // Toolbar search
        view.findViewById<ImageView>(R.id.toolbar_search).setOnClickListener {
            startActivity(Intent(requireContext(), SearchActivity::class.java))
        }

        browseAdapter = BrowseAdapter()
        setupLayoutManager()
        recyclerView.adapter = browseAdapter

        swipeRefresh.setColorSchemeColors(
            ContextCompat.getColor(requireContext(), R.color.gb_red)
        )
        swipeRefresh.setProgressBackgroundColorSchemeColor(
            ContextCompat.getColor(requireContext(), R.color.gb_surface)
        )
        swipeRefresh.setOnRefreshListener { loadContent() }

        if (prefs.apiKey.isNullOrEmpty()) {
            launchSetup()
        } else {
            loadContent()
        }
    }

    private var hasBeenVisible = false
    private var wasInBackground = false

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun setupLayoutManager() {
        val spanCount = if (isLandscape()) 2 else 1
        val glm = GridLayoutManager(requireContext(), spanCount)
        glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                if (position >= browseItems.size) return spanCount
                return when (browseItems[position]) {
                    is BrowseItem.VerticalVideo -> 1
                    else -> spanCount
                }
            }
        }
        recyclerView.layoutManager = glm
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setupLayoutManager()
    }

    override fun onPause() {
        super.onPause()
        wasInBackground = true
    }

    override fun onResume() {
        super.onResume()
        if (browseItems.isEmpty()) {
            loadContent()
        } else if (wasInBackground && hasBeenVisible) {
            // Only reload when returning from another activity, not on rotation
            loadContent()
        }
        hasBeenVisible = true
        wasInBackground = false
    }

    fun loadContent() {
        if (isLoading) return
        isLoading = true
        swipeRefresh.isRefreshing = true

        val key = prefs.apiKey ?: ""
        val api = GiantBombApi(key)

        launch {
            try {
                val items = mutableListOf<BrowseItem>()

                val upcomingDeferred = async { api.getUpcoming() }
                val watchlistDeferred = async { api.getWatchlist() }
                val progressDeferred = async { api.getProgress() }
                val recentDeferred = async { api.getVideos(limit = INITIAL_VIDEO_LIMIT) }
                val showsDeferred = async { api.getShows() }

                // Await shows early for fallback thumbnails
                val shows = showsDeferred.await().getOrNull()
                val showPosterMap = shows?.associate { it.id to (it.posterUrl ?: it.logoUrl) } ?: emptyMap()
                fun Video.withFallbackThumb(): Video {
                    if (!thumbnailUrl.isNullOrEmpty()) return this
                    val fallback = showId?.let { showPosterMap[it] }
                    return if (fallback != null) copy(thumbnailUrl = fallback, isFallbackThumb = true) else this
                }

                // Progress map
                var progressMap = emptyMap<Int, ProgressEntry>()
                if (key.isNotEmpty()) {
                    val progress = progressDeferred.await().getOrNull()
                    if (progress != null && progress.isNotEmpty()) {
                        progressMap = progress.associateBy { it.videoId }
                    }
                }

                fun Video.withProgress(): Video {
                    val entry = progressMap[id]
                    val v = when {
                        entry != null && entry.percentComplete >= 95 -> copy(watched = true)
                        entry != null && entry.percentComplete in 1..94 -> copy(progressPercent = entry.percentComplete)
                        else -> this
                    }
                    return v.withFallbackThumb()
                }

                // Upcoming & Live
                val upcomingResult = upcomingDeferred.await().getOrNull()
                if (upcomingResult != null) {
                    val hasContent = upcomingResult.liveNow != null || upcomingResult.upcoming.isNotEmpty()
                    if (hasContent) {
                        val headerTitle = if (upcomingResult.liveNow != null) "\uD83D\uDD34 Upcoming & Live" else "Upcoming"
                        items.add(BrowseItem.SectionHeader(headerTitle))
                        items.add(BrowseItem.UpcomingRow(upcomingResult.upcoming, upcomingResult.liveNow))
                    }
                }

                // Continue Watching
                if (key.isNotEmpty() && progressMap.isNotEmpty()) {
                    val inProgressEntries = progressMap.values
                        .filter { it.percentComplete in 1..94 }
                        .sortedByDescending { it.currentTime }
                        .take(20)
                    val inProgressIds = inProgressEntries.map { it.videoId }.toSet()

                    if (inProgressIds.isNotEmpty()) {
                        val recentVideos = recentDeferred.await().getOrNull()
                        val continueVideos = recentVideos?.filter { it.id in inProgressIds }
                            ?.map { video ->
                                val entry = progressMap[video.id]
                                val v = if (entry != null) video.copy(progressPercent = entry.percentComplete) else video
                                v.withFallbackThumb()
                            }

                        if (continueVideos != null && continueVideos.isNotEmpty()) {
                            items.add(BrowseItem.SectionHeader(getString(R.string.continue_watching)))
                            items.add(BrowseItem.HorizontalVideoRow(continueVideos))
                        }
                    }
                }

                // Recent Videos
                val recent = recentDeferred.await()
                recent.onSuccess { videos ->
                    if (videos.isNotEmpty()) {
                        items.add(BrowseItem.SectionHeader(getString(R.string.recent)))

                        // First N as full-width vertical cards
                        val verticalVideos = videos.take(RECENT_VERTICAL_COUNT)
                        for (v in verticalVideos) {
                            items.add(BrowseItem.VerticalVideo(v.withProgress()))
                        }

                        // Rest in horizontal row
                        val remaining = videos.drop(RECENT_VERTICAL_COUNT)
                        if (remaining.isNotEmpty()) {
                            items.add(BrowseItem.HorizontalVideoRow(remaining.map { it.withProgress() }))
                        }

                        // Premium row
                        val premiumVideos = videos.filter { it.premium }
                        if (premiumVideos.size >= 2) {
                            items.add(BrowseItem.SectionHeader("Premium"))
                            items.add(BrowseItem.HorizontalVideoRow(premiumVideos.map { it.withProgress() }))
                        }
                    }
                }

                recent.onFailure { e ->
                    if (isAdded) {
                        Toast.makeText(requireContext(),
                            GiantBombApi.friendlyErrorMessage(e), Toast.LENGTH_LONG).show()
                    }
                }

                // Per-show rows
                if (shows != null && shows.isNotEmpty()) {
                    val activeShows = shows.filter { it.active }
                    val showVideoResults = activeShows.map { s ->
                        s to async { api.getShowVideos(s.id, limit = ROW_PAGE_SIZE) }
                    }
                    for ((s, deferred) in showVideoResults) {
                        val showVideos = deferred.await().getOrNull() ?: continue
                        if (showVideos.isEmpty()) continue
                        items.add(BrowseItem.SectionHeader(s.title))
                        items.add(BrowseItem.HorizontalVideoRow(showVideos.map { it.withProgress() }))
                    }

                    // All Shows
                    if (activeShows.isNotEmpty()) {
                        items.add(BrowseItem.SectionHeader("All Shows"))
                        items.add(BrowseItem.HorizontalShowRow(activeShows))
                    }
                }

                // Watchlist
                val watchlist = if (key.isNotEmpty()) watchlistDeferred.await().getOrNull() else null
                if (watchlist != null && watchlist.isNotEmpty()) {
                    items.add(BrowseItem.SectionHeader("My Watchlist"))
                    items.add(BrowseItem.HorizontalVideoRow(watchlist.map { it.withProgress() }))
                }

                // Settings
                items.add(BrowseItem.SectionHeader(getString(R.string.settings)))
                items.add(BrowseItem.SettingRow(SettingsItem(
                    SETTINGS_REFRESH,
                    getString(R.string.settings_refresh),
                    getString(R.string.settings_refresh_desc),
                    R.drawable.ic_settings_refresh
                )))
                items.add(BrowseItem.SettingRow(SettingsItem(
                    SETTINGS_QUALITY,
                    "Stream Quality",
                    "Default: ${PrefsManager.qualityLabel(prefs.preferredQuality)}",
                    R.drawable.ic_settings_quality
                )))
                items.add(BrowseItem.SettingRow(SettingsItem(
                    SETTINGS_SETUP,
                    getString(R.string.settings_setup),
                    getString(R.string.settings_setup_desc),
                    R.drawable.ic_settings_cog
                )))

                // Version info
                val packageInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
                val versionText = "v${packageInfo.versionName} (${packageInfo.longVersionCode})"
                items.add(BrowseItem.SettingRow(SettingsItem(
                    -1,
                    "Version",
                    versionText,
                    R.drawable.ic_settings_cog
                )))

                browseItems.clear()
                browseItems.addAll(items)
                browseAdapter.notifyDataSetChanged()

            } finally {
                isLoading = false
                if (isAdded) {
                    swipeRefresh.isRefreshing = false
                }
            }
        }
    }

    fun getMiniPlayerContainer(): FrameLayout? = miniPlayerContainer

    private fun cycleQuality() {
        val options = PrefsManager.QUALITY_OPTIONS
        val current = prefs.preferredQuality
        val nextIndex = (options.indexOf(current) + 1) % options.size
        prefs.preferredQuality = options[nextIndex]
        Toast.makeText(requireContext(),
            "Default quality: ${PrefsManager.qualityLabel(options[nextIndex])}", Toast.LENGTH_SHORT).show()
        loadContent()
    }

    private fun launchTwitchStream(title: String) {
        Toast.makeText(requireContext(), "Loading live stream...", Toast.LENGTH_SHORT).show()
        launch {
            val result = TwitchExtractor().extract("giantbomb")
            result.onSuccess { stream ->
                val liveTitle = stream.title.ifEmpty { title }
                val intent = Intent(requireContext(), PlaybackActivity::class.java).apply {
                    putExtra(PlaybackActivity.EXTRA_LIVE_HLS_URL, stream.hlsUrl)
                    putExtra(PlaybackActivity.EXTRA_LIVE_TITLE, liveTitle)
                }
                startActivity(intent)
            }
            result.onFailure { e ->
                if (isAdded) {
                    Toast.makeText(requireContext(),
                        "Stream not available: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun launchSetup() {
        val intent = Intent(requireContext(), SetupActivity::class.java)
        @Suppress("DEPRECATION")
        requireActivity().startActivityForResult(intent, MainActivity.SETUP_REQUEST)
    }

    override fun onDestroy() {
        super.onDestroy()
        cancel()
    }

    // -----------------------------------------------------------------------
    // Adapter
    // -----------------------------------------------------------------------

    private inner class BrowseAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        val TYPE_SECTION_HEADER = 0
        val TYPE_HORIZONTAL_VIDEO_ROW = 1
        val TYPE_HORIZONTAL_SHOW_ROW = 2
        val TYPE_VERTICAL_VIDEO = 3
        val TYPE_SETTING = 4
        val TYPE_UPCOMING_ROW = 5

        override fun getItemViewType(position: Int): Int = when (browseItems[position]) {
            is BrowseItem.SectionHeader -> TYPE_SECTION_HEADER
            is BrowseItem.HorizontalVideoRow -> TYPE_HORIZONTAL_VIDEO_ROW
            is BrowseItem.HorizontalShowRow -> TYPE_HORIZONTAL_SHOW_ROW
            is BrowseItem.VerticalVideo -> TYPE_VERTICAL_VIDEO
            is BrowseItem.SettingRow -> TYPE_SETTING
            is BrowseItem.UpcomingRow -> TYPE_UPCOMING_ROW
        }

        override fun getItemCount() = browseItems.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_SECTION_HEADER -> SectionHeaderVH(inflater.inflate(R.layout.item_section_header, parent, false))
                TYPE_HORIZONTAL_VIDEO_ROW -> HorizontalVideoRowVH(inflater.inflate(R.layout.item_horizontal_row, parent, false))
                TYPE_HORIZONTAL_SHOW_ROW -> HorizontalShowRowVH(inflater.inflate(R.layout.item_horizontal_row, parent, false))
                TYPE_VERTICAL_VIDEO -> VerticalVideoVH(inflater.inflate(R.layout.item_mobile_video, parent, false))
                TYPE_SETTING -> SettingVH(inflater.inflate(R.layout.item_mobile_setting, parent, false))
                TYPE_UPCOMING_ROW -> UpcomingRowVH(inflater.inflate(R.layout.item_horizontal_row, parent, false))
                else -> throw IllegalArgumentException("Unknown view type: $viewType")
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = browseItems[position]) {
                is BrowseItem.SectionHeader -> (holder as SectionHeaderVH).bind(item)
                is BrowseItem.HorizontalVideoRow -> (holder as HorizontalVideoRowVH).bind(item)
                is BrowseItem.HorizontalShowRow -> (holder as HorizontalShowRowVH).bind(item)
                is BrowseItem.VerticalVideo -> (holder as VerticalVideoVH).bind(item)
                is BrowseItem.SettingRow -> (holder as SettingVH).bind(item)
                is BrowseItem.UpcomingRow -> (holder as UpcomingRowVH).bind(item)
            }
        }
    }

    // -----------------------------------------------------------------------
    // ViewHolders
    // -----------------------------------------------------------------------

    private inner class SectionHeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.section_title)
        private val seeAll: TextView = view.findViewById(R.id.section_see_all)

        fun bind(item: BrowseItem.SectionHeader) {
            title.text = item.title
            seeAll.visibility = if (item.showSeeAll) View.VISIBLE else View.GONE
        }
    }

    private inner class VerticalVideoVH(view: View) : RecyclerView.ViewHolder(view) {
        private val thumbnailContainer: FrameLayout = view.findViewById(R.id.thumbnail_container)
        private val thumbnail: ImageView = view.findViewById(R.id.video_thumbnail)
        private val titleView: TextView = view.findViewById(R.id.video_title)
        private val metaView: TextView = view.findViewById(R.id.video_meta)
        private val premiumBadge: TextView = view.findViewById(R.id.video_premium_badge)
        private val watchedBadge: TextView = view.findViewById(R.id.video_watched)
        private val progressTrack: View = view.findViewById(R.id.video_progress_track)
        private val progressBar: View = view.findViewById(R.id.video_progress_bar)
        private val durationView: TextView = view.findViewById(R.id.video_duration)

        init {
            val density = view.resources.displayMetrics.density

            // Premium badge styling
            premiumBadge.background = GradientDrawable().apply {
                setColor(0xCCFFD700.toInt())
                cornerRadius = 4f * density
            }
            // Watched badge styling
            watchedBadge.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC4CAF50.toInt())
            }
            // Duration badge styling
            durationView.background = GradientDrawable().apply {
                setColor(0xCC000000.toInt())
                cornerRadius = 3f * density
            }
            // Progress track styling
            progressTrack.setBackgroundColor(0x55FFFFFF)
        }

        fun bind(item: BrowseItem.VerticalVideo) {
            val video = item.video

            // Enforce 16:9 on each bind (handles orientation changes & recycling)
            thumbnailContainer.post {
                val width = thumbnailContainer.width
                if (width > 0) {
                    val targetHeight = (width * 9) / 16
                    val lp = thumbnailContainer.layoutParams
                    if (lp.height != targetHeight) {
                        lp.height = targetHeight
                        thumbnailContainer.layoutParams = lp
                    }
                }
            }

            titleView.text = video.title

            val meta = buildString {
                video.showTitle?.let { append(it) }
                if (video.publishDate.isNotEmpty()) {
                    if (isNotEmpty()) append(" - ")
                    append(formatDate(video.publishDate))
                }
            }
            metaView.text = meta
            metaView.visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE

            premiumBadge.visibility = if (video.premium) View.VISIBLE else View.GONE
            watchedBadge.visibility = if (video.watched) View.VISIBLE else View.GONE

            // Duration
            if (video.durationSeconds > 0) {
                durationView.text = formatDuration(video.durationSeconds)
                durationView.visibility = View.VISIBLE
            } else {
                durationView.visibility = View.GONE
            }

            // Progress bar
            if (video.progressPercent in 1..99) {
                progressTrack.visibility = View.VISIBLE
                progressBar.visibility = View.VISIBLE
                progressBar.post {
                    val parent = progressBar.parent as? View ?: return@post
                    val lp = progressBar.layoutParams
                    lp.width = (parent.width * video.progressPercent / 100)
                    progressBar.layoutParams = lp
                }
            } else {
                progressTrack.visibility = View.GONE
                progressBar.visibility = View.GONE
            }

            // Thumbnail - use placeholder size to avoid stale cache sizing
            if (!video.thumbnailUrl.isNullOrEmpty()) {
                Glide.with(thumbnail)
                    .load(video.thumbnailUrl)
                    .centerCrop()
                    .into(thumbnail)
            } else {
                thumbnail.setImageResource(0)
            }

            itemView.setOnClickListener {
                val intent = Intent(requireContext(), DetailActivity::class.java).apply {
                    putExtra(DetailActivity.EXTRA_VIDEO, video)
                }
                startActivity(intent)
            }
        }
    }

    private inner class HorizontalVideoRowVH(view: View) : RecyclerView.ViewHolder(view) {
        private val horizontalRecycler: RecyclerView = view.findViewById(R.id.horizontal_recycler)

        init {
            horizontalRecycler.layoutManager = LinearLayoutManager(view.context, LinearLayoutManager.HORIZONTAL, false)
        }

        fun bind(item: BrowseItem.HorizontalVideoRow) {
            horizontalRecycler.adapter = SmallVideoAdapter(item.videos)
        }
    }

    private inner class HorizontalShowRowVH(view: View) : RecyclerView.ViewHolder(view) {
        private val horizontalRecycler: RecyclerView = view.findViewById(R.id.horizontal_recycler)

        init {
            horizontalRecycler.layoutManager = LinearLayoutManager(view.context, LinearLayoutManager.HORIZONTAL, false)
        }

        fun bind(item: BrowseItem.HorizontalShowRow) {
            horizontalRecycler.adapter = SmallShowAdapter(item.shows)
        }
    }

    private inner class SettingVH(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.setting_icon)
        private val title: TextView = view.findViewById(R.id.setting_title)
        private val description: TextView = view.findViewById(R.id.setting_description)

        fun bind(item: BrowseItem.SettingRow) {
            val si = item.item
            icon.setImageResource(si.iconResId)
            icon.setColorFilter(ContextCompat.getColor(requireContext(), R.color.gb_text_secondary))
            title.text = si.title
            description.text = si.description

            itemView.setOnClickListener {
                when (si.id) {
                    SETTINGS_REFRESH -> loadContent()
                    SETTINGS_SETUP -> launchSetup()
                    SETTINGS_QUALITY -> cycleQuality()
                }
            }
        }
    }

    private inner class UpcomingRowVH(view: View) : RecyclerView.ViewHolder(view) {
        private val horizontalRecycler: RecyclerView = view.findViewById(R.id.horizontal_recycler)

        init {
            horizontalRecycler.layoutManager = LinearLayoutManager(view.context, LinearLayoutManager.HORIZONTAL, false)
        }

        fun bind(item: BrowseItem.UpcomingRow) {
            val allStreams = mutableListOf<UpcomingStream>()
            item.liveNow?.let { allStreams.add(it) }
            allStreams.addAll(item.streams)
            horizontalRecycler.adapter = UpcomingAdapter(allStreams)
        }
    }

    // -----------------------------------------------------------------------
    // Inner adapters for horizontal rows
    // -----------------------------------------------------------------------

    private inner class SmallVideoAdapter(private val videos: List<Video>) :
        RecyclerView.Adapter<SmallVideoAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val thumbnail: ImageView = view.findViewById(R.id.small_thumbnail)
            val title: TextView = view.findViewById(R.id.small_title)
            val showName: TextView = view.findViewById(R.id.small_show_name)
            val progressTrack: View = view.findViewById(R.id.small_progress_track)
            val progressBar: View = view.findViewById(R.id.small_progress_bar)
            val watched: TextView = view.findViewById(R.id.small_watched)

            init {
                watched.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xCC4CAF50.toInt())
                }
                progressTrack.setBackgroundColor(0x55FFFFFF)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_mobile_video_small, parent, false)
            return VH(view)
        }

        override fun getItemCount() = videos.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val video = videos[position]
            holder.title.text = video.title
            holder.showName.text = video.showTitle ?: ""
            holder.showName.visibility = if (video.showTitle.isNullOrEmpty()) View.GONE else View.VISIBLE
            holder.watched.visibility = if (video.watched) View.VISIBLE else View.GONE

            // Progress
            if (video.progressPercent in 1..99) {
                holder.progressTrack.visibility = View.VISIBLE
                holder.progressBar.visibility = View.VISIBLE
                holder.progressBar.post {
                    val parent = holder.progressBar.parent as? View ?: return@post
                    val lp = holder.progressBar.layoutParams
                    lp.width = (parent.width * video.progressPercent / 100)
                    holder.progressBar.layoutParams = lp
                }
            } else {
                holder.progressTrack.visibility = View.GONE
                holder.progressBar.visibility = View.GONE
            }

            if (!video.thumbnailUrl.isNullOrEmpty()) {
                Glide.with(holder.thumbnail)
                    .load(video.thumbnailUrl)
                    .centerCrop()
                    .into(holder.thumbnail)
            } else {
                holder.thumbnail.setImageResource(0)
            }

            holder.itemView.setOnClickListener {
                val intent = Intent(requireContext(), DetailActivity::class.java).apply {
                    putExtra(DetailActivity.EXTRA_VIDEO, video)
                }
                startActivity(intent)
            }
        }
    }

    private inner class SmallShowAdapter(private val shows: List<Show>) :
        RecyclerView.Adapter<SmallShowAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val poster: ImageView = view.findViewById(R.id.show_poster)
            val title: TextView = view.findViewById(R.id.show_title)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_mobile_show_small, parent, false)
            return VH(view)
        }

        override fun getItemCount() = shows.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val show = shows[position]
            holder.title.text = show.title

            val imageUrl = show.posterUrl ?: show.logoUrl
            if (!imageUrl.isNullOrEmpty()) {
                Glide.with(holder.poster)
                    .load(imageUrl)
                    .centerCrop()
                    .into(holder.poster)
            } else {
                holder.poster.setImageResource(0)
            }

            holder.itemView.setOnClickListener {
                val intent = Intent(requireContext(), ShowActivity::class.java).apply {
                    putExtra(ShowActivity.EXTRA_SHOW, show)
                }
                startActivity(intent)
            }
        }
    }

    private inner class UpcomingAdapter(
        private val streams: List<UpcomingStream>
    ) : RecyclerView.Adapter<UpcomingAdapter.VH>() {

        private val handler = android.os.Handler(android.os.Looper.getMainLooper())

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val cardBg: FrameLayout = view.findViewById(R.id.upcoming_card_bg)
            val image: ImageView = view.findViewById(R.id.upcoming_image)
            val title: TextView = view.findViewById(R.id.upcoming_title)
            val time: TextView = view.findViewById(R.id.upcoming_time)
            val countdownGroup: View = view.findViewById(R.id.upcoming_countdown_group)
            val hours: TextView = view.findViewById(R.id.upcoming_hours)
            val minutes: TextView = view.findViewById(R.id.upcoming_minutes)
            val seconds: TextView = view.findViewById(R.id.upcoming_seconds)
            val days: TextView = view.findViewById(R.id.upcoming_days)
            val daysGroup: View = view.findViewById(R.id.upcoming_days_group)
            val daysSep: View = view.findViewById(R.id.upcoming_sep1)
            val premiumBadge: TextView = view.findViewById(R.id.upcoming_premium_badge)
            val liveBadge: TextView = view.findViewById(R.id.upcoming_live_badge)
            var countdownRunnable: Runnable? = null

            init {
                val density = view.resources.displayMetrics.density
                val cornerRadius = 12f * density

                cardBg.background = GradientDrawable().apply {
                    setColor(0x18FFFFFF)
                    setCornerRadius(cornerRadius)
                }
                cardBg.outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
                    }
                }
                cardBg.clipToOutline = true

                view.findViewById<View>(R.id.upcoming_scrim).background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0x80000000.toInt(), 0x60000000.toInt())
                )

                view.findViewById<View>(R.id.upcoming_text_area).background = GradientDrawable().apply {
                    setColor(0x0DFFFFFF)
                    cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, cornerRadius, cornerRadius, cornerRadius, cornerRadius)
                }

                premiumBadge.background = GradientDrawable().apply {
                    setColor(0xCCFFD700.toInt())
                    setCornerRadius(4f * density)
                }

                liveBadge.background = GradientDrawable().apply {
                    setColor(0xFFE3192C.toInt())
                    setCornerRadius(4f * density)
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_mobile_upcoming, parent, false)
            return VH(view)
        }

        override fun getItemCount() = streams.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val stream = streams[position]
            holder.title.text = stream.title
            holder.premiumBadge.visibility = if (stream.premium) View.VISIBLE else View.GONE

            if (!stream.image.isNullOrEmpty()) {
                Glide.with(holder.image).load(stream.image).centerCrop().into(holder.image)
            } else {
                holder.image.setImageResource(R.drawable.banner)
            }

            // Stop any previous countdown
            holder.countdownRunnable?.let { handler.removeCallbacks(it) }

            if (stream.isLive) {
                holder.liveBadge.visibility = View.VISIBLE
                holder.countdownGroup.visibility = View.GONE
                holder.time.text = "Streaming now"
                // Load Twitch preview thumbnail for live streams
                Glide.with(holder.image)
                    .load("https://static-cdn.jtvnw.net/previews-ttv/live_user_giantbomb-640x360.jpg")
                    .centerCrop()
                    .into(holder.image)
            } else {
                holder.liveBadge.visibility = View.GONE
                val targetMs = UpcomingCardView.parseDate(stream.date)

                if (targetMs == 0L) {
                    // Unknown time - hide countdown, show fallback
                    holder.countdownGroup.visibility = View.GONE
                    holder.time.text = "Time TBD"
                    return
                }

                holder.countdownGroup.visibility = View.VISIBLE
                holder.time.text = UpcomingCardView.formatLocalTime(targetMs)

                val countdown = object : Runnable {
                    override fun run() {
                        val remaining = targetMs - System.currentTimeMillis()
                        if (remaining <= 0) {
                            holder.countdownGroup.visibility = View.GONE
                            holder.time.text = "Starting soon"
                            return
                        }
                        val totalSec = remaining / 1000
                        val d = totalSec / 86400
                        val h = (totalSec % 86400) / 3600
                        val m = (totalSec % 3600) / 60
                        val s = totalSec % 60

                        if (d > 0) {
                            holder.daysGroup.visibility = View.VISIBLE
                            holder.daysSep.visibility = View.VISIBLE
                            holder.days.text = "%02d".format(d)
                        } else {
                            holder.daysGroup.visibility = View.GONE
                            holder.daysSep.visibility = View.GONE
                        }
                        holder.hours.text = "%02d".format(h)
                        holder.minutes.text = "%02d".format(m)
                        holder.seconds.text = "%02d".format(s)

                        handler.postDelayed(this, 1000)
                    }
                }
                holder.countdownRunnable = countdown
                handler.post(countdown)
            }

            holder.itemView.setOnClickListener {
                // Only allow playback for live streams; future items can't be played yet
                val targetMs = UpcomingCardView.parseDate(stream.date)
                val isStreamLive = stream.isLive || (targetMs > 0L && targetMs <= System.currentTimeMillis())
                if (isStreamLive) {
                    launchTwitchStream(stream.title)
                } else {
                    if (isAdded) {
                        Toast.makeText(requireContext(), "This show hasn't started yet", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        override fun onViewRecycled(holder: VH) {
            holder.countdownRunnable?.let { handler.removeCallbacks(it) }
            holder.countdownRunnable = null
        }

        override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
            super.onDetachedFromRecyclerView(recyclerView)
            for (i in 0 until recyclerView.childCount) {
                val holder = recyclerView.getChildViewHolder(recyclerView.getChildAt(i)) as? VH
                holder?.countdownRunnable?.let { handler.removeCallbacks(it) }
                holder?.countdownRunnable = null
            }
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun formatDate(dateStr: String): String {
        // publishDate format: "2024-01-15 12:00:00" or similar
        return try {
            val parts = dateStr.split(" ")[0].split("-")
            if (parts.size == 3) {
                val months = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun",
                    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
                val month = parts[1].toIntOrNull()?.let { months.getOrNull(it - 1) } ?: parts[1]
                val day = parts[2].toIntOrNull()?.toString() ?: parts[2]
                "$month $day, ${parts[0]}"
            } else {
                dateStr
            }
        } catch (_: Exception) {
            dateStr
        }
    }

    private fun formatDuration(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) {
            "%d:%02d:%02d".format(h, m, s)
        } else {
            "%d:%02d".format(m, s)
        }
    }
}
