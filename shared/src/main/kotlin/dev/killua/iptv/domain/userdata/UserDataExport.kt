package dev.killua.iptv.domain.userdata

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Locale

/**
 * Everything the provider cannot give back.
 *
 * Categories, titles and episodes are re-downloadable and deliberately absent - an export is not a
 * backup of the library, which is hundreds of megabytes and stale the moment it is written. What is
 * here is what only this device knows: where you stopped, what you marked, what you saved.
 *
 * **No credentials, ever.** Not the server, not the username, not the password. The account is
 * identified by [accountFingerprint], a one-way hash, so a file can be carried around, backed up or
 * handed to a second device without being worth anything to whoever finds it.
 *
 * Records carry no `accountId`. That column is a random UUID minted at login and means nothing on
 * another device; the fingerprint is what identifies the account across installations.
 */
@Serializable
data class UserDataExport(
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val exportedAtEpochMillis: Long,
    val accountFingerprint: String,
    val watchProgress: List<ProgressRecord> = emptyList(),
    val movieFavorites: List<MarkRecord> = emptyList(),
    val seriesFavorites: List<MarkRecord> = emptyList(),
    val watchlist: List<WatchlistRecord> = emptyList(),
    val recentChannels: List<MarkRecord> = emptyList(),
) {
    val recordCount: Int
        get() = watchProgress.size + movieFavorites.size + seriesFavorites.size +
            watchlist.size + recentChannels.size

    /**
     * The same total, split by kind, for a screen that shows what is stored.
     *
     * One number answers "is anything in there"; five answer "what is growing", which is the
     * question worth being able to ask of a file that is never pruned.
     */
    val recordCounts: Map<String, Int>
        get() = linkedMapOf(
            "Watch progress" to watchProgress.size,
            "Film favourites" to movieFavorites.size,
            "Series favourites" to seriesFavorites.size,
            "Saved" to watchlist.size,
            "Recent channels" to recentChannels.size,
        )
}

/**
 * One resumable position.
 *
 * [contentId] is the provider's own id, which is the reason any of this can travel: the same film or
 * episode carries the same id on every device pointed at the same provider. [updatedAtEpochMillis]
 * is what a merge would compare, so a newer position wins wherever it was watched.
 */
@Serializable
data class ProgressRecord(
    val contentType: String,
    val contentId: String,
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val updatedAtEpochMillis: Long,
    /**
     * Which series an episode belongs to, and null for everything else.
     *
     * Added because a client reading this file could not otherwise say what an episode *is*. A film
     * or a series can be looked up by its id in a listing the client already has; an episode cannot
     * — no Xtream listing indexes episodes, and finding one means asking `get_series_info` for every
     * series in the library. So a half-watched episode arrived on the other device as a number and
     * was simply not shown.
     *
     * Optional, and read by anything that has it: both codecs ignore unknown keys, so a file with
     * this field is still readable by a build that has never heard of it, and a file without it
     * still imports here. That is why the format version did not move.
     */
    val seriesId: String? = null,
)

/** A favourite or a recently watched channel: an id and when it happened. */
@Serializable
data class MarkRecord(
    val contentId: String,
    val atEpochMillis: Long,
)

@Serializable
data class WatchlistRecord(
    val contentType: String,
    val contentId: String,
    val addedAtEpochMillis: Long,
)

/** What reading an export file produced. A file this app did not write must fail, not half-load. */
sealed interface UserDataImportResult {
    data class Ok(val export: UserDataExport) : UserDataImportResult

    /** Written by a newer build. Refused rather than guessed at. */
    data class UnsupportedVersion(val found: Int) : UserDataImportResult

    data object NotAnExport : UserDataImportResult
}

/** Bumped whenever a field stops meaning what it used to. Adding an optional field does not. */
const val CURRENT_FORMAT_VERSION = 1

object UserDataExportCodec {
    private val json = Json {
        prettyPrint = true
        // A file from a newer build may carry fields this one has never heard of. Ignoring them is
        // what lets an older build still read the parts it does understand.
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(export: UserDataExport): String = json.encodeToString(export)

    fun decode(text: String): UserDataImportResult = try {
        val export = json.decodeFromString<UserDataExport>(text)
        when {
            export.accountFingerprint.isBlank() -> UserDataImportResult.NotAnExport
            export.formatVersion > CURRENT_FORMAT_VERSION ->
                UserDataImportResult.UnsupportedVersion(export.formatVersion)
            export.formatVersion < 1 -> UserDataImportResult.NotAnExport
            else -> UserDataImportResult.Ok(export)
        }
    } catch (_: SerializationException) {
        UserDataImportResult.NotAnExport
    } catch (_: IllegalArgumentException) {
        UserDataImportResult.NotAnExport
    }

    /**
     * A one-way identifier for the account, so an import can tell whether a file belongs to the
     * account it is being merged into.
     *
     * Built from host and username rather than the whole URL, because the same account reached over
     * `http` and `https` or with a trailing slash is the same account. The password is deliberately
     * not part of it: changing a password must not orphan an export.
     *
     * SHA-256 through `java.security.MessageDigest`, which is the Java standard library and so
     * available on Android and desktop. An iOS target would need a multiplatform hash here.
     */
    fun fingerprint(serverHost: String, username: String): String {
        val material = "${serverHost.trim().lowercase(Locale.ROOT)}|${username.trim()}"
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
