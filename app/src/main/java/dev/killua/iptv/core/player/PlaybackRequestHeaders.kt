package dev.killua.iptv.core.player

import android.os.Bundle
import androidx.media3.common.MediaItem
import dev.killua.iptv.domain.model.StreamHeaders

/**
 * Carrying a playlist channel's request headers to the player, which is in another process.
 *
 * On the desktop these were two strings handed to the libvlc media instance and that was the whole
 * of it. Media3 will not do that: the player runs behind a `MediaSession` in its own service, a
 * `MediaItem` has no notion of request headers, and headers belong to a `DataSource` which the
 * service builds. So they travel in `MediaItem.RequestMetadata.extras` - a `Bundle`, which is what
 * survives the boundary - and are turned back into a `DataSource` on the other side.
 *
 * Only a playlist channel has any. An Xtream stream is authorised by the credentials already inside
 * its URL, so its items carry nothing and its path is unchanged.
 */
object PlaybackRequestHeaders {
    private const val USER_AGENT = "dev.killua.iptv.userAgent"
    private const val REFERRER = "dev.killua.iptv.referrer"

    /** Null when there is nothing to carry, so an Xtream item gets no extras at all. */
    fun toExtras(headers: StreamHeaders?): Bundle? {
        if (headers == null || headers.isEmpty) return null
        return Bundle().apply {
            headers.userAgent?.let { putString(USER_AGENT, it) }
            headers.referrer?.let { putString(REFERRER, it) }
        }
    }

    /**
     * What the service should put on the request, or null where the item named nothing.
     *
     * Reading a `Bundle` that crossed a process boundary is a place to be careful rather than
     * clever: a malformed one throws on access, and the honest answer to that is to play without
     * the headers rather than to fail the whole item.
     */
    fun fromItem(item: MediaItem): StreamHeaders? {
        val extras = runCatching { item.requestMetadata.extras }.getOrNull() ?: return null
        val agent = runCatching { extras.getString(USER_AGENT) }.getOrNull()
        val referrer = runCatching { extras.getString(REFERRER) }.getOrNull()
        return StreamHeaders(agent, referrer).takeUnless { it.isEmpty }
    }
}
