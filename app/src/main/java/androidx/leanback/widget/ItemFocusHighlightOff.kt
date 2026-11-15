package androidx.leanback.widget

/*
 * Leanback's item focus highlight, switched off for rows and grids whose
 * cards lift themselves (com.giantbomb.tv.ui.CardFocus).
 *
 * Leanback 1.0.0 installs the highlight even with FocusHighlight.ZOOM_FACTOR_NONE
 * and no dimmer. On every focus change it then animates the card's scale
 * towards 1.0 for 150 ms, on the same view CardFocus lifts to 1.10 over the
 * same 150 ms, and whichever animator wrote last won. The setter and the
 * grid presenter's adapter field are package-private, so this file sits in
 * Leanback's package. Kept internal to the app module.
 */

/** Stops this adapter's items getting Leanback's focus zoom (and its setSelected). */
internal fun ItemBridgeAdapter.removeItemFocusHighlight() {
    setFocusHighlight(null)
}

/** True while Leanback's focus highlight is installed on this adapter. */
internal val ItemBridgeAdapter.hasItemFocusHighlight: Boolean
    get() = mFocusHighlight != null

/** The adapter behind a grid's cards (ListRowPresenter.ViewHolder has a public getter; this one does not). */
internal val VerticalGridPresenter.ViewHolder.itemBridgeAdapter: ItemBridgeAdapter
    get() = mItemBridgeAdapter
