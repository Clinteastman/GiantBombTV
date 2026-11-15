package com.giantbomb.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Neon lettering must hand the renderer the same outline object every time a
 * glyph is drawn at the same size. A fresh outline per draw gives each glyph a
 * new identity, so the GPU path caches never hit and fill up instead.
 */
class GlyphOutlineCacheTest {

    @Test
    fun sameGlyphAndSizeReusesTheOutline() {
        var builds = 0
        val cache = GlyphOutlineCache<Any>(maxEntries = 8)
        val first = cache.get('A', 3f) { builds++; Any() }
        val second = cache.get('A', 3f) { builds++; Any() }
        assertSame(first, second)
        assertEquals(1, builds)
    }

    @Test
    fun differentGlyphOrSizeBuildsItsOwnOutline() {
        val cache = GlyphOutlineCache<Any>(maxEntries = 8)
        val a = cache.get('A', 3f) { Any() }
        assertNotSame(a, cache.get('B', 3f) { Any() })
        assertNotSame(a, cache.get('A', 4f) { Any() })
        assertEquals(3, cache.size)
    }

    @Test
    fun differentGlowRadiusBuildsItsOwnEntry() {
        val cache = GlyphOutlineCache<Any>(maxEntries = 8)
        val plain = cache.get('A', 3f, variant = 24) { Any() }
        assertSame(plain, cache.get('A', 3f, variant = 24) { Any() })
        assertNotSame(plain, cache.get('A', 3f, variant = 40) { Any() })
    }

    @Test
    fun staysWithinItsLimit() {
        val cache = GlyphOutlineCache<Any>(maxEntries = 4)
        for (size in 1..20) cache.get('A', size.toFloat()) { Any() }
        assertEquals(true, cache.size <= 4)
    }
}
