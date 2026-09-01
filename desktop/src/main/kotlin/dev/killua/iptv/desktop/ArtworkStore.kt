package dev.killua.iptv.desktop

import java.io.File
import java.security.MessageDigest

/**
 * Posters kept between launches.
 *
 * The memory cache in [ArtworkLoader] is bounded at a few hundred images and dies with the process,
 * so every launch re-fetched the same grid of posters over the same connection the video wants. This
 * is the disk half: a third disposable sidecar, alongside the title cache and the preferences, in
 * the same directory and under the same rule — deleting it costs a few seconds of re-fetching and
 * nothing else.
 *
 * **A file is named by the hash of its URL**, not by the URL. Partly because a URL is not a
 * filename, and partly because provider artwork is sometimes served from the provider's own host: a
 * directory listing full of readable URLs would put that host on disk in plain text for no reason.
 * Authenticated media paths never reach here at all — only the `logoUrl`, `posterUrl` and `stillUrl`
 * fields of a listing do.
 *
 * **Bounded by total bytes**, pruned oldest-first. A six-figure library would otherwise fill a disk
 * quietly over months, which is the kind of bug that gets discovered by something else failing.
 */
class ArtworkStore(
    private val directory: File,
    /**
     * Injected so the eviction order can be tested at all.
     *
     * Reads happen microseconds apart, and file timestamps have neither the resolution nor the
     * guarantees to tell two of them apart; a test that relied on the wall clock would pass or fail
     * by luck. With the clock handed in, "least recently read" becomes something that can be stated.
     */
    private val now: () -> Long = System::currentTimeMillis,
) {

    fun read(url: String): ByteArray? = runCatching {
        val file = fileFor(url)
        if (!file.isFile) return null
        // Touched on every read, so pruning drops what has not been looked at rather than what
        // happens to have been downloaded longest ago.
        file.setLastModified(now())
        file.readBytes().takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun write(url: String, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        // Same rule as the state file: a half-written image would decode to nothing and then be
        // cached as a failure for the rest of the session.
        writeAtomically(directory, keyOf(url)) { it.writeBytes(bytes) }?.setLastModified(now())
    }

    /**
     * Drops the least recently read files until the directory fits in [maxBytes].
     *
     * Returns the number of bytes held afterwards, which is also what the settings screen reports.
     */
    fun prune(maxBytes: Long = MAX_BYTES): Long {
        val files = directory.listFiles()?.filter { it.isFile }.orEmpty()
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return total
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= maxBytes) return total
            val size = file.length()
            if (file.delete()) total -= size
        }
        return total
    }

    /** What the cache currently occupies, for the settings screen. */
    fun size(): Long = directory.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    /** Everything, on request. The next grid simply fetches again. */
    fun clear() {
        directory.listFiles()?.forEach { it.delete() }
    }

    private fun fileFor(url: String) = File(directory, keyOf(url))

    private companion object {
        /** 200MB. A category of posters is a few megabytes; a year of browsing is not two hundred. */
        const val MAX_BYTES = 200L * 1024L * 1024L

        fun keyOf(url: String): String = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
