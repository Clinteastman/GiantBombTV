package com.giantbomb.tv.mobile

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.giantbomb.tv.R
import com.giantbomb.tv.ShowActivity
import com.giantbomb.tv.data.GiantBombApi
import com.giantbomb.tv.data.PrefsManager
import com.giantbomb.tv.model.Show
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Bottom-nav tab that renders a grid of shows. Used by:
 *   - the Shows tab: all active shows
 *   - the Podcasts tab: shows whose title matches PODCAST_KEYWORDS
 *
 * Substring matching on title is a deliberate v1 heuristic; the Giant Bomb
 * API has no "is podcast" flag. If a podcast show ever stops matching, the
 * right fix is probably a curated allow-list rather than smarter matching.
 */
class MobileShowGridFragment : Fragment(), CoroutineScope by MainScope() {

    enum class Mode { SHOWS, PODCASTS }

    private lateinit var prefs: PrefsManager
    private lateinit var recycler: RecyclerView
    private lateinit var titleView: TextView
    private lateinit var emptyView: TextView
    private lateinit var loadingView: ProgressBar
    private lateinit var adapter: ShowGridAdapter
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
        ViewCompat.setOnApplyWindowInsetsListener(titleView) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            v.setPadding(v.paddingLeft, top + v.paddingTop, v.paddingRight, v.paddingBottom)
            insets
        }

        adapter = ShowGridAdapter { show ->
            val intent = Intent(requireContext(), ShowActivity::class.java).apply {
                putExtra(ShowActivity.EXTRA_SHOW, show)
            }
            startActivity(intent)
        }
        recycler.layoutManager = GridLayoutManager(requireContext(), GRID_COLUMNS)
        recycler.adapter = adapter

        loadShows()
    }

    private fun loadShows() {
        val key = prefs.apiKey
        if (key.isNullOrEmpty()) {
            showEmpty("Sign in with your Giant Bomb API key on the Home tab to load shows.")
            return
        }
        val api = GiantBombApi(key)
        loadingView.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
        launch {
            val result = api.getShows()
            if (!isAdded) return@launch
            loadingView.visibility = View.GONE
            result.onSuccess { shows ->
                val filtered = filterForMode(shows)
                if (filtered.isEmpty()) {
                    showEmpty(
                        when (mode) {
                            Mode.SHOWS -> "No active shows."
                            Mode.PODCASTS -> "No podcast-style shows matched."
                        }
                    )
                } else {
                    emptyView.visibility = View.GONE
                    adapter.submit(filtered)
                }
            }
            result.onFailure { e ->
                showEmpty(GiantBombApi.friendlyErrorMessage(e))
            }
        }
    }

    private fun filterForMode(all: List<Show>): List<Show> = when (mode) {
        Mode.SHOWS -> all.filter { it.active }
        Mode.PODCASTS -> all.filter { show ->
            val t = show.title.lowercase()
            PODCAST_KEYWORDS.any { kw -> t.contains(kw) }
        }
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
        private const val GRID_COLUMNS = 3
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
