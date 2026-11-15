package androidx.leanback.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs against the real Leanback classes. The scale fight itself can only be
 * seen on a device; this pins down the two facts the fix rests on.
 */
class ItemFocusHighlightOffTest {

    @Test
    fun zoomFactorNoneStillInstallsLeanbacksFocusAnimator() {
        // What ListRowPresenter and VerticalGridPresenter do with
        // ZOOM_FACTOR_NONE and no dimmer. If a Leanback upgrade stops doing
        // this, removeItemFocusHighlight is no longer needed.
        val adapter = ItemBridgeAdapter()
        FocusHighlightHelper.setupBrowseItemFocusHighlight(adapter, FocusHighlight.ZOOM_FACTOR_NONE, false)
        assertTrue(adapter.hasItemFocusHighlight)
    }

    @Test
    fun removeItemFocusHighlightLeavesNothingToScaleTheCards() {
        val adapter = ItemBridgeAdapter()
        FocusHighlightHelper.setupBrowseItemFocusHighlight(adapter, FocusHighlight.ZOOM_FACTOR_NONE, false)
        adapter.removeItemFocusHighlight()
        assertFalse(adapter.hasItemFocusHighlight)
    }
}
