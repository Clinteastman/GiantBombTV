package com.giantbomb.tv.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The home row on a 1080p TV (density 2, so 1 dp = 2 px): each card's outer
 * view is the 320 dp card plus 8 dp padding each side (336 dp), the row puts
 * 8 dp between outer views and starts 56 dp in. So the visible cards sit 24 dp
 * apart. The 1.10 lift must keep the visible (ringed) card inside the 5%
 * overscan band and leave a clear gap to each unlifted neighbour.
 */
class CardFocusPivotTest {

    private val dp = 2f
    private val screenW = 1920
    private val safe = 0.05f * screenW
    private val scale = CardFocus.FOCUSED_SCALE
    private val pad = (8 * dp).toInt()
    private val outerW = (336 * dp).toInt()
    private val pitch = (344 * dp)
    private val minGap = 6 * dp

    /** Visible card edges (screen px) after lifting the outer view at [outerLeft]. */
    private fun lifted(outerLeft: Float): Pair<Float, Float> {
        val pivot = CardFocus.liftPivotX(outerLeft, outerW, pad, pad, scale, screenW, safe)
        val visLeft = outerLeft + pivot + (pad - pivot) * scale
        val visRight = outerLeft + pivot + (outerW - pad - pivot) * scale
        return visLeft to visRight
    }

    private fun check(name: String, outerLeft: Float) {
        val (visLeft, visRight) = lifted(outerLeft)
        val leftNeighbourRight = outerLeft - pitch + outerW - pad
        val rightNeighbourLeft = outerLeft + pitch + pad
        val eps = 0.01f
        assertTrue("$name: left edge $visLeft inside overscan band $safe", visLeft >= safe - eps)
        assertTrue("$name: right edge $visRight inside overscan band", visRight <= screenW - safe + eps)
        assertTrue(
            "$name: gap to left neighbour ${(visLeft - leftNeighbourRight) / dp} dp",
            visLeft - leftNeighbourRight >= minGap
        )
        assertTrue(
            "$name: gap to right neighbour ${(rightNeighbourLeft - visRight) / dp} dp",
            rightNeighbourLeft - visRight >= minGap
        )
    }

    @Test
    fun firstCardInRowKeepsGapAndSafeArea() = check("first", 56 * dp)

    @Test
    fun middleCardKeepsGapAndSafeArea() = check("middle", 56 * dp + pitch)

    /** Mirror of the first card: the visible card ends 16 dp short of the right band. */
    @Test
    fun lastWholeCardKeepsGapAndSafeArea() = check("last", screenW - 56 * dp - outerW)

    @Test
    fun cardWithoutPaddingStillKeepsSafeArea() {
        // Settings and show cards scale their own (unpadded) view.
        val w = (300 * dp).toInt()
        val left = 50 * dp
        val pivot = CardFocus.liftPivotX(left, w, 0, 0, scale, screenW, safe)
        assertTrue(left + pivot - pivot * scale >= safe - 0.01f)
    }
}
