package com.giantbomb.tv

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.*
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.giantbomb.tv.data.GiantBombApi
import com.giantbomb.tv.data.PrefsManager
import com.giantbomb.tv.model.ProgressEntry
import com.giantbomb.tv.model.SettingsItem
import com.giantbomb.tv.model.Show
import com.giantbomb.tv.model.Video
import com.giantbomb.tv.data.TwitchExtractor
import com.giantbomb.tv.model.UpcomingStream
import com.giantbomb.tv.ui.CardPresenter
import com.giantbomb.tv.ui.SettingsCardPresenter
import com.giantbomb.tv.ui.ShowCardPresenter
import com.giantbomb.tv.ui.UpcomingCardPresenter
import kotlinx.coroutines.*

class BrowseFragment : BrowseSupportFragment(), CoroutineScope by MainScope() {

    private lateinit var prefs: PrefsManager
    private var isLoading = false
    private val handler = Handler(Looper.getMainLooper())
    private var backdropRunnable: Runnable? = null
    private var backdropImageView: ImageView? = null
    private var backdropNextView: ImageView? = null
    private var currentBackdropUrl: String? = null
    private var loadingSpinner: ProgressBar? = null

    companion object {
        const val SETTINGS_REFRESH = 2
        const val SETTINGS_SETUP = 3
        const val SETTINGS_QUALITY = 4
        const val SETTINGS_PRIVACY = 5
        private const val BACKDROP_DELAY_MS = 300L
        private const val CROSSFADE_DURATION = 600L
        private const val BACKDROP_ALPHA = 0.5f
        private const val INITIAL_VIDEO_LIMIT = 100
        private const val ROW_PAGE_SIZE = 40
        private const val LOAD_MORE_THRESHOLD = 15
    }

    // Per-row pagination state for show-grouped rows
    private data class RowPagination(
        val showTitle: String,
        val showId: Int,
        val adapter: ArrayObjectAdapter,
        var offset: Int,
        var hasMore: Boolean = true,
        var isLoading: Boolean = false
    )
    private val rowPaginationMap = mutableMapOf<String, RowPagination>()

    @Suppress("DEPRECATION")
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        prefs = PrefsManager(requireContext())

        loadingSpinner = requireActivity().findViewById(R.id.loading_spinner)
        setupBackdrop()
        setupUIElements()
        setupListeners()
        setupVerticalGridPrefetch()
        // setupKeyInterceptor() // TODO: re-enable when pin/unpin is fixed

