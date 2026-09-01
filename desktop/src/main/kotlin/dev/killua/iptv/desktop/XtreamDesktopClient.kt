package dev.killua.iptv.desktop

import dev.killua.iptv.core.network.NormalizedServer
import dev.killua.iptv.core.network.ServerUrlNormalizer
import dev.killua.iptv.core.network.XtreamM3uUrlParser
import dev.killua.iptv.core.network.XtreamM3uUrlResult
import dev.killua.iptv.core.network.UrlNormalizationResult
import dev.killua.iptv.data.xtream.XtreamJsonParser
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The provider adapter for the desktop client.
 *
 * Deliberately plain OkHttp rather than Retrofit. Retrofit is the one networking dependency
 * `:shared` refuses, so the Android app keeps it and this module builds its four requests by hand;
 * that is about thirty lines against a framework in the shared module that iOS would have to
 * replace anyway.
 *
 * **All parsing is the shared parser.** Nothing about a provider's JSON is re-interpreted here, so
 * the defensive handling that took the Android app several releases to get right — mixed number and
 * string fields, missing ids, duplicate entries — applies unchanged.
 *
 * **No cache on disk, still.** What changed is that the whole listing is now asked for once per
 * sign-in and held in memory — see [LibraryIndex] for why that reversal was worth making — so the
 * three `withAll…` calls stream through the shared parser exactly as the phone does. Nothing here
 * writes anything down, and the next launch asks again.
 */
