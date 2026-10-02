package com.giantbomb.tv.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.giantbomb.tv.data.PrefsManager

/**
 * Visual theme chooser. Replaces the stock AlertDialog so the picker matches
 * the app, and shows a small preview of each theme next to its name.
 * Works with D-pad focus on TV and touch on phones.
 */
object ThemePickerDialog {

    private const val ACCENT_RED = 0xFFE3192C.toInt()
    private const val NEON_PINK = 0xFFFF43E6.toInt()
    private const val NEON_CYAN = 0xFF42F5FF.toInt()

    private fun description(value: String): String = when (value) {
        PrefsManager.THEME_SIMPLE -> "Flat, solid cards"
        PrefsManager.THEME_FROSTED -> "Soft frosted glass over the artwork"
        PrefsManager.THEME_EXTREME -> "Clear glass with a lens and shine"
        PrefsManager.THEME_NEON -> "Dark grid with neon edges"
        else -> ""
    }

    fun show(context: Context, current: String, onPick: (String) -> Unit) {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val neon = current == PrefsManager.THEME_NEON
        val focusAccent = if (neon) NEON_PINK else ACCENT_RED

        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(22), dp(24), dp(18))
            background = GradientDrawable().apply {
                setColor(0xF2141620.toInt())
                cornerRadius = 22f * density
                setStroke(dp(1), if (neon) 0x6642F5FF else 0x33FFFFFF)
            }
        }
        panel.addView(TextView(context).apply {
            text = "Visual Theme"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(4), 0, 0, dp(14))
        })

        var currentRow: View? = null
        PrefsManager.VISUAL_THEMES.forEach { value ->
            val selected = value == current
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(14), dp(10))
                isFocusable = true
                isClickable = true
                contentDescription = "${PrefsManager.visualThemeLabel(value)}. ${description(value)}" +
                    if (selected) ". Selected" else ""
                background = rowBackground(density, focusAccent, selected)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
                setOnClickListener {
                    dialog.dismiss()
                    if (!selected) onPick(value)
                }
            }
            row.addView(FrameLayout(context).apply {
                background = preview(value, density)
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(40)).apply { marginEnd = dp(16) }
            })
            row.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(context).apply {
                    text = PrefsManager.visualThemeLabel(value)
                    textSize = 17f
                    setTextColor(Color.WHITE)
                    if (selected) setTypeface(typeface, Typeface.BOLD)
                })
                addView(TextView(context).apply {
                    text = description(value)
                    textSize = 13f
                    setTextColor(0xB3FFFFFF.toInt())
                })
            })
            row.addView(TextView(context).apply {
                text = if (selected) "✓" else ""
                textSize = 20f
                setTextColor(focusAccent)
                setPadding(dp(12), 0, 0, 0)
            })
            panel.addView(row)
            if (selected) currentRow = row
        }

        dialog.setContentView(panel)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val screenWidth = context.resources.displayMetrics.widthPixels
            setLayout(minOf((screenWidth * 0.9f).toInt(), dp(520)), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.setOnShowListener { currentRow?.requestFocus() }
        dialog.show()
    }

    private fun rowBackground(density: Float, accent: Int, selected: Boolean): Drawable {
        fun shape(fill: Int, stroke: Int, strokeDp: Float) = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = 14f * density
            setStroke((strokeDp * density).toInt().coerceAtLeast(1), stroke)
        }
        val idle = shape(if (selected) 0x22FFFFFF else 0x10FFFFFF, if (selected) 0x40FFFFFF else 0x14FFFFFF, 1f)
        val active = shape(0x30FFFFFF, accent, 2f)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), active)
            addState(intArrayOf(android.R.attr.state_pressed), active)
            addState(intArrayOf(), idle)
        }
    }

    /** A tiny sketch of a card in each theme, over sample artwork colours. */
    private fun preview(value: String, density: Float): Drawable {
        val radius = 8f * density
        val stroke = (1f * density).toInt().coerceAtLeast(1)
        val artwork = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFFE3672C.toInt(), 0xFF8E2A6E.toInt(), 0xFF27306E.toInt())
        ).apply { cornerRadius = radius }
        return when (value) {
            PrefsManager.THEME_SIMPLE -> GradientDrawable().apply {
                setColor(0xFF23262E.toInt())
                cornerRadius = radius
                setStroke(stroke, 0x40FFFFFF)
            }
            PrefsManager.THEME_FROSTED -> LayerDrawable(arrayOf(
                artwork,
                GradientDrawable().apply {
                    setColor(0x8CE8ECF5.toInt())
                    cornerRadius = radius
                }
            ))
            PrefsManager.THEME_EXTREME -> LayerDrawable(arrayOf(
                artwork,
                GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0x66FFFFFF, 0x12FFFFFF, 0x00FFFFFF)
                ).apply {
                    cornerRadius = radius
                    setStroke(stroke, 0xB0FFFFFF.toInt())
                }
            ))
            else -> LayerDrawable(arrayOf(
                GradientDrawable().apply {
                    setColor(0xFF0B0E22.toInt())
                    cornerRadius = radius
                },
                GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = radius
                    setStroke(stroke * 2, NEON_CYAN)
                }
            ))
        }
    }
}
