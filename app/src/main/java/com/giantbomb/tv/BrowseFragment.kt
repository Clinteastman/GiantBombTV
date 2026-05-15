package com.giantbomb.tv

import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
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
import com.giantbomb.tv.data.toggleTwitchChatPref
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

    // Upcoming/Live row tracked separately so we can refresh just that one without
    // rebuilding the whole grid (which would lose focus and re-fetch every endpoint).
    private var rowsAdapterRef: ArrayObjectAdapter? = null
    private var upcomingRowAdapter: ArrayObjectAdapter? = null
    private var upcomingRowIndex: Int = -1
    private var upcomingHasLive: Boolean = false
    private var upcomingRefreshRunnable: Runnable? = null

    companion object {
        const val SETTINGS_REFRESH = 2
        const val SETTINGS_SETUP = 3
        const val SETTINGS_QUALITY = 4
        const val SETTINGS_PRIVACY = 5
        const val SETTINGS_TWITCH_CHAT = 6
        private const val BACKDROP_DELAY_MS = 300L
        private const val CROSSFADE_DURATION = 600L
        private const val BACKDROP_ALPHA = 0.5f
        private const val INITIAL_VIDEO_LIMIT = 100
        private const val ROW_PAGE_SIZE = 40
        private const val LOAD_MORE_THRESHOLD = 15
        // Number of cards eagerly populated into each show row at startup.
        // Users only ever see about three cards before scrolling/focusing, so
        // prefetching this many makes the first peek feel populated. Focus then
        // triggers loadMoreForRow to fetch the rest of the page.
        private const val SHOW_ROW_PREFETCH = 3
        // How often to re-poll the upcoming/live feed while the screen is foregrounded.
        // Twitch's preview thumbnail also refreshes ~every minute, so this aligns nicely.
        // Match the mobile interval (was 60s). Together with the failure
        // backoff below this drops our /upcoming_json request rate to about
        // a third per device, which keeps Cloudflare's bot challenge off
        // when both phone and TV are running on the same LAN.
        private const val UPCOMING_REFRESH_MS = 180_000L
        private const val UPCOMING_FAILURE_BACKOFF_MS = 300_000L
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

    // Maps each row's HeaderItem.id to the action target it represents, so the
    // custom side-menu header presenter can show the right context menu on
    // long-press (pin/unpin a show, reorder a top-level section, etc.).
    private sealed class HeaderContext {
        data class Section(val id: String) : HeaderContext()
        data class Show(val show: com.giantbomb.tv.model.Show, val pinned: Boolean) : HeaderContext()
    }
    private val headerContexts = mutableMapOf<Long, HeaderContext>()

    // Monotonically-increasing HeaderItem id, bumped each time a section
    // renderer adds a row. Reset to 0 at the top of loadContent() so every
    // render pass produces a stable id sequence.
    private var headerIdCounter: Long = 0L

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
                    val extraPx = (400 * resources.displayMetrics.density).toInt()
                    gridView?.setExtraLayoutSpace(extraPx)
                } catch (e: Exception) {
                    android.util.Log.w("BrowseFragment", "Prefetch setup failed", e)
                }
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

        // Custom header presenter so each header view carries its HeaderItem
        // as a tag — used by MainActivity.dispatchKeyEvent to identify which
        // row the user was holding when long-press fires. Leanback's
        // HeadersSupportFragment overwrites view-level click listeners and
        // BaseGridView short-circuits DPAD_CENTER to performClick(), so the
        // long-press has to be detected at the activity level.
        setHeaderPresenterSelector(SinglePresenterSelector(BrowseHeaderPresenter()))
    }

    /**
     * RowHeaderPresenter that tags each header view with its HeaderItem so the
     * activity-level dispatchKeyEvent long-press handler can find which row
     * was held without trying to navigate the Leanback adapter internals.
     *
     * View-level setOnLongClickListener / setOnKeyListener don't fire here:
     * Leanback's HeadersSupportFragment installs its own OnClickListener on
     * each header view via ItemBridgeAdapter.AdapterListener, and BaseGridView
     * short-circuits DPAD_CENTER to performClick() directly — bypassing the
     * View framework's long-press detection. The activity-level handler in
     * MainActivity.dispatchKeyEvent (calling tryShowFocusedHeaderMenu below)
     * is the reliable place to catch a held centre key.
     */
    private class BrowseHeaderPresenter : RowHeaderPresenter() {
        override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any) {
            super.onBindViewHolder(viewHolder, item)
            viewHolder.view.tag = item as? HeaderItem
            applyHint(viewHolder as ViewHolder)
        }

        // Leanback drives header selection via setSelectLevel (0 = unselected,
        // 1 = the focused header in the side menu). On the selected header we
        // upgrade the discreet "⋮" glyph to a red-accented "⋮ Hold OK" hint so
        // the long-press affordance is unmistakable; everything else keeps the
        // subtle "⋮".
        override fun onSelectLevelChanged(holder: ViewHolder) {
            super.onSelectLevelChanged(holder)
            applyHint(holder)
        }

        private fun applyHint(holder: ViewHolder) {
            val header = holder.view.tag as? HeaderItem ?: return
            val tv = holder.view as? android.widget.TextView ?: return
            val name = header.name
            if (holder.selectLevel > 0.5f) {
                val hint = "  ⋮ Hold OK"
                val span = android.text.SpannableString("$name$hint")
                val start = name.length
                val end = span.length
                span.setSpan(
                    android.text.style.ForegroundColorSpan(0xFFE3192C.toInt()),
                    start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                span.setSpan(
                    android.text.style.RelativeSizeSpan(0.72f),
                    start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                span.setSpan(
                    android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                tv.text = span
            } else {
                tv.text = "$name  ⋮"
            }
        }
    }

    /**
     * Called by MainActivity when D-pad centre has been held past the
     * long-press threshold while the side menu is showing. Walks the focused
     * view's tag to find the bound HeaderItem and pops the context menu.
     * Returns true if we found a header to act on.
     */
    fun tryShowFocusedHeaderMenu(): Boolean {
        if (!isShowingHeaders) return false
        // Skip the view-tree walking and use Leanback's own state: the
        // currently-selected position in the rows adapter corresponds to the
        // focused header in the side menu (Leanback updates it as the user
        // navigates up/down in HeadersSupportFragment). Pull the ListRow at
        // that position and use its HeaderItem.
        val pos = selectedPosition
        val rows = adapter as? ArrayObjectAdapter ?: return false
        if (pos < 0 || pos >= rows.size()) return false
        val row = rows.get(pos) as? ListRow ?: return false
        val header = row.headerItem ?: return false
        showHeaderContextMenu(header)
        return true
    }

    private fun showHeaderContextMenu(header: HeaderItem) {
        val ctx = headerContexts[header.id] ?: return
        val items = mutableListOf<Pair<String, () -> Unit>>()
        when (ctx) {
            is HeaderContext.Section -> {
                items += "Move up" to { moveSection(ctx.id, -1) }
                items += "Move down" to { moveSection(ctx.id, +1) }
            }
            is HeaderContext.Show -> {
                items += (if (ctx.pinned) "Unpin" else "Pin to top") to { togglePin(ctx.show) }
                if (ctx.pinned) {
                    items += "Move up" to { movePinnedShow(ctx.show.id, -1) }
                    items += "Move down" to { movePinnedShow(ctx.show.id, +1) }
                }
            }
        }
        if (items.isEmpty()) return
        val labels = items.map { it.first }.toTypedArray()
        AlertDialog.Builder(requireContext(), R.style.GbDialogTheme)
            .setTitle(header.name)
            .setItems(labels) { _, which -> items[which].second() }
            .show()
    }

    private fun moveSection(sectionId: String, direction: Int) {
        val current = prefs.getSectionOrder().toMutableList()
        val idx = current.indexOf(sectionId)
        if (idx < 0) return
        val newIdx = idx + direction
        if (newIdx !in current.indices) return
        current.removeAt(idx)
        current.add(newIdx, sectionId)
        prefs.setSectionOrder(current)
        loadContent()
    }

    private fun movePinnedShow(showId: Int, direction: Int) {
        val current = prefs.getPinnedShowIds().toMutableList()
        val idx = current.indexOf(showId)
        if (idx < 0) return
        val newIdx = idx + direction
        if (newIdx !in current.indices) return
        current.removeAt(idx)
        current.add(newIdx, showId)
        prefs.setPinnedShowOrder(current)
        loadContent()
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
                        SETTINGS_TWITCH_CHAT -> toggleTwitchChat()
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
            headerIdCounter = 0L
            // Reset row tracking — rowsAdapter is brand new and the upcoming row, if any,
            // will be added below. We'll capture references then for partial refresh.
            rowsAdapterRef = rowsAdapter
            upcomingRowAdapter = null
            upcomingRowIndex = -1
            upcomingHasLive = false

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

            // Resolve all the deferred fetches so the section dispatcher below
            // sees plain values (the lazy-load show pagination is set up inside
            // the relevant render block).
            val upcomingResult = upcomingDeferred.await().getOrNull()
            val recent = recentDeferred.await()
            recent.onFailure { e ->
                Toast.makeText(requireContext(),
                    GiantBombApi.friendlyErrorMessage(e), Toast.LENGTH_LONG).show()
            }
            val recentVideosRaw = recent.getOrNull()

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

            val recentVideos = recentVideosRaw?.map { it.withProgress() }
            val watchlist = if (key.isNotEmpty()) {
                watchlistDeferred.await().getOrNull()?.map { it.withProgress() }
            } else null

            val pinnedIds = prefs.getPinnedShowIds()
            val hidden = prefs.getHiddenSections()

            // Show pagination map drives lazy-loading of per-show video rows on
            // focus. Reset before each render so the new ListRows own their
            // adapters.
            rowPaginationMap.clear()
            headerContexts.clear()

            // Render sections in user-defined order, mirroring the mobile
            // section model. The TV-specific bits:
            //   - the chip bar's job is done by Leanback's headers fragment;
            //   - SECTION_ACTIVE_SHOWS prepends a "Browse Shows" grid row so
            //     users have a place to long-press D-pad-centre on a show
            //     card to pin / unpin it.
            for (sectionId in prefs.getSectionOrder()) {
                if (sectionId in hidden) continue
                when (sectionId) {
                    PrefsManager.SECTION_LIVE         -> renderLive(rowsAdapter, upcomingResult)
                    PrefsManager.SECTION_CONTINUE     -> renderContinue(rowsAdapter, recentVideos, progressMap, key)
                    PrefsManager.SECTION_WATCHLIST    -> renderWatchlist(rowsAdapter, watchlist, key)
                    PrefsManager.SECTION_RECENT       -> renderRecent(rowsAdapter, recentVideos)
                    PrefsManager.SECTION_PINNED       -> renderPinnedShows(rowsAdapter, shows, pinnedIds)
                    PrefsManager.SECTION_ACTIVE_SHOWS -> renderActiveShows(rowsAdapter, shows, pinnedIds)
                    PrefsManager.SECTION_PREMIUM      -> renderPremium(rowsAdapter, recentVideos)
                    PrefsManager.SECTION_LEGACY       -> renderLegacyShows(rowsAdapter, shows, pinnedIds)
                    PrefsManager.SECTION_SETTINGS    -> renderSettings(rowsAdapter)
                }
            }

                        // Set adapter last so show rows (appended lazily as they load) are present.
            adapter = rowsAdapter
            title = null

            // Eagerly prefetch the first few cards for every show row. Users
            // never see more than ~3 cards before they focus a row, so making
            // those three appear immediately makes the page feel "loaded"
            // without us having to fetch every show's full page upfront.
            // We mark each pagination as loading first so the focus listener
            // doesn't race with the prefetch and end up firing a duplicate
            // GET before the prefetch completes.
            for (pagination in rowPaginationMap.values) {
                pagination.isLoading = true
            }
            for (pagination in rowPaginationMap.values) {
                launch {
                    val result = api.getShowVideos(
                        showId = pagination.showId,
                        limit = SHOW_ROW_PREFETCH,
                        offset = 0
                    )
                    val videos = result.getOrNull()
                    if (videos != null && isAdded) {
                        videos.forEach { pagination.adapter.add(it.withProgress()) }
                        pagination.offset = videos.size
                        if (videos.size < SHOW_ROW_PREFETCH) pagination.hasMore = false
                    }
                    pagination.isLoading = false
                }
            }
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
        scheduleUpcomingRefresh()
    }

    override fun onPause() {
        super.onPause()
        upcomingRefreshRunnable?.let { handler.removeCallbacks(it) }
        upcomingRefreshRunnable = null
    }

    private fun scheduleUpcomingRefresh() {
        upcomingRefreshRunnable?.let { handler.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                refreshUpcomingRow()
                handler.postDelayed(this, UPCOMING_REFRESH_MS)
            }
        }
        upcomingRefreshRunnable = r
        handler.postDelayed(r, UPCOMING_REFRESH_MS)
    }

    private var lastUpcomingFailureMs: Long = 0L

    private fun refreshUpcomingRow() {
        val key = prefs.apiKey ?: return
        if (key.isEmpty()) return
        // Don't keep hammering /upcoming_json while we're in the bad-state window.
        if (System.currentTimeMillis() - lastUpcomingFailureMs < UPCOMING_FAILURE_BACKOFF_MS) return
        val rowsAdapter = rowsAdapterRef ?: return
        val api = GiantBombApi(key)
        launch {
            val result = api.getUpcoming().getOrNull()
            if (result == null) {
                lastUpcomingFailureMs = System.currentTimeMillis()
                return@launch
            }
            if (!isAdded) return@launch

            val hasContent = result.liveNow != null || result.upcoming.isNotEmpty()
            val isLive = result.liveNow != null
            val current = upcomingRowAdapter

            when {
                current != null && hasContent && isLive == upcomingHasLive -> {
                    // Same live state — swap items in place; preserves focus.
                    current.clear()
                    result.liveNow?.let { current.add(it) }
                    result.upcoming.forEach { current.add(it) }
                }
                current != null && hasContent -> {
                    // Live state flipped — replace the row so the header updates too.
                    // Preserve the existing HeaderItem.id so the row stays mapped
                    // in headerContexts; using a fresh id (or 0) would silently
                    // collide with another section and break the long-press menu.
                    val newAdapter = ArrayObjectAdapter(UpcomingCardPresenter())
                    result.liveNow?.let { newAdapter.add(it) }
                    result.upcoming.forEach { newAdapter.add(it) }
                    val title = if (isLive) "🔴 Upcoming & Live" else "Upcoming"
                    val existingHid = (rowsAdapter.get(upcomingRowIndex) as? ListRow)
                        ?.headerItem?.id
                    if (existingHid != null) {
                        rowsAdapter.replace(
                            upcomingRowIndex,
                            ListRow(HeaderItem(existingHid, title), newAdapter)
                        )
                        upcomingRowAdapter = newAdapter
                        upcomingHasLive = isLive
                    }
                }
                current != null && !hasContent -> {
                    rowsAdapter.removeItems(upcomingRowIndex, 1)
                    upcomingRowAdapter = null
                    upcomingRowIndex = -1
                    upcomingHasLive = false
                }
                current == null && hasContent -> {
                    // Section wasn't rendered at last loadContent (either there
                    // was no content then, or the user hid SECTION_LIVE entirely).
                    // Don't blindly insert at index 0 — that bypasses the user-
                    // defined section order and re-introduces a hidden section.
                    // The next loadContent (onResume / pull-to-refresh) will
                    // render it in its configured position with a fresh
                    // headerContexts entry.
                }
            }
        }
    }

    private fun toggleTwitchChat() {
        val nowShown = prefs.toggleTwitchChatPref()
        val msg = if (nowShown) "Twitch chat: shown on live streams"
                  else "Twitch chat: hidden on live streams"
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

    // ----------------------------------------------------------------------
    // Section renderers — appended to `rowsAdapter` in user-defined order by
    // the dispatcher in loadContent(). Each mutates `headerIdCounter`,
    // `headerContexts`, and (for show rows) `rowPaginationMap` directly,
    // mirroring the structure of MobileBrowseFragment's renderXxxSection
    // helpers so both fragments stay easy to follow side-by-side.
    // ----------------------------------------------------------------------

    private fun renderLive(
        rowsAdapter: ArrayObjectAdapter,
        upcoming: com.giantbomb.tv.model.UpcomingResponse?
    ) {
        if (upcoming == null) return
        val hasContent = upcoming.liveNow != null || upcoming.upcoming.isNotEmpty()
        if (!hasContent) return
        val headerTitle = if (upcoming.liveNow != null) "🔴 Upcoming & Live" else "Upcoming"
        val adapter = ArrayObjectAdapter(UpcomingCardPresenter())
        if (upcoming.liveNow != null) adapter.add(upcoming.liveNow)
        upcoming.upcoming.forEach { adapter.add(it) }
        upcomingRowIndex = rowsAdapter.size()
        val hid = headerIdCounter++
        headerContexts[hid] = HeaderContext.Section(PrefsManager.SECTION_LIVE)
        rowsAdapter.add(ListRow(HeaderItem(hid, headerTitle), adapter))
        upcomingRowAdapter = adapter
        upcomingHasLive = upcoming.liveNow != null
    }

    private fun renderContinue(
        rowsAdapter: ArrayObjectAdapter,
        recentVideos: List<Video>?,
        progressMap: Map<Int, ProgressEntry>,
        key: String
    ) {
        if (key.isEmpty() || progressMap.isEmpty() || recentVideos.isNullOrEmpty()) return
        val inProgressIds = progressMap.values
            .filter { it.percentComplete in 1..94 }
            .sortedByDescending { it.currentTime }
            .take(20)
            .map { it.videoId }
            .toSet()
        val continueVideos = recentVideos.filter { it.id in inProgressIds }
        if (continueVideos.isEmpty()) return
        val adapter = ArrayObjectAdapter(CardPresenter())
        continueVideos.forEach { adapter.add(it) }
        val hid = headerIdCounter++
        headerContexts[hid] = HeaderContext.Section(PrefsManager.SECTION_CONTINUE)
        rowsAdapter.add(ListRow(HeaderItem(hid, getString(R.string.continue_watching)), adapter))
    }

    private fun renderWatchlist(
        rowsAdapter: ArrayObjectAdapter,
        watchlist: List<Video>?,
        key: String
    ) {
        // Always render the row when we could reach the API (i.e. there's a key)
        // so users know the section exists. null = couldn't fetch — hide;
        // empty = fetched-but-empty — show a hint card.
        if (watchlist == null || key.isEmpty()) return
        val hid = headerIdCounter++
        headerContexts[hid] = HeaderContext.Section(PrefsManager.SECTION_WATCHLIST)
        if (watchlist.isNotEmpty()) {
            val adapter = ArrayObjectAdapter(CardPresenter())
            watchlist.forEach { adapter.add(it) }
            rowsAdapter.add(ListRow(HeaderItem(hid, "My Watchlist"), adapter))
        } else {
            val hintAdapter = ArrayObjectAdapter(SettingsCardPresenter())
            hintAdapter.add(SettingsItem(
                -1,
                "Your watchlist is empty",
                "Open a video and tap Add to Watchlist",
                R.drawable.ic_watched
            ))
            rowsAdapter.add(ListRow(HeaderItem(hid, "My Watchlist"), hintAdapter))
        }
    }

    private fun renderRecent(
        rowsAdapter: ArrayObjectAdapter,
        recentVideos: List<Video>?
    ) {
        if (recentVideos.isNullOrEmpty()) return
        val adapter = ArrayObjectAdapter(CardPresenter())
        recentVideos.forEach { adapter.add(it) }
        val hid = headerIdCounter++
        headerContexts[hid] = HeaderContext.Section(PrefsManager.SECTION_RECENT)
        rowsAdapter.add(ListRow(HeaderItem(hid, getString(R.string.recent)), adapter))
    }

    private fun renderPinnedShows(
        rowsAdapter: ArrayObjectAdapter,
        shows: List<Show>?,
        pinnedIds: List<Int>
    ) {
        if (shows == null || pinnedIds.isEmpty()) return
        val byId = shows.associateBy { it.id }
        val pinnedShows = pinnedIds.mapNotNull { byId[it] }
        for (s in pinnedShows) {
            val listRowAdapter = ArrayObjectAdapter(CardPresenter())
            val rowTitle = "★ ${s.title}"
            val hid = headerIdCounter++
            headerContexts[hid] = HeaderContext.Show(s, pinned = true)
            rowsAdapter.add(ListRow(HeaderItem(hid, rowTitle), listRowAdapter))
            rowPaginationMap[rowTitle] = RowPagination(
                showTitle = s.title,
                showId = s.id,
                adapter = listRowAdapter,
                offset = 0,
                hasMore = true
            )
        }
    }

    private fun renderActiveShows(
        rowsAdapter: ArrayObjectAdapter,
        shows: List<Show>?,
        pinnedIds: List<Int>
    ) {
        if (shows == null) return
        val pinnedSet = pinnedIds.toSet()
        val activeShows = shows.filter { it.active }
        // Pinned shows appear FIRST in the grid (in pin order) with their
        // ★ prefix from ShowCardPresenter, so users can long-press the
        // same card to unpin even after pinning. Non-pinned active shows follow.
        val byId = activeShows.associateBy { it.id }
        val pinnedActive = pinnedIds.mapNotNull { byId[it] }
        val activeNonPinned = activeShows.filter { it.id !in pinnedSet }
        val gridShows = pinnedActive + activeNonPinned
        if (gridShows.isEmpty()) return
        // Browse Shows grid — the discovery surface for pin/unpin via
        // D-pad-centre long-press.
        val showsAdapter = ArrayObjectAdapter(
            ShowCardPresenter(onLongClick = { togglePin(it) })
        )
        gridShows.forEach { showsAdapter.add(it) }
        val browseHid = headerIdCounter++
        headerContexts[browseHid] = HeaderContext.Section(PrefsManager.SECTION_ACTIVE_SHOWS)
        rowsAdapter.add(ListRow(HeaderItem(browseHid, "Browse Shows"), showsAdapter))
        // Per-show rows — videos lazy-load on focus. Outlined ☆ prefix
        // mirrors mobile's pinnable affordance: a quick visual signal that
        // this show can be pinned to the top via the header's long-press menu.
        for (s in activeNonPinned) {
            val listRowAdapter = ArrayObjectAdapter(CardPresenter())
            val rowTitle = "☆ ${s.title}"
            val showHid = headerIdCounter++
            headerContexts[showHid] = HeaderContext.Show(s, pinned = false)
            rowsAdapter.add(ListRow(HeaderItem(showHid, rowTitle), listRowAdapter))
            rowPaginationMap[rowTitle] = RowPagination(
                showTitle = s.title,
                showId = s.id,
                adapter = listRowAdapter,
                offset = 0,
                hasMore = true
            )
        }
    }

    private fun renderPremium(
        rowsAdapter: ArrayObjectAdapter,
        recentVideos: List<Video>?
    ) {
        if (recentVideos == null) return
        val premiumVideos = recentVideos.filter { it.premium }
        if (premiumVideos.size < 2) return
        val adapter = ArrayObjectAdapter(CardPresenter())
        premiumVideos.forEach { adapter.add(it) }
        val hid = headerIdCounter++
        headerContexts[hid] = HeaderContext.Section(PrefsManager.SECTION_PREMIUM)
        rowsAdapter.add(ListRow(HeaderItem(hid, "Premium"), adapter))
    }

    private fun renderLegacyShows(
        rowsAdapter: ArrayObjectAdapter,
        shows: List<Show>?,
        pinnedIds: List<Int>
    ) {
        if (shows == null) return
        val pinnedSet = pinnedIds.toSet()
        val legacyShows = shows.filter { !it.active }
        // Same treatment as Browse Shows: pinned legacy shows stay visible
        // (with ★ prefix) so long-press can unpin them from the same card.
        val byId = legacyShows.associateBy { it.id }
        val pinnedLegacy = pinnedIds.mapNotNull { byId[it] }
        val legacyNonPinned = legacyShows.filter { it.id !in pinnedSet }
        val gridShows = pinnedLegacy + legacyNonPinned
        if (gridShows.isEmpty()) return
        val adapter = ArrayObjectAdapter(ShowCardPresenter(onLongClick = { togglePin(it) }))
        gridShows.forEach { adapter.add(it) }
        val hid = headerIdCounter++
        headerContexts[hid] = HeaderContext.Section(PrefsManager.SECTION_LEGACY)
        rowsAdapter.add(ListRow(HeaderItem(hid, "Legacy Shows"), adapter))
    }

    private fun renderSettings(rowsAdapter: ArrayObjectAdapter) {
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
            SETTINGS_TWITCH_CHAT,
            "Twitch Chat",
            if (prefs.showTwitchChat) "Shown on live streams" else "Hidden on live streams",
            R.drawable.ic_settings_cog
        ))
        utilAdapter.add(SettingsItem(
            SETTINGS_PRIVACY,
            "Privacy Policy",
            "View privacy policy",
            R.drawable.ic_settings_about
        ))
        val hid = headerIdCounter++
        headerContexts[hid] = HeaderContext.Section(PrefsManager.SECTION_SETTINGS)
        rowsAdapter.add(ListRow(
            HeaderItem(hid, getString(R.string.settings)),
            utilAdapter
        ))
    }

    /**
     * Pin or unpin a show from the TV side. Triggered by long-press
     * (D-pad-centre held) on a show card in either the Browse Shows
     * grid or the Legacy Shows grid. Reloads the rows so the show
     * jumps in/out of its dedicated Pinned section.
     */
    private fun togglePin(show: Show) {
        val nowPinned = prefs.togglePinnedShow(show.id)
        if (isAdded) {
            val msg = if (nowPinned) "Pinned: ${show.title}" else "Unpinned: ${show.title}"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }
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
        upcomingRefreshRunnable?.let { handler.removeCallbacks(it) }
        cancel()
    }
}
