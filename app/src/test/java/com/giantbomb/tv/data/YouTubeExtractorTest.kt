package com.giantbomb.tv.data

import org.junit.Assert.*
import org.junit.Test

class YouTubeExtractorTest {

    // --- extractVideoId ---

    @Test
    fun `extractVideoId - bare 11-char ID`() {
        assertEquals("dQw4w9WgXcQ", YouTubeExtractor.extractVideoId("dQw4w9WgXcQ"))
    }

    @Test
    fun `extractVideoId - standard watch URL`() {
        assertEquals("dQw4w9WgXcQ", YouTubeExtractor.extractVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test
    fun `extractVideoId - watch URL with extra params`() {
        assertEquals("dQw4w9WgXcQ", YouTubeExtractor.extractVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s"))
    }

    @Test
    fun `extractVideoId - short youtu-be URL`() {
        assertEquals("dQw4w9WgXcQ", YouTubeExtractor.extractVideoId("https://youtu.be/dQw4w9WgXcQ"))
    }

    @Test
    fun `extractVideoId - embed URL`() {
        assertEquals("dQw4w9WgXcQ", YouTubeExtractor.extractVideoId("https://www.youtube.com/embed/dQw4w9WgXcQ"))
    }

    @Test
    fun `extractVideoId - shorts URL`() {
        assertEquals("dQw4w9WgXcQ", YouTubeExtractor.extractVideoId("https://www.youtube.com/shorts/dQw4w9WgXcQ"))
    }

    @Test
    fun `extractVideoId - live URL`() {
        assertEquals("dQw4w9WgXcQ", YouTubeExtractor.extractVideoId("https://www.youtube.com/live/dQw4w9WgXcQ"))
    }

    @Test
    fun `extractVideoId - v path URL`() {
        assertEquals("dQw4w9WgXcQ", YouTubeExtractor.extractVideoId("https://www.youtube.com/v/dQw4w9WgXcQ"))
    }

    @Test
    fun `extractVideoId - invalid URL returns null`() {
        assertNull(YouTubeExtractor.extractVideoId("https://example.com/not-youtube"))
    }

    @Test
    fun `extractVideoId - empty string returns null`() {
        assertNull(YouTubeExtractor.extractVideoId(""))
    }

    @Test
    fun `extractVideoId - too short ID returns null`() {
        assertNull(YouTubeExtractor.extractVideoId("abc"))
    }

    @Test
    fun `extractVideoId - ID with hyphens and underscores`() {
        assertEquals("a-B_c1d2E3f", YouTubeExtractor.extractVideoId("a-B_c1d2E3f"))
    }

    // --- StreamInfo data class ---

    @Test
    fun `StreamInfo - muxed stream has both audio and video`() {
        val stream = YouTubeExtractor.StreamInfo(
            url = "https://example.com/stream",
            mimeType = "video/mp4",
            quality = "medium",
            qualityLabel = "360p",
            width = 640,
            height = 360,
            bitrate = 500000,
            isAdaptive = false,
            hasAudio = true,
            hasVideo = true
        )
        assertTrue(stream.hasAudio)
        assertTrue(stream.hasVideo)
        assertFalse(stream.isAdaptive)
    }

    @Test
    fun `StreamInfo - adaptive video-only stream`() {
        val stream = YouTubeExtractor.StreamInfo(
            url = "https://example.com/stream",
            mimeType = "video/webm",
            quality = "hd1080",
            qualityLabel = "1080p",
            width = 1920,
            height = 1080,
            bitrate = 2500000,
            isAdaptive = true,
            hasAudio = false,
            hasVideo = true
        )
        assertFalse(stream.hasAudio)
        assertTrue(stream.hasVideo)
        assertTrue(stream.isAdaptive)
    }
}
