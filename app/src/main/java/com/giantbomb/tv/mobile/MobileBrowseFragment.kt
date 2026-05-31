package com.giantbomb.tv.mobile

import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable

import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.giantbomb.tv.data.toggleTwitchChatPref
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
    private var api: GiantBombApi? = null
    private var isLoading = false

    private lateinit var recyclerView: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private var miniPlayerContainer: FrameLayout? = null

    private val browseItems = mutableListOf<BrowseItem>()
    private lateinit var browseAdapter: BrowseAdapter

    // Chip bar: quick-jump strip of section + per-show entries. Populated each
    // time loadContent() finishes, hidden when there's nothing to chip.
    private lateinit var chipBar: RecyclerView
    private data class ChipEntry(val key: String, val label: String, val targetIndex: Int)
    private val chipItems = mutableListOf<ChipEntry>()
    private lateinit var chipAdapter: ChipAdapter
    private var activeChipIndex: Int = -1

    // Set of video IDs currently on the watchlist — drives the per-card
    // bookmark overlay's filled/outlined state. Refreshed at every loadContent
    // and mutated optimistically when the user taps the bookmark button.
    private var watchlistIds: MutableSet<Int> = mutableSetOf()
    // Materialised watchlist videos, kept in sync with watchlistIds so the
    // Watchlist row can be rebuilt in place after a toggle without a full
    // loadContent.
    private var watchlistVideos: MutableList<Video> = mutableListOf()
    // Progress entries (videoId → entry) cached so card taps can pass an
    // explicit resume position to PlaybackActivity, avoiding a second
    // network round-trip from the player and the race that comes with it.
    private var progressByVideoId: Map<Int, ProgressEntry> = emptyMap()
    // Indices of the watchlist header / content row in browseItems, captured
    // at the end of loadContent. -1 if the watchlist section isn't rendered
    // (hidden, or section order put it somewhere we can't find).
    private var watchlistHeaderItemIndex: Int = -1
    private var watchlistContentItemIndex: Int = -1

    // Upcoming/Live row tracked separately so we can refresh just that row
    // (and its header) every minute without rebuilding the whole grid.
    private var upcomingRowItemIndex: Int = -1
    private var upcomingHeaderItemIndex: Int = -1
    private val refreshHandler = Handler(Looper.getMainLooper())
    private var upcomingRefreshRunnable: Runnable? = null
    private var lastUpcomingFailureMs: Long = 0L

    // Section header positions, populated each time loadContent runs. Drives the
    // chip-bar quick-jump and any other "scroll to section X" affordance.
    private var sectionStartIndices: Map<String, Int> = emptyMap()

    companion object {
        private const val SETTINGS_REFRESH = 2
        private const val SETTINGS_SETUP = 3
        private const val SETTINGS_QUALITY = 4
        private const val SETTINGS_PRIVACY = 5
        private const val SETTINGS_CUSTOMIZE = 6
        private const val SETTINGS_PIP_BACK = 7
        private const val SETTINGS_TWITCH_CHAT = 8
        private const val INITIAL_VIDEO_LIMIT = 100
        private const val ROW_PAGE_SIZE = 40
        private const val RECENT_VERTICAL_COUNT = 5
        // Polling /upcoming_json every 60s pushed us into the endpoint's
        // hard rate limit (it applies to everyone, not just our UA). 180s is
        // still snappy enough for "stream went live" to surface within a few
        // minutes while cutting our request count to a third.
        private const val UPCOMING_REFRESH_MS = 180_000L
        // After a failed upcoming poll, suppress the next few ticks so we
        // don't keep hammering an endpoint that's already throttling us.
        private const val UPCOMING_FAILURE_BACKOFF_MS = 300_000L
    }

    // Sealed class representing different row types in the feed
    sealed class BrowseItem {
        data class SectionHeader(val title: String, val showSeeAll: Boolean = false) : BrowseItem()
        // Per-show header that knows which Show it represents — long-press toggles
        // pinned state. Visually identical to SectionHeader, distinguished only
        // because the toggle target needs the Show object at click time.
        data class ShowSectionHeader(val show: Show, val pinned: Boolean) : BrowseItem()
        data class HorizontalVideoRow(val videos: List<Video>) : BrowseItem()
        data class HorizontalShowRow(val shows: List<Show>) : BrowseItem()
        data class VerticalVideo(val video: Video) : BrowseItem()
        data class SettingRow(val item: SettingsItem) : BrowseItem()
        data class UpcomingRow(val streams: List<UpcomingStream>, val liveNow: UpcomingStream?) : BrowseItem()
        class LazyShowRow(val show: Show, var videos: List<Video>? = null, var isLoading: Boolean = false) : BrowseItem()
        // Used when a section is intentionally rendered with a friendly message
        // instead of being hidden — e.g. an empty watchlist after a fresh install.
        data class EmptyStateRow(val message: String) : BrowseItem()
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

        chipBar = view.findViewById(R.id.chip_bar)
        chipBar.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        chipAdapter = ChipAdapter()
        chipBar.adapter = chipAdapter

        // Sync the active chip + add visual breathing room between sections.
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                updateActiveChip()
            }
        })
        recyclerView.addItemDecoration(SectionGapDecoration())

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
        upcomingRefreshRunnable?.let { refreshHandler.removeCallbacks(it) }
        upcomingRefreshRunnable = null
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
        scheduleUpcomingRefresh()
    }

    private fun scheduleUpcomingRefresh() {
        upcomingRefreshRunnable?.let { refreshHandler.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                refreshUpcomingRow()
                refreshHandler.postDelayed(this, UPCOMING_REFRESH_MS)
            }
        }
        upcomingRefreshRunnable = r
        refreshHandler.postDelayed(r, UPCOMING_REFRESH_MS)
    }

    private fun refreshUpcomingRow() {
        val key = prefs.apiKey ?: return
        if (key.isEmpty()) return
        // Skip if we recently got rate-limited / failed — polling harder makes
        // the throttle stickier, not softer.
        if (System.currentTimeMillis() - lastUpcomingFailureMs < UPCOMING_FAILURE_BACKOFF_MS) return
        val apiInstance = api ?: GiantBombApi(key)
        launch {
            val rawResult = apiInstance.getUpcoming()
            val result = rawResult.getOrNull()
            if (result == null) {
                lastUpcomingFailureMs = System.currentTimeMillis()
                return@launch
            }
            if (!isAdded) return@launch

            val hasContent = result.liveNow != null || result.upcoming.isNotEmpty()
            val rowIdx = upcomingRowItemIndex
            val hdrIdx = upcomingHeaderItemIndex
            val rowPresent = rowIdx in browseItems.indices && browseItems[rowIdx] is BrowseItem.UpcomingRow

            when {
                rowPresent && hasContent -> {
                    browseItems[rowIdx] = BrowseItem.UpcomingRow(result.upcoming, result.liveNow)
                    browseAdapter.notifyItemChanged(rowIdx)
                    if (hdrIdx in browseItems.indices) {
                        val newTitle = if (result.liveNow != null) "🔴 Upcoming & Live" else "Upcoming"
                        val current = browseItems[hdrIdx] as? BrowseItem.SectionHeader
                        if (current != null && current.title != newTitle) {
                            browseItems[hdrIdx] = current.copy(title = newTitle)
                            browseAdapter.notifyItemChanged(hdrIdx)
                        }
                    }
                }
                rowPresent && !hasContent -> {
                    // Remove header + row pair (header sits immediately before the row).
                    val removeFrom = if (hdrIdx in browseItems.indices && hdrIdx == rowIdx - 1) hdrIdx else rowIdx
                    val removeCount = if (removeFrom == hdrIdx) 2 else 1
                    repeat(removeCount) { browseItems.removeAt(removeFrom) }
                    browseAdapter.notifyItemRangeRemoved(removeFrom, removeCount)
                    upcomingRowItemIndex = -1
                    upcomingHeaderItemIndex = -1
                    // Any tracked index that sat AFTER the removal point shifts
                    // down by `removeCount`. Without this the watchlist refresh
                    // and the chip-bar quick-jump aim at stale positions.
                    if (watchlistHeaderItemIndex >= removeFrom) {
                        watchlistHeaderItemIndex -= removeCount
                    }
                    if (watchlistContentItemIndex >= removeFrom) {
                        watchlistContentItemIndex -= removeCount
                    }
                    sectionStartIndices = sectionStartIndices
                        .filterKeys { it != PrefsManager.SECTION_LIVE }
                        .mapValues { (_, idx) ->
                            if (idx >= removeFrom) idx - removeCount else idx
                        }
                    updateChipBar()
                }
                !rowPresent && hasContent -> {
                    // Section wasn't rendered at last loadContent (either there
                    // was no content then, or the user hid SECTION_LIVE).
                    // Inserting blindly at index 0 would bypass the user-
                    // defined section order and re-introduce a hidden section,
                    // and would also break every tracked index without a
                    // matching shift. Leave it — the next loadContent
                    // (onResume / pull-to-refresh) will render it in its
                    // configured position.
                }
                // !rowPresent && !hasContent → nothing to do
            }
        }
    }

    fun loadContent() {
        if (isLoading) return
        isLoading = true
        swipeRefresh.isRefreshing = true

        val key = prefs.apiKey ?: ""
        api = GiantBombApi(key)
        val api = api!!

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
                progressByVideoId = progressMap

                fun Video.withProgress(): Video {
                    val entry = progressMap[id]
                    val v = when {
                        entry != null && entry.percentComplete >= 95 -> copy(watched = true)
                        entry != null && entry.percentComplete in 1..94 -> copy(progressPercent = entry.percentComplete)
                        else -> this
                    }
                    return v.withFallbackThumb()
                }

                // Resolve all the deferred fetches we'll need before rendering.
                val upcomingResult = upcomingDeferred.await().getOrNull()
                val recentResult = recentDeferred.await()
                recentResult.onFailure { e ->
                    if (isAdded) {
                        Toast.makeText(requireContext(),
                            GiantBombApi.friendlyErrorMessage(e), Toast.LENGTH_LONG).show()
                    }
                }
                val recentVideos = recentResult.getOrNull()?.map { it.withProgress() }
                val watchlist = if (key.isNotEmpty()) {
                    watchlistDeferred.await().getOrNull()?.map { it.withProgress() }
                } else null
                watchlistIds = watchlist?.map { it.id }?.toMutableSet() ?: mutableSetOf()
                watchlistVideos = watchlist?.toMutableList() ?: mutableListOf()

                val pinnedIds = prefs.getPinnedShowIds()
                val hidden = prefs.getHiddenSections()

                // Render sections in user-defined order. Each helper appends to `items`
                // and returns true if it produced anything; we track the first item's
                // index so the chip bar can scroll-to-section.
                val indices = mutableMapOf<String, Int>()
                upcomingHeaderItemIndex = -1
                upcomingRowItemIndex = -1

                for (sectionId in prefs.getSectionOrder()) {
                    if (sectionId in hidden) continue
                    val startIdx = items.size
                    val added = when (sectionId) {
                        PrefsManager.SECTION_LIVE -> renderLiveSection(items, upcomingResult)
                        PrefsManager.SECTION_CONTINUE -> renderContinueSection(items, recentVideos, progressMap, key)
                        PrefsManager.SECTION_WATCHLIST -> renderWatchlistSection(items, watchlist)
                        PrefsManager.SECTION_RECENT -> renderRecentSection(items, recentVideos)
                        PrefsManager.SECTION_PINNED -> renderPinnedShowsSection(items, shows, pinnedIds)
                        PrefsManager.SECTION_ACTIVE_SHOWS -> renderActiveShowsSection(items, shows, pinnedIds)
                        PrefsManager.SECTION_PREMIUM -> renderPremiumSection(items, recentVideos)
                        PrefsManager.SECTION_LEGACY -> renderLegacyShowsSection(items, shows, pinnedIds)
                        PrefsManager.SECTION_SETTINGS -> renderSettingsSection(items)
                        else -> false
                    }
                    if (added) indices[sectionId] = startIdx
                }

                sectionStartIndices = indices
                indices[PrefsManager.SECTION_LIVE]?.let { liveStart ->
                    upcomingHeaderItemIndex = liveStart
                    upcomingRowItemIndex = liveStart + 1
                }
                indices[PrefsManager.SECTION_WATCHLIST]?.let { wlStart ->
                    watchlistHeaderItemIndex = wlStart
                    watchlistContentItemIndex = wlStart + 1
                } ?: run {
                    watchlistHeaderItemIndex = -1
                    watchlistContentItemIndex = -1
                }

                browseItems.clear()
                browseItems.addAll(items)
                browseAdapter.notifyDataSetChanged()
                updateChipBar()

            } finally {
                isLoading = false
                if (isAdded) {
                    swipeRefresh.isRefreshing = false
                }
            }
        }
    }

    // ----------------------------------------------------------------------
    // Section renderers
    // Each appends to `items` and returns true if it produced anything. The
    // driver in loadContent() iterates prefs.getSectionOrder() and calls these
    // in the user-defined order, then records each section's start index for
    // the chip-bar quick-jump and the upcoming-row mid-flight refresh.
    // ----------------------------------------------------------------------

    private fun renderLiveSection(
        items: MutableList<BrowseItem>,
        upcoming: com.giantbomb.tv.model.UpcomingResponse?
    ): Boolean {
        val u = upcoming ?: return false
        if (u.liveNow == null && u.upcoming.isEmpty()) return false
        val title = if (u.liveNow != null) "🔴 Upcoming & Live" else "Upcoming"
        items.add(BrowseItem.SectionHeader(title))
        items.add(BrowseItem.UpcomingRow(u.upcoming, u.liveNow))
        return true
    }

    private fun renderContinueSection(
        items: MutableList<BrowseItem>,
        recentVideos: List<Video>?,
        progressMap: Map<Int, ProgressEntry>,
        apiKey: String
    ): Boolean {
        if (apiKey.isEmpty() || progressMap.isEmpty() || recentVideos.isNullOrEmpty()) return false
        val inProgressIds = progressMap.values
            .filter { it.percentComplete in 1..94 }
            .sortedByDescending { it.currentTime }
            .take(20)
            .map { it.videoId }
            .toSet()
        if (inProgressIds.isEmpty()) return false
        val videos = recentVideos.filter { it.id in inProgressIds }
        if (videos.isEmpty()) return false
        items.add(BrowseItem.SectionHeader(getString(R.string.continue_watching)))
        items.add(BrowseItem.HorizontalVideoRow(videos))
        return true
    }

    private fun renderWatchlistSection(
        items: MutableList<BrowseItem>,
        watchlist: List<Video>?
    ): Boolean {
        // null means we couldn't fetch (no key / network error) — hide.
        if (watchlist == null) return false
        items.add(BrowseItem.SectionHeader("My Watchlist"))
        if (watchlist.isEmpty()) {
            // Empty but reachable — render a friendly hint instead of hiding so
            // users know where the watchlist lives and how to add to it.
            items.add(BrowseItem.EmptyStateRow(
                "Your watchlist is empty. Tap the + on any video to save it for later."
            ))
        } else {
            items.add(BrowseItem.HorizontalVideoRow(watchlist))
        }
        return true
    }

    private fun renderRecentSection(
        items: MutableList<BrowseItem>,
        recentVideos: List<Video>?
    ): Boolean {
        if (recentVideos.isNullOrEmpty()) return false
        items.add(BrowseItem.SectionHeader(getString(R.string.recent)))
        recentVideos.take(RECENT_VERTICAL_COUNT).forEach { items.add(BrowseItem.VerticalVideo(it)) }
        val remaining = recentVideos.drop(RECENT_VERTICAL_COUNT)
        if (remaining.isNotEmpty()) {
            items.add(BrowseItem.HorizontalVideoRow(remaining))
        }
        return true
    }

    private fun renderPinnedShowsSection(
        items: MutableList<BrowseItem>,
        shows: List<Show>?,
        pinnedIds: List<Int>
    ): Boolean {
        if (shows == null || pinnedIds.isEmpty()) return false
        // Render in pin order, not API order.
        val byId = shows.associateBy { it.id }
        val pinnedShows = pinnedIds.mapNotNull { byId[it] }
        if (pinnedShows.isEmpty()) return false
        for (s in pinnedShows) {
            items.add(BrowseItem.ShowSectionHeader(s, pinned = true))
            items.add(BrowseItem.LazyShowRow(s))
        }
        return true
    }

    private fun renderActiveShowsSection(
        items: MutableList<BrowseItem>,
        shows: List<Show>?,
        pinnedIds: List<Int>
    ): Boolean {
        if (shows == null) return false
        val pinnedSet = pinnedIds.toSet()
        val nonPinnedActive = shows.filter { it.active && it.id !in pinnedSet }
        if (nonPinnedActive.isEmpty()) return false
        for (s in nonPinnedActive) {
            items.add(BrowseItem.ShowSectionHeader(s, pinned = false))
            items.add(BrowseItem.LazyShowRow(s))
        }
        return true
    }

    private fun renderPremiumSection(
        items: MutableList<BrowseItem>,
        recentVideos: List<Video>?
    ): Boolean {
        if (recentVideos == null) return false
        val premium = recentVideos.filter { it.premium }
        if (premium.size < 2) return false
        items.add(BrowseItem.SectionHeader("Premium"))
        items.add(BrowseItem.HorizontalVideoRow(premium))
        return true
    }

    private fun renderLegacyShowsSection(
        items: MutableList<BrowseItem>,
        shows: List<Show>?,
        pinnedIds: List<Int>
    ): Boolean {
        if (shows == null) return false
        val pinnedSet = pinnedIds.toSet()
        // Legacy shows the user has pinned bubble up to the Pinned section, so
        // exclude them here to avoid duplicate cards.
        val legacy = shows.filter { !it.active && it.id !in pinnedSet }
        if (legacy.isEmpty()) return false
        items.add(BrowseItem.SectionHeader("Legacy Shows"))
        items.add(BrowseItem.HorizontalShowRow(legacy))
        return true
    }

    private fun renderSettingsSection(items: MutableList<BrowseItem>): Boolean {
        items.add(BrowseItem.SectionHeader(getString(R.string.settings)))
        items.add(BrowseItem.SettingRow(SettingsItem(
            SETTINGS_CUSTOMIZE,
            "Customize Browse",
            "Reorder or hide browse sections",
            R.drawable.ic_settings_cog
        )))
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
            SETTINGS_PIP_BACK,
            "Back Enters Picture-in-Picture",
            backPipSubtitle(prefs.backEntersPip),
            R.drawable.ic_settings_cog
        )))
        items.add(BrowseItem.SettingRow(SettingsItem(
            SETTINGS_SETUP,
            getString(R.string.settings_setup),
            getString(R.string.settings_setup_desc),
            R.drawable.ic_settings_cog
        )))
        items.add(BrowseItem.SettingRow(SettingsItem(
            SETTINGS_TWITCH_CHAT,
            getString(R.string.settings_twitch_chat),
            getString(
                if (prefs.showTwitchChat) R.string.settings_twitch_chat_shown
                else R.string.settings_twitch_chat_hidden
            ),
            R.drawable.ic_settings_cog
        )))
        items.add(BrowseItem.SettingRow(SettingsItem(
            SETTINGS_PRIVACY,
            "Privacy Policy",
            "View privacy policy",
            R.drawable.ic_settings_about
        )))
        val now = java.text.SimpleDateFormat("EEE, MMM d 'at' h:mm:ss a", java.util.Locale.getDefault()).format(java.util.Date())
        items.add(BrowseItem.SettingRow(SettingsItem(
            -1,
            "Last Updated",
            now,
            R.drawable.ic_settings_refresh
        )))
        val packageInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
        val versionText = "v${packageInfo.versionName} (${packageInfo.longVersionCode})"
        items.add(BrowseItem.SettingRow(SettingsItem(
            -1,
            "Version",
            versionText,
            R.drawable.ic_settings_cog
        )))
        return true
    }

    fun getMiniPlayerContainer(): FrameLayout? = miniPlayerContainer

    private fun toggleTwitchChat() {
        val nowShown = prefs.toggleTwitchChatPref()
        val msg = getString(
            if (nowShown) R.string.toast_twitch_chat_shown
            else R.string.toast_twitch_chat_hidden
        )
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        loadContent()
    }

    private fun cycleQuality() {
        val options = PrefsManager.QUALITY_OPTIONS
        val current = prefs.preferredQuality
        val nextIndex = (options.indexOf(current) + 1) % options.size
        prefs.preferredQuality = options[nextIndex]
        Toast.makeText(requireContext(),
            "Default quality: ${PrefsManager.qualityLabel(options[nextIndex])}", Toast.LENGTH_SHORT).show()
        loadContent()
    }

    private fun backPipSubtitle(enabled: Boolean): String =
        if (enabled) "On — Back shrinks the player into a floating window"
        else "Off — Back returns to the previous screen"

    private fun toggleBackPip() {
        val enabled = !prefs.backEntersPip
        prefs.backEntersPip = enabled
        Toast.makeText(requireContext(), backPipSubtitle(enabled), Toast.LENGTH_SHORT).show()
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
                    putExtra(PlaybackActivity.EXTRA_LIVE_TWITCH_CHANNEL, PlaybackActivity.DEFAULT_TWITCH_CHANNEL)
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

    private fun openPrivacyPolicy() {
        val intent = Intent(
            Intent.ACTION_VIEW,
            android.net.Uri.parse("https://clinteastman.github.io/GiantBombTV/privacy.html")
        )
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        upcomingRefreshRunnable?.let { refreshHandler.removeCallbacks(it) }
        cancel()
    }

    // -----------------------------------------------------------------------
    // Adapter
    // -----------------------------------------------------------------------

    private fun updateChipBar() {
        val order = prefs.getSectionOrder()
        val hidden = prefs.getHiddenSections()
        chipItems.clear()
        // Keep section starts sorted so we can find the boundary of each
        // section when expanding pinned/active sections into per-show chips.
        val sortedStarts = sectionStartIndices.entries
            .filter { it.key !in hidden }
            .sortedBy { it.value }
        for (id in order) {
            if (id in hidden) continue
            val start = sectionStartIndices[id] ?: continue
            when (id) {
                PrefsManager.SECTION_PINNED, PrefsManager.SECTION_ACTIVE_SHOWS -> {
                    // Expand into one chip per show. Find the slice of
                    // browseItems that belongs to this section, pick out
                    // each ShowSectionHeader, and emit a chip targeting
                    // that header's row.
                    val nextStart = sortedStarts.firstOrNull { it.value > start }
                        ?.value ?: browseItems.size
                    for (i in start until nextStart) {
                        val item = browseItems.getOrNull(i) ?: break
                        if (item is BrowseItem.ShowSectionHeader) {
                            chipItems.add(
                                ChipEntry(
                                    key = "show:${item.show.id}",
                                    label = item.show.title,
                                    targetIndex = i
                                )
                            )
                        }
                    }
                }
                else -> {
                    chipItems.add(
                        ChipEntry(
                            key = id,
                            label = PrefsManager.sectionLabel(id),
                            targetIndex = start
                        )
                    )
                }
            }
        }
        activeChipIndex = -1
        if (::chipAdapter.isInitialized) chipAdapter.notifyDataSetChanged()
        chipBar.visibility = if (chipItems.isEmpty()) View.GONE else View.VISIBLE
        // Snap the active chip to whatever's already in view at this point —
        // matters on initial load and after pull-to-refresh.
        updateActiveChip()
    }

    private fun updateActiveChip() {
        if (chipItems.isEmpty()) return
        val lm = recyclerView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        if (first < 0) return
        // chipItems are emitted in row-order during updateChipBar, so the
        // last one whose target is ≤ first-visible-row is the chip the user
        // is currently sitting on.
        var newIdx = -1
        for ((i, chip) in chipItems.withIndex()) {
            if (chip.targetIndex <= first) newIdx = i else break
        }
        if (newIdx < 0) newIdx = 0
        if (newIdx == activeChipIndex) return
        val old = activeChipIndex
        activeChipIndex = newIdx
        if (old in chipItems.indices) chipAdapter.notifyItemChanged(old)
        if (newIdx in chipItems.indices) {
            chipAdapter.notifyItemChanged(newIdx)
            chipBar.smoothScrollToPosition(newIdx)
        }
    }

    private fun jumpToChipTarget(targetIndex: Int) {
        if (targetIndex < 0 || targetIndex >= browseItems.size) return
        val lm = recyclerView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
        if (lm != null) {
            val scroller = object : androidx.recyclerview.widget.LinearSmoothScroller(requireContext()) {
                override fun getVerticalSnapPreference(): Int = SNAP_TO_START
            }
            scroller.targetPosition = targetIndex
            lm.startSmoothScroll(scroller)
        } else {
            recyclerView.smoothScrollToPosition(targetIndex)
        }
    }

    private inner class ChipAdapter : RecyclerView.Adapter<ChipAdapter.VH>() {
        inner class VH(val text: TextView) : RecyclerView.ViewHolder(text)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val density = ctx.resources.displayMetrics.density
            val tv = TextView(ctx).apply {
                setPadding(
                    (16 * density).toInt(),
                    (8 * density).toInt(),
                    (16 * density).toInt(),
                    (8 * density).toInt()
                )
                setTextColor(ContextCompat.getColor(ctx, R.color.gb_white))
                textSize = 13f
                isClickable = true
                isFocusable = true
                layoutParams = ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = (8 * density).toInt()
                }
            }
            return VH(tv)
        }

        override fun getItemCount(): Int = chipItems.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val chip = chipItems[position]
            holder.text.text = chip.label
            val ctx = holder.text.context
            val density = ctx.resources.displayMetrics.density
            val active = position == activeChipIndex
            holder.text.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 100f * density
                if (active) {
                    setColor(ContextCompat.getColor(ctx, R.color.gb_red))
                    setStroke((1 * density).toInt(), ContextCompat.getColor(ctx, R.color.gb_red))
                } else {
                    setColor(0x33FFFFFF)
                    setStroke((1 * density).toInt(), 0x55FFFFFF)
                }
            }
            holder.text.typeface = if (active) {
                android.graphics.Typeface.DEFAULT_BOLD
            } else {
                android.graphics.Typeface.DEFAULT
            }
            holder.text.setOnClickListener { jumpToChipTarget(chip.targetIndex) }
        }
    }

    /**
     * Adds breathing room before each section header so the visual transition
     * between sections is easier to spot. First item gets no gap.
     */
    private inner class SectionGapDecoration : RecyclerView.ItemDecoration() {
        private val gapPx: Int = (24 * resources.displayMetrics.density).toInt()

        override fun getItemOffsets(
            outRect: android.graphics.Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            val pos = parent.getChildAdapterPosition(view)
            if (pos <= 0 || pos >= browseItems.size) return
            val item = browseItems[pos]
            if (item is BrowseItem.SectionHeader || item is BrowseItem.ShowSectionHeader) {
                outRect.top = gapPx
            }
        }
    }

    private inner class BrowseAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        val TYPE_SECTION_HEADER = 0
        val TYPE_HORIZONTAL_VIDEO_ROW = 1
        val TYPE_HORIZONTAL_SHOW_ROW = 2
        val TYPE_VERTICAL_VIDEO = 3
        val TYPE_SETTING = 4
        val TYPE_UPCOMING_ROW = 5
        val TYPE_LAZY_SHOW_ROW = 6
        val TYPE_SHOW_SECTION_HEADER = 7
        val TYPE_EMPTY_STATE = 8

        override fun getItemViewType(position: Int): Int = when (browseItems[position]) {
            is BrowseItem.SectionHeader -> TYPE_SECTION_HEADER
            is BrowseItem.ShowSectionHeader -> TYPE_SHOW_SECTION_HEADER
            is BrowseItem.HorizontalVideoRow -> TYPE_HORIZONTAL_VIDEO_ROW
            is BrowseItem.HorizontalShowRow -> TYPE_HORIZONTAL_SHOW_ROW
            is BrowseItem.VerticalVideo -> TYPE_VERTICAL_VIDEO
            is BrowseItem.SettingRow -> TYPE_SETTING
            is BrowseItem.UpcomingRow -> TYPE_UPCOMING_ROW
            is BrowseItem.LazyShowRow -> TYPE_LAZY_SHOW_ROW
            is BrowseItem.EmptyStateRow -> TYPE_EMPTY_STATE
        }

        override fun getItemCount() = browseItems.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_SECTION_HEADER -> SectionHeaderVH(inflater.inflate(R.layout.item_section_header, parent, false))
                TYPE_SHOW_SECTION_HEADER -> ShowSectionHeaderVH(inflater.inflate(R.layout.item_show_section_header, parent, false))
                TYPE_HORIZONTAL_VIDEO_ROW -> HorizontalVideoRowVH(inflater.inflate(R.layout.item_horizontal_row, parent, false))
                TYPE_HORIZONTAL_SHOW_ROW -> HorizontalShowRowVH(inflater.inflate(R.layout.item_horizontal_row, parent, false))
                TYPE_VERTICAL_VIDEO -> VerticalVideoVH(inflater.inflate(R.layout.item_mobile_video, parent, false))
                TYPE_SETTING -> SettingVH(inflater.inflate(R.layout.item_mobile_setting, parent, false))
                TYPE_UPCOMING_ROW -> UpcomingRowVH(inflater.inflate(R.layout.item_horizontal_row, parent, false))
                TYPE_LAZY_SHOW_ROW -> LazyShowRowVH(inflater.inflate(R.layout.item_horizontal_row, parent, false))
                TYPE_EMPTY_STATE -> {
                    val ctx = parent.context
                    val density = ctx.resources.displayMetrics.density
                    val tv = TextView(ctx).apply {
                        setPadding(
                            (20 * density).toInt(),
                            (8 * density).toInt(),
                            (20 * density).toInt(),
                            (16 * density).toInt()
                        )
                        textSize = 14f
                        setTextColor(0xFFA0A0A0.toInt())
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    }
                    EmptyStateVH(tv)
                }
                else -> throw IllegalArgumentException("Unknown view type: $viewType")
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = browseItems[position]) {
                is BrowseItem.SectionHeader -> (holder as SectionHeaderVH).bind(item)
                is BrowseItem.ShowSectionHeader -> (holder as ShowSectionHeaderVH).bind(item)
                is BrowseItem.HorizontalVideoRow -> (holder as HorizontalVideoRowVH).bind(item)
                is BrowseItem.HorizontalShowRow -> (holder as HorizontalShowRowVH).bind(item)
                is BrowseItem.VerticalVideo -> (holder as VerticalVideoVH).bind(item)
                is BrowseItem.SettingRow -> (holder as SettingVH).bind(item)
                is BrowseItem.UpcomingRow -> (holder as UpcomingRowVH).bind(item)
                is BrowseItem.LazyShowRow -> (holder as LazyShowRowVH).bind(item)
                is BrowseItem.EmptyStateRow -> (holder as EmptyStateVH).bind(item)
            }
        }
    }

    private inner class EmptyStateVH(val text: TextView) : RecyclerView.ViewHolder(text) {
        fun bind(item: BrowseItem.EmptyStateRow) {
            text.text = item.message
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
            itemView.setOnLongClickListener(null)
        }
    }

    private inner class ShowSectionHeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.section_title)
        private val pinStar: TextView = view.findViewById(R.id.pin_star)

        fun bind(item: BrowseItem.ShowSectionHeader) {
            title.text = item.show.title
            // Filled ★ = pinned, outlined ☆ = pinnable. Tap toggles; long-press
            // on the row anywhere also toggles, kept as a power-user shortcut.
            pinStar.text = if (item.pinned) "★" else "☆"
            pinStar.contentDescription = if (item.pinned) {
                "Unpin ${item.show.title}"
            } else {
                "Pin ${item.show.title} to top"
            }
            pinStar.setOnClickListener { togglePin(item.show) }
            itemView.setOnLongClickListener {
                togglePin(item.show)
                true
            }
        }
    }

    /**
     * Style the bookmark overlay button on a video card based on whether the
     * video is currently on the watchlist. Callers wire setOnClickListener to
     * toggleWatchlist() so a tap flips the state.
     */
    private fun bindWatchlistButton(btn: TextView, video: Video) {
        val onWatchlist = video.id in watchlistIds
        val ctx = btn.context
        val density = ctx.resources.displayMetrics.density
        btn.text = if (onWatchlist) "✓" else "+"
        btn.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            if (onWatchlist) {
                setColor(ContextCompat.getColor(ctx, R.color.gb_red))
            } else {
                setColor(0x99000000.toInt())
                setStroke((1 * density).toInt(), 0x66FFFFFF)
            }
        }
        btn.contentDescription = if (onWatchlist) {
            "Remove ${video.title} from watchlist"
        } else {
            "Add ${video.title} to watchlist"
        }
        btn.setOnClickListener { v ->
            // Optimistic flip — animate immediately, revert on API failure.
            v.isClickable = false
            val nowOn = video.id !in watchlistIds
            if (nowOn) {
                watchlistIds.add(video.id)
                // Add to the local watchlist (newest first matches the API).
                if (watchlistVideos.none { it.id == video.id }) {
                    watchlistVideos.add(0, video)
                }
            } else {
                watchlistIds.remove(video.id)
                watchlistVideos.removeAll { it.id == video.id }
            }
            bindWatchlistButton(btn, video)
            refreshWatchlistRow()
            launch {
                val api = api ?: GiantBombApi(prefs.apiKey ?: "")
                val result = if (nowOn) api.addToWatchlist(video.id) else api.removeFromWatchlist(video.id)
                v.isClickable = true
                result.onSuccess {
                    if (isAdded) {
                        Toast.makeText(
                            requireContext(),
                            if (nowOn) "Added to watchlist" else "Removed from watchlist",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                result.onFailure {
                    // Revert local state and re-render.
                    if (nowOn) {
                        watchlistIds.remove(video.id)
                        watchlistVideos.removeAll { it.id == video.id }
                    } else {
                        watchlistIds.add(video.id)
                        if (watchlistVideos.none { it.id == video.id }) {
                            watchlistVideos.add(0, video)
                        }
                    }
                    bindWatchlistButton(btn, video)
                    refreshWatchlistRow()
                    if (isAdded) {
                        Toast.makeText(
                            requireContext(),
                            "Watchlist update failed",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
    }

    /**
     * Replaces the Watchlist content row in browseItems based on watchlistVideos
     * and notifies the adapter, so the row redraws without a full loadContent.
     * Handles the empty ↔ non-empty transition by swapping between
     * EmptyStateRow and HorizontalVideoRow at the same row index.
     */
    private fun refreshWatchlistRow() {
        val rowIdx = watchlistContentItemIndex
        if (rowIdx !in browseItems.indices) return
        val newRow: BrowseItem = if (watchlistVideos.isEmpty()) {
            BrowseItem.EmptyStateRow(
                "Your watchlist is empty. Tap the + on any video to save it for later."
            )
        } else {
            BrowseItem.HorizontalVideoRow(watchlistVideos.toList())
        }
        browseItems[rowIdx] = newRow
        browseAdapter.notifyItemChanged(rowIdx)
    }

    /**
     * Builds a PlaybackActivity intent for a video card, attaching the cached
     * resume position when we have one. Mobile taps go straight to playback
     * (skipping DetailActivity), so without this helper the player has to do
     * its own getProgress() round-trip — which races with playback start, so
     * the user observes "starts from the beginning" even though they had a
     * saved position.
     */
    private fun buildPlaybackIntent(video: Video): Intent {
        val intent = Intent(requireContext(), PlaybackActivity::class.java).apply {
            putExtra(PlaybackActivity.EXTRA_VIDEO, video)
        }
        val entry = progressByVideoId[video.id]
        if (entry != null && entry.percentComplete in 1..94 && entry.currentTime > 0) {
            intent.putExtra(PlaybackActivity.EXTRA_RESUME_SECONDS, entry.currentTime)
        }
        return intent
    }

    private fun togglePin(show: Show) {
        val nowPinned = prefs.togglePinnedShow(show.id)
        val msg = if (nowPinned) "Pinned: ${show.title}" else "Unpinned: ${show.title}"
        if (isAdded) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        // Re-render so the show jumps in/out of the Pinned section. Cheap enough
        // — pin is a low-frequency action.
        loadContent()
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
        private val watchlistBtn: TextView = view.findViewById(R.id.video_watchlist_btn)

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
                Glide.with(thumbnail.context)
                    .load(video.thumbnailUrl)
                    .centerCrop()
                    .placeholder(R.drawable.default_card)
                    .error(R.drawable.default_card)
                    .into(thumbnail)
            } else {
                thumbnail.setImageResource(R.drawable.default_card)
            }

            bindWatchlistButton(watchlistBtn, video)

            itemView.setOnClickListener {
                startActivity(buildPlaybackIntent(video))
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
                    SETTINGS_PRIVACY -> openPrivacyPolicy()
                    SETTINGS_CUSTOMIZE -> launchCustomizeBrowse()
                    SETTINGS_PIP_BACK -> toggleBackPip()
                    SETTINGS_TWITCH_CHAT -> toggleTwitchChat()
                }
            }
        }
    }

    private fun launchCustomizeBrowse() {
        startActivity(Intent(requireContext(), CustomizeBrowseActivity::class.java))
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

    private inner class LazyShowRowVH(view: View) : RecyclerView.ViewHolder(view) {
        private val horizontalRecycler: RecyclerView = view.findViewById(R.id.horizontal_recycler)

        init {
            horizontalRecycler.layoutManager = LinearLayoutManager(view.context, LinearLayoutManager.HORIZONTAL, false)
        }

        fun bind(item: BrowseItem.LazyShowRow) {
            if (item.videos != null) {
                horizontalRecycler.adapter = SmallVideoAdapter(item.videos!!)
                return
            }
            if (item.isLoading) return
            val currentApi = api ?: return
            item.isLoading = true
            horizontalRecycler.adapter = null
            launch {
                val result = currentApi.getShowVideos(item.show.id, limit = ROW_PAGE_SIZE)
                result.onSuccess { videos ->
                    item.videos = videos
                    item.isLoading = false
                    if (isAdded) {
                        horizontalRecycler.adapter = SmallVideoAdapter(videos)
                    }
                }
                result.onFailure {
                    item.isLoading = false
                }
            }
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
            val watchlistBtn: TextView = view.findViewById(R.id.small_watchlist_btn)

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
                Glide.with(holder.thumbnail.context)
                    .load(video.thumbnailUrl)
                    .centerCrop()
                    .placeholder(R.drawable.default_card)
                    .error(R.drawable.default_card)
                    .into(holder.thumbnail)
            } else {
                holder.thumbnail.setImageResource(R.drawable.default_card)
            }

            bindWatchlistButton(holder.watchlistBtn, video)

            holder.itemView.setOnClickListener {
                startActivity(buildPlaybackIntent(video))
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
                Glide.with(holder.poster.context)
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
            holder.itemView.setOnLongClickListener {
                togglePin(show)
                true
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
                Glide.with(holder.image.context).load(stream.image).centerCrop().into(holder.image)
            } else {
                holder.image.setImageResource(R.drawable.banner)
            }

            // Stop any previous countdown
            holder.countdownRunnable?.let { handler.removeCallbacks(it) }

            if (stream.isLive) {
                holder.liveBadge.visibility = View.VISIBLE
                holder.countdownGroup.visibility = View.GONE
                holder.time.text = "Streaming now"
                // stream.image is already the 1280x720 cache-busted Twitch preview
                // (populated by the API layer when live) — loaded by the shared
                // Glide call above.
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

    private fun formatDate(dateStr: String): String =
        com.giantbomb.tv.util.DateFormat.formatPublishDate(dateStr)

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
