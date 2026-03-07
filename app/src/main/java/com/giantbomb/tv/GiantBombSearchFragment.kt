package com.giantbomb.tv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.leanback.app.SearchSupportFragment
import androidx.leanback.widget.*
import com.giantbomb.tv.data.GiantBombApi
import com.giantbomb.tv.data.PrefsManager
import com.giantbomb.tv.model.Video
import com.giantbomb.tv.ui.CardPresenter
import kotlinx.coroutines.*

class GiantBombSearchFragment : SearchSupportFragment(),
    SearchSupportFragment.SearchResultProvider, CoroutineScope by MainScope() {

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
    private val handler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null
    private lateinit var api: GiantBombApi

    companion object {
        private const val SEARCH_DELAY_MS = 400L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = PrefsManager(requireContext())
        api = GiantBombApi(prefs.apiKey ?: "")
        setSearchResultProvider(this)

        setOnItemViewClickedListener { _, item, _, _ ->
            if (item is Video) {
                val intent = Intent(requireContext(), DetailActivity::class.java).apply {
                    putExtra(DetailActivity.EXTRA_VIDEO, item)
                }
                startActivity(intent)
            }
        }
    }

    override fun getResultsAdapter(): ObjectAdapter = rowsAdapter

    override fun onQueryTextChange(newQuery: String?): Boolean {
        searchDebounced(newQuery ?: "")
        return true
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        searchDebounced(query ?: "")
        return true
    }

    private fun searchDebounced(query: String) {
        searchRunnable?.let { handler.removeCallbacks(it) }
        if (query.length < 2) {
            rowsAdapter.clear()
            return
        }
        searchRunnable = Runnable { performSearch(query) }
        handler.postDelayed(searchRunnable!!, SEARCH_DELAY_MS)
    }

    private fun performSearch(query: String) {
        launch {
            val result = api.getVideos(limit = 30, query = query)
            result.onSuccess { videos ->
                rowsAdapter.clear()
                if (videos.isNotEmpty()) {
                    val listRowAdapter = ArrayObjectAdapter(CardPresenter())
                    videos.forEach { listRowAdapter.add(it) }
                    rowsAdapter.add(ListRow(
                        HeaderItem(0, "Results for \"$query\""),
                        listRowAdapter
                    ))
                }
            }
            result.onFailure {
                rowsAdapter.clear()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        searchRunnable?.let { handler.removeCallbacks(it) }
        cancel()
    }
}
