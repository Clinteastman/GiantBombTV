package com.giantbomb.tv

import android.content.Intent
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.bumptech.glide.Glide
import com.giantbomb.tv.data.GiantBombApi
import com.giantbomb.tv.data.PrefsManager
import com.giantbomb.tv.model.Video
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.*

class DetailActivity : FragmentActivity(), CoroutineScope by MainScope() {

    companion object {
        const val EXTRA_VIDEO = "extra_video"
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
    private val density by lazy { resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        val video = intent.getSerializableExtra(EXTRA_VIDEO) as? Video ?: run {
            finish()
            return
        }

        val prefs = PrefsManager(this)
        val apiKey = prefs.apiKey ?: ""
        val api = GiantBombApi(apiKey)

        val root = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.bg_ambient_gradient)
            clipChildren = false
        }

        // Background image
        val backdrop = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.35f
        }
        val imageUrl = video.thumbnailUrl ?: video.posterUrl
        if (!imageUrl.isNullOrEmpty()) {
            Glide.with(this)
                .load(imageUrl)
                .override(480, 270)
                .into(backdrop)
        }
        root.addView(backdrop)

        // Multi-layer gradient overlays
        val gradientBottom = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xE81A1A20.toInt(), 0x801A1A20.toInt(), 0x001A1A20)
            )
        }
        root.addView(gradientBottom)

        val gradientLeft = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xDD1A1A20.toInt(), 0x551A1A20.toInt(), 0x001A1A20)
            )
        }
        root.addView(gradientLeft)

        val gradientTop = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                120.dp()
            )
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x991A1A20.toInt(), 0x001A1A20)
            )
        }
        root.addView(gradientTop)

        // Content
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(48.dp(), 30.dp(), 200.dp(), 40.dp())
            clipChildren = false
            clipToPadding = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val animViews = mutableListOf<View>()

        // Show tag
        if (!video.showTitle.isNullOrEmpty()) {
            val showTag = TextView(this@DetailActivity).apply {
                text = video.showTitle.uppercase()
                textSize = 13f
                setTextColor(0xFFE3192C.toInt())
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.1f
                setPadding(0, 0, 0, 6.dp())
            }
            content.addView(showTag)
            animViews.add(showTag)
        }

        // Premium badge — frosted gold pill
        if (video.premium) {
            val premiumTag = TextView(this).apply {
                text = "  PREMIUM  "
                textSize = 11f
                setTextColor(0xFF1A1A1A.toInt())
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.1f
                val pillFill = GradientDrawable().apply {
                    setColor(0xBBFFD700.toInt())
                    cornerRadius = 4f * density
                }
                val pillBorder = GradientDrawable().apply {
                    setColor(0x00000000)
                    setStroke((0.5f * density).toInt(), 0x40FFFFFF)
                    cornerRadius = 4f * density
                }
                background = LayerDrawable(arrayOf(pillFill, pillBorder))
                setPadding(6.dp(), 2.dp(), 6.dp(), 2.dp())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 6.dp() }
            }
            content.addView(premiumTag)
            animViews.add(premiumTag)
        }

        val titleView = TextView(this).apply {
            text = video.title
            textSize = 32f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 8.dp())
            maxLines = 2
        }
        content.addView(titleView)
        animViews.add(titleView)

        // Meta
        val metaView = TextView(this).apply {
            val parts = mutableListOf<String>()
            if (video.publishDate.isNotEmpty()) parts.add(video.publishDate.take(10))
            if (!video.author.isNullOrEmpty()) parts.add(video.author)
            text = parts.joinToString("  \u2022  ")
            textSize = 15f
            setTextColor(0xFFA0A0A0.toInt())
            setPadding(0, 0, 0, 12.dp())
        }
        content.addView(metaView)
        animViews.add(metaView)

        // Fetch duration from playback API and update meta
        launch {
            val playback = api.getPlayback(video.id).getOrNull()
            if (playback != null && playback.duration > 0) {
                val totalSec = playback.duration.toInt()
                val h = totalSec / 3600
                val m = (totalSec % 3600) / 60
                val durationStr = if (h > 0) "${h}h ${m}m" else "${m}m"
                val parts = mutableListOf(durationStr)
                if (video.publishDate.isNotEmpty()) parts.add(video.publishDate.take(10))
                if (!video.author.isNullOrEmpty()) parts.add(video.author)
                metaView.text = parts.joinToString("  \u2022  ")
            }
        }

        // Description
        if (!video.description.isNullOrEmpty()) {
            val descView = TextView(this).apply {
                text = video.description.replace(Regex("<[^>]*>"), "")
                textSize = 15f
                setTextColor(0xFF999999.toInt())
                setPadding(0, 0, 0, 20.dp())
                maxLines = 4
                setLineSpacing(0f, 1.3f)
            }
            content.addView(descView)
            animViews.add(descView)
        }

        // Buttons
        val buttonLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }

        val watchButton = createGlassButton(
            text = getString(R.string.watch),
            fillColor = 0x55E3192C,
            focusFillColor = 0x88E3192C.toInt(),
            borderColor = 0x40E3192C,
            focusBorderColor = 0x66E3192C,
            textColor = Color.WHITE,
            bold = true
        ).apply {
            setPadding(32.dp(), 10.dp(), 32.dp(), 10.dp())
            setOnClickListener {
                val intent = Intent(this@DetailActivity, PlaybackActivity::class.java).apply {
                    putExtra(PlaybackActivity.EXTRA_VIDEO, video)
                }
                startActivity(intent)
            }
        }
        buttonLayout.addView(watchButton)

        val watchlistButton = createGlassButton(
            text = "+ Watchlist",
            fillColor = 0x1AFFFFFF,
            focusFillColor = 0x2AFFFFFF,
            borderColor = 0x18FFFFFF,
            focusBorderColor = 0x30FFFFFF,
            textColor = 0xFFCCCCCC.toInt(),
            bold = false
        ).apply {
            setPadding(24.dp(), 10.dp(), 24.dp(), 10.dp())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 10.dp() }
            setOnClickListener {
                launch {
                    val result = api.addToWatchlist(video.id)
                    result.onSuccess {
                        text = "\u2713 Watchlist"
                        Toast.makeText(this@DetailActivity, "Added to watchlist", Toast.LENGTH_SHORT).show()
                    }
                    result.onFailure {
                        Toast.makeText(this@DetailActivity, "Failed to add", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        buttonLayout.addView(watchlistButton)

        content.addView(buttonLayout)
        animViews.add(buttonLayout)

        root.addView(content)
        setContentView(root)

        // Staggered entrance animation
        animViews.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = 30f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(100L + index * 60L)
                .setDuration(350)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        watchButton.requestFocus()
    }

    private fun createGlassButton(
        text: String, fillColor: Int, focusFillColor: Int,
        borderColor: Int, focusBorderColor: Int,
        textColor: Int, bold: Boolean
    ): Button {
        val cornerRadius = 8f * density
        return Button(this).apply {
            this.text = text
            textSize = 16f
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setTextColor(textColor)
            isAllCaps = false
            isFocusable = true

            fun glassDrawable(fill: Int, border: Int): LayerDrawable {
                val f = GradientDrawable().apply {
                    setColor(fill)
                    setCornerRadius(cornerRadius)
                }
                val b = GradientDrawable().apply {
                    setColor(0x00000000)
                    setStroke((1f * density).toInt(), border)
                    setCornerRadius(cornerRadius)
                }
                return LayerDrawable(arrayOf(f, b))
            }

            background = glassDrawable(fillColor, borderColor)

            setOnFocusChangeListener { v, hasFocus ->
                val scale = if (hasFocus) 1.05f else 1.0f
                v.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
                v.background = glassDrawable(
                    if (hasFocus) focusFillColor else fillColor,
                    if (hasFocus) focusBorderColor else borderColor
                )
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Use OnBackPressedDispatcher")
    override fun onBackPressed() {
        super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancel()
    }
}
