package com.giantbomb.tv

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import com.giantbomb.tv.data.GiantBombApi
import com.giantbomb.tv.data.PrefsManager
import com.giantbomb.tv.util.DeviceUtil
import kotlinx.coroutines.*

class SetupActivity : Activity(), CoroutineScope by MainScope() {

    private lateinit var statusText: TextView
    private val density by lazy { resources.displayMetrics.density }

    private fun dp(value: Int): Int = (value * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = PrefsManager(this)

        val deepLinkKey = intent?.data?.getQueryParameter("key")
        if (!deepLinkKey.isNullOrEmpty()) {
            prefs.apiKey = deepLinkKey
            setResult(RESULT_OK)
            finish()
            return
        }

        val cornerRadius = 16f * density

        val root = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.bg_ambient_gradient)
        }

        // Glass card panel
        val cardFill = GradientDrawable().apply {
            setColor(0x18FFFFFF)
            setCornerRadius(cornerRadius)
        }
        val cardBorder = GradientDrawable().apply {
            setColor(0x00000000)
            setStroke((1f * density).toInt(), 0x14FFFFFF)
            setCornerRadius(cornerRadius)
        }
        val isTv = DeviceUtil.isTv(this)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = LayerDrawable(arrayOf(cardFill, cardBorder))
            setPadding(dp(40), dp(30), dp(40), dp(30))
            layoutParams = if (isTv) {
                FrameLayout.LayoutParams(dp(450), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER
                }
            } else {
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER
                    marginStart = dp(24)
                    marginEnd = dp(24)
                }
            }
        }

        // Accent bar — subtle red glow at top
        val accentBg = GradientDrawable().apply {
            setColor(0xAAE3192C.toInt())
            cornerRadii = floatArrayOf(
                cornerRadius, cornerRadius,
                cornerRadius, cornerRadius,
                0f, 0f, 0f, 0f
            )
        }
        val accent = android.view.View(this).apply {
            background = accentBg
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(3)
            ).apply { bottomMargin = dp(20) }
        }

        val logoImage = ImageView(this).apply {
            setImageResource(R.drawable.giant_bomb_logo)
            contentDescription = "Giant Bomb logo"
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(dp(220), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(16)
            }
        }

        val title = TextView(this).apply {
            text = "Connect Your Account"
            textSize = 22f
            setTextColor(0xFFF1F1F1.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(12))
        }

        val instructions = TextView(this).apply {
            text = "Find your API key on your Giant Bomb profile page.\n\nLog in at giantbomb.com, go to your profile, and copy the 40-character API key."
            textSize = 16f
            setTextColor(0xFFA0A0A0.toInt())
            setPadding(0, 0, 0, dp(16))
            setLineSpacing(0f, 1.3f)
        }

        // Glass input field
        val inputFill = GradientDrawable().apply {
            setColor(0x10FFFFFF)
            setCornerRadius(8f * density)
        }
        val inputBorder = GradientDrawable().apply {
            setColor(0x00000000)
            setStroke((1f * density).toInt(), 0x12FFFFFF)
            setCornerRadius(8f * density)
        }
        val editText = EditText(this).apply {
            hint = "API Key"
            setText(prefs.apiKey ?: "")
            textSize = 18f
            setTextColor(0xFFF1F1F1.toInt())
            setHintTextColor(0xFF555555.toInt())
            background = LayerDrawable(arrayOf(inputFill, inputBorder))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isSingleLine = true
            isFocusable = true
            isFocusableInTouchMode = true
            imeOptions = EditorInfo.IME_ACTION_DONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        statusText = TextView(this).apply {
            text = ""
            textSize = 14f
            setTextColor(0xFFA0A0A0.toInt())
            setPadding(0, 0, 0, dp(12))
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        val cancelButton = createGlassButton(
            "Cancel", 0x1AFFFFFF, 0x2AFFFFFF, 0x18FFFFFF, 0x30FFFFFF, 0xFFCCCCCC.toInt()
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
            setOnClickListener { finish() }
        }

        val saveButton = createGlassButton(
            "Connect", 0x55E3192C, 0x88E3192C.toInt(), 0x40E3192C, 0x66E3192C, 0xFFFFFFFF.toInt()
        ).apply {
            setOnClickListener {
                val key = editText.text.toString().trim()
                if (key.isEmpty()) {
                    statusText.text = "Please enter your API key."
                    statusText.setTextColor(0xFFFF5555.toInt())
                    return@setOnClickListener
                }
                validateAndSave(key, prefs)
            }
        }

        buttonRow.addView(cancelButton)
        buttonRow.addView(saveButton)

        card.addView(accent)
        card.addView(logoImage)
        card.addView(title)
        card.addView(instructions)
        card.addView(editText)
        card.addView(statusText)
        card.addView(buttonRow)

        val scrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isFillViewport = true
        }
        scrollView.addView(card)
        root.addView(scrollView)
        setContentView(root)

        editText.post { editText.requestFocus() }
    }

    private fun createGlassButton(
        text: String, fillColor: Int, focusFillColor: Int,
        borderColor: Int, focusBorderColor: Int, textColor: Int
    ): Button {
        val cornerRadius = 8f * density
        return Button(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(textColor)
            isAllCaps = false
            setPadding(dp(24), dp(8), dp(24), dp(8))
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

    private fun validateAndSave(key: String, prefs: PrefsManager) {
        statusText.text = "Validating\u2026"
        statusText.setTextColor(0xFFA0A0A0.toInt())

        launch {
            val api = GiantBombApi(key)
            val result = api.validateKey()

            result.onSuccess { isPremium ->
                prefs.apiKey = key
                prefs.isPremium = isPremium
                statusText.text = if (isPremium) "Premium account linked!" else "Free account linked."
                statusText.setTextColor(0xFF4CAF50.toInt())
                setResult(RESULT_OK)
                finish()
            }

            result.onFailure { e ->
                statusText.text = "Invalid key or connection error: ${e.message}"
                statusText.setTextColor(0xFFFF5555.toInt())
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cancel()
    }
}
