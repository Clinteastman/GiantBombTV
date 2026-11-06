package com.giantbomb.tv.ui

import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import com.giantbomb.tv.model.Membership
import com.giantbomb.tv.model.Video

/**
 * Badge text and colours for paid videos. Premium keeps its gold; Premium 64
 * (API tier "supporter") gets violet so the two read apart at a glance in
 * every theme. The colours only feed the existing pill and glass accent
 * paths, so each theme draws them the same way it draws Premium.
 */
object TierBadge {
    enum class Kind { NONE, PREMIUM, PREMIUM_64 }

    const val PREMIUM_LABEL = "PREMIUM"
    const val PREMIUM_64_LABEL = "PREMIUM 64"

    // TV and detail pills.
    const val PREMIUM_PILL = 0xCCFFD700.toInt()
    const val PREMIUM_PILL_TEXT = 0xFF1A1A1A.toInt()
    const val PREMIUM_64_PILL = 0xDD7A4DFF.toInt()
    const val PREMIUM_64_PILL_TEXT = 0xFFFFFFFF.toInt()

    // Phone cards: glass tint plus a plain label.
    const val PREMIUM_GLASS_TINT = 0xFFFFB436.toInt()
    const val PREMIUM_64_GLASS_TINT = 0xFF8A63FF.toInt()
    const val PREMIUM_LABEL_TEXT = 0xFFFFFFF4.toInt()
    const val PREMIUM_64_LABEL_TEXT = 0xFFE6DCFF.toInt()

    fun kindOf(video: Video): Kind = when {
        Membership.isPremium64(video.requiredTier) -> Kind.PREMIUM_64
        video.premium -> Kind.PREMIUM
        else -> Kind.NONE
    }

    fun label(kind: Kind): String =
        if (kind == Kind.PREMIUM_64) PREMIUM_64_LABEL else PREMIUM_LABEL

    /** Phone glass accent for a card, or null for a free video. */
    fun glassTint(kind: Kind): Int? = when (kind) {
        Kind.PREMIUM_64 -> PREMIUM_64_GLASS_TINT
        Kind.PREMIUM -> PREMIUM_GLASS_TINT
        Kind.NONE -> null
    }

    fun labelTextColor(kind: Kind): Int =
        if (kind == Kind.PREMIUM_64) PREMIUM_64_LABEL_TEXT else PREMIUM_LABEL_TEXT

    fun pillTextColor(kind: Kind): Int =
        if (kind == Kind.PREMIUM_64) PREMIUM_64_PILL_TEXT else PREMIUM_PILL_TEXT

    /** Rounded pill fill with the same thin light edge the Premium pill uses. */
    fun pillBackground(kind: Kind, density: Float): Drawable = GradientDrawable().apply {
        setColor(if (kind == Kind.PREMIUM_64) PREMIUM_64_PILL else PREMIUM_PILL)
        cornerRadius = 4f * density
        setStroke((0.5f * density).toInt(), 0x40FFFFFF)
    }
}
