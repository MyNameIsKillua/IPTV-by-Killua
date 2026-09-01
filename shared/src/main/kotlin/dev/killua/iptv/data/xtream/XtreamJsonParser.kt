package dev.killua.iptv.data.xtream

import dev.killua.iptv.domain.model.AccountStatus
import dev.killua.iptv.domain.model.AppFailure
import dev.killua.iptv.domain.model.AppFailureException
import dev.killua.iptv.domain.model.EpgEntry
import dev.killua.iptv.domain.model.FailureKind
import dev.killua.iptv.domain.model.LiveCategory
import dev.killua.iptv.domain.model.LiveChannel
import dev.killua.iptv.domain.model.MovieCategory
import dev.killua.iptv.domain.model.MovieDetails
import dev.killua.iptv.domain.model.MovieSummary
import dev.killua.iptv.domain.model.RemoteAccount
import dev.killua.iptv.domain.model.SeriesCategory
import dev.killua.iptv.domain.model.SeriesDetails
import dev.killua.iptv.domain.model.SeriesEpisode
import dev.killua.iptv.domain.model.SeriesSummary
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeToSequence
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.Base64
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class XtreamJsonParser(
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    fun parseAccount(payload: String): RemoteAccount {
        val root = parseRoot(payload) as? JsonObject ?: invalidResponse()
        val userInfo = root["user_info"] as? JsonObject ?: invalidResponse()
        val authenticated = userInfo["auth"].flexibleBoolean()
        if (authenticated != true) authenticationFailed()

        val rawStatus = userInfo["status"].cleanString()?.lowercase()
        val expiresAt = userInfo["exp_date"].flexibleEpochSeconds()
        val status = when (rawStatus) {
            "active", null -> AccountStatus.Active
            "expired" -> AccountStatus.Expired
            "disabled", "banned" -> AccountStatus.Disabled
            else -> AccountStatus.Unknown
        }
        when {
            status == AccountStatus.Expired ||
                (expiresAt != null && expiresAt < nowEpochSeconds()) ->
                throw AppFailureException(AppFailure(FailureKind.AccountExpired))
            status == AccountStatus.Disabled ->
                throw AppFailureException(AppFailure(FailureKind.AccountDisabled))
        }

        val serverInfo = root["server_info"] as? JsonObject
        return RemoteAccount(
            username = userInfo["username"].cleanString(),
            status = status,
            expiresAtEpochSeconds = expiresAt,
            activeConnections = userInfo["active_cons"].flexibleInt(),
            maximumConnections = userInfo["max_connections"].flexibleInt(),
            serverTimezone = serverInfo?.get("timezone").cleanString(),
            allowedOutputFormats = userInfo["allowed_output_formats"].formatSet(),
        )
    }

    fun parseCategories(payload: String): List<LiveCategory> =
        parseObjectList(payload).mapIndexedNotNull { index, item ->
            val id = item["category_id"].cleanString() ?: return@mapIndexedNotNull null
            LiveCategory(
                id = id,
                name = item["category_name"].cleanString() ?: "Category $id",
                sortOrder = index,
            )
        }.distinctBy(LiveCategory::id)

    /**
     * Streams the provider's full channel listing.
     *
     * Neither the response nor the resulting collection may be held whole: a large provider
     * exceeds Android's 192 MB heap limit either way. The sequence is consumed lazily by the
     * caller, which writes it to the database in batches, so only one batch is alive at a time.
     * The sequence is valid only while [block] runs, because it reads from the open stream.
     */
    suspend fun <T> withChannels(
        stream: InputStream,
        block: suspend (Sequence<LiveChannel>) -> T,
    ): T = withObjectSequence(
            stream = stream,
            fallback = { payload -> block(parseChannels(payload).asSequence()) },
            block = { objects -> block(channelSequence(objects)) },
        )

    fun parseChannels(payload: String): List<LiveChannel> =
        channelSequence(parseObjectList(payload).asSequence()).toList()

    /**
     * De-duplicates by provider ID while streaming. Only the seen IDs are retained, which is a
     * few megabytes even for a very large library, rather than every parsed object.
     */
    private fun channelSequence(items: Sequence<JsonObject>): Sequence<LiveChannel> {
        val seen = HashSet<String>()
        return items.mapIndexedNotNull { index, item ->
            val id = item["stream_id"].cleanString() ?: return@mapIndexedNotNull null
            if (!seen.add(id)) return@mapIndexedNotNull null
            buildChannel(id, index, item)
        }
    }

    private fun buildChannel(id: String, index: Int, item: JsonObject): LiveChannel = LiveChannel(
        id = id,
        categoryId = item["category_id"].cleanString(),
        name = item["name"].cleanString() ?: "Channel $id",
        logoUrl = item["stream_icon"].safeHttpUrl(),
        epgChannelId = item["epg_channel_id"].cleanString(),
        containerExtension = item["container_extension"].cleanString()
            ?.lowercase()
            ?.takeIf { it in setOf("m3u8", "ts") },
        directSource = item["direct_source"].safeHttpUrl(),
        providerOrder = item["num"].flexibleInt() ?: index,
    )

    fun parseMovieCategories(payload: String): List<MovieCategory> =
        parseObjectList(payload).mapIndexedNotNull { index, item ->
            val id = item["category_id"].cleanString() ?: return@mapIndexedNotNull null
            MovieCategory(
                id = id,
                name = item["category_name"].cleanString() ?: "Category $id",
                sortOrder = index,
            )
        }.distinctBy(MovieCategory::id)

    /** Streams the provider's full VOD listing; see [withChannels] for why. */
    suspend fun <T> withMovieSummaries(
        stream: InputStream,
        block: suspend (Sequence<MovieSummary>) -> T,
    ): T = withObjectSequence(
            stream = stream,
            fallback = { payload -> block(parseMovieSummaries(payload).asSequence()) },
            block = { objects -> block(movieSequence(objects)) },
        )

    fun parseMovieSummaries(payload: String): List<MovieSummary> =
        movieSequence(parseObjectList(payload).asSequence()).toList()

    private fun movieSequence(items: Sequence<JsonObject>): Sequence<MovieSummary> {
        val seen = HashSet<String>()
        return items.mapIndexedNotNull { index, item ->
            val id = item["stream_id"].cleanString() ?: return@mapIndexedNotNull null
            if (!seen.add(id)) return@mapIndexedNotNull null
            buildMovie(id, index, item)
        }
    }

    private fun buildMovie(id: String, index: Int, item: JsonObject): MovieSummary = MovieSummary(
        id = id,
        categoryId = item["category_id"].cleanString(),
        name = item["name"].cleanString() ?: item["title"].cleanString() ?: "Movie $id",
        posterUrl = item["stream_icon"].safeHttpUrl() ?: item["cover"].safeHttpUrl(),
        containerExtension = XtreamStreamUrlFactory.sanitizeVodExtension(
            item["container_extension"].cleanString(),
        ),
        rating = item["rating"].flexibleRating(),
        releaseYear = item["year"].flexibleYear()
            ?: item["releasedate"].flexibleYear()
            ?: item["release_date"].flexibleYear(),
        addedAtEpochSeconds = item["added"].flexibleEpochSeconds(),
        providerOrder = item["num"].flexibleInt() ?: index,
    )

    /**
     * Parses `get_vod_info`, whose payload splits descriptive fields across `info` and stream
     * identity across `movie_data`. A provider that answers with a different `stream_id` than the
     * one requested is rejected rather than attributed to the requested title.
     *
     * `youtube_trailer`, `direct_source`, and external subtitle URLs are deliberately not read:
     * each would introduce a second, unreviewed network destination.
     */
    fun parseMovieDetails(payload: String, requestedId: String): MovieDetails {
        val root = parseRoot(payload) as? JsonObject ?: invalidResponse()
        val info = root["info"] as? JsonObject
        val movieData = root["movie_data"] as? JsonObject
        if (info == null && movieData == null) invalidResponse()

        val providerId = movieData?.get("stream_id").cleanString()
        if (providerId != null && providerId != requestedId) invalidResponse()
        val id = providerId ?: requestedId

        return MovieDetails(
            id = id,
            name = movieData?.get("name").cleanString()
                ?: info?.get("name").cleanString()
                ?: info?.get("title").cleanString()
                ?: "Movie $id",
            categoryId = movieData?.get("category_id").cleanString(),
            containerExtension = XtreamStreamUrlFactory.sanitizeVodExtension(
                movieData?.get("container_extension").cleanString(),
            ),
            posterUrl = info?.get("movie_image").safeHttpUrl()
                ?: info?.get("cover_big").safeHttpUrl(),
            backdropUrl = info?.get("backdrop_path").firstSafeHttpUrl(),
            plot = info?.get("plot").cleanString() ?: info?.get("description").cleanString(),
            genre = info?.get("genre").cleanString(),
            cast = info?.get("cast").cleanString() ?: info?.get("actors").cleanString(),
            director = info?.get("director").cleanString(),
            releaseYear = info?.get("releasedate").flexibleYear()
                ?: info?.get("release_date").flexibleYear()
                ?: info?.get("year").flexibleYear(),
            rating = info?.get("rating").flexibleRating(),
            durationSeconds = info?.get("duration_secs").flexibleDurationSeconds()
                ?: info?.get("duration").flexibleDurationSeconds(),
        )
    }

    /**
     * Reads `get_short_epg`, the per-channel guide.
     *
     * Two provider realities shape this. Titles and descriptions are usually Base64 but not
     * always, so [decodedText] accepts either. And an entry is only kept when it carries usable
     * epoch timestamps: the formatted `start`/`end` strings are provider-local with no offset, so
     * placing an entry from them would need the account timezone to be right and would still be
     * ambiguous twice a year. A guessed time on a guide is worse than a missing entry.
     */
    fun parseShortEpg(payload: String): List<EpgEntry> {
        val root = runCatching { json.parseToJsonElement(payload) }.getOrElse { invalidResponse() }
        val listings = when (root) {
            is JsonObject -> root["epg_listings"] as? JsonArray
            is JsonArray -> root
            else -> null
        } ?: return emptyList()

        return listings.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val start = item["start_timestamp"].flexibleEpochSeconds() ?: return@mapNotNull null
            val end = item["stop_timestamp"].flexibleEpochSeconds()
                ?: item["end_timestamp"].flexibleEpochSeconds()
                ?: return@mapNotNull null
            if (end <= start) return@mapNotNull null
            val title = item["title"].decodedText() ?: return@mapNotNull null
            EpgEntry(
                title = title,
                description = item["description"].decodedText(),
                startEpochSeconds = start,
                endEpochSeconds = end,
            )
        }.sortedBy(EpgEntry::startEpochSeconds)
    }

    fun parseSeriesCategories(payload: String): List<SeriesCategory> =
        parseObjectList(payload).mapIndexedNotNull { index, item ->
            val id = item["category_id"].cleanString() ?: return@mapIndexedNotNull null
            SeriesCategory(
                id = id,
                name = item["category_name"].cleanString() ?: "Category $id",
                sortOrder = index,
            )
        }.distinctBy(SeriesCategory::id)

    /**
     * Streams the provider's full Series listing; see [withChannels] for why.
     *
     * A provider that carries six figures of movies carries a listing of the same order here, so
     * this endpoint gets the same treatment from the start rather than after it first crashes.
     */
    suspend fun <T> withSeriesSummaries(
        stream: InputStream,
        block: suspend (Sequence<SeriesSummary>) -> T,
    ): T = withObjectSequence(
            stream = stream,
            fallback = { payload -> block(parseSeriesSummaries(payload).asSequence()) },
            block = { objects -> block(seriesSequence(objects)) },
        )

    fun parseSeriesSummaries(payload: String): List<SeriesSummary> =
        seriesSequence(parseObjectList(payload).asSequence()).toList()

    private fun seriesSequence(items: Sequence<JsonObject>): Sequence<SeriesSummary> {
        val seen = HashSet<String>()
        return items.mapIndexedNotNull { index, item ->
            val id = item["series_id"].cleanString() ?: return@mapIndexedNotNull null
            if (!seen.add(id)) return@mapIndexedNotNull null
            buildSeries(id, index, item)
        }
    }

    private fun buildSeries(id: String, index: Int, item: JsonObject): SeriesSummary = SeriesSummary(
        id = id,
        categoryId = item["category_id"].cleanString(),
        name = item["name"].cleanString() ?: item["title"].cleanString() ?: "Series $id",
        posterUrl = item["cover"].safeHttpUrl() ?: item["stream_icon"].safeHttpUrl(),
        rating = item["rating"].flexibleRating(),
        releaseYear = item["releaseDate"].flexibleYear()
            ?: item["release_date"].flexibleYear()
            ?: item["year"].flexibleYear(),
        lastModifiedEpochSeconds = item["last_modified"].flexibleEpochSeconds(),
        providerOrder = item["num"].flexibleInt() ?: index,
    )

    /**
     * Parses `get_series_info`, whose payload carries descriptive fields in `info` and episodes in
     * an `episodes` object keyed by season number.
     *
     * Episodes are flattened into one ordered list because that is what a UI and a "next episode"
     * rule need; the season each belongs to is kept on the episode itself. An episode without a
     * provider ID is skipped rather than given a synthesised one, since that ID is what playback
     * and watch progress key on.
     *
     * As with `get_vod_info`, a payload identifying a different series than the one requested is
     * rejected rather than attributed to the requested one.
     */
    fun parseSeriesDetails(payload: String, requestedId: String): SeriesDetails {
        val root = parseRoot(payload) as? JsonObject ?: invalidResponse()
        val info = root["info"] as? JsonObject
        val episodesNode = root["episodes"]
        if (info == null && episodesNode == null) invalidResponse()

        val providerId = info?.get("series_id").cleanString()
        if (providerId != null && providerId != requestedId) invalidResponse()

        return SeriesDetails(
            id = requestedId,
            name = info?.get("name").cleanString()
                ?: info?.get("title").cleanString()
                ?: "Series $requestedId",
            posterUrl = info?.get("cover").safeHttpUrl(),
            backdropUrl = info?.get("backdrop_path").firstSafeHttpUrl(),
            plot = info?.get("plot").cleanString() ?: info?.get("description").cleanString(),
            genre = info?.get("genre").cleanString(),
            cast = info?.get("cast").cleanString() ?: info?.get("actors").cleanString(),
            director = info?.get("director").cleanString(),
            releaseYear = info?.get("releaseDate").flexibleYear()
                ?: info?.get("release_date").flexibleYear()
                ?: info?.get("year").flexibleYear(),
            rating = info?.get("rating").flexibleRating(),
            episodes = parseEpisodes(requestedId, episodesNode),
        )
    }

    /**
     * Reads the `episodes` map. Providers key it by season number and occasionally answer with an
     * array instead; both are accepted. The season on the episode itself wins over the key, which
     * some providers leave inconsistent.
     */
    private fun parseEpisodes(seriesId: String, node: JsonElement?): List<SeriesEpisode> {
        val bySeason: List<Pair<Int?, JsonArray>> = when (node) {
            is JsonObject -> node.entries.map { (key, value) ->
                key.trim().toIntOrNull() to (value as? JsonArray ?: JsonArray(emptyList()))
            }
            is JsonArray -> listOf(null to node)
            else -> return emptyList()
        }

        val seen = HashSet<String>()
        return bySeason.flatMap { (seasonKey, entries) ->
            entries.mapNotNull { entry ->
                val item = entry as? JsonObject ?: return@mapNotNull null
                val id = item["id"].cleanString() ?: return@mapNotNull null
                if (!seen.add(id)) return@mapNotNull null
                buildEpisode(seriesId, id, seasonKey, item)
            }
        }.sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber ?: Int.MAX_VALUE }, { it.id }))
    }

    private fun buildEpisode(
        seriesId: String,
        id: String,
        seasonKey: Int?,
        item: JsonObject,
    ): SeriesEpisode {
        val info = item["info"] as? JsonObject
        val episodeNumber = item["episode_num"].flexibleInt()
        return SeriesEpisode(
            id = id,
            seriesId = seriesId,
            seasonNumber = item["season"].flexibleInt() ?: seasonKey ?: 0,
            episodeNumber = episodeNumber,
            title = item["title"].cleanString()
                ?: episodeNumber?.let { "Episode $it" }
                ?: "Episode $id",
            containerExtension = XtreamStreamUrlFactory.sanitizeVodExtension(
                item["container_extension"].cleanString(),
            ),
            durationSeconds = info?.get("duration_secs").flexibleDurationSeconds()
                ?: info?.get("duration").flexibleDurationSeconds(),
            plot = info?.get("plot").cleanString() ?: info?.get("description").cleanString(),
            stillUrl = info?.get("movie_image").safeHttpUrl(),
        )
    }

    /**
     * Decodes a provider listing without ever holding the whole response.
     *
     * The common shape is a top-level JSON array, which is streamed element by element. Some
     * providers instead answer with an object keyed by ID, and errors arrive as HTML or a small
     * object; those cannot be streamed, so the remaining bytes are buffered and handed to
     * [fallback]. Only the array case is large in practice.
     */
    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun <T> withObjectSequence(
        stream: InputStream,
        fallback: suspend (String) -> T,
        block: suspend (Sequence<JsonObject>) -> T,
    ): T {
        val buffered = stream.buffered()
        val first = skipToFirstMeaningfulByte(buffered)
        if (first != '['.code) {
            return fallback(buffered.readBytes().toString(Charsets.UTF_8))
        }
        // Decoding is lazy, so a malformed payload only fails while the sequence is consumed.
        // Both paths must reduce to the same safe failure the buffered parser produces, and a
        // read error must stay an IOException so the caller's retry policy still applies.
        return try {
            block(
                json.decodeToSequence(
                    stream = buffered,
                    deserializer = JsonObject.serializer(),
                    format = DecodeSequenceMode.ARRAY_WRAPPED,
                ),
            )
        } catch (failure: AppFailureException) {
            throw failure
        } catch (_: SerializationException) {
            invalidResponse()
        }
    }

    /**
     * Consumes leading whitespace and any byte-order mark, then leaves the stream positioned on
     * the first meaningful byte and returns it. The JSON decoder rejects a byte-order mark, so it
     * must be swallowed rather than rewound over.
     */
    private fun skipToFirstMeaningfulByte(stream: BufferedInputStream): Int {
        var skipped = 0
        while (skipped < PEEK_LIMIT) {
            stream.mark(1)
            val value = stream.read()
            if (value == -1) return -1
            if (value in IGNORED_LEADING_BYTES) {
                skipped++
                continue
            }
            stream.reset()
            return value
        }
        return -1
    }

    private fun parseObjectList(payload: String): List<JsonObject> = when (val root = parseRoot(payload)) {
        is JsonArray -> root.mapNotNull { it as? JsonObject }
        is JsonObject -> root.values.mapNotNull { it as? JsonObject }
        JsonNull -> emptyList()
        else -> invalidResponse()
    }

    private fun parseRoot(payload: String): JsonElement {
        val trimmed = payload.trim()
        if (trimmed.isEmpty() || trimmed.startsWith('<')) invalidResponse()
        return try {
            json.parseToJsonElement(trimmed)
        } catch (_: Exception) {
            invalidResponse()
        }
    }

    private fun JsonElement?.cleanString(): String? {
        val primitive = this as? JsonPrimitive ?: return null
        val value = primitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return value.takeUnless {
            it.equals("null", true) || it.equals("undefined", true) || it.equals("n/a", true)
        }
    }

    private fun JsonElement?.flexibleBoolean(): Boolean? {
        val primitive = this as? JsonPrimitive ?: return null
        primitive.booleanOrNull?.let { return it }
        return when (primitive.contentOrNull?.trim()?.lowercase()) {
            "1", "true", "yes" -> true
            "0", "false", "no" -> false
            else -> null
        }
    }

    private fun JsonElement?.flexibleInt(): Int? {
        val primitive = this as? JsonPrimitive ?: return null
        return primitive.intOrNull ?: primitive.contentOrNull?.trim()?.toIntOrNull()
    }

    private fun JsonElement?.flexibleEpochSeconds(): Long? {
        val primitive = this as? JsonPrimitive ?: return null
        val value = primitive.longOrNull ?: primitive.contentOrNull?.trim()?.toLongOrNull() ?: return null
        if (value <= 0L) return null
        return if (value > 100_000_000_000L) value / 1_000L else value
    }

    private fun JsonElement?.formatSet(): Set<String> = when (this) {
        is JsonArray -> mapNotNull { it.cleanString()?.lowercase() }.filterTo(linkedSetOf()) {
            it in setOf("m3u8", "ts")
        }
        is JsonPrimitive -> cleanString()
            ?.split(',', ';')
            ?.map(String::trim)
            ?.map(String::lowercase)
            ?.filterTo(linkedSetOf()) { it in setOf("m3u8", "ts") }
            .orEmpty()
        else -> emptySet()
    }

    private fun JsonElement?.safeHttpUrl(): String? {
        val raw = cleanString() ?: return null
        val url = raw.toHttpUrlOrNull() ?: return null
        return url.toString().takeIf { url.scheme in setOf("http", "https") }
    }

    /** Providers send backdrops either as a single URL or as an array of them. */
    private fun JsonElement?.firstSafeHttpUrl(): String? = when (this) {
        is JsonArray -> firstNotNullOfOrNull { it.safeHttpUrl() }
        else -> safeHttpUrl()
    }

    /** Ratings arrive as numbers or strings on a 0-10 scale; 0 and blanks mean "not rated". */
    private fun JsonElement?.flexibleRating(): Double? {
        val value = cleanString()?.toDoubleOrNull() ?: return null
        if (value.isNaN() || value <= 0.0) return null
        return value.coerceAtMost(MAXIMUM_RATING)
    }

    /** Accepts `2019`, `2019-05-24`, and `24-05-2019` style values. */
    private fun JsonElement?.flexibleYear(): Int? {
        val raw = cleanString() ?: return null
        val year = FOUR_DIGIT_YEAR.find(raw)?.value?.toIntOrNull() ?: return null
        return year.takeIf { it in EARLIEST_FILM_YEAR..LATEST_PLAUSIBLE_YEAR }
    }

    /** Accepts a seconds count or a `HH:MM:SS`/`MM:SS` duration string. */
    private fun JsonElement?.flexibleDurationSeconds(): Int? {
        val raw = cleanString() ?: return null
        raw.toIntOrNull()?.let { return it.takeIf { seconds -> seconds > 0 } }
        val parts = raw.split(':')
        if (parts.size !in 2..3) return null
        val numbers = parts.map { part -> part.trim().toIntOrNull()?.takeIf { it >= 0 } ?: return null }
        val seconds = when (numbers.size) {
            2 -> numbers[0] * 60 + numbers[1]
            else -> numbers[0] * 3_600 + numbers[1] * 60 + numbers[2]
        }
        return seconds.takeIf { it > 0 }
    }

    /**
     * Text that a provider may or may not have Base64-encoded.
     *
     * Decoding is only accepted when the raw value looks like Base64 *and* the result is printable
     * text. Short ASCII words are valid Base64 by accident — "News" decodes cleanly to bytes — so
     * without the printability check a plain title would be replaced by mojibake.
     */
    private fun JsonElement?.decodedText(): String? {
        val raw = cleanString() ?: return null
        if (raw.length < 4 || raw.length % 4 != 0 || !BASE64_ALPHABET.matches(raw)) return raw
        val decoded = runCatching { String(Base64.getDecoder().decode(raw), Charsets.UTF_8) }
            .getOrNull()
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return raw
        // Real text may wrap; control characters and a replacement character mean the decode
        // produced bytes that were never text, which is what a false Base64 positive looks like.
        val printable = decoded.none { it.isISOControl() && it != '\n' && it != '\t' } &&
            !decoded.contains(REPLACEMENT_CHARACTER)
        return if (printable) decoded else raw
    }

    private fun invalidResponse(): Nothing = throw AppFailureException(
        AppFailure(FailureKind.InvalidServerResponse),
    )

    private fun authenticationFailed(): Nothing = throw AppFailureException(
        AppFailure(FailureKind.AuthenticationFailed),
    )

    private companion object {
        val BASE64_ALPHABET = Regex("^[A-Za-z0-9+/]+={0,2}$")

        /** What a UTF-8 decode leaves behind when the bytes were never text. */
        const val REPLACEMENT_CHARACTER = '�'

        const val PEEK_LIMIT = 64

        /** Whitespace plus the three bytes of a UTF-8 byte-order mark. */
        val IGNORED_LEADING_BYTES = setOf(
            ' '.code, '\n'.code, '\r'.code, '\t'.code, 0xEF, 0xBB, 0xBF,
        )
        const val MAXIMUM_RATING = 10.0
        const val EARLIEST_FILM_YEAR = 1880
        const val LATEST_PLAUSIBLE_YEAR = 2200
        val FOUR_DIGIT_YEAR = Regex("""\d{4}""")
    }
}
