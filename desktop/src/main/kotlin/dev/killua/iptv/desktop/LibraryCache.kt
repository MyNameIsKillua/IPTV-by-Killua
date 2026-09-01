package dev.killua.iptv.desktop

import dev.killua.iptv.domain.model.LiveChannel
import dev.killua.iptv.domain.model.MovieSummary
import dev.killua.iptv.domain.model.SeriesSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File

/**
 * The library, kept between launches so that opening the client is not a wait.
 *
 * This is the one thing in this module that was deliberately absent and has now been asked for. The
 * reasoning that kept it out is still worth stating, because it is what shapes what this is:
 *
 * - **It is not a database.** There is no schema to migrate and nothing reconciles it against the
 *   provider. A file that cannot be read, or that was written by another version, is **deleted**
 *   rather than repaired — the client then reads the library again, which is what it did before this
 *   file existed. That is the whole of the recovery story and it is why this can stay simple.
 * - **It is a copy of a listing, not of an account.** No credential is written, and the two ways one
 *   could sneak in are closed on the way out rather than hoped about: a channel's `directSource` is
 *   dropped entirely, because providers do sometimes populate it with a full authenticated URL, and
 *   any artwork address that contains the account's own username or password is dropped too. Neither
 *   is used to play anything — every stream URL is built fresh by the shared factory.
 * - **It is per account**, keyed by the same one-way fingerprint the state file uses, so two
 *   providers on one machine cannot see each other's libraries and neither is ever merged.
 *
 * What it costs is disk: a six-figure library is tens of megabytes. What it buys is the difference
 * between a client that is usable on the second launch and one that spends minutes reading first.
 */
class LibraryCache(private val directory: File = DesktopUserData.defaultDirectory()) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun fileFor(fingerprint: String) = File(directory, "library-$fingerprint.json")

    /**
     * What was kept for this account, or null when there is nothing usable.
     *
     * [maxAgeMillis] of zero means "however old it is" — the viewer has said they will ask for a
     * refresh themselves.
     */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun load(fingerprint: String, maxAgeMillis: Long): CachedLibrary? =
        withContext(Dispatchers.IO) {
            val file = fileFor(fingerprint).takeIf { it.isFile } ?: return@withContext null
            val document = runCatching {
                BufferedInputStream(file.inputStream()).use { json.decodeFromStream<CachedLibrary>(it) }
            }.getOrNull()
            if (document == null || document.version != FORMAT_VERSION) {
                // Unreadable, or from a version that meant something else by it. Reading the library
                // again is always available, so the file is simply not worth keeping.
                forget(fingerprint)
                return@withContext null
            }
            val age = System.currentTimeMillis() - document.savedAtEpochMillis
            if (maxAgeMillis > 0L && age > maxAgeMillis) null else document
        }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun save(fingerprint: String, index: LibraryIndex, credentials: SecretsToStrip): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val document = CachedLibrary(
                    savedAtEpochMillis = System.currentTimeMillis(),
                    channels = index.channels.map { it.cached(credentials) },
                    movies = index.movies.map { it.cached(credentials) },
                    series = index.series.map { it.cached(credentials) },
                    loaded = index.loaded.map { it.name },
                    truncated = index.truncated.map { it.name },
                )
                writeAtomically(directory, fileFor(fingerprint).name) { target ->
                    BufferedOutputStream(target.outputStream()).use { json.encodeToStream(document, it) }
                }
                true
            }.getOrDefault(false)
        }

    fun forget(fingerprint: String) {
        runCatching { fileFor(fingerprint).delete() }
    }

    /** Every account's copy, for the button that clears them all. */
    fun forgetAll() {
        runCatching {
            directory.listFiles { file -> file.name.startsWith("library-") }?.forEach { it.delete() }
        }
    }

    suspend fun bytesOnDisk(): Long = withContext(Dispatchers.IO) {
        runCatching {
            directory.listFiles { file -> file.name.startsWith("library-") }
                ?.sumOf { it.length() } ?: 0L
        }.getOrDefault(0L)
    }

    companion object {
        /**
         * Bumped when the shape changes, which is what makes an old file a deleted file rather than
         * a migration.
         */
        const val FORMAT_VERSION = 1
    }
}

/**
 * The two strings that must never reach the disk, so a URL carrying either can be recognised.
 *
 * Held as the account's own username and password rather than as a pattern, because a pattern would
 * have to guess at what a provider's authenticated URL looks like and these do not.
 */
data class SecretsToStrip(val username: String, val password: String) {
    fun cleans(url: String?): String? {
        val address = url?.takeIf { it.isNotBlank() } ?: return null
        val carriesAccount = (username.length >= 3 && address.contains(username)) ||
            (password.length >= 3 && address.contains(password))
        return address.takeUnless { carriesAccount }
    }
}

