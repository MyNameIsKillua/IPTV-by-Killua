package dev.killua.iptv.desktop

import dev.killua.iptv.core.network.StreamUrlPolicy
import dev.killua.iptv.core.network.StreamUrlVerdict
import dev.killua.iptv.data.m3u.M3uParseReport
import dev.killua.iptv.data.m3u.M3uPlaylistParser
import dev.killua.iptv.domain.model.LiveChannel
import dev.killua.iptv.domain.model.MovieSummary
import dev.killua.iptv.domain.model.SeriesSummary
import dev.killua.iptv.domain.model.XtreamCredentials
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * A playlist as a library, so everything above it stays exactly as it was.
 *
 * The whole point of implementing [LibraryReader] rather than adding a second path is that the sync
 * screen, [LibraryIndex], search, My list, watch progress and the user-data file never learn that a
 * playlist exists. One request replaces three, and the shared parser fills the same [LiveChannel]
 * the Xtream listing does.
 *
 * **A playlist offers channels and nothing else**, which [offers] states rather than leaving the
 * loader to discover by reading two empty listings. An M3U has no films, no series and no guide
 * endpoint; showing those rails empty would look like a provider that failed rather than a format
 * that has no such thing.
 *
 * **Redirects are followed here rather than by OkHttp**, and that is deliberate. A playlist address
 * comes from a viewer or from a link they were given, and the useful ones redirect - a shortener, a
 * `github.io` path, a provider's own front door. Letting the client follow them silently would mean
 * the address that is finally opened was never checked, so each hop goes back through
 * [StreamUrlPolicy]: bounded at [MAX_REDIRECTS], never onto a private or loopback host, and never
 * from `https` down to `http`, which is the one downgrade the project has always refused.
 *
 * The body is read a line at a time and handed on as a sequence, for the reason the phone learned in
 * `v0.2.0-alpha.2`: a provider's playlist is the same six figures of channels its API is, and
 * holding the text and the objects at once doubles the worst moment for nothing.
 */