class XtreamDesktopClient(
    private val parser: XtreamJsonParser = XtreamJsonParser(),
) : LibraryReader {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    /**
     * The same client without a deadline on the whole call, for the three whole-library requests.
     *
     * Sharing the connection pool and everything else, because it is [http] with one value changed:
     * a 35-second ceiling is right for a category and wrong for a listing that legitimately takes
     * minutes. The read timeout is untouched — a provider that has gone quiet still fails.
     */
    private val longHttp = http.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Normalizes a typed server address and signs in.
     *
     * The normalizer is the shared one, so a pasted `player_api.php` link, a missing scheme or a
     * trailing slash behave exactly as they do on the phone.
     */
    suspend fun signIn(server: String, username: String, password: String): SignInResult =
        withContext(Dispatchers.IO) {
            val normalized = when (val result = ServerUrlNormalizer.normalize(server)) {
                is UrlNormalizationResult.Valid -> result.server
                is UrlNormalizationResult.Invalid ->
                    return@withContext SignInResult.BadServer(result.reason.name)
            }
            signInWith(normalized, username, password)
        }

    /**
     * Signs in from a provider-issued playlist link.
     *
     * The same `get.php?username=…&password=…` line a provider hands out for a set-top box or VLC.
     * Typing three fields out of one URL is transcription work a computer should be doing, and it is
     * where a mistyped password comes from.
     *
     * The parsing rule is `:shared`'s and the phone's, deliberately: it accepts only `get.php` and
     * `player_api.php`, refuses a repeated or blank credential, and does not pretend to read an M3U
     * *file* — the URL is the credential, and a playlist body would be a library this client has
     * decided never to download.
     *
     * The link is a credential in every sense, so it lives exactly as long as the password does:
     * in memory, never written, never named in an error. `ParsedXtreamM3uUrl` prints as REDACTED for
     * the same reason.
     */
    suspend fun signInWithLink(link: String): SignInResult = withContext(Dispatchers.IO) {
        when (val parsed = XtreamM3uUrlParser.parse(link)) {
            is XtreamM3uUrlResult.Invalid -> SignInResult.BadLink(parsed.reason.name)
            is XtreamM3uUrlResult.Valid -> signInWith(
                parsed.credentials.server,
                parsed.credentials.username,
                parsed.credentials.password,
            )
        }
    }

    private fun signInWith(
        normalized: NormalizedServer,
        username: String,
        password: String,
    ): SignInResult {
        val credentials = XtreamCredentials(
            accountId = "desktop",
            serverUrl = normalized.baseUrl,
            username = username,
            password = password,
        )
        return try {
            val account = parser.parseAccount(get(credentials, action = null))
            SignInResult.Ok(credentials, account, normalized.isCleartext)
        } catch (refused: ProviderRefused) {
            // Before this branch a 401 arrived as an IOException and the screen said the server
            // could not be reached — of a server that had just answered. Caught first because
            // it *is* an IOException, and the order of catch blocks is the whole distinction.
            SignInResult.Refused(refused.code)
        } catch (io: IOException) {
            SignInResult.Unreachable(io.message.orEmpty())
        } catch (_: Exception) {
            // Includes the parser's own refusal of a payload that is not an account, which is
            // how most providers say no: 200, with `auth` set to zero.
            SignInResult.Rejected
        }
    }

    suspend fun liveCategories(credentials: XtreamCredentials): List<LiveCategory> =
        withContext(Dispatchers.IO) {
            parser.parseCategories(get(credentials, "get_live_categories"))
        }

    /**
     * Channels of one category.
     *
     * A category is small — tens to hundreds — which is the whole reason this client needs no
     * database. Asking for the full listing instead would be tens of megabytes and is exactly what
     * the Android app had to learn to stream.
     */
    suspend fun channels(credentials: XtreamCredentials, categoryId: String): List<LiveChannel> =
        withContext(Dispatchers.IO) {
            parser.parseChannels(get(credentials, "get_live_streams", "category_id" to categoryId))
        }

    /**
     * The whole listing of one library, handed over an item at a time.
     *
     * This is the request the client used to refuse. It is what a library that is simply *there*
     * costs, and it is asked for **once per sign-in**, behind a screen that says what it is doing —
     * not per category, not per keystroke, and never written to disk.
     *
     * Streamed rather than read into a string, through the same shared parser the phone uses for the
     * same reason: a provider's film listing arrives as tens of megabytes, and holding the JSON and
     * the objects at the same time doubles the worst moment for nothing.
     *
     * [block] runs while the response is still open, so whatever it does with the sequence it must
     * do before returning — the sequence is not valid afterwards. Stopping early is allowed and is
     * how the item cap works: leaving the block closes the response, which abandons the download.
     */
    override suspend fun <T> withAllChannels(
        credentials: XtreamCredentials,
        block: suspend (Sequence<LiveChannel>) -> T,
    ): T = withContext(Dispatchers.IO) {
        open(credentials, "get_live_streams", whole = true).use { response ->
            parser.withChannels(response.body.byteStream()) { block(it) }
        }
    }

    override suspend fun <T> withAllMovies(
        credentials: XtreamCredentials,
        block: suspend (Sequence<MovieSummary>) -> T,
    ): T = withContext(Dispatchers.IO) {
        open(credentials, "get_vod_streams", whole = true).use { response ->
            parser.withMovieSummaries(response.body.byteStream()) { block(it) }
        }
    }

    override suspend fun <T> withAllSeries(
        credentials: XtreamCredentials,
        block: suspend (Sequence<SeriesSummary>) -> T,
    ): T = withContext(Dispatchers.IO) {
        open(credentials, "get_series", whole = true).use { response ->
            parser.withSeriesSummaries(response.body.byteStream()) { block(it) }
        }
    }

    suspend fun movieCategories(credentials: XtreamCredentials): List<MovieCategory> =
        withContext(Dispatchers.IO) {
            parser.parseMovieCategories(get(credentials, "get_vod_categories"))
        }

    suspend fun movies(credentials: XtreamCredentials, categoryId: String): List<MovieSummary> =
        withContext(Dispatchers.IO) {
            parser.parseMovieSummaries(
                get(credentials, "get_vod_streams", "category_id" to categoryId),
            )
        }

    suspend fun seriesCategories(credentials: XtreamCredentials): List<SeriesCategory> =
        withContext(Dispatchers.IO) {
            parser.parseSeriesCategories(get(credentials, "get_series_categories"))
        }

    suspend fun series(credentials: XtreamCredentials, categoryId: String): List<SeriesSummary> =
        withContext(Dispatchers.IO) {
            parser.parseSeriesSummaries(get(credentials, "get_series", "category_id" to categoryId))
        }

    /**
     * Everything the provider knows about one film.
     *
     * The parser is handed the id it asked for and refuses a payload describing a different title —
     * a provider answering the wrong thing is a real failure mode this project has already had to
     * defend against elsewhere.
     */
    suspend fun movieDetails(credentials: XtreamCredentials, movieId: String): MovieDetails =
        withContext(Dispatchers.IO) {
            parser.parseMovieDetails(
                get(credentials, "get_vod_info", "vod_id" to movieId),
                movieId,
            )
        }

    /**
     * One series, with its episodes.
     *
     * The whole record rather than only the episode list: the same request carries the plot and the
     * cast, and throwing them away only to have nothing to show above the episodes would be a second
     * request for something already in hand.
     */
    suspend fun seriesDetails(credentials: XtreamCredentials, seriesId: String): SeriesDetails =
        withContext(Dispatchers.IO) {
            parser.parseSeriesDetails(
                get(credentials, "get_series_info", "series_id" to seriesId),
                seriesId,
            )
        }

    /**
     * The short guide for one channel.
     *
     * `get_short_epg` per channel, never the whole XMLTV file: a provider with six figures of
     * channels would answer that with something the size of the listings this project already had
     * to learn to stream, and a guide is only ever read one channel at a time.
     *
     * A failure is an empty guide rather than an error. The guide is decoration around the picture;
     * losing it must never look like playback going wrong.
     */
    suspend fun shortEpg(credentials: XtreamCredentials, streamId: String): List<EpgEntry> =
        withContext(Dispatchers.IO) {
            runCatching {
                parser.parseShortEpg(
                    get(credentials, "get_short_epg", "stream_id" to streamId, "limit" to "8"),
                )
            }.getOrDefault(emptyList())
        }

    private fun get(
        credentials: XtreamCredentials,
        action: String?,
        vararg extra: Pair<String, String>,
    ): String = open(credentials, action, *extra).use { it.body.string() }

    /**
     * The response itself, for the callers that read it a piece at a time.
     *
     * Split out of [get] rather than duplicated: the URL building, the refusal codes and the failure
     * message are the part that must not diverge, and the only difference between the two is whether
     * the body is turned into a string before the connection is let go.
     *
     * A whole-library request takes minutes on a large provider, so it is given its own client with
     * no call timeout. The read timeout still applies, which is the one that matters: a provider
     * that has stopped sending is a failure, a provider that is sending slowly is not.
     */
    private fun open(
        credentials: XtreamCredentials,
        action: String?,
        vararg extra: Pair<String, String>,
        whole: Boolean = false,
    ): Response {
        val url = credentials.serverUrl.toHttpUrl().newBuilder()
            .addPathSegment("player_api.php")
            .addQueryParameter("username", credentials.username)
            .addQueryParameter("password", credentials.password)
            .apply {
                if (action != null) addQueryParameter("action", action)
                extra.forEach { (key, value) -> addQueryParameter(key, value) }
            }
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        val response = (if (whole) longHttp else http).newCall(request).execute()
        // A refusal is not a fault. An account that has expired, been disabled, or hit its
        // connection limit answers 401 or 403, and telling someone "that library could not be
        // loaded" sends them looking at their network for a problem that is on their bill.
        if (response.code in REFUSED_CODES) {
            response.close()
            throw ProviderRefused(response.code)
        }
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw IOException("HTTP $code")
        }
        return response
    }

    private companion object {
        const val USER_AGENT = "KilluaIPTV-desktop/0.1"
    }
}