/** What was kept, and when. */
@Serializable
data class CachedLibrary(
    val version: Int = LibraryCache.FORMAT_VERSION,
    val savedAtEpochMillis: Long,
    val channels: List<CachedChannel> = emptyList(),
    val movies: List<CachedMovie> = emptyList(),
    val series: List<CachedSeries> = emptyList(),
    val loaded: List<String> = emptyList(),
    val truncated: List<String> = emptyList(),
) {
    /** The age in whole hours, for a screen that has to say how old this is. */
    fun ageMillis(now: Long = System.currentTimeMillis()): Long = now - savedAtEpochMillis

    fun asIndex(): LibraryIndex = LibraryIndex(
        channels = channels.map { it.asChannel() },
        movies = movies.map { it.asMovie() },
        series = series.map { it.asSeries() },
        loaded = loaded.mapNotNull { name -> LibraryKind.entries.firstOrNull { it.name == name } }
            .toSet(),
        truncated = truncated.mapNotNull { name -> LibraryKind.entries.firstOrNull { it.name == name } }
            .toSet(),
    )
}

@Serializable
data class CachedChannel(
    val id: String,
    val categoryId: String? = null,
    val name: String,
    val logoUrl: String? = null,
    val epgChannelId: String? = null,
    val containerExtension: String? = null,
    val providerOrder: Int = 0,
)

@Serializable
data class CachedMovie(
    val id: String,
    val categoryId: String? = null,
    val name: String,
    val posterUrl: String? = null,
    val containerExtension: String? = null,
    val rating: Double? = null,
    val releaseYear: Int? = null,
    val addedAtEpochSeconds: Long? = null,
    val providerOrder: Int = 0,
)

@Serializable
data class CachedSeries(
    val id: String,
    val categoryId: String? = null,
    val name: String,
    val posterUrl: String? = null,
    val rating: Double? = null,
    val releaseYear: Int? = null,
    val lastModifiedEpochSeconds: Long? = null,
    val providerOrder: Int = 0,
)

/**
 * The listing as it is written down — deliberately its own shape rather than the domain model's.
 *
 * Two reasons. The domain models belong to `:shared` and are read by the phone; giving them a
 * serialized form here would quietly turn them into a stored contract that nobody meant to promise.
 * And **`directSource` is not copied at all**: it is the one field a provider fills with a full
 * authenticated URL, nothing in this client plays from it, and a credential on disk is exactly what
 * this file must not be.
 */
private fun LiveChannel.cached(secrets: SecretsToStrip) = CachedChannel(
    id = id,
    categoryId = categoryId,
    name = name,
    logoUrl = secrets.cleans(logoUrl),
    epgChannelId = epgChannelId,
    containerExtension = containerExtension,
    providerOrder = providerOrder,
)

private fun MovieSummary.cached(secrets: SecretsToStrip) = CachedMovie(
    id = id,
    categoryId = categoryId,
    name = name,
    posterUrl = secrets.cleans(posterUrl),
    containerExtension = containerExtension,
    rating = rating,
    releaseYear = releaseYear,
    addedAtEpochSeconds = addedAtEpochSeconds,
    providerOrder = providerOrder,
)

private fun SeriesSummary.cached(secrets: SecretsToStrip) = CachedSeries(
    id = id,
    categoryId = categoryId,
    name = name,
    posterUrl = secrets.cleans(posterUrl),
    rating = rating,
    releaseYear = releaseYear,
    lastModifiedEpochSeconds = lastModifiedEpochSeconds,
    providerOrder = providerOrder,
)

private fun CachedChannel.asChannel() = LiveChannel(
    id = id,
    categoryId = categoryId,
    name = name,
    logoUrl = logoUrl,
    epgChannelId = epgChannelId,
    containerExtension = containerExtension,
    // Never kept, and never needed: every stream URL is built by the shared factory.
    directSource = null,
    providerOrder = providerOrder,
)

private fun CachedMovie.asMovie() = MovieSummary(
    id = id,
    categoryId = categoryId,
    name = name,
    posterUrl = posterUrl,
    containerExtension = containerExtension,
    rating = rating,
    releaseYear = releaseYear,
    addedAtEpochSeconds = addedAtEpochSeconds,
    providerOrder = providerOrder,
)

private fun CachedSeries.asSeries() = SeriesSummary(
    id = id,
    categoryId = categoryId,
    name = name,
    posterUrl = posterUrl,
    rating = rating,
    releaseYear = releaseYear,
    lastModifiedEpochSeconds = lastModifiedEpochSeconds,
    providerOrder = providerOrder,
)
