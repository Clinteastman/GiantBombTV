package com.giantbomb.tv

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.leanback.app.VerticalGridSupportFragment
import androidx.leanback.widget.*
import com.giantbomb.tv.data.GiantBombApi
import com.giantbomb.tv.data.PrefsManager
import com.giantbomb.tv.model.ProgressEntry
import com.giantbomb.tv.model.Show
import com.giantbomb.tv.model.Video
import com.giantbomb.tv.ui.CardPresenter
import com.giantbomb.tv.util.DeviceUtil
import kotlinx.coroutines.*

/**
 * A dedicated show page. Episodes are laid out as a vertical grid (rather
 * than a single horizontally-scrolling row) so the whole back catalogue can
 * be scanned by scrolling down, which makes more sense for a show-specific
 * page. The column count is derived from the screen width so the fixed-size
 * cards always fit without clipping.
 */
class ShowBrowseFragment : VerticalGridSupportFragment(), CoroutineScope by MainScope() {

    private lateinit var api: GiantBombApi
    private var show: Show? = null
    private var isLoading = false
    private var currentOffset = 0
    private var hasMore = true
    private var progressMap = emptyMap<Int, ProgressEntry>()
    private var gridAdapter: ArrayObjectAdapter? = null

    companion object {
        private const val PAGE_SIZE = 40
        private const val LOAD_MORE_THRESHOLD = 5
        private const val MIN_COLUMNS = 2
        private const val MAX_COLUMNS = 5
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = PrefsManager(requireContext())
        api = GiantBombApi(prefs.apiKey ?: "")

        @Suppress("DEPRECATION")
        show = requireActivity().intent.getSerializableExtra(ShowActivity.EXTRA_SHOW) as? Show

        show?.let {
            title = if (it.deck.isNotEmpty()) "${it.title} — ${it.deck}" else it.title
        }

        // Grid presenter. Card views provide their own focus scaling, so the
        // presenter's zoom is disabled to avoid a double zoom.
        gridPresenter = VerticalGridPresenter(FocusHighlight.ZOOM_FACTOR_NONE, false).apply {
            numberOfColumns = computeColumns()
            shadowEnabled = false
        }

        gridAdapter = ArrayObjectAdapter(CardPresenter())
        adapter = gridAdapter

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            if (item is Video) {
                val intent = Intent(requireContext(), DetailActivity::class.java).apply {
                    putExtra(DetailActivity.EXTRA_VIDEO, item)
                }
                startActivity(intent)
            }
        }

        // Infinite scroll: load more when focus nears the end of the grid.
        setOnItemViewSelectedListener { _, item, _, _ ->
            if (item is Video && !isLoading && hasMore) {
                val a = gridAdapter ?: return@setOnItemViewSelectedListener
                val position = a.indexOf(item)
                if (position >= a.size() - LOAD_MORE_THRESHOLD) {
                    loadMore()
                }
            }
        }

        loadContent()
    }

    /** Number of columns the fixed-width cards fit into at this screen width. */
    private fun computeColumns(): Int {
        val res = resources
        val cardTotal = res.getDimensionPixelSize(R.dimen.card_width) +
            res.getDimensionPixelSize(R.dimen.card_margin) * 2
        // This fragment is shared with the phone UI. On a narrow phone two
        // fixed-width cards can exceed the usable width, so allow a single
        // column there; only TV (always wide) gets the 2-column floor.
        val minColumns = if (DeviceUtil.isTv(requireContext())) MIN_COLUMNS else 1
        if (cardTotal <= 0) return minColumns
        // The leanback grid is a centred wrap_content view with browse padding
        // on each side; subtract it so a near-boundary width doesn't pick one
        // column too many and clip the outer cards.
        val sidePadding = res.getDimensionPixelSize(androidx.leanback.R.dimen.lb_browse_padding_start) * 2
        val usable = res.displayMetrics.widthPixels - sidePadding
        val fit = usable / cardTotal
        return fit.coerceIn(minColumns, MAX_COLUMNS)
    }

    private fun loadContent() {
        if (isLoading) return
        isLoading = true
        val s = show ?: return

        launch {
            // Load progress for watched/progress badges
            val progress = api.getProgress().getOrNull()
            if (progress != null) {
                progressMap = progress.associateBy { it.videoId }
            }

            // Load first page
            loadPage(s)
        }
    }

    private fun loadMore() {
        val s = show ?: return
        isLoading = true
        launch { loadPage(s) }
    }

    private suspend fun loadPage(s: Show) {
        val result = api.getShowVideos(s.id, limit = PAGE_SIZE, offset = currentOffset)

        result.onSuccess { videos ->
            if (videos.size < PAGE_SIZE) hasMore = false
            currentOffset += videos.size

            val a = gridAdapter ?: return@onSuccess
            videos.forEach { video ->
                val entry = progressMap[video.id]
                val v = when {
                    entry != null && entry.percentComplete >= 95 -> video.copy(watched = true)
                    entry != null && entry.percentComplete in 1..94 -> video.copy(progressPercent = entry.percentComplete)
                    else -> video
                }
                a.add(v)
            }
        }

        result.onFailure { e ->
            Toast.makeText(requireContext(),
                GiantBombApi.friendlyErrorMessage(e), Toast.LENGTH_LONG).show()
        }

        isLoading = false
    }

    override fun onDestroy() {
        super.onDestroy()
        cancel()
    }
}