class PlaylistLibraryReader(
    private val playlistUrl: String,
    private val http: OkHttpClient = defaultClient(),
    /**
     * The address rule, as a seam, and the reason is worth stating so nobody widens it.
     *
     * Production takes the default and gets [StreamUrlPolicy] exactly. A test cannot: a local HTTP
     * server answers on `127.0.0.1`, which the real rule refuses - **correctly**, since refusing
     * loopback is most of the point. Rather than weaken the rule so its own tests can pass, the
     * rule is passed in, and the tests hand it one that permits loopback and nothing else.
     */
    private val checkAddress: (String) -> StreamUrlVerdict = StreamUrlPolicy::check,
) : LibraryReader {

    /** Filled in as the last read is consumed, for a screen that wants to say what was skipped. */
    @Volatile
    var lastReport: M3uParseReport = M3uParseReport()
        private set

    override val offers: Set<LibraryKind> = setOf(LibraryKind.Channels)

    override suspend fun <T> withAllChannels(
        credentials: XtreamCredentials,
        block: suspend (Sequence<LiveChannel>) -> T,
    ): T = withContext(Dispatchers.IO) {
        val report = M3uParseReport()
        lastReport = report
        open(playlistUrl).use { response ->
            response.body.byteStream().bufferedReader().use { reader ->
                block(M3uPlaylistParser.parse(reader.lineSequence(), report))
            }
        }
    }

    /**
     * Nothing, and not because the request failed.
     *
     * The loader never calls these while [offers] says what it says; they answer anyway so that a
     * future caller reaching past [offers] gets an empty library rather than an exception.
     */
    override suspend fun <T> withAllMovies(
        credentials: XtreamCredentials,
        block: suspend (Sequence<MovieSummary>) -> T,
    ): T = block(emptySequence())

    override suspend fun <T> withAllSeries(
        credentials: XtreamCredentials,
        block: suspend (Sequence<SeriesSummary>) -> T,
    ): T = block(emptySequence())

    /**
     * Opens the address and reads just enough of it to know whether it is a playlist.
     *
     * A playlist has no sign-in: there is no account to authenticate and nothing that can say yes.
     * Without this, a mistyped address would "sign in" happily and then arrive as a library that is
     * empty for reasons the viewer cannot see. So the address is opened once, the first few hundred
     * bytes are read, and the answer is whether they look like the format.
     *
     * Only the head of the body is read and the rest is abandoned, so this costs a connection rather
     * than a download - the sync that follows immediately asks for the whole thing anyway.
     */
    suspend fun probe(): PlaylistProbe = withContext(Dispatchers.IO) {
        try {
            open(playlistUrl).use { response ->
                val head = CharArray(PROBE_CHARACTERS)
                val read = response.body.byteStream().bufferedReader().read(head)
                val text = if (read > 0) String(head, 0, read) else ""
                if (text.contains(PLAYLIST_MARKER, ignoreCase = true) ||
                    text.contains(ENTRY_MARKER, ignoreCase = true)
                ) {
                    PlaylistProbe.Ok(playlistUrl)
                } else {
                    PlaylistProbe.NotAPlaylist
                }
            }
        } catch (refused: PlaylistAddressRefused) {
            PlaylistProbe.Refused(refused.reasonName)
        } catch (io: IOException) {
            PlaylistProbe.Unreachable(io.message.orEmpty())
        }
    }

    private fun open(url: String): Response {
        var current = checked(url, previousWasSecure = false)
        var hops = 0
        while (true) {
            val response = http.newCall(
                Request.Builder().url(current).header("User-Agent", USER_AGENT).build(),
            ).execute()

            if (response.code !in REDIRECT_CODES) {
                if (!response.isSuccessful) {
                    val code = response.code
                    response.close()
                    throw IOException("HTTP $code")
                }
                return response
            }

            val location = response.header("Location")
            val wasSecure = current.startsWith("https://")
            response.close()
            if (++hops > MAX_REDIRECTS) throw IOException("Too many redirects")
            if (location.isNullOrBlank()) throw IOException("Redirect without a destination")
            // Resolved against the current address, because a Location may be a bare path.
            val resolved = runCatching { java.net.URI(current).resolve(location).toString() }
                .getOrElse { throw IOException("Redirect could not be read") }
            current = checked(resolved, previousWasSecure = wasSecure)
        }
    }

    /**
     * One hop, through the same rule every stream address goes through.
     *
     * The messages name what was wrong and never the address: a provider's playlist link carries the
     * account inside it, and an error is a thing people paste into a chat window.
     */
    private fun checked(url: String, previousWasSecure: Boolean): String =
        when (val verdict = checkAddress(url)) {
            is StreamUrlVerdict.Refused -> throw PlaylistAddressRefused(verdict.reason.name)
            is StreamUrlVerdict.Allowed -> {
                if (previousWasSecure && verdict.isCleartext) {
                    throw PlaylistAddressRefused("HttpsDowngrade")
                }
                verdict.url
            }
        }

    private companion object {
        const val USER_AGENT = "KilluaIPTV-desktop/0.1"
        const val MAX_REDIRECTS = 5
        const val PROBE_CHARACTERS = 512
        const val PLAYLIST_MARKER = "#EXTM3U"
        const val ENTRY_MARKER = "#EXTINF"
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        /**
         * No call timeout, a read timeout that does apply.
         *
         * The same shape [XtreamDesktopClient] uses for a whole listing and for the same reason: a
         * large playlist legitimately takes minutes, while a server that has stopped sending is a
         * failure whatever it is sending.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .build()
    }
}

/**
 * An address the rule would not open, kept apart from a network failure.
 *
 * They read the same to a stack trace and mean opposite things to a viewer: one is "that address is
 * not one this program will follow", the other is "that server did not answer". The message carries
 * the reason's name and never the address, because a provider's playlist link is a credential.
 */
class PlaylistAddressRefused(val reasonName: String) :
    IOException("Address refused: $reasonName")

/** What opening a playlist address once told us. */
sealed interface PlaylistProbe {
    data class Ok(val url: String) : PlaylistProbe

    /** It answered, and what came back was not a playlist. A web page looks like this. */
    data object NotAPlaylist : PlaylistProbe

    data class Refused(val reasonName: String) : PlaylistProbe

    data class Unreachable(val detail: String) : PlaylistProbe
}
