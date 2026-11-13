package com.giantbomb.tv

import android.app.AlertDialog
import android.content.Intent
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
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
import androidx.lifecycle.lifecycleScope
import androidx.leanback.widget.*
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.giantbomb.tv.data.GiantBombApi
import com.giantbomb.tv.data.GiantBombRepository
import com.giantbomb.tv.data.PrefsManager
import com.giantbomb.tv.data.RowPrefetcher
import com.giantbomb.tv.data.toggleTwitchChatPref
import com.giantbomb.tv.model.ProgressEntry
import com.giantbomb.tv.model.SettingsItem
import com.giantbomb.tv.model.Show
import com.giantbomb.tv.model.Video
import com.giantbomb.tv.data.TwitchExtractor
import com.giantbomb.tv.model.UpcomingStream
import com.giantbomb.tv.ui.CardPresenter
import com.giantbomb.tv.ui.GlassSurface
import com.giantbomb.tv.ui.ImagePreloader
import com.giantbomb.tv.ui.SettingsCardPresenter
import com.giantbomb.tv.ui.ShowCardPresenter
import com.giantbomb.tv.ui.SkeletonCard
import com.giantbomb.tv.ui.SkeletonCardPresenter
import com.giantbomb.tv.ui.UpcomingCardPresenter
import kotlinx.coroutines.*

class BrowseFragment : BrowseSupportFragment() {

    private lateinit var prefs: PrefsManager
    private var isLoading = false
    private var pendingForcedRefresh = false
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
    private var progressByVideoId: Map<Int, ProgressEntry> = emptyMap()

    // Fills every show row's first cards in the background, in row order,
    // and jumps the focused row and the rows just below it to the front.
    private var rowPrefetcher: RowPrefetcher<String>? = null
    private var imagePreloader: ImagePreloader? = null
    // After a 429, row paging waits this long before asking again.
    private var rateLimitedUntilMs = 0L

    companion object {
        const val SETTINGS_REFRESH = 2
        const val SETTINGS_SETUP = 3
        const val SETTINGS_QUALITY = 4
        const val SETTINGS_PRIVACY = 5
        const val SETTINGS_CUSTOMIZE = 6
        const val SETTINGS_TWITCH_CHAT = 7
        const val SETTINGS_DOWNLOADS = 8
        const val SETTINGS_VISUAL_THEME = 9
        // Neon HUD lettering size for the side menu (narrow, one line).
        const val NEON_SIDE_HEADER_SP = 19f
        const val SETTINGS_NEON_PARTICLES = 10
        private const val BACKDROP_DELAY_MS = 300L
        private const val CROSSFADE_DURATION = 600L
        private const val BACKDROP_ALPHA = 0.36f
        private const val INITIAL_VIDEO_LIMIT = 100
        private const val ROW_PAGE_SIZE = 40
        // Focus within this many cards of a row's end fetches the next page.
        // Against a 40-card page that is about five screens of warning on a
        // 1080p TV (three cards per screen), so paging finishes well before
        // the user can reach the end with the D-pad.
        private const val LOAD_MORE_THRESHOLD = 15
        // Every show row gets its first screen of cards at startup: the cards
        // that fit across the screen plus a few for the first D-pad presses
        // right. Focus then fetches the rest of the page via loadMoreForRow.
        private const val FIRST_SCREEN_EXTRA_CARDS = 4
        private const val FIRST_SCREEN_MIN_CARDS = 6
        private const val FIRST_SCREEN_MAX_CARDS = 10
        // Show-row requests at once. The repository caps show requests at 3
        // too; the catalog endpoint is lighter than the per-user ones, but a
        // home screen has 30+ show rows, so keep the burst small.
        private const val ROW_PREFETCH_CONCURRENCY = 3
        // Rows below the focused one that jump the background queue.
        private const val ROWS_AHEAD = 2
        private const val RATE_LIMIT_PAUSE_MS = 10_000L
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val blur = RenderEffect.createBlurEffect(4f, 4f, Shader.TileMode.CLAMP)
            backdropImageView?.setRenderEffect(blur)
            backdropNextView?.setRenderEffect(blur)
        }
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
            // RowHeaderPresenter is bound with the Row, not the HeaderItem
            // directly. The previous cast always produced null, so all custom
            // contrast and focus styling silently returned without running.
            viewHolder.view.tag = when (item) {
                is Row -> item.headerItem
                is HeaderItem -> item
                else -> null
            }
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
            val tv = (holder.view as? android.widget.TextView)
                ?: holder.view.findViewById<android.widget.TextView>(
                    androidx.leanback.R.id.row_header
                )
                ?: return
            val name = header.name
            val selected = holder.selectLevel > 0.5f

