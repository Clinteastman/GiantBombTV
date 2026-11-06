package com.giantbomb.tv.model

/**
 * Giant Bomb membership tiers, as the public API names them.
 *
 * The API calls Premium 64 "supporter". A video's `required_tier` is either
 * "premium" or "supporter", and `/api/public/key-status` reports the key's
 * `tier` as "premium", "supporter" or null (free key). Playback only returns
 * the premium sources when the key holds the video's required tier, so a
 * normal Premium key gets no source at all for a Premium 64 video.
 */
object Membership {
    const val TIER_PREMIUM = "premium"
    const val TIER_SUPPORTER = "supporter"

    const val MESSAGE_NEEDS_PREMIUM_64 = "This video needs a Premium 64 membership."
    const val MESSAGE_NEEDS_PREMIUM = "This video needs a Giant Bomb Premium membership."
    const val MESSAGE_NO_SOURCE = "No playback source available"

    /** Why a video with no playable source can't be played. */
    enum class Block { NEEDS_PREMIUM_64, NEEDS_PREMIUM, NONE }

    /** A video's required tier. Missing or unknown values count as normal Premium. */
    fun parseRequiredTier(raw: String?): String =
        if (raw?.trim().equals(TIER_SUPPORTER, ignoreCase = true)) TIER_SUPPORTER else TIER_PREMIUM

    /** A key's tier: "premium", "supporter", or null for a free or unknown key. */
    fun parseAccountTier(raw: String?): String? = when (raw?.trim()?.lowercase()) {
        TIER_SUPPORTER -> TIER_SUPPORTER
        TIER_PREMIUM -> TIER_PREMIUM
        else -> null
    }

    /**
     * The tier to act on for the saved account. Installs from before tiers
     * were stored only have the is-premium flag, so they count as normal
     * Premium until the key is checked again.
     */
    fun effectiveAccountTier(storedTier: String?, isPremium: Boolean): String? =
        parseAccountTier(storedTier) ?: if (isPremium) TIER_PREMIUM else null

    fun isPremium64(requiredTier: String?): Boolean = parseRequiredTier(requiredTier) == TIER_SUPPORTER

    /**
     * Picks why a video came back with no playable source.
     *
     * [requiredTier] is the video's required tier, [videoIsPremium] its
     * premium flag, and [accountTier] the saved account's effective tier
     * (null for a free key or no key). [Block.NONE] means the account
     * should have had access, so the caller shows the generic error.
     */
    fun noSourceBlock(requiredTier: String?, videoIsPremium: Boolean, accountTier: String?): Block {
        val tier = parseAccountTier(accountTier)
        return when {
            isPremium64(requiredTier) && tier != TIER_SUPPORTER -> Block.NEEDS_PREMIUM_64
            videoIsPremium && tier == null -> Block.NEEDS_PREMIUM
            else -> Block.NONE
        }
    }

    /** Strictest of several reports of one video's required tier. */
    fun strictestTier(vararg tiers: String?): String =
        if (tiers.any { isPremium64(it) }) TIER_SUPPORTER else TIER_PREMIUM

    /**
     * Why [playback] of [video] can't be played, or [Block.NONE] when it has
     * a source or the account should have had one. Either the list entry or
     * the playback response marking it Premium 64 is enough.
     */
    fun playbackBlock(playback: PlaybackInfo, video: Video, accountTier: String?): Block {
        if (!playback.hasNoSource) return Block.NONE
        return noSourceBlock(
            strictestTier(playback.requiredTier, video.requiredTier),
            video.premium,
            accountTier
        )
    }

    /** Short dialog title for a block. */
    fun title(block: Block): String = when (block) {
        Block.NEEDS_PREMIUM_64 -> "Premium 64 required"
        Block.NEEDS_PREMIUM -> "Premium required"
        Block.NONE -> "Can't play this video"
    }

    fun message(block: Block): String = when (block) {
        Block.NEEDS_PREMIUM_64 -> MESSAGE_NEEDS_PREMIUM_64
        Block.NEEDS_PREMIUM -> MESSAGE_NEEDS_PREMIUM
        Block.NONE -> MESSAGE_NO_SOURCE
    }
}
