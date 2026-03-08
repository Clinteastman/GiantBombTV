package com.giantbomb.tv.ui

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.giantbomb.tv.R

class VideoCardView(context: Context) : FrameLayout(context) {

    val thumbnail: ImageView
    val titleView: TextView
    val metaView: TextView
    val progressBar: View
    val progressTrack: View
    val premiumBadge: TextView
    val watchedBadge: TextView
    val cardRoot: View
    val glassBg: FrameLayout

    private val cardWidthPx: Int
    private val cardHeightPx: Int

    init {
        val density = context.resources.displayMetrics.density
        val hPadding = (CARD_MARGIN_DP * density).toInt()
        val vPaddingPx = (CARD_VPADDING_DP * density).toInt()
        cardWidthPx = (CARD_WIDTH_DP * density).toInt() + hPadding * 2
        cardHeightPx = (CARD_HEIGHT_DP * density).toInt() + vPaddingPx * 2
        val cornerRadius = 12f * density

        val vPadding = (CARD_VPADDING_DP * density).toInt()
        setPadding(hPadding, vPadding, hPadding, vPadding)
        clipChildren = false
        clipToPadding = false

        LayoutInflater.from(context).inflate(R.layout.view_video_card, this, true)
        cardRoot = findViewById(R.id.card_root)
        glassBg = findViewById(R.id.card_glass_bg)
        thumbnail = findViewById(R.id.card_thumbnail)
        titleView = findViewById(R.id.card_title)
        metaView = findViewById(R.id.card_meta)
        progressBar = findViewById(R.id.card_progress)
        progressTrack = findViewById(R.id.card_progress_track)
        premiumBadge = findViewById(R.id.card_premium_badge)
        watchedBadge = findViewById(R.id.card_watched_badge)

        // Ensure no accidental dark backgrounds leak through
        setBackgroundColor(0x00000000)
        (cardRoot as? FrameLayout)?.setBackgroundColor(0x00000000)

        // Glass background: fill only, no stroke (stroke causes dark fringe with clipToOutline)
        glassBg.background = GradientDrawable().apply {
            setColor(0x18FFFFFF)
            setCornerRadius(cornerRadius)
        }

        // Clip the glass container to rounded corners
        glassBg.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
            }
        }
        glassBg.clipToOutline = true

        // Gradient overlay on thumbnail bottom
        val gradient = findViewById<View>(R.id.card_gradient)
        gradient.background = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(0x60000000, 0x00000000)
        )

        // Text area subtle glass tint
        val textArea = findViewById<View>(R.id.card_text_area)
        val textBg = GradientDrawable().apply {
            setColor(0x0DFFFFFF)
            cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, cornerRadius, cornerRadius, cornerRadius, cornerRadius)
        }
        textArea.background = textBg

        // Premium badge — frosted gold pill
        val badgeBg = GradientDrawable().apply {
            setColor(0xCCFFD700.toInt())
            setCornerRadius(4f * density)
            setStroke((0.5f * density).toInt(), 0x40FFFFFF)
        }
        premiumBadge.background = badgeBg

        // Watched badge — small green circle with checkmark
        val watchedBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xCC4CAF50.toInt())
        }
        watchedBadge.background = watchedBg

        // Make focusable for D-pad navigation
        isFocusable = true
        isFocusableInTouchMode = true

        // Focus animation: scale up + brighten glass
        setOnFocusChangeListener { _, hasFocus ->
            val scale = if (hasFocus) 1.05f else 1.0f
            cardRoot.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
            glassBg.background = GradientDrawable().apply {
                setColor(if (hasFocus) 0x30FFFFFF else 0x18FFFFFF)
                setCornerRadius(cornerRadius)
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wSpec = MeasureSpec.makeMeasureSpec(cardWidthPx, MeasureSpec.EXACTLY)
        val hSpec = MeasureSpec.makeMeasureSpec(cardHeightPx, MeasureSpec.EXACTLY)
        super.onMeasure(wSpec, hSpec)
    }

    fun setTitle(title: String) {
        titleView.text = title
        contentDescription = title
    }

    fun setMeta(meta: String) {
        metaView.text = meta
        metaView.visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE
    }

    fun setProgress(percent: Int) {
        if (percent in 1..99) {
            progressTrack.visibility = View.VISIBLE
            progressBar.visibility = View.VISIBLE
            progressBar.post {
                val parent = progressBar.parent as? View ?: return@post
                val width = parent.width
                val params = progressBar.layoutParams
                params.width = (width * percent / 100)
                progressBar.layoutParams = params
            }
        } else {
            progressTrack.visibility = View.GONE
            progressBar.visibility = View.GONE
        }
    }

    fun setPremium(show: Boolean) {
        premiumBadge.visibility = if (show) View.VISIBLE else View.GONE
    }

    fun setWatched(watched: Boolean) {
        watchedBadge.visibility = if (watched) View.VISIBLE else View.GONE
    }

    companion object {
        private const val CARD_WIDTH_DP = 320
        private const val CARD_HEIGHT_DP = 240
        private const val CARD_MARGIN_DP = 8
        private const val CARD_VPADDING_DP = 8
    }
}
