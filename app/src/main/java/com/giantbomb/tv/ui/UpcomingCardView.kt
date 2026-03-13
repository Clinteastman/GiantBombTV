package com.giantbomb.tv.ui

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.bumptech.glide.Glide
import com.giantbomb.tv.R
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class UpcomingCardView(context: Context) : FrameLayout(context) {

    val imageView: ImageView
    val titleView: TextView
    val timeView: TextView
    val premiumBadge: TextView
    val liveBadge: TextView
    val cardRoot: View
    val glassBg: FrameLayout

    private val hoursView: TextView
    private val minutesView: TextView
    private val secondsView: TextView
    private val daysView: TextView
    private val daysGroup: View
    private val daysSep: View
    private val countdownGroup: View

    private val handler = Handler(Looper.getMainLooper())
    private var targetTimeMs: Long = 0L
    private var isLiveNow = false
    private var countdownRunnable: Runnable? = null

    private val cardWidthPx: Int
    private val cardHeightPx: Int

    init {
        val res = context.resources
        val density = res.displayMetrics.density
        val hPadding = res.getDimensionPixelSize(R.dimen.card_margin)
        val vPaddingPx = res.getDimensionPixelSize(R.dimen.card_vpadding)
        cardWidthPx = res.getDimensionPixelSize(R.dimen.card_width) + hPadding * 2
        cardHeightPx = res.getDimensionPixelSize(R.dimen.card_height) + vPaddingPx * 2
        val cornerRadius = 12f * density

        setPadding(hPadding, vPaddingPx, hPadding, vPaddingPx)
        clipChildren = false
        clipToPadding = false

        LayoutInflater.from(context).inflate(R.layout.view_upcoming_card, this, true)
        cardRoot = findViewById(R.id.upcoming_card_root)
        glassBg = findViewById(R.id.upcoming_glass_bg)
        imageView = findViewById(R.id.upcoming_image)
        titleView = findViewById(R.id.upcoming_title)
        timeView = findViewById(R.id.upcoming_time)
        premiumBadge = findViewById(R.id.upcoming_premium_badge)
        liveBadge = findViewById(R.id.upcoming_live_badge)
        hoursView = findViewById(R.id.upcoming_hours)
        minutesView = findViewById(R.id.upcoming_minutes)
        secondsView = findViewById(R.id.upcoming_seconds)
        daysView = findViewById(R.id.upcoming_days)
        daysGroup = findViewById(R.id.upcoming_days_group)
        daysSep = findViewById(R.id.upcoming_sep1)
        countdownGroup = findViewById(R.id.upcoming_countdown_group)

        setBackgroundColor(0x00000000)
        (cardRoot as? FrameLayout)?.setBackgroundColor(0x00000000)

        // Glass background
        glassBg.background = GradientDrawable().apply {
            setColor(0x18FFFFFF)
            setCornerRadius(cornerRadius)
        }
        glassBg.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
            }
        }
        glassBg.clipToOutline = true

        // Light scrim so art shows through
        findViewById<View>(R.id.upcoming_scrim).background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x80000000.toInt(), 0x60000000.toInt())
        )

        // Bottom gradient (same as video card)
        findViewById<View>(R.id.upcoming_gradient).background = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(0x60000000, 0x00000000)
        )

        // Text area glass tint (same as video card)
        val textArea = findViewById<View>(R.id.upcoming_text_area)
        textArea.background = GradientDrawable().apply {
            setColor(0x0DFFFFFF)
            cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, cornerRadius, cornerRadius, cornerRadius, cornerRadius)
        }

        // Premium badge
        premiumBadge.background = GradientDrawable().apply {
            setColor(0xCCFFD700.toInt())
            setCornerRadius(4f * density)
            setStroke((0.5f * density).toInt(), 0x40FFFFFF)
        }

        // Live badge
        liveBadge.background = GradientDrawable().apply {
            setColor(0xFFE3192C.toInt())
            setCornerRadius(4f * density)
        }

        isFocusable = true
        isFocusableInTouchMode = true

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

    fun setUpcomingData(title: String, dateStr: String, premium: Boolean, liveNow: Boolean) {
        titleView.text = title
        contentDescription = title
        premiumBadge.visibility = if (premium) View.VISIBLE else View.GONE
        isLiveNow = liveNow

        if (liveNow) {
            liveBadge.visibility = View.VISIBLE
            countdownGroup.visibility = View.GONE
            timeView.text = "Streaming now"
        } else {
            liveBadge.visibility = View.GONE
            countdownGroup.visibility = View.VISIBLE
            targetTimeMs = parseDate(dateStr)
            timeView.text = formatLocalTime(targetTimeMs)
            startCountdown()
        }
    }

    private fun startCountdown() {
        countdownRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable = object : Runnable {
            override fun run() {
                val remaining = targetTimeMs - System.currentTimeMillis()
                if (remaining <= 0) {
                    // Switch to LIVE state
                    countdownGroup.visibility = View.GONE
                    liveBadge.visibility = View.VISIBLE
                    timeView.text = "Starting soon - tap to watch"
                    isLiveNow = true
                    // Load Twitch preview thumbnail
                    Glide.with(this@UpcomingCardView)
                        .load("https://static-cdn.jtvnw.net/previews-ttv/live_user_giantbomb-640x360.jpg")
                        .centerCrop()
                        .into(imageView)
                    return
                }
                val totalSec = remaining / 1000
                val days = totalSec / 86400
                val hours = (totalSec % 86400) / 3600
                val mins = (totalSec % 3600) / 60
                val secs = totalSec % 60

                if (days > 0) {
                    daysGroup.visibility = View.VISIBLE
                    daysSep.visibility = View.VISIBLE
                    daysView.text = "%02d".format(days)
                } else {
                    daysGroup.visibility = View.GONE
                    daysSep.visibility = View.GONE
                }
                hoursView.text = "%02d".format(hours)
                minutesView.text = "%02d".format(mins)
                secondsView.text = "%02d".format(secs)

                handler.postDelayed(this, 1000)
            }
        }
        handler.post(countdownRunnable!!)
    }

    fun stopCountdown() {
        countdownRunnable?.let { handler.removeCallbacks(it) }
        countdownRunnable = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopCountdown()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (targetTimeMs > 0 && !isLiveNow) {
            startCountdown()
        }
    }

    companion object {
        private val PT_ZONE = TimeZone.getTimeZone("America/Los_Angeles")

        fun parseDate(dateStr: String): Long {
            val formats = arrayOf(
                "MMM dd, yyyy hh:mm a",
                "MMM d, yyyy hh:mm a",
                "MMM dd, yyyy h:mm a",
                "MMM d, yyyy h:mm a"
            )
            for (fmt in formats) {
                try {
                    val sdf = SimpleDateFormat(fmt, Locale.US)
                    sdf.timeZone = PT_ZONE
                    val date = sdf.parse(dateStr) ?: continue
                    return date.time
                } catch (_: Exception) { }
            }
            return 0L
        }

        fun formatLocalTime(timeMs: Long): String {
            if (timeMs == 0L) return ""
            val sdf = SimpleDateFormat("EEE, MMM d 'at' h:mm a", Locale.getDefault())
            sdf.timeZone = TimeZone.getDefault()
            return sdf.format(timeMs)
        }
    }
}