/**
 * The provider answered, and the answer was no.
 *
 * Kept apart from every other failure because it is the one a viewer can act on — and the one that
 * arrives when nothing about the client or the network has changed at all.
 */
class ProviderRefused(val code: Int) : IOException("HTTP $code")

/** 401 is "not you", 403 is "not now". Both are the account rather than the request. */
private val REFUSED_CODES = setOf(401, 403)

/**
 * What to tell someone whose provider refused them.
 *
 * Deliberately does not guess which of the reasons it is: expiry, a disabled account and too many
 * connections all arrive the same way, and naming the wrong one sends the viewer to the wrong place.
 * It says what happened and where the answer is.
 */
fun providerRefusedMessage(code: Int): String = when (code) {
    403 -> "The provider refused this account. It may have expired, or every connection may be in use."
    else -> "The provider did not recognise this account."
}

sealed interface SignInResult {
    data class Ok(
        val credentials: XtreamCredentials,
        val account: RemoteAccount,
        val isCleartext: Boolean,
    ) : SignInResult

    data class BadServer(val reason: String) : SignInResult

    /** The playlist link could not be read. The reason is a name, never the link itself. */
    data class BadLink(val reason: String) : SignInResult

    data class Unreachable(val detail: String) : SignInResult

    /** The provider answered, but not with a usable account. Wrong credentials look like this. */
    /** The provider answered `auth: 0`, or something that is not an account at all. */
    data object Rejected : SignInResult

    /** The provider refused with a status code, which says a little more than `auth: 0` does. */
    data class Refused(val code: Int) : SignInResult
}
