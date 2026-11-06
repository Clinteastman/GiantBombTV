package com.giantbomb.tv.model

import com.giantbomb.tv.model.Membership.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MembershipTest {

    private fun video(premium: Boolean, requiredTier: String = Membership.TIER_PREMIUM) = Video(
        id = 1, slug = "v", title = "Video", description = null, publishDate = "",
        posterUrl = null, premium = premium, showId = null, showTitle = null,
        author = null, thumbnailUrl = null, requiredTier = requiredTier
    )

    private fun playback(
        requiredTier: String = Membership.TIER_PREMIUM,
        hlsUrl: String? = null,
        mp4s: List<Mp4Source> = emptyList(),
        youtubeUrl: String? = null
    ) = PlaybackInfo(
        videoId = 1, title = "Video", hlsUrl = hlsUrl, mp4s = mp4s, duration = 0.0,
        posterUrl = null, youtubeUrl = youtubeUrl, requiredTier = requiredTier
    )

    // --- parsing ---

    @Test
    fun `required tier - missing, blank and unknown default to premium`() {
        assertEquals("premium", Membership.parseRequiredTier(null))
        assertEquals("premium", Membership.parseRequiredTier(""))
        assertEquals("premium", Membership.parseRequiredTier("gold"))
        assertEquals("premium", Membership.parseRequiredTier("premium"))
    }

    @Test
    fun `required tier - supporter in any case`() {
        assertEquals("supporter", Membership.parseRequiredTier("supporter"))
        assertEquals("supporter", Membership.parseRequiredTier(" Supporter "))
        assertTrue(Membership.isPremium64("SUPPORTER"))
        assertFalse(Membership.isPremium64(null))
    }

    @Test
    fun `account tier - premium, supporter or null`() {
        assertEquals("premium", Membership.parseAccountTier("premium"))
        assertEquals("supporter", Membership.parseAccountTier("Supporter"))
        assertNull(Membership.parseAccountTier(null))
        assertNull(Membership.parseAccountTier(""))
        assertNull(Membership.parseAccountTier("platinum"))
    }

    @Test
    fun `effective tier - older installs fall back to is-premium`() {
        assertEquals("premium", Membership.effectiveAccountTier(null, isPremium = true))
        assertNull(Membership.effectiveAccountTier(null, isPremium = false))
        assertEquals("supporter", Membership.effectiveAccountTier("supporter", isPremium = true))
        // A stored tier wins over the older flag.
        assertEquals("premium", Membership.effectiveAccountTier("premium", isPremium = false))
    }

    // --- message choice ---

    @Test
    fun `Premium 64 video - normal Premium account needs Premium 64`() {
        assertEquals(Block.NEEDS_PREMIUM_64, Membership.noSourceBlock("supporter", true, "premium"))
    }

    @Test
    fun `Premium 64 video - free or no key needs Premium 64`() {
        assertEquals(Block.NEEDS_PREMIUM_64, Membership.noSourceBlock("supporter", true, null))
    }

    @Test
    fun `Premium 64 video - Premium 64 account gets the generic error`() {
        assertEquals(Block.NONE, Membership.noSourceBlock("supporter", true, "supporter"))
    }

    @Test
    fun `Premium video - free or no key needs Premium`() {
        assertEquals(Block.NEEDS_PREMIUM, Membership.noSourceBlock("premium", true, null))
        assertEquals(Block.NEEDS_PREMIUM, Membership.noSourceBlock(null, true, null))
    }

    @Test
    fun `Premium video - paid account gets the generic error`() {
        assertEquals(Block.NONE, Membership.noSourceBlock("premium", true, "premium"))
        assertEquals(Block.NONE, Membership.noSourceBlock("premium", true, "supporter"))
    }

    @Test
    fun `free video with no source is a real failure`() {
        assertEquals(Block.NONE, Membership.noSourceBlock("premium", false, null))
    }

    @Test
    fun `messages`() {
        assertEquals("This video needs a Premium 64 membership.", Membership.message(Block.NEEDS_PREMIUM_64))
        assertEquals("This video needs a Giant Bomb Premium membership.", Membership.message(Block.NEEDS_PREMIUM))
        assertEquals("No playback source available", Membership.message(Block.NONE))
    }

    // --- playback block ---

    @Test
    fun `playback block - any source means no block`() {
        val v = video(premium = true, requiredTier = "supporter")
        assertEquals(Block.NONE, Membership.playbackBlock(playback("supporter", hlsUrl = "h"), v, "premium"))
        assertEquals(
            Block.NONE,
            Membership.playbackBlock(playback("supporter", mp4s = listOf(Mp4Source("u", 1, 1, ""))), v, null)
        )
        assertEquals(Block.NONE, Membership.playbackBlock(playback("supporter", youtubeUrl = "y"), v, null))
    }

    @Test
    fun `playback block - either source marking Premium 64 is enough`() {
        val listSaysP64 = video(premium = true, requiredTier = "supporter")
        val listSaysPremium = video(premium = true)
        assertEquals(Block.NEEDS_PREMIUM_64, Membership.playbackBlock(playback("premium"), listSaysP64, "premium"))
        assertEquals(Block.NEEDS_PREMIUM_64, Membership.playbackBlock(playback("supporter"), listSaysPremium, "premium"))
        assertEquals(Block.NONE, Membership.playbackBlock(playback("premium"), listSaysPremium, "premium"))
    }
}
