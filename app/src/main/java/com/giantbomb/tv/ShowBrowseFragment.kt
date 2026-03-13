package com.giantbomb.tv

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.*
import com.giantbomb.tv.data.GiantBombApi
import com.giantbomb.tv.data.PrefsManager
import com.giantbomb.tv.model.ProgressEntry
import com.giantbomb.tv.model.Show
import com.giantbomb.tv.model.Video
import com.giantbomb.tv.ui.CardPresenter
import kotlinx.coroutines.*

class ShowBrowseFragment : RowsSupportFragment(), CoroutineScope by MainScope() {

    private lateinit var api: GiantBombApi
    private var show: Show? = null
    private var isLoading = false
    private var currentOffset = 0
    private var hasMore = true
    private var progressMap = emptyMap<Int, ProgressEntry>()
    private var episodesAdapter: ArrayObjectAdapter? = null

    companion object {
        private const val PAGE_SIZE = 40
        private const val LOAD_MORE_THRESHOLD = 5
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = PrefsManager(requireContext())
        api = GiantBombApi(prefs.apiKey ?: "")

        @Suppress("DEPRECATION")
        show = requireActivity().intent.getSerializableExtra(ShowActivity.EXTRA_SHOW) as? Show

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            if (item is Video) {
                val intent = Intent(requireContext(), DetailActivity::class.java).apply {
                    putExtra(DetailActivity.EXTRA_VIDEO, item)
                }
                startActivity(intent)
            }
        }

        // Infinite scroll: load more when user focuses near the end
        onItemViewSelectedListener = OnItemViewSelectedListener { _, item, _, _ ->
            if (item is Video && !isLoading && hasMore) {
                val adapter = episodesAdapter ?: return@OnItemViewSelectedListener
                val position = adapter.indexOf(item)
                if (position >= adapter.size() - LOAD_MORE_THRESHOLD) {
                    loadMore()
                }
            }
        }

        loadContent()
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

            // Set up the rows adapter
            val rowPresenter = ListRowPresenter(FocusHighlight.ZOOM_FACTOR_NONE).apply {
                shadowEnabled = false
                selectEffectEnabled = false
            }
            val rowsAdapter = ArrayObjectAdapter(rowPresenter)

            episodesAdapter = ArrayObjectAdapter(CardPresenter())
            val headerTitle = if (s.deck.isNotEmpty()) "${s.title} \u2014 ${s.deck}" else s.title
            rowsAdapter.add(ListRow(HeaderItem(0, headerTitle), episodesAdapter!!))

            adapter = rowsAdapter

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

            val adapter = episodesAdapter ?: return@onSuccess
            videos.forEach { video ->
                val entry = progressMap[video.id]
                val v = when {
                    entry != null && entry.percentComplete >= 95 -> video.copy(watched = true)
                    entry != null && entry.percentComplete in 1..94 -> video.copy(progressPercent = entry.percentComplete)
                    else -> video
                }
                adapter.add(v)
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