            // Leanback's stock header presenter deliberately makes inactive
            // labels very faint. That works over a plain backdrop, but the
            // animated glass and Neon grid compete with it. Keep every section
            // readable, then use weight and glow to preserve a strong focus
            // distinction instead of relying on low opacity.
            holder.view.alpha = 1f
            tv.alpha = 1f
            val neon = GlassSurface.theme == GlassSurface.Theme.NEON
            if (neon) {
                com.giantbomb.tv.ui.VectorFont.applyIfNeon(tv)
                // The side menu is narrow: keep each name on one line rather
                // than letting the wider lettering wrap mid-word.
                tv.maxLines = 1
                tv.ellipsize = android.text.TextUtils.TruncateAt.END
                tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, NEON_SIDE_HEADER_SP)
            }
            tv.setTextColor(
                when {
                    // Geometry Wars HUD green; brighter on the focused header.
                    neon && selected -> 0xFFC6FF9E.toInt()
                    neon -> com.giantbomb.tv.ui.VectorFont.GW_GREEN
                    selected -> android.graphics.Color.WHITE
                    else -> 0xDEFFFFFF.toInt()
                }
            )
            tv.typeface = android.graphics.Typeface.create(
                "sans-serif-medium",
                if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
            )
            if (neon) {
                tv.setShadowLayer(
                    if (selected) 10f else 6f,
                    0f,
                    0f,
                    if (selected) 0xE07CFF3C.toInt() else 0x904CFF1E.toInt()
                )
            } else {
                tv.setShadowLayer(if (selected) 6f else 4f, 0f, 1f, 0xCC000000.toInt())
            }

            if (selected) {
                val hint = "  ⋮ Hold OK"
                val span = android.text.SpannableString("$name$hint")
                val start = name.length
                val end = span.length
                span.setSpan(
                    android.text.style.ForegroundColorSpan(0xFFE3192C.toInt()),
                    start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                span.setSpan(
                    android.text.style.RelativeSizeSpan(if (neon) 0.5f else 0.72f),
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
                // Never offer "Hide row" for Settings: the only entry point to
                // re-enable hidden rows lives inside the Settings row, so hiding
                // it would strand the user with no way back to Customize Browse.
                if (ctx.id != PrefsManager.SECTION_SETTINGS) {
                    items += "Hide row" to { hideSection(ctx.id) }
                }
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

    private fun hideSection(sectionId: String) {
        val hidden = prefs.getHiddenSections().toMutableSet()
        if (hidden.add(sectionId)) {
            prefs.setHiddenSections(hidden)
            val label = PrefsManager.sectionLabel(sectionId)
            Toast.makeText(
                requireContext(),
                "Hidden: $label. Re-enable in Settings > Customize Browse.",
                Toast.LENGTH_LONG
            ).show()
            loadContent()
        }
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
                        SETTINGS_REFRESH -> loadContent(forceRefresh = true)
                        SETTINGS_SETUP -> launchSetup()
                        SETTINGS_QUALITY -> cycleQuality()
                        SETTINGS_PRIVACY -> openPrivacyPolicy()
                        SETTINGS_CUSTOMIZE -> launchCustomize()
                        SETTINGS_TWITCH_CHAT -> toggleTwitchChat()
                        SETTINGS_VISUAL_THEME -> showVisualThemePicker()
                        SETTINGS_NEON_PARTICLES -> toggleNeonParticles()
                        SETTINGS_DOWNLOADS -> startActivity(
                            Intent(requireContext(), DownloadsActivity::class.java)
                        )
                    }
                }
            }
        }

        // Debounced backdrop update on item focus + per-row pagination
        onItemViewSelectedListener = OnItemViewSelectedListener { _, item, _, row ->
            // Make sure this row and the next ones are loaded (or next in line)
            // and their first thumbnails are on their way.
            val header = (row as? ListRow)?.headerItem?.name
            (row as? ListRow)?.let { prefetchAround(it, item) }

            when (item) {
                is Video -> {
                    updateBackdropDebounced(
                        item.thumbnailUrl ?: item.posterUrl,
                        nextBackdropUrl(row as? ListRow, item)
                    )
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

    /** The backdrop request, shared by the real load and the neighbour preload. */
    private fun backdropRequest(imageUrl: String): com.bumptech.glide.RequestBuilder<Drawable> {
        val request = Glide.with(requireContext()).load(imageUrl).override(960, 540)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            request.centerCrop()
        } else {
            request.transform(
                com.bumptech.glide.load.resource.bitmap.CenterCrop(),
                com.giantbomb.tv.ui.BlurTransformation(radius = 2, passes = 2)
            )
        }
    }

    /** The card to the right of [item]: the most likely next focus. */
    private fun nextBackdropUrl(row: ListRow?, item: Video): String? {
        val cards = row?.adapter as? ArrayObjectAdapter ?: return null
        val index = cards.indexOf(item)
        val next = if (index in 0 until cards.size() - 1) cards.get(index + 1) as? Video else null
        return next?.let { it.thumbnailUrl ?: it.posterUrl }
    }

    private fun updateBackdropDebounced(imageUrl: String?, nextUrl: String? = null) {
        backdropRunnable?.let { handler.removeCallbacks(it) }
        if (imageUrl == currentBackdropUrl) return
        backdropRunnable = Runnable {
            if (!isAdded) return@Runnable
            val current = backdropImageView ?: return@Runnable
            val next = backdropNextView ?: return@Runnable
            currentBackdropUrl = imageUrl

            if (imageUrl.isNullOrEmpty()) {
                GlassSurface.updateBackdrop(
                    null,
                    requireActivity().window.decorView.width,
                    requireActivity().window.decorView.height
                )
                current.animate().alpha(0f).setDuration(CROSSFADE_DURATION).start()
                next.animate().alpha(0f).setDuration(CROSSFADE_DURATION).start()
                return@Runnable
            }

            // Load new image into the hidden "next" layer
            val backdropRequest = backdropRequest(imageUrl)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                // The displayed backdrop is pre-blurred on these versions, so
                // give the glass its own unblurred copy: Extreme's clear lens
                // needs the detail that the blur removes.
                if (GlassSurface.usesBackdropRefraction) {
                    Glide.with(requireContext())
                        .asBitmap()
                        .load(imageUrl)
                        .override(960, 540)
                        .centerCrop()
                        .into(object : com.bumptech.glide.request.target.CustomTarget<android.graphics.Bitmap>() {
                            override fun onResourceReady(
                                bitmap: android.graphics.Bitmap,
                                transition: com.bumptech.glide.request.transition.Transition<in android.graphics.Bitmap>?
                            ) {
                                if (!isAdded || currentBackdropUrl != imageUrl) return
                                GlassSurface.updateBackdrop(
                                    bitmap,
                                    requireActivity().window.decorView.width,
                                    requireActivity().window.decorView.height
                                )
                            }
                            override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) = Unit
                        })
                }
            }
            backdropRequest.into(object : com.bumptech.glide.request.target.CustomTarget<android.graphics.drawable.Drawable>() {
                    override fun onResourceReady(resource: android.graphics.drawable.Drawable, transition: com.bumptech.glide.request.transition.Transition<in android.graphics.drawable.Drawable>?) {
                        // Focus may have moved on while this loaded; a late
                        // older image must not replace the newer backdrop.
                        if (!isAdded || currentBackdropUrl != imageUrl) return
                        // On older versions the glass gets the unblurred copy above.
                        (resource as? BitmapDrawable)?.bitmap
                            ?.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.S }
                            ?.let { bitmap ->
                            GlassSurface.updateBackdrop(
                                bitmap,
                                requireActivity().window.decorView.width,
                                requireActivity().window.decorView.height
                            )
                        }
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
            // The user has settled on this card, so decode the next card's
            // backdrop now; moving right then crossfades without a wait.
            // Only here, after the debounce, so a fast scroll costs nothing.
            if (nextUrl != null && nextUrl != imageUrl && preloader().allowDecode) {
                backdropRequest(nextUrl).priority(com.bumptech.glide.Priority.LOW).preload()
            }
        }
        handler.postDelayed(backdropRunnable!!, BACKDROP_DELAY_MS)
    }

    fun loadContent(forceRefresh: Boolean = false) {
        if (isLoading) {
            // An explicit refresh mustn't be lost behind a normal load:
            // run it once the current one finishes.
            if (forceRefresh) pendingForcedRefresh = true
            return
        }
        isLoading = true

        val key = prefs.apiKey ?: ""
        val repository = GiantBombRepository.get(key)
        if (forceRefresh) repository.invalidateShowVideos()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
            loadingSpinner?.visibility = View.VISIBLE
            val rowPresenter = com.giantbomb.tv.ui.TvRowPresenter.create()
            val rowsAdapter = ArrayObjectAdapter(rowPresenter)
            headerIdCounter = 0L
            // Reset row tracking — rowsAdapter is brand new and the upcoming row, if any,
            // will be added below. We'll capture references then for partial refresh.
            rowsAdapterRef = rowsAdapter
            upcomingRowAdapter = null
            upcomingRowIndex = -1
            upcomingHasLive = false

            val upcomingDeferred = async { repository.getUpcoming(forceRefresh) }
            val watchlistDeferred = async { repository.getWatchlist(forceRefresh) }
            val progressDeferred = async { repository.getProgress(forceRefresh) }
            val recentDeferred = async { repository.getRecentVideos(INITIAL_VIDEO_LIMIT, forceRefresh) }
            val showsDeferred = async { repository.getShows(forceRefresh) }

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

            val recentVideos = recentVideosRaw?.map { it.withProgress() }
            val watchlist = if (key.isNotEmpty()) {
                watchlistDeferred.await().getOrNull()?.map { it.withProgress() }
            } else null

            val pinnedIds = prefs.getPinnedShowIds()
            val hidden = prefs.getHiddenSections()

            // Show pagination map drives lazy-loading of per-show video rows on
            // focus. Reset before each render so the new ListRows own their
            // adapters.
            rowPrefetcher?.cancel()
            rowPrefetcher = null
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

            // Fill the first screen of every show row in the background, in
            // row order, so rows are ready before the user scrolls to them.
            // Each row shows skeleton cards until then.
            startRowPrefetch(repository) { it.withProgress() }
            } finally {
                loadingSpinner?.visibility = View.GONE
                isLoading = false
                if (pendingForcedRefresh) {
                    pendingForcedRefresh = false
                    if (isAdded && view != null) loadContent(forceRefresh = true)
                }
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
        val repository = GiantBombRepository.get(key)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = repository.getUpcoming(force = true).getOrNull()
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
        val msg = getString(
            if (nowShown) R.string.toast_twitch_chat_shown
            else R.string.toast_twitch_chat_hidden
        )
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        loadContent()
    }

    private fun showVisualThemePicker() {
        com.giantbomb.tv.ui.ThemePickerDialog.show(requireContext(), prefs.visualTheme) { value ->
            prefs.visualTheme = value
            GlassSurface.configure(value)
            requireActivity().recreate()
        }
    }

    private fun toggleNeonParticles() {
        prefs.neonParticlesEnabled = !prefs.neonParticlesEnabled
        if (prefs.visualTheme == PrefsManager.THEME_NEON) {
            requireActivity().recreate()
        } else {
            loadContent()
        }
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
        if (System.currentTimeMillis() < rateLimitedUntilMs) return
        pagination.isLoading = true
        val key = prefs.apiKey ?: ""
        val repository = GiantBombRepository.get(key)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = repository.getShowVideos(pagination.showId, limit = ROW_PAGE_SIZE, offset = pagination.offset)
                result.onSuccess { videos ->
                    if (videos.size < ROW_PAGE_SIZE) pagination.hasMore = false
                    pagination.offset += videos.size

                    // Repository progress is cached briefly, so this is usually
                    // free; it keeps badges current after returning from playback.
                    repository.getProgress().getOrNull()?.let { entries ->
                        progressByVideoId = entries.associateBy { it.videoId }
                    }

                    videos.forEach { video ->
                        val entry = progressByVideoId[video.id]
                        val v = when {
                            entry != null && entry.percentComplete >= 95 -> video.copy(watched = true)
                            entry != null && entry.percentComplete in 1..94 -> video.copy(progressPercent = entry.percentComplete)
                            else -> video
                        }
                        pagination.adapter.add(v)
                    }
                }
                result.onFailure { e ->
                    // A 429 is temporary: keep the row pageable and try
                    // again after the pause. Anything else ends the row.
                    if (RowPrefetcher.isRateLimited(e)) {
                        rateLimitedUntilMs = System.currentTimeMillis() + RATE_LIMIT_PAUSE_MS
                    } else {
                        pagination.hasMore = false
                    }
                }
            } finally {
                pagination.isLoading = false
            }
        }
    }

    private fun preloader(): ImagePreloader =
        imagePreloader ?: ImagePreloader(requireContext(), Glide.with(this)).also { imagePreloader = it }

    /** Cards that fit across the screen at once (about three on a 1080p TV). */
    private fun visibleCardCount(): Int {
        val res = resources
        val card = res.getDimensionPixelSize(R.dimen.card_width) +
            res.getDimensionPixelSize(R.dimen.card_margin) * 2
        if (card <= 0) return 3
        return (res.displayMetrics.widthPixels + card - 1) / card
    }

    private fun firstScreenCardCount(): Int =
        (visibleCardCount() + FIRST_SCREEN_EXTRA_CARDS)
            .coerceIn(FIRST_SCREEN_MIN_CARDS, FIRST_SCREEN_MAX_CARDS)

    /** A show row's adapter, holding skeleton cards until its videos arrive. */
    private fun newShowRowAdapter(): ArrayObjectAdapter {
        val selector = ClassPresenterSelector()
            // A show row is headed by the show name, so cards skip repeating it.
            .addClassPresenter(Video::class.java, CardPresenter(showShowName = false))
            .addClassPresenter(SkeletonCard::class.java, SkeletonCardPresenter())
        return ArrayObjectAdapter(selector).apply {
            repeat(visibleCardCount() + 1) { add(SkeletonCard(it)) }
        }
    }

    private fun skeletonCount(adapter: ArrayObjectAdapter): Int {
        var n = 0
        while (n < adapter.size() && adapter.get(n) is SkeletonCard) n++
        return n
    }

    /**
     * Swaps a row's skeleton cards for its first videos in place, so a card
     * that already has focus keeps it.
     */
    private fun showFirstVideos(adapter: ArrayObjectAdapter, videos: List<Video>) {
        val skeletons = skeletonCount(adapter)
        val swapped = minOf(skeletons, videos.size)
        for (i in 0 until swapped) adapter.replace(i, videos[i])
        if (skeletons > swapped) adapter.removeItems(swapped, skeletons - swapped)
        if (videos.size > swapped) adapter.addAll(swapped, videos.subList(swapped, videos.size))
    }

    private fun startRowPrefetch(repository: GiantBombRepository, decorate: (Video) -> Video) {
        rowPrefetcher?.cancel()
        val firstScreen = firstScreenCardCount()
        val prefetcher = RowPrefetcher<String>(
            scope = viewLifecycleOwner.lifecycleScope,
            maxConcurrent = ROW_PREFETCH_CONCURRENCY,
            // A row with no pagination, or already cached, costs no request.
            isCached = { title ->
                rowPaginationMap[title]?.let { repository.hasShowVideos(it.showId, firstScreen) } ?: true
            }
        ) { title ->
            val pagination = rowPaginationMap[title]
                ?: return@RowPrefetcher RowPrefetcher.Outcome.DONE
            fetchFirstScreen(repository, title, pagination, firstScreen, decorate)
        }
        rowPrefetcher = prefetcher
        // rowPaginationMap keeps insertion order, which is the row order.
        prefetcher.enqueue(rowPaginationMap.keys.toList())
    }

    private suspend fun fetchFirstScreen(
        repository: GiantBombRepository,
        title: String,
        pagination: RowPagination,
        count: Int,
        decorate: (Video) -> Video
    ): RowPrefetcher.Outcome {
        // Already filled, or a page load is running: nothing to do.
        if (pagination.offset > 0 || !pagination.hasMore || pagination.isLoading) {
            return RowPrefetcher.Outcome.DONE
        }
        pagination.isLoading = true
        try {
            val result = repository.getShowVideos(pagination.showId, limit = count, offset = 0)
            if (!isAdded) return RowPrefetcher.Outcome.DONE
            val videos = result.getOrElse { e ->
                if (RowPrefetcher.isRateLimited(e)) {
                    rateLimitedUntilMs = System.currentTimeMillis() + RATE_LIMIT_PAUSE_MS
                    return RowPrefetcher.Outcome.RATE_LIMITED
                }
                // Same as before this change: a row that can't load stays empty.
                val skeletons = skeletonCount(pagination.adapter)
                if (skeletons > 0) pagination.adapter.removeItems(0, skeletons)
                return RowPrefetcher.Outcome.FAILED
            }.map(decorate)
            showFirstVideos(pagination.adapter, videos)
            pagination.offset = videos.size
            if (videos.size < count) pagination.hasMore = false
            android.util.Log.d("RowPrefetch", "filled '$title' with ${videos.size}")
            // Download (not decode) the thumbnails that show first, so a fast
            // scroll down finds them on disk instead of on the network.
            preloader().warm(videos.take(visibleCardCount()).map { it.thumbnailUrl ?: it.posterUrl })
            return RowPrefetcher.Outcome.DONE
        } finally {
            pagination.isLoading = false
        }
    }

    /**
     * Called on every focus change. Moves the focused row (if still empty)
     * and the next [ROWS_AHEAD] show rows to the front of the fill queue, and
     * downloads the thumbnails about to come on screen: the next cards to the
     * right of [item], and the first cards of the row below.
     */
    private fun prefetchAround(row: ListRow, item: Any?) {
        val rows = rowsAdapterRef ?: return
        val index = rows.indexOf(row)
        if (index < 0) return
        val keys = (0 until rows.size()).map { i ->
            (rows.get(i) as? ListRow)?.headerItem?.name?.takeIf { it in rowPaginationMap }
        }
        rowPrefetcher?.prioritize(listOfNotNull(keys[index]) + RowPrefetcher.ahead(keys, index, ROWS_AHEAD))
        // Download (not decode) the thumbnails about to come on screen.
        // Decoding them at card size here as well cost frame time while
        // scrolling (harness scroll test, 6 Nov 2026), so leave the decode
        // to the cards themselves; the disk copy already saves the network.
        val visible = visibleCardCount()
        val urls = mutableListOf<String?>()
        fun add(card: Any?) {
            (card as? Video)?.let { urls.add(it.thumbnailUrl ?: it.posterUrl) }
        }
        (row.adapter as? ArrayObjectAdapter)?.let { cards ->
            val from = if (item != null) cards.indexOf(item) + 1 else 0
            for (i in from until minOf(cards.size(), from + visible)) add(cards.get(i))
        }
        val below = if (index + 1 < rows.size()) (rows.get(index + 1) as? ListRow)?.adapter else null
        if (below != null) {
            for (i in 0 until minOf(below.size(), visible)) add(below.get(i))
        }
        preloader().warm(urls)
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
            val listRowAdapter = newShowRowAdapter()
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
            val listRowAdapter = newShowRowAdapter()
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
            SETTINGS_VISUAL_THEME,
            "Visual Theme",
            PrefsManager.visualThemeLabel(prefs.visualTheme),
            R.drawable.ic_settings_theme
        ))
        // Only meaningful for the Neon theme, so only shown there.
        if (prefs.visualTheme == PrefsManager.THEME_NEON) {
            utilAdapter.add(SettingsItem(
                SETTINGS_NEON_PARTICLES,
                "Neon Grid Motion",
                if (prefs.neonParticlesEnabled) "On - grid reacts to movement" else "Off - static grid",
                R.drawable.ic_settings_motion
            ))
        }
        utilAdapter.add(SettingsItem(
            SETTINGS_DOWNLOADS,
            "Downloads",
            "Watch saved videos offline",
            R.drawable.ic_settings_downloads
        ))
        utilAdapter.add(SettingsItem(
            SETTINGS_CUSTOMIZE,
            "Customize Browse",
            "Reorder, hide, or restore rows",
            R.drawable.ic_settings_feed
        ))
        utilAdapter.add(SettingsItem(
            SETTINGS_SETUP,
            getString(R.string.settings_setup),
            getString(R.string.settings_setup_desc),
            R.drawable.ic_settings_cog
        ))
        utilAdapter.add(SettingsItem(
            SETTINGS_TWITCH_CHAT,
            getString(R.string.settings_twitch_chat),
            getString(
                if (prefs.showTwitchChat) R.string.settings_twitch_chat_shown
                else R.string.settings_twitch_chat_hidden
            ),
            R.drawable.ic_settings_chat
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
        viewLifecycleOwner.lifecycleScope.launch {
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

    private fun launchCustomize() {
        val intent = Intent(requireContext(), CustomizeBrowseActivity::class.java)
        startActivity(intent)
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
    }
}
