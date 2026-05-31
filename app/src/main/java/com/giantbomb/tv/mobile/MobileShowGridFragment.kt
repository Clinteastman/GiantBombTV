package com.giantbomb.tv.mobile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.giantbomb.tv.PlaybackActivity
import com.giantbomb.tv.R
import com.giantbomb.tv.ShowActivity
import com.giantbomb.tv.data.GiantBombApi
import com.giantbomb.tv.data.PrefsManager
import com.giantbomb.tv.model.Show
import com.giantbomb.tv.model.Video
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Bottom-nav tab that renders a grid of shows. Used by:
 *   - the Shows tab: all active shows, pinned ones sorted to the front.
 *   - the Podcasts tab: a "newest podcast" hero + the next 3 newest episodes,
 *     followed by the grid of podcast-style shows (title matches a keyword).
 *
 * Substring matching on title is a deliberate v1 heuristic; the Giant Bomb
 * API has no "is podcast" flag. If a podcast show ever stops matching, the
 * right fix is probably a curated allow-list rather than smarter matching.
 *
 * Long-pressing any show card toggles its pinned state, which also drives the
 * Home tab's "Pinned Shows" section.
 */
class MobileShowGridFragment : Fragment(), CoroutineScope by MainScope() {

    enum class Mode { SHOWS, PODCASTS }

    private lateinit var prefs: PrefsManager
    private lateinit var recycler: RecyclerView
    private lateinit var titleView: TextView
    private lateinit var emptyView: TextView
    private lateinit var loadingView: ProgressBar
    private lateinit var adapter: MobileGridAdapter
    private var mode: Mode = Mode.SHOWS

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        mode = arguments?.getString(ARG_MODE)
            ?.let { runCatching { Mode.valueOf(it) }.getOrNull() }
            ?: Mode.SHOWS
        return inflater.inflate(R.layout.fragment_show_grid, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefs = PrefsManager(requireContext())

        recycler = view.findViewById(R.id.grid_recycler)
        titleView = view.findViewById(R.id.grid_title)
        emptyView = view.findViewById(R.id.grid_empty)
        loadingView = view.findViewById(R.id.grid_loading)

        titleView.text = when (mode) {
            Mode.SHOWS -> "Shows"
            Mode.PODCASTS -> "Podcasts"
        }

        // Apply status-bar inset to the title so it sits below the system bar
        // (the rest of activity_main.xml uses enableEdgeToEdge for mobile).
        // Capture the layout's base top padding once — otherwise each inset
        // dispatch reads the already-mutated paddingTop and the gap accumulates.
        val baseTitlePadTop = titleView.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(titleView) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            v.setPadding(v.paddingLeft, baseTitlePadTop + top, v.paddingRight, v.paddingBottom)
            insets
        }

