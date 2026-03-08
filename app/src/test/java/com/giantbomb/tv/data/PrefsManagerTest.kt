package com.giantbomb.tv.data

import org.junit.Assert.*
import org.junit.Test

class PrefsManagerTest {

    @Test
    fun `qualityLabel - auto returns Auto HLS`() {
        assertEquals("Auto (HLS)", PrefsManager.qualityLabel("auto"))
    }

    @Test
    fun `qualityLabel - specific quality returns as-is`() {
        assertEquals("1080p", PrefsManager.qualityLabel("1080p"))
        assertEquals("720p", PrefsManager.qualityLabel("720p"))
        assertEquals("480p", PrefsManager.qualityLabel("480p"))
        assertEquals("360p", PrefsManager.qualityLabel("360p"))
    }

    @Test
    fun `QUALITY_OPTIONS contains expected values`() {
        assertEquals(listOf("auto", "1080p", "720p", "480p", "360p"), PrefsManager.QUALITY_OPTIONS)
    }
}
