package dev.killua.iptv.data.repository

import dev.killua.iptv.domain.model.LiveChannel
import dev.killua.iptv.domain.model.LiveCategory
import dev.killua.iptv.domain.model.XtreamCredentials

/**
 * Where a live listing comes from, as the only thing the refresh needs to know about it.
 *
 * Two implementations: the Xtream API, and a playlist file. Everything above this - the cache, the
 * transaction, paging, filtering, search - never learns which one it got, because both hand over
 * the same [LiveChannel] and the same guarantees about it.
 *
 * The desktop client reached the same shape from the other direction and calls it `LibraryReader`.
 * Naming them differently is deliberate: this one is about a *live* listing only, because that is
 * all Android needs a seam for - films and series have no playlist equivalent to abstract over.
 */
interface LiveListingSource {
    /**
     * The categories the source lists up front, which a playlist has none of.
     *
     * An M3U keeps its grouping *inside* the entries - `group-title` on each line - so a playlist
     * cannot answer this without reading the whole file, and reading it twice for a six-figure
     * listing is a download nobody asked for. It returns nothing, and the refresh collects the
     * groups from the channels as they stream past instead.
     */
    suspend fun liveCategories(credentials: XtreamCredentials): List<LiveCategory>

    /**
     * The whole listing, an item at a time, while the response is still open.
     *
     * [block] must do what it needs with the sequence before returning: leaving it closes the
     * response. Stopping early is allowed and abandons the rest of the download.
     */
    suspend fun <T> withLiveChannels(
        credentials: XtreamCredentials,
        block: suspend (Sequence<LiveChannel>) -> T,
    ): T
}