        adapter = MobileGridAdapter(
            columns = GRID_COLUMNS,
            onPlayVideo = { video -> startActivity(buildPlaybackIntent(video)) },
            onOpenShow = { show ->
                startActivity(Intent(requireContext(), ShowActivity::class.java).apply {
                    putExtra(ShowActivity.EXTRA_SHOW, show)
                })
            },
            onPinShow = { show -> togglePin(show) }
        )
        val lm = GridLayoutManager(requireContext(), GRID_COLUMNS)
        lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int = adapter.spanSizeAt(position)
        }
        recycler.layoutManager = lm
        recycler.adapter = adapter

        loadContent()
    }

    private fun loadContent() {
        val key = prefs.apiKey
        if (key.isNullOrEmpty()) {
            showEmpty("Sign in with your Giant Bomb API key on the Home tab to load shows.")
            return
        }
        val api = GiantBombApi(key)
        loadingView.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
        launch {
            val showsResult = api.getShows()
            if (!isAdded) return@launch
            val shows = showsResult.getOrNull()
            if (shows == null) {
                loadingView.visibility = View.GONE
                showEmpty(
                    GiantBombApi.friendlyErrorMessage(
                        showsResult.exceptionOrNull() ?: Exception("Failed to load shows.")
                    )
                )
                return@launch
            }

            val rows = when (mode) {
                Mode.SHOWS -> buildShowsRows(shows)
                Mode.PODCASTS -> buildPodcastRows(api, shows)
            }
            if (!isAdded) return@launch
            loadingView.visibility = View.GONE
            if (rows.isEmpty()) {
                showEmpty(
                    when (mode) {
                        Mode.SHOWS -> "No active shows."
                        Mode.PODCASTS -> "No podcasts matched."
                    }
                )
            } else {
                emptyView.visibility = View.GONE
                adapter.submit(rows)
            }
        }
    }

    private fun buildShowsRows(all: List<Show>): List<MobileGridAdapter.Row> {
        val pinnedSet = prefs.getPinnedShowIds().toSet()
        return pinnedFirst(all.filter { it.active })
            .map { MobileGridAdapter.Row.ShowCard(it, pinned = it.id in pinnedSet) }
    }

    private suspend fun buildPodcastRows(
        api: GiantBombApi,
        all: List<Show>
    ): List<MobileGridAdapter.Row> {
        val podcastShows = all.filter { show ->
            val t = show.title.lowercase()
            PODCAST_KEYWORDS.any { kw -> t.contains(kw) }
        }
        if (podcastShows.isEmpty()) return emptyList()

        // Newest podcast episodes overall: pull the latest videos and keep the
        // ones belonging to a podcast show. Best-effort — if it fails we still
        // render the shows grid below.
        val podcastIds = podcastShows.map { it.id }.toSet()
        val episodes = api.getVideos(limit = EPISODE_SCAN_LIMIT).getOrNull()
            ?.filter { it.showId in podcastIds }
            ?.take(HERO_PLUS_NEXT)
            ?: emptyList()

        val pinnedSet = prefs.getPinnedShowIds().toSet()
        val rows = mutableListOf<MobileGridAdapter.Row>()

        if (episodes.isNotEmpty()) {
            rows += MobileGridAdapter.Row.Hero(episodes.first())
            val next = episodes.drop(1)
            if (next.isNotEmpty()) {
                rows += MobileGridAdapter.Row.Header("Up Next")
                next.forEach { rows += MobileGridAdapter.Row.Episode(it) }
            }
        }
        rows += MobileGridAdapter.Row.Header("Shows")
        pinnedFirst(podcastShows).forEach {
            rows += MobileGridAdapter.Row.ShowCard(it, pinned = it.id in pinnedSet)
        }
        return rows
    }

    /** Pinned shows first (in pin order), then everything else in API order. */
    private fun pinnedFirst(shows: List<Show>): List<Show> {
        val pinnedIds = prefs.getPinnedShowIds()
        if (pinnedIds.isEmpty()) return shows
        val pinnedSet = pinnedIds.toSet()
        val byId = shows.associateBy { it.id }
        val pinned = pinnedIds.mapNotNull { byId[it] }
        val rest = shows.filter { it.id !in pinnedSet }
        return pinned + rest
    }

    private fun buildPlaybackIntent(video: Video): Intent =
        Intent(requireContext(), PlaybackActivity::class.java).apply {
            putExtra(PlaybackActivity.EXTRA_VIDEO, video)
        }

    private fun togglePin(show: Show) {
        val nowPinned = prefs.togglePinnedShow(show.id)
        if (isAdded) {
            val msg = if (nowPinned) "Pinned: ${show.title}" else "Unpinned: ${show.title}"
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }
        // Re-render so the show jumps to/from the front of the grid.
        loadContent()
    }

    private fun showEmpty(message: String) {
        emptyView.text = message
        emptyView.visibility = View.VISIBLE
        adapter.submit(emptyList())
    }

    override fun onDestroy() {
        super.onDestroy()
        cancel()
    }

    companion object {
        private const val ARG_MODE = "mode"
        // 6-unit base grid so show cards (span 3 → 2 across) and podcast
        // episodes (span 2 → 3 across) can share one GridLayoutManager.
        private const val GRID_COLUMNS = 6
        private const val HERO_PLUS_NEXT = 4
        private const val EPISODE_SCAN_LIMIT = 60
        private val PODCAST_KEYWORDS = listOf(
            "bombcast",
            "podcast",
            "cast",
            "hotspot"
        )

        fun newInstance(mode: Mode): MobileShowGridFragment {
            return MobileShowGridFragment().apply {
                arguments = Bundle().apply { putString(ARG_MODE, mode.name) }
            }
        }
    }
}