        // First launch: redirect to setup if no API key
        if (prefs.apiKey.isNullOrEmpty()) {
            launchSetup()
        } else {
            loadContent()
        }
    }

    private fun setupVerticalGridPrefetch() {
        // Pre-layout extra rows offscreen so they're rendered before the user scrolls to them
        view?.viewTreeObserver?.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                view?.viewTreeObserver?.removeOnGlobalLayoutListener(this)
                try {
                    val rowsFragment = childFragmentManager.fragments.firstOrNull { it is androidx.leanback.app.RowsSupportFragment }
                    val gridView = (rowsFragment as? androidx.leanback.app.RowsSupportFragment)?.verticalGridView
                    gridView?.setExtraLayoutSpace(800)
                } catch (_: Exception) { }
            }
        })
    }

    private fun setupBackdrop() {
        backdropImageView = requireActivity().findViewById(R.id.backdrop_image)
        backdropNextView = requireActivity().findViewById(R.id.backdrop_image_next)
    }

    private fun setupUIElements() {
        badgeDrawable = ContextCompat.getDrawable(requireContext(), R.drawable.giant_bomb_logo)
        headersState = HEADERS_ENABLED
        isHeadersTransitionOnBackEnabled = true
        brandColor = 0x00000000 // transparent — let the blurred backdrop show through

        searchAffordanceColor = ContextCompat.getColor(requireContext(), R.color.gb_surface)
        val searchDrawable = ContextCompat.getDrawable(requireContext(), R.drawable.ic_search)
        titleView?.findViewById<SearchOrbView>(androidx.leanback.R.id.title_orb)?.let { orb ->
            orb.orbIcon = searchDrawable
        }
    }

    private fun setupListeners() {
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, row ->
            when (item) {
                is Video -> {
                    val intent = Intent(requireContext(), DetailActivity::class.java).apply {
                        putExtra(DetailActivity.EXTRA_VIDEO, item)
                    }
                    startActivity(intent)
                }
                is Show -> {
                    // TODO: Pin/unpin favourite shows - disabled due to Leanback key repeat issue
                    // val isFav = prefs.toggleFavouriteShow(item.id)
                    // val msg = if (isFav) "\u2605 ${item.title} pinned" else "${item.title} unpinned"
                    // Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                    val intent = Intent(requireContext(), ShowActivity::class.java).apply {
                        putExtra(ShowActivity.EXTRA_SHOW, item)
                    }
                    startActivity(intent)
                }
                is UpcomingStream -> {
                    val targetMs = com.giantbomb.tv.ui.UpcomingCardView.parseDate(item.date)
                    val isStreamLive = item.isLive || (targetMs > 0L && targetMs <= System.currentTimeMillis())
                    if (isStreamLive) {
                        launchTwitchStream(item.title)
                    } else {
                        Toast.makeText(requireContext(), "This show hasn't started yet", Toast.LENGTH_SHORT).show()
                    }
                }
                is SettingsItem -> {
                    when (item.id) {
                        SETTINGS_REFRESH -> loadContent()
                        SETTINGS_SETUP -> launchSetup()
                        SETTINGS_QUALITY -> cycleQuality()
                        SETTINGS_PRIVACY -> openPrivacyPolicy()
                    }
                }
            }
        }

        // Debounced backdrop update on item focus + per-row pagination
        onItemViewSelectedListener = OnItemViewSelectedListener { _, item, _, row ->
            // Lazy-load empty show rows when they receive focus
            val header = (row as? ListRow)?.headerItem?.name
            if (header != null) {
                val pagination = rowPaginationMap[header]
                if (pagination != null && !pagination.isLoading && pagination.hasMore && pagination.offset == 0) {
                    loadMoreForRow(pagination)
                }
            }

            when (item) {
                is Video -> {
                    updateBackdropDebounced(item.thumbnailUrl ?: item.posterUrl)
                    // Check if we need to load more for this row
                    if (header != null) {
                        val pagination = rowPaginationMap[header]
                        if (pagination != null && !pagination.isLoading && pagination.hasMore) {
                            val position = pagination.adapter.indexOf(item)
                            if (position >= pagination.adapter.size() - LOAD_MORE_THRESHOLD) {
                                loadMoreForRow(pagination)
                            }
                        }
                    }
                }
                is Show -> updateBackdropDebounced(item.posterUrl ?: item.logoUrl)
            }
        }

        setOnSearchClickedListener {
            val intent = Intent(requireContext(), SearchActivity::class.java)
            startActivity(intent)
        }
    }

    private fun setupKeyInterceptor() {
        // Block select/enter key repeats to prevent rapid-fire pin toggling
        // Leanback consumes key events internally, so we must intercept at the grid level
        view?.viewTreeObserver?.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                view?.viewTreeObserver?.removeOnGlobalLayoutListener(this)
                try {
                    val rowsFragment = childFragmentManager.fragments.firstOrNull { it is androidx.leanback.app.RowsSupportFragment }
                    val gridView = (rowsFragment as? androidx.leanback.app.RowsSupportFragment)?.verticalGridView
                    gridView?.setOnKeyInterceptListener { event ->
                        val isSelectKey = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                                event.keyCode == KeyEvent.KEYCODE_ENTER ||
                                event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                                event.keyCode == KeyEvent.KEYCODE_BUTTON_A
                        isSelectKey && event.repeatCount > 0
                    }
                } catch (_: Exception) { }
            }
        })
    }

    private fun updateBackdropDebounced(imageUrl: String?) {
        backdropRunnable?.let { handler.removeCallbacks(it) }
        if (imageUrl == currentBackdropUrl) return
        backdropRunnable = Runnable {
            if (!isAdded) return@Runnable
            val current = backdropImageView ?: return@Runnable
            val next = backdropNextView ?: return@Runnable
            currentBackdropUrl = imageUrl

            if (imageUrl.isNullOrEmpty()) {
                current.animate().alpha(0f).setDuration(CROSSFADE_DURATION).start()
                next.animate().alpha(0f).setDuration(CROSSFADE_DURATION).start()
                return@Runnable
            }

            // Load new image into the hidden "next" layer
            Glide.with(requireContext())
                .load(imageUrl)
                .override(480, 270)
                .transform(
                    com.bumptech.glide.load.resource.bitmap.CenterCrop(),
                    com.giantbomb.tv.ui.BlurTransformation(radius = 10, passes = 2)
                )
                .into(object : com.bumptech.glide.request.target.CustomTarget<android.graphics.drawable.Drawable>() {
                    override fun onResourceReady(resource: android.graphics.drawable.Drawable, transition: com.bumptech.glide.request.transition.Transition<in android.graphics.drawable.Drawable>?) {
                        if (!isAdded) return
                        next.setImageDrawable(resource)
                        // Crossfade: fade in next, fade out current
                        next.animate().alpha(BACKDROP_ALPHA).setDuration(CROSSFADE_DURATION).start()
                        current.animate().alpha(0f).setDuration(CROSSFADE_DURATION)
                            .withEndAction {
                                // Swap: move the loaded image to "current", clear "next"
                                current.setImageDrawable(resource)
                                current.alpha = BACKDROP_ALPHA
                                next.alpha = 0f
                                next.setImageDrawable(null)
                            }
                            .start()
                    }
                    override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {
                        next.setImageDrawable(null)
                    }
                })
        }
        handler.postDelayed(backdropRunnable!!, BACKDROP_DELAY_MS)
    }

    fun loadContent() {
        if (isLoading) return
        isLoading = true

        val key = prefs.apiKey ?: ""
        val api = GiantBombApi(key)

        launch {
            try {
            loadingSpinner?.visibility = View.VISIBLE
            val rowPresenter = ListRowPresenter(androidx.leanback.widget.FocusHighlight.ZOOM_FACTOR_NONE).apply {
                shadowEnabled = false
                selectEffectEnabled = false  // disable the dim overlay on unfocused rows
            }
            val rowsAdapter = ArrayObjectAdapter(rowPresenter)
            var headerIdCounter = 0L

            val upcomingDeferred = async { api.getUpcoming() }
            val watchlistDeferred = async { api.getWatchlist() }
            val progressDeferred = async { api.getProgress() }
            val recentDeferred = async { api.getVideos(limit = INITIAL_VIDEO_LIMIT) }
            val showsDeferred = async { api.getShows() }

            // Await shows early so we can use show posters as thumbnail fallback
            val shows = showsDeferred.await().getOrNull()
            val showPosterMap = shows?.associate { it.id to (it.posterUrl ?: it.logoUrl) } ?: emptyMap()
            fun Video.withFallbackThumb(): Video {
                if (!thumbnailUrl.isNullOrEmpty()) return this
                val fallback = showId?.let { showPosterMap[it] }
                return if (fallback != null) copy(thumbnailUrl = fallback, isFallbackThumb = true) else this
            }

            // Upcoming & Live
            val upcomingResult = upcomingDeferred.await().getOrNull()
            if (upcomingResult != null) {
                val hasContent = upcomingResult.liveNow != null || upcomingResult.upcoming.isNotEmpty()
                if (hasContent) {
                    val headerTitle = if (upcomingResult.liveNow != null) "\uD83D\uDD34 Upcoming & Live" else "Upcoming"
                    val adapter = ArrayObjectAdapter(UpcomingCardPresenter())
                    // Live item first
                    if (upcomingResult.liveNow != null) {
                        adapter.add(upcomingResult.liveNow)
                    }
                    upcomingResult.upcoming.forEach { adapter.add(it) }
                    rowsAdapter.add(ListRow(
                        HeaderItem(headerIdCounter++, headerTitle),
                        adapter
                    ))
                }
            }

            // Continue Watching
            var progressMap = emptyMap<Int, ProgressEntry>()
            if (key.isNotEmpty()) {
                val progress = progressDeferred.await().getOrNull()
                if (progress != null && progress.isNotEmpty()) {
                    progressMap = progress.associateBy { it.videoId }
                    val inProgressEntries = progress
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
                            val continueAdapter = ArrayObjectAdapter(CardPresenter())
                            continueVideos.forEach { continueAdapter.add(it) }
                            rowsAdapter.add(ListRow(
                                HeaderItem(headerIdCounter++, getString(R.string.continue_watching)),
                                continueAdapter
                            ))
                        }
                    }
                }
            }

            // Helper to apply progress + watched status + fallback thumb to any video
            fun Video.withProgress(): Video {
                val entry = progressMap[id]
                val v = when {
                    entry != null && entry.percentComplete >= 95 -> copy(watched = true)
                    entry != null && entry.percentComplete in 1..94 -> copy(progressPercent = entry.percentComplete)
                    else -> this
                }
                return v.withFallbackThumb()
            }

            // Recent videos
            val recent = recentDeferred.await()
            recent.onSuccess { videos ->
                if (videos.isNotEmpty()) {
                    val recentAdapter = ArrayObjectAdapter(CardPresenter())
                    videos.forEach { recentAdapter.add(it.withProgress()) }
                    rowsAdapter.add(ListRow(
                        HeaderItem(headerIdCounter++, getString(R.string.recent)),
                        recentAdapter
                    ))
                }

                // Premium row right after Recent
                val premiumVideos = videos.filter { it.premium }
                if (premiumVideos.size >= 2) {
                    val premiumAdapter = ArrayObjectAdapter(CardPresenter())
                    premiumVideos.forEach { premiumAdapter.add(it.withProgress()) }
                    rowsAdapter.add(ListRow(
                        HeaderItem(headerIdCounter++, "Premium"),
                        premiumAdapter
                    ))
                }
            }

            recent.onFailure { e ->
                Toast.makeText(requireContext(),
                    GiantBombApi.friendlyErrorMessage(e), Toast.LENGTH_LONG).show()
            }

            // Per-show video rows - lazy loaded on focus
            rowPaginationMap.clear()
            if (shows != null && shows.isNotEmpty()) {
                val activeShows = shows.filter { it.active }

                // Browse Shows card row
                val showsAdapter = ArrayObjectAdapter(ShowCardPresenter())
                activeShows.forEach { showsAdapter.add(it) }
                if (showsAdapter.size() > 0) {
                    rowsAdapter.add(ListRow(
                        HeaderItem(headerIdCounter++, "All Shows"),
                        showsAdapter
                    ))
                }

                // Empty show rows - videos load when the row is focused
                for (s in activeShows) {
                    val listRowAdapter = ArrayObjectAdapter(CardPresenter())
                    val rowTitle = s.title
                    rowsAdapter.add(ListRow(
                        HeaderItem(headerIdCounter++, rowTitle),
                        listRowAdapter
                    ))
                    rowPaginationMap[rowTitle] = RowPagination(
                        showTitle = s.title,
                        showId = s.id,
                        adapter = listRowAdapter,
                        offset = 0,
                        hasMore = true
                    )
                }

                // Past shows card row
                val inactiveShows = shows.filter { !it.active }
                if (inactiveShows.isNotEmpty()) {
                    val inactiveAdapter = ArrayObjectAdapter(ShowCardPresenter())
                    inactiveShows.forEach { inactiveAdapter.add(it) }
                    rowsAdapter.add(ListRow(
                        HeaderItem(headerIdCounter++, "Past Shows"),
                        inactiveAdapter
                    ))
                }
            }

            // Watchlist
            val watchlist = if (key.isNotEmpty()) watchlistDeferred.await().getOrNull() else null
            if (watchlist != null && watchlist.isNotEmpty()) {
                val wlAdapter = ArrayObjectAdapter(CardPresenter())
                watchlist.forEach { wlAdapter.add(it.withProgress()) }
                rowsAdapter.add(ListRow(
                    HeaderItem(headerIdCounter++, "My Watchlist"),
                    wlAdapter
                ))
            }

            // Free Videos row
            recent.onSuccess { videos ->
                val freeVideos = videos.filter { !it.premium }
                if (freeVideos.size >= 2) {
                    val freeAdapter = ArrayObjectAdapter(CardPresenter())
                    freeVideos.forEach { freeAdapter.add(it.withProgress()) }
                    rowsAdapter.add(ListRow(
                        HeaderItem(headerIdCounter++, "Free Videos"),
                        freeAdapter
                    ))
                }
            }

            val utilAdapter = ArrayObjectAdapter(SettingsCardPresenter())
            utilAdapter.add(SettingsItem(
                SETTINGS_REFRESH,
                getString(R.string.settings_refresh),
                getString(R.string.settings_refresh_desc),
                R.drawable.ic_settings_refresh
            ))
            utilAdapter.add(SettingsItem(
                SETTINGS_QUALITY,
                "Stream Quality",
                "Default: ${PrefsManager.qualityLabel(prefs.preferredQuality)}",
                R.drawable.ic_settings_quality
            ))
            utilAdapter.add(SettingsItem(
                SETTINGS_SETUP,
                getString(R.string.settings_setup),
                getString(R.string.settings_setup_desc),
                R.drawable.ic_settings_cog
            ))
            utilAdapter.add(SettingsItem(
                SETTINGS_PRIVACY,
                "Privacy Policy",
                "View privacy policy",
                R.drawable.ic_settings_about
            ))
            rowsAdapter.add(ListRow(
                HeaderItem(headerIdCounter, getString(R.string.settings)),
                utilAdapter
            ))

            // Set adapter last so show rows (appended lazily as they load) are present.
            adapter = rowsAdapter
            title = null
            } finally {
                loadingSpinner?.visibility = View.GONE
                isLoading = false
            }
        }
    }

    private var hasBeenVisible = false

    override fun onResume() {
        super.onResume()
        if (adapter == null || adapter.size() == 0) {
            loadContent()
        } else if (hasBeenVisible) {
            // Refresh after returning from playback/detail to update progress & watched state
            loadContent()
        }
        hasBeenVisible = true
    }

    private fun cycleQuality() {
        val options = PrefsManager.QUALITY_OPTIONS
        val current = prefs.preferredQuality
        val nextIndex = (options.indexOf(current) + 1) % options.size
        prefs.preferredQuality = options[nextIndex]
        Toast.makeText(requireContext(),
            "Default quality: ${PrefsManager.qualityLabel(options[nextIndex])}", Toast.LENGTH_SHORT).show()
        // Reload to update the settings card description
        loadContent()
    }

    private fun loadMoreForRow(pagination: RowPagination) {
        pagination.isLoading = true
        val key = prefs.apiKey ?: ""
        val api = GiantBombApi(key)

        launch {
            try {
                val result = api.getShowVideos(pagination.showId, limit = ROW_PAGE_SIZE, offset = pagination.offset)
                result.onSuccess { videos ->
                    if (videos.size < ROW_PAGE_SIZE) pagination.hasMore = false
                    pagination.offset += videos.size

                    val progressResult = api.getProgress().getOrNull()
                    val progressMap = progressResult?.associateBy { it.videoId } ?: emptyMap()

                    videos.forEach { video ->
                        val entry = progressMap[video.id]
                        val v = when {
                            entry != null && entry.percentComplete >= 95 -> video.copy(watched = true)
                            entry != null && entry.percentComplete in 1..94 -> video.copy(progressPercent = entry.percentComplete)
                            else -> video
                        }
                        pagination.adapter.add(v)
                    }
                }
                result.onFailure {
                    pagination.hasMore = false
                }
            } finally {
                pagination.isLoading = false
            }
        }
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
                Toast.makeText(requireContext(),
                    "Stream not available: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun launchSetup() {
        val intent = Intent(requireContext(), SetupActivity::class.java)
        @Suppress("DEPRECATION")
        requireActivity().startActivityForResult(intent, MainActivity.SETUP_REQUEST)
    }

    private fun openPrivacyPolicy() {
        val intent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("https://clinteastman.github.io/GiantBombTV/privacy.html")
        )
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        backdropRunnable?.let { handler.removeCallbacks(it) }
        cancel()
    }
}
