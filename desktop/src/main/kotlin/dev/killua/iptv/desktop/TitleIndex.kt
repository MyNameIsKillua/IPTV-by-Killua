package dev.killua.iptv.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.killua.iptv.core.text.withoutByteOrderMark
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Names and artwork for the things this client has marked.
 *
 * The state file stores only provider ids, deliberately: it is the export format, it is
 * interchangeable with the phone, and it should stay that way. But an id alone cannot be shown, so
 * a favourites list needs somewhere to look up what "501" was called.
 *
 * This is that somewhere, and it is a **cache beside the state rather than part of it**. Adding a
 * title field to the export would mean the phone writing files this client understands only half of,
 * and the reverse; keeping it separate means the format stays exactly what it is and this file can
 * be deleted at any time with nothing worse than a few tiles losing their captions until they are
 * seen again.
 *
 * Only marked or played items are recorded. Indexing everything browsed would grow without limit
 * across a six-figure library, and nothing else ever needs a name looked up.
 */
class TitleIndex(private val directory: File = DesktopUserData.defaultDirectory()) {

    private val file: File get() = File(directory, "titles.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /**
     * The names known for [fingerprint], or none.
     *
     * Scoped by account for the same reason the state file is: the provider numbers each library
     * from one, so id 501 is a different film on a different account. An unscoped cache would put
     * one account's captions under another's saved list — a small wrongness, but a confusing one,
     * and it survives until something happens to overwrite it.
     */
    suspend fun load(fingerprint: String): Map<String, IndexedTitle> = withContext(Dispatchers.IO) {
        runCatching {
            file.takeIf { it.isFile }?.readText()
                ?.let { json.decodeFromString<IndexedTitles>(it.withoutByteOrderMark()) }
                ?.takeIf { it.accountFingerprint == fingerprint }
                ?.entries
        }.getOrNull().orEmpty()
    }

    suspend fun save(
        fingerprint: String,
        entries: Map<String, IndexedTitle>,
    ) = withContext(Dispatchers.IO) {
        writeAtomically(directory, "titles.json") {
            it.writeText(json.encodeToString(IndexedTitles(fingerprint, entries)))
        }
        Unit
    }

    companion object {
        /** `movie:501`. The same key shape the export uses for a progress row. */
        fun keyOf(contentType: String, id: String) = "$contentType:$id"
    }
}

/**
 * The file's shape: whose names these are, and the names.
 *
 * The fingerprint is the same one-way hash the export format uses — no host, no username, no
 * password — and it is here so that a cache can be recognised as belonging to someone else rather
 * than silently believed.
 */
@Serializable
private data class IndexedTitles(
    val accountFingerprint: String,
    val entries: Map<String, IndexedTitle> = emptyMap(),
)

@Serializable
data class IndexedTitle(
    val label: String,
    val artworkUrl: String? = null,
    /**
     * The container the provider serves this title in.
     *
     * Cached because it is needed to build the stream URL, and a marked title has to be playable
     * from the saved list without first walking back through the category it came from.
     */
    val containerExtension: String? = null,
)
