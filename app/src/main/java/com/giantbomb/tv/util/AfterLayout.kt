package com.giantbomb.tv.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * A scope for work that is started from a Leanback item-selected listener
 * and then changes a row adapter.
 *
 * Leanback calls that listener in the middle of a RecyclerView layout pass,
 * and changing an adapter there throws "Cannot call this method while
 * RecyclerView is computing a layout or scrolling". lifecycleScope runs on
 * Dispatchers.Main.immediate, which runs a new coroutine inline until it
 * first suspends, so a page answered from the cache changed the adapter
 * inside the listener (crash on Back to TV home, 13 Nov 2026).
 *
 * The returned scope keeps [scope]'s Job, so the work still stops with the
 * view, but runs on [main]. The plain main dispatcher always posts, so the
 * work starts on a later turn of the main loop, after the layout pass has
 * returned.
 */
fun afterLayoutScope(
    scope: CoroutineScope,
    main: CoroutineDispatcher = Dispatchers.Main
): CoroutineScope = CoroutineScope(scope.coroutineContext + main)
