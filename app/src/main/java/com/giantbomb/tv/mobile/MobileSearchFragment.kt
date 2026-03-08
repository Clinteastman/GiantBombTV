package com.giantbomb.tv.mobile

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.giantbomb.tv.DetailActivity
import com.giantbomb.tv.R
import com.giantbomb.tv.data.GiantBombApi
import com.giantbomb.tv.data.PrefsManager
import com.giantbomb.tv.model.Video
import kotlinx.coroutines.*

class MobileSearchFragment : Fragment(), CoroutineScope by MainScope() {

    private lateinit var api: GiantBombApi
    private lateinit var searchInput: EditText
    private lateinit var resultsRecycler: RecyclerView
    private lateinit var emptyView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null
    private val results = mutableListOf<Video>()
    private lateinit var adapter: SearchResultAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_mobile_search, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = PrefsManager(requireContext())
        api = GiantBombApi(prefs.apiKey ?: "")

        searchInput = view.findViewById(R.id.search_input)
        resultsRecycler = view.findViewById(R.id.search_results)
        emptyView = view.findViewById(R.id.search_empty)

        view.findViewById<ImageView>(R.id.search_back).setOnClickListener {
            requireActivity().finish()
        }

        adapter = SearchResultAdapter()
        resultsRecycler.layoutManager = LinearLayoutManager(requireContext())
        resultsRecycler.adapter = adapter

        searchInput.addTextChangedListener { text ->
            searchDebounced(text?.toString() ?: "")
        }

        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                searchDebounced(searchInput.text.toString())
                hideKeyboard()
                true
            } else false
        }

        // Auto-focus search input and show keyboard
        searchInput.requestFocus()
        searchInput.postDelayed({
            val imm = ContextCompat.getSystemService(requireContext(), InputMethodManager::class.java)
            imm?.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    private fun hideKeyboard() {
        val imm = ContextCompat.getSystemService(requireContext(), InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    private fun searchDebounced(query: String) {
        searchRunnable?.let { handler.removeCallbacks(it) }
        if (query.length < 2) {
            results.clear()
            adapter.notifyDataSetChanged()
            emptyView.text = "Search for Giant Bomb videos"
            emptyView.visibility = View.VISIBLE
            resultsRecycler.visibility = View.GONE
            return
        }
        searchRunnable = Runnable { performSearch(query) }
        handler.postDelayed(searchRunnable!!, 400)
    }

    private fun performSearch(query: String) {
        launch {
            val result = api.getVideos(limit = 30, query = query)
            result.onSuccess { videos ->
                results.clear()
                results.addAll(videos)
                adapter.notifyDataSetChanged()
                if (videos.isEmpty()) {
                    emptyView.text = "No results for \"$query\""
                    emptyView.visibility = View.VISIBLE
                    resultsRecycler.visibility = View.GONE
                } else {
                    emptyView.visibility = View.GONE
                    resultsRecycler.visibility = View.VISIBLE
                }
            }
            result.onFailure {
                results.clear()
                adapter.notifyDataSetChanged()
                emptyView.text = "Search failed"
                emptyView.visibility = View.VISIBLE
                resultsRecycler.visibility = View.GONE
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        searchRunnable?.let { handler.removeCallbacks(it) }
        cancel()
    }

    private inner class SearchResultAdapter : RecyclerView.Adapter<SearchResultAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val thumbnail: ImageView = view.findViewById(R.id.related_thumbnail)
            val title: TextView = view.findViewById(R.id.related_title)
            val meta: TextView = view.findViewById(R.id.related_meta)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_related_video, parent, false)
            return VH(view)
        }

        override fun getItemCount() = results.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val video = results[position]
            holder.title.text = video.title

            val meta = buildString {
                video.showTitle?.let { append(it) }
                if (video.publishDate.isNotEmpty()) {
                    if (isNotEmpty()) append(" \u2022 ")
                    append(video.publishDate.take(10))
                }
            }
            holder.meta.text = meta

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
}
