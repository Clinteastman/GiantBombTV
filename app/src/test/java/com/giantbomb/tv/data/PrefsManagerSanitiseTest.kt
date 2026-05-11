package com.giantbomb.tv.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * sanitiseSectionOrder() guards against upgrades silently losing user
 * preferences: a stored order from an old version that's missing newer
 * sections needs to gain them; unknown / typo'd IDs need to be dropped;
 * and duplicates (from any glitch in setSectionOrder) shouldn't blow up
 * the renderer.
 */
class PrefsManagerSanitiseTest {

    @Test
    fun nullStored_returnsDefaultOrder() {
        val result = PrefsManager.sanitiseSectionOrder(null)
        assertEquals(PrefsManager.DEFAULT_SECTION_ORDER, result)
    }

    @Test
    fun emptyStored_returnsDefaultOrder() {
        // Empty string splits into [""] which the filter drops, leaving us
        // with no parts and all defaults appended.
        val result = PrefsManager.sanitiseSectionOrder("")
        assertEquals(PrefsManager.DEFAULT_SECTION_ORDER, result)
    }

    @Test
    fun fullCustomOrder_isPreserved() {
        val custom = listOf(
            PrefsManager.SECTION_SETTINGS,
            PrefsManager.SECTION_LIVE,
            PrefsManager.SECTION_CONTINUE,
            PrefsManager.SECTION_WATCHLIST,
            PrefsManager.SECTION_RECENT,
            PrefsManager.SECTION_PINNED,
            PrefsManager.SECTION_ACTIVE_SHOWS,
            PrefsManager.SECTION_PREMIUM,
            PrefsManager.SECTION_LEGACY,
        )
        val result = PrefsManager.sanitiseSectionOrder(custom.joinToString(","))
        assertEquals(custom, result)
    }

    @Test
    fun unknownIdsAreDropped() {
        val stored = "${PrefsManager.SECTION_LIVE},bogus,${PrefsManager.SECTION_RECENT}"
        val result = PrefsManager.sanitiseSectionOrder(stored)
        // bogus is gone; the rest of the defaults come in after the two we kept.
        assertEquals(PrefsManager.SECTION_LIVE, result[0])
        assertEquals(PrefsManager.SECTION_RECENT, result[1])
        assertEquals(PrefsManager.ALL_SECTIONS, result.toSet())
    }

    @Test
    fun missingSectionsAreAppendedInDefaultOrder() {
        // Simulate an upgrade: an older version only knew about live + recent.
        // The newer defaults (continue, watchlist, etc.) must be appended so
        // the user doesn't lose them — and in the order they appear in
        // DEFAULT_SECTION_ORDER, not random.
        val stored = "${PrefsManager.SECTION_LIVE},${PrefsManager.SECTION_RECENT}"
        val result = PrefsManager.sanitiseSectionOrder(stored)
        assertEquals(PrefsManager.SECTION_LIVE, result[0])
        assertEquals(PrefsManager.SECTION_RECENT, result[1])
        // The remaining defaults follow in their canonical order, minus the two above.
        val expectedTail = PrefsManager.DEFAULT_SECTION_ORDER.filter {
            it != PrefsManager.SECTION_LIVE && it != PrefsManager.SECTION_RECENT
        }
        assertEquals(expectedTail, result.drop(2))
    }

    @Test
    fun duplicatesAreDeduplicated() {
        val stored = "${PrefsManager.SECTION_LIVE},${PrefsManager.SECTION_LIVE},${PrefsManager.SECTION_RECENT}"
        val result = PrefsManager.sanitiseSectionOrder(stored)
        assertEquals(1, result.count { it == PrefsManager.SECTION_LIVE })
        assertEquals(1, result.count { it == PrefsManager.SECTION_RECENT })
    }

    @Test
    fun emptyEntriesFromTrailingCommaAreDropped() {
        val stored = "${PrefsManager.SECTION_LIVE},,${PrefsManager.SECTION_RECENT},"
        val result = PrefsManager.sanitiseSectionOrder(stored)
        assertEquals(PrefsManager.ALL_SECTIONS, result.toSet())
    }
}
