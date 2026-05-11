package com.giantbomb.tv

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.giantbomb.tv.data.PrefsManager
import java.util.Collections

/**
 * Customize Browse: drag-to-reorder + visibility-toggle for the browse-screen
 * sections. Working copy is mutated in place; the new order and hidden set
 * are persisted on finish() so swipe-back, system-back, and the toolbar
 * back arrow all save uniformly.
 */
class CustomizeBrowseActivity : ComponentActivity() {

    private lateinit var prefs: PrefsManager
    private val rows = mutableListOf<SectionRow>()

    private data class SectionRow(val id: String, val label: String, var visible: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefs = PrefsManager(this)

        val order = prefs.getSectionOrder()
        val hidden = prefs.getHiddenSections()
        for (id in order) {
            rows.add(SectionRow(id, PrefsManager.sectionLabel(id), id !in hidden))
        }

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@CustomizeBrowseActivity, R.color.gb_background))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Toolbar
        val toolbar = FrameLayout(this).apply {
            setPadding(dp(8), dp(40), dp(16), dp(8))
        }
        val backBtn = TextView(this).apply {
            text = "←  Customize Browse"
            textSize = 18f
            setTextColor(0xFFE6E6E6.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isClickable = true
            isFocusable = true
            background = ContextCompat.getDrawable(context, android.R.drawable.list_selector_background)
            setOnClickListener { finish() }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }
        }
        toolbar.addView(backBtn)
        root.addView(toolbar)

        // Helper text
        val help = TextView(this).apply {
            text = "Drag to reorder. Toggle a switch to hide a section."
            textSize = 13f
            setTextColor(0xFFA0A0A0.toInt())
            setPadding(dp(20), dp(0), dp(20), dp(16))
        }
        root.addView(help)

        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@CustomizeBrowseActivity)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0
            ).apply { weight = 1f }
        }
        val adapter = SectionAdapter()
        rv.adapter = adapter

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun isLongPressDragEnabled() = false
            override fun isItemViewSwipeEnabled() = false
            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from < 0 || to < 0) return false
                Collections.swap(rows, from, to)
                adapter.notifyItemMoved(from, to)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
        })
        touchHelper.attachToRecyclerView(rv)
        adapter.touchHelper = touchHelper

        root.addView(rv)
        setContentView(root)
    }

    override fun finish() {
        // Persist whatever the user has in the working list — order + visibility.
        prefs.setSectionOrder(rows.map { it.id })
        prefs.setHiddenSections(rows.filter { !it.visible }.map { it.id }.toSet())
        super.finish()
    }

    private inner class SectionAdapter : RecyclerView.Adapter<SectionAdapter.VH>() {
        var touchHelper: ItemTouchHelper? = null

        inner class VH(
            val container: LinearLayout,
            val handle: TextView,
            val label: TextView,
            val toggle: SwitchCompat
        ) : RecyclerView.ViewHolder(container)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val density = ctx.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()

            val container = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(14), dp(20), dp(14))
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val handle = TextView(ctx).apply {
                text = "☰" // ☰
                textSize = 22f
                setTextColor(0xFFA0A0A0.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(16) }
            }
            val label = TextView(ctx).apply {
                textSize = 16f
                setTextColor(0xFFE6E6E6.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val toggle = SwitchCompat(ctx)
            container.addView(handle)
            container.addView(label)
            container.addView(toggle)
            return VH(container, handle, label, toggle)
        }

        override fun getItemCount(): Int = rows.size

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = rows[position]
            holder.label.text = row.label
            holder.label.alpha = if (row.visible) 1.0f else 0.5f
            holder.toggle.setOnCheckedChangeListener(null)
            holder.toggle.isChecked = row.visible
            holder.toggle.setOnCheckedChangeListener { _, checked ->
                row.visible = checked
                holder.label.alpha = if (checked) 1.0f else 0.5f
            }
            // The drag handle only initiates an ItemTouchHelper drag — there's
            // no semantic click target, so accessibility lint is satisfied with
            // the suppression on the binder.
            holder.handle.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    touchHelper?.startDrag(holder)
                }
                false
            }
        }
    }
}
