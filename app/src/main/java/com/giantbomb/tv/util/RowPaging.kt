package com.giantbomb.tv.util

/**
 * Next page of a TV show row.
 *
 * The videos endpoint pages by whole pages: it serves page
 * floor(offset / limit) + 1, so an offset that is not a multiple of the
 * limit snaps back to the start of its page. A show row loads a short
 * first screen, then full pages, so each request asks a page-aligned
 * offset and drops the videos the row already shows.
 */
object RowPaging {
    /** Ask [offset] (a multiple of the page size), then drop [drop] leading results. */
    data class Request(val offset: Int, val drop: Int)

    /** Videos to add, how far the row now reaches, and whether more may follow. */
    data class Step<T>(val items: List<T>, val offset: Int, val hasMore: Boolean)

    /** [shown] is how far the row reaches in the server's order. */
    fun next(shown: Int, pageSize: Int): Request {
        val offset = (shown / pageSize) * pageSize
        return Request(offset = offset, drop = shown - offset)
    }

    /**
     * Turns the server's [page] for [request] into the row's next step.
     * Skips items whose key is in [held], in case a new episode shifted the
     * order between requests. The row ends on a page shorter than [pageSize].
     */
    fun <T, K> take(request: Request, page: List<T>, pageSize: Int, held: Set<K>, key: (T) -> K): Step<T> {
        val seen = held.toMutableSet()
        val items = page.drop(request.drop).filter { seen.add(key(it)) }
        return Step(items, request.offset + page.size, page.size >= pageSize)
    }
}
