package dev.killua.iptv.data.playlist

import dev.killua.iptv.core.network.StreamUrlPolicy
import dev.killua.iptv.core.network.StreamUrlVerdict
import dev.killua.iptv.data.m3u.M3uParseReport
import dev.killua.iptv.data.m3u.M3uPlaylistParser
import dev.killua.iptv.data.repository.LiveListingSource
import dev.killua.iptv.domain.model.LiveCategory
import dev.killua.iptv.domain.model.LiveChannel
import dev.killua.iptv.domain.model.XtreamCredentials
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * A playlist file as a live listing.
 *
 * The address is in [XtreamCredentials.serverUrl], because a playlist account has no user name and
 * no password - reading the file is the whole of the authorisation. Everything downstream sees the
 * same [LiveChannel] the Xtream listing produces, so the cache, the transaction and every screen
 * are unchanged.
 *
 * **Redirects are followed here rather than by OkHttp**, and each hop goes back through
 * [StreamUrlPolicy]. The useful playlist addresses do redirect - a shortener, a `github.io` path, a
 * provider's front door - and letting the client follow them silently would mean the address
 * finally opened was never checked. An `https` to `http` hop is refused outright.
 *
 * The body is read a line at a time and handed on as a sequence, which is not optional at this
 * scale: the phone learned in `v0.2.0-alpha.2` that holding a six-figure listing and the objects
 * mapped from it at the same time is what exhausts the heap, and a provider's playlist is the same
 * six figures its API is.
 */
class PlaylistLiveSource(
    private val http: OkHttpClient,
    /**
     * The address rule, as a seam, and worth stating so nobody widens it.
     *
     * Production takes the default and gets [StreamUrlPolicy] exactly. A test cannot: a local HTTP
     * server answers on `127.0.0.1`, which the real rule refuses - correctly, since refusing
     * loopback is most of what it is for. The rule is passed in rather than weakened, and a test
     * hands it one that permits loopback and nothing else. The desktop reader has the same seam.
     */
    private val checkAddress: (String) -> StreamUrlVerdict = StreamUrlPolicy::check,
) : LiveListingSource, PlaylistProbe {

    /**
     * None, and not because anything failed.
     *
     * An M3U keeps its grouping inside the entries, so the categories are only known once the file
     * has been read - and reading it twice is a download nobody asked for. `DefaultLiveRepository`
     * collects the groups from the channels as they stream past.
     */
    override suspend fun liveCategories(credentials: XtreamCredentials): List<LiveCategory> =
        emptyList()

    override suspend fun <T> withLiveChannels(
        credentials: XtreamCredentials,
        block: suspend (Sequence<LiveChannel>) -> T,
    ): T = withContext(Dispatchers.IO) {
        open(credentials.serverUrl).use { response ->
            response.body.byteStream().bufferedReader().use { reader ->
                block(M3uPlaylistParser.parse(reader.lineSequence(), M3uParseReport()))
            }
        }
    }

    /**
     * Opens the address and reads just enough of it to know whether it is a playlist.
     *
     * Only the head of the body is read and the rest abandoned, so this costs a connection rather
     * than a download - the sync that follows asks for the whole thing anyway.
     */
    override suspend fun probe(url: String): PlaylistProbeResult = withContext(Dispatchers.IO) {
        try {
            open(url).use { response ->
                val head = CharArray(PROBE_CHARACTERS)
                val read = response.body.byteStream().bufferedReader().read(head)
                val text = if (read > 0) String(head, 0, read) else ""
                if (text.contains(PLAYLIST_MARKER, true) || text.contains(ENTRY_MARKER, true)) {
                    // The canonical form, which is what gets stored: a playlist address is
                    // untrusted text and its spelling is not worth preserving.
                    PlaylistProbeResult.Ok(checked(url, previousWasSecure = false))
                } else {
                    PlaylistProbeResult.NotAPlaylist
                }
            }
        } catch (refused: PlaylistAddressRefused) {
            PlaylistProbeResult.Refused(refused.reasonName)
        } catch (_: IOException) {
            PlaylistProbeResult.Unreachable
        }
    }

    private fun open(url: String): Response {
        var current = checked(url, previousWasSecure = false)
        var hops = 0
        while (true) {
            val response = http.newCall(Request.Builder().url(current).build()).execute()
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
            val resolved = runCatching { java.net.URI(current).resolve(location).toString() }
                .getOrElse { throw IOException("Redirect could not be read") }
            current = checked(resolved, previousWasSecure = wasSecure)
        }
    }

    /**
     * One hop, through the rule every playlist address goes through.
     *
     * The message names the reason and never the address: a provider's playlist link carries the
     * account inside it, and a failure is a thing people paste into a chat window.
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
        const val MAX_REDIRECTS = 5
        const val PROBE_CHARACTERS = 512
        const val PLAYLIST_MARKER = "#EXTM3U"
        const val ENTRY_MARKER = "#EXTINF"
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

/**
 * An address the rule would not open, kept apart from a network failure.
 *
 * They read the same to a stack trace and mean opposite things to a viewer: one is "that address is
 * not one this program will follow", the other is "that server did not answer". The message carries
 * the reason's name and never the address, because a provider's playlist link is a credential.
 */
class PlaylistAddressRefused(val reasonName: String) : IOException("Address refused: $reasonName")
