package dev.killua.iptv.data.xtream

import dev.killua.iptv.core.network.NetworkFailureMapper
import dev.killua.iptv.domain.model.AppFailureException
import dev.killua.iptv.domain.model.EpgEntry
import dev.killua.iptv.domain.model.LiveCategory
import dev.killua.iptv.domain.model.LiveChannel
import dev.killua.iptv.domain.model.MovieCategory
import dev.killua.iptv.domain.model.MovieDetails
import dev.killua.iptv.domain.model.MovieSummary
import dev.killua.iptv.domain.model.RemoteAccount
import dev.killua.iptv.domain.model.SeriesCategory
import dev.killua.iptv.domain.model.SeriesDetails
import dev.killua.iptv.domain.model.SeriesSummary
import dev.killua.iptv.domain.model.XtreamCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.ResponseBody
import retrofit2.Retrofit
import java.io.IOException
import java.io.InputStream

class XtreamRemoteDataSource(
    retrofit: Retrofit,
    private val failureMapper: NetworkFailureMapper,
    private val parser: XtreamJsonParser = XtreamJsonParser(),
) {
    private val api = retrofit.create(XtreamApi::class.java)

    suspend fun authenticate(credentials: XtreamCredentials): RemoteAccount = withContext(Dispatchers.IO) {
        parser.parseAccount(request(credentials, action = null, authentication = true))
    }

    suspend fun liveCategories(credentials: XtreamCredentials): List<LiveCategory> = withContext(Dispatchers.IO) {
        parser.parseCategories(request(credentials, action = "get_live_categories"))
    }

    /**
     * Streams the full channel listing to [block].
     *
     * The listing endpoints are the only responses large enough to matter, and neither the raw
     * response nor the parsed collection may be held whole. [block] receives a lazy sequence and
     * is expected to consume it in batches; the sequence is only valid until it returns.
     */
    suspend fun <T> withLiveChannels(
        credentials: XtreamCredentials,
        block: suspend (Sequence<LiveChannel>) -> T,
    ): T = withContext(Dispatchers.IO) {
        streamingRequest(credentials, action = "get_live_streams") { stream ->
            parser.withChannels(stream) { channels -> block(channels) }
        }
    }

    /**
     * The next [limit] programmes for one channel.
     *
     * Deliberately the per-channel endpoint rather than the whole-guide XMLTV file: a provider
     * with 60,000 channels would answer that with something the size of the listings this project
     * already had to learn to stream, and a guide is only ever looked at one channel at a time.
     */
    suspend fun shortEpg(
        credentials: XtreamCredentials,
        streamId: String,
        limit: Int,
    ): List<EpgEntry> = withContext(Dispatchers.IO) {
        require(streamId.isNotBlank()) { "Stream ID is required" }
        require(limit > 0) { "Limit must be positive" }
        parser.parseShortEpg(
            request(credentials, action = "get_short_epg", streamId = streamId, limit = limit),
        )
    }

    suspend fun movieCategories(credentials: XtreamCredentials): List<MovieCategory> =
        withContext(Dispatchers.IO) {
            parser.parseMovieCategories(request(credentials, action = "get_vod_categories"))
        }

    /** Streams the full VOD listing to [block]; see [withLiveChannels]. */
    suspend fun <T> withMovieSummaries(
        credentials: XtreamCredentials,
        block: suspend (Sequence<MovieSummary>) -> T,
    ): T = withContext(Dispatchers.IO) {
        streamingRequest(credentials, action = "get_vod_streams") { stream ->
            parser.withMovieSummaries(stream) { movies -> block(movies) }
        }
    }

    suspend fun movieDetails(
        credentials: XtreamCredentials,
        movieId: String,
    ): MovieDetails = withContext(Dispatchers.IO) {
        require(movieId.isNotBlank()) { "Movie ID is required" }
        parser.parseMovieDetails(
            payload = request(credentials, action = "get_vod_info", vodId = movieId),
            requestedId = movieId,
        )
    }

    suspend fun seriesCategories(credentials: XtreamCredentials): List<SeriesCategory> =
        withContext(Dispatchers.IO) {
            parser.parseSeriesCategories(request(credentials, action = "get_series_categories"))
        }

    /** Streams the full Series listing to [block]; see [withLiveChannels]. */
    suspend fun <T> withSeriesSummaries(
        credentials: XtreamCredentials,
        block: suspend (Sequence<SeriesSummary>) -> T,
    ): T = withContext(Dispatchers.IO) {
        streamingRequest(credentials, action = "get_series") { stream ->
            parser.withSeriesSummaries(stream) { series -> block(series) }
        }
    }

    suspend fun seriesDetails(
        credentials: XtreamCredentials,
        seriesId: String,
    ): SeriesDetails = withContext(Dispatchers.IO) {
        require(seriesId.isNotBlank()) { "Series ID is required" }
        parser.parseSeriesDetails(
            payload = request(credentials, action = "get_series_info", seriesId = seriesId),
            requestedId = seriesId,
        )
    }

    /**
     * Same request and retry behaviour as [request], but hands the response body to [parse] as a
     * stream so a large listing never has to exist in memory as one String.
     */
    private suspend fun <T> streamingRequest(
        credentials: XtreamCredentials,
        action: String?,
        parse: suspend (InputStream) -> T,
    ): T = perform(
        credentials = credentials,
        action = action,
        vodId = null,
        seriesId = null,
        streamId = null,
        limit = null,
        authentication = false,
    ) { body ->
        body.byteStream().use { stream -> parse(stream) }
    }

    private suspend fun request(
        credentials: XtreamCredentials,
        action: String?,
        vodId: String? = null,
        seriesId: String? = null,
        streamId: String? = null,
        limit: Int? = null,
        authentication: Boolean = false,
    ): String = perform(
        credentials = credentials,
        action = action,
        vodId = vodId,
        seriesId = seriesId,
        streamId = streamId,
        limit = limit,
        authentication = authentication,
    ) { body ->
        body.string()
    }

    private suspend fun <T> perform(
        credentials: XtreamCredentials,
        action: String?,
        vodId: String?,
        seriesId: String?,
        streamId: String?,
        limit: Int?,
        authentication: Boolean,
        consume: suspend (ResponseBody) -> T,
    ): T {
        val endpoint = credentials.serverUrl.toHttpUrl().newBuilder()
            .addPathSegment("player_api.php")
            .build()
            .toString()

        var attempt = 0
        while (true) {
            try {
                val response = api.request(
                    endpoint = endpoint,
                    username = credentials.username,
                    password = credentials.password,
                    action = action,
                    vodId = vodId,
                    seriesId = seriesId,
                    streamId = streamId,
                    limit = limit,
                )
                if (response.isSuccessful) {
                    return response.body()?.use { body -> consume(body) }
                        ?: throw AppFailureException(
                            failureMapper.fromHttpStatus(204, authentication),
                        )
                }
                response.errorBody()?.close()
                val failure = failureMapper.fromHttpStatus(response.code(), authentication)
                if (failure.retryable && attempt < MAX_RETRIES) {
                    delay(RETRY_DELAYS_MS[attempt])
                    attempt++
                    continue
                }
                throw AppFailureException(failure)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: AppFailureException) {
                throw failure
            } catch (error: IOException) {
                val failure = failureMapper.fromException(error)
                if (failure.retryable && attempt < MAX_RETRIES) {
                    delay(RETRY_DELAYS_MS[attempt])
                    attempt++
                    continue
                }
                throw AppFailureException(failure)
            }
        }
    }

    private companion object {
        const val MAX_RETRIES = 2
        val RETRY_DELAYS_MS = longArrayOf(200L, 600L)
    }
}
