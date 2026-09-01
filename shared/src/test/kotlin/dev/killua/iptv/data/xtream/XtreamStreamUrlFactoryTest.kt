package dev.killua.iptv.data.xtream

import com.google.common.truth.Truth.assertThat
import dev.killua.iptv.domain.model.Account
import dev.killua.iptv.domain.model.AccountStatus
import dev.killua.iptv.domain.model.LiveChannel
import dev.killua.iptv.domain.model.XtreamCredentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertThrows
import org.junit.Test

class XtreamStreamUrlFactoryTest {
    @Test
    fun `format selection prefers advertised HLS then advertised TS`() {
        assertThat(select(allowed = setOf("m3u8", "ts"), channel = "ts")).isEqualTo("m3u8")
        assertThat(select(allowed = setOf("M3U8"), channel = "ts")).isEqualTo("m3u8")
        assertThat(select(allowed = setOf("TS"), channel = "m3u8")).isEqualTo("ts")
        assertThat(select(allowed = setOf("ts"), channel = null)).isEqualTo("ts")
    }

    @Test
    fun `format selection uses a supported channel hint when formats are not advertised`() {
        assertThat(select(allowed = emptySet(), channel = "M3U8")).isEqualTo("m3u8")
        assertThat(select(allowed = emptySet(), channel = "ts")).isEqualTo("ts")
    }

    @Test
    fun `format selection safely defaults to TS for absent or unknown metadata`() {
        assertThat(select(allowed = emptySet(), channel = null)).isEqualTo("ts")
        assertThat(select(allowed = setOf("mp4"), channel = "mp4")).isEqualTo("ts")
        assertThat(select(allowed = setOf("unknown"), channel = "mkv")).isEqualTo("ts")
    }

    @Test
    fun `live URL appends to a normalized server subpath`() {
        val url = XtreamStreamUrlFactory.buildLiveUrl(
            credentials = credentials(serverUrl = "https://example.com:8443/xtream/"),
            streamId = "42",
            format = "m3u8",
        )

        assertThat(url).isEqualTo("https://example.com:8443/xtream/live/alice/secret/42.m3u8")
    }

    @Test
    fun `credentials and stream IDs are encoded as individual path segments`() {
        val url = XtreamStreamUrlFactory.buildLiveUrl(
            credentials = XtreamCredentials(
                accountId = "account",
                serverUrl = "https://example.com/base/",
                username = "user/name",
                password = "p ass?#%",
            ),
            streamId = "../Kanal ü/1",
            format = "ts",
        ).toHttpUrl()

        assertThat(url.pathSegments).containsExactly(
            "base",
            "live",
            "user/name",
            "p ass?#%",
            "../Kanal ü/1.ts",
        ).inOrder()
        assertThat(url.encodedPath).contains("user%2Fname")
        assertThat(url.encodedPath).contains("..%2FKanal%20%C3%BC%2F1.ts")
    }

    @Test
    fun `movie URL uses the movie path under a normalized server subpath`() {
        val url = XtreamStreamUrlFactory.buildMovieUrl(
            credentials = credentials(serverUrl = "https://example.com:8443/xtream/"),
            movieId = "501",
            extension = "mkv",
        )

        assertThat(url).isEqualTo("https://example.com:8443/xtream/movie/alice/secret/501.mkv")
    }

    @Test
    fun `movie credentials and IDs are encoded as individual path segments`() {
        val url = XtreamStreamUrlFactory.buildMovieUrl(
            credentials = XtreamCredentials(
                accountId = "account",
                serverUrl = "https://example.com/base/",
                username = "user/name",
                password = "p ass?#%",
            ),
            movieId = "../Film ü/1",
            extension = "mp4",
        ).toHttpUrl()

        assertThat(url.pathSegments).containsExactly(
            "base",
            "movie",
            "user/name",
            "p ass?#%",
            "../Film ü/1.mp4",
        ).inOrder()
        assertThat(url.encodedPath).contains("..%2FFilm%20%C3%BC%2F1.mp4")
    }

    @Test
    fun `movie extensions outside the safe list are rejected`() {
        listOf("", "exe", "MP4", " mkv ", "php", "m3u8").forEach { extension ->
            assertThrows(IllegalArgumentException::class.java) {
                XtreamStreamUrlFactory.buildMovieUrl(credentials(), "501", extension)
            }
        }
    }

    @Test
    fun `blank movie ID is rejected`() {
        listOf("", "   ", "\t").forEach { movieId ->
            assertThrows(IllegalArgumentException::class.java) {
                XtreamStreamUrlFactory.buildMovieUrl(credentials(), movieId, "mp4")
            }
        }
    }

    @Test
    fun `episode URL uses the series path, not the movie path`() {
        val url = XtreamStreamUrlFactory.buildEpisodeUrl(
            credentials = credentials(serverUrl = "https://example.com:8443/xtream/"),
            episodeId = "101",
            extension = "mkv",
        )

        assertThat(url).isEqualTo("https://example.com:8443/xtream/series/alice/secret/101.mkv")
    }

    @Test
    fun `episode credentials and IDs are encoded as individual path segments`() {
        val url = XtreamStreamUrlFactory.buildEpisodeUrl(
            credentials = XtreamCredentials(
                accountId = "account",
                serverUrl = "https://example.com/base/",
                username = "user/name",
                password = "p ass?#%",
            ),
            episodeId = "../Folge ü/1",
            extension = "mp4",
        ).toHttpUrl()

        assertThat(url.pathSegments).containsExactly(
            "base",
            "series",
            "user/name",
            "p ass?#%",
            "../Folge ü/1.mp4",
        ).inOrder()
        assertThat(url.encodedPath).contains("..%2FFolge%20%C3%BC%2F1.mp4")
    }

    @Test
    fun `episode extensions outside the safe list are rejected`() {
        listOf("", "exe", "MP4", " mkv ", "php", "m3u8").forEach { extension ->
            assertThrows(IllegalArgumentException::class.java) {
                XtreamStreamUrlFactory.buildEpisodeUrl(credentials(), "101", extension)
            }
        }
    }

    @Test
    fun `blank episode ID is rejected`() {
        listOf("", "   ", "\t").forEach { episodeId ->
            assertThrows(IllegalArgumentException::class.java) {
                XtreamStreamUrlFactory.buildEpisodeUrl(credentials(), episodeId, "mp4")
            }
        }
    }

    @Test
    fun `movie extension sanitizing normalizes case and a leading dot`() {
        assertThat(XtreamStreamUrlFactory.sanitizeVodExtension("MKV")).isEqualTo("mkv")
        assertThat(XtreamStreamUrlFactory.sanitizeVodExtension(" .Mp4 ")).isEqualTo("mp4")
        assertThat(XtreamStreamUrlFactory.sanitizeVodExtension("exe")).isNull()
        assertThat(XtreamStreamUrlFactory.sanitizeVodExtension(null)).isNull()
    }

    @Test
    fun `movie extension selection falls back to the Xtream default`() {
        assertThat(XtreamStreamUrlFactory.selectVodExtension("avi")).isEqualTo("avi")
        assertThat(XtreamStreamUrlFactory.selectVodExtension(null)).isEqualTo("mp4")
        assertThat(XtreamStreamUrlFactory.selectVodExtension("exe")).isEqualTo("mp4")
        assertThat(XtreamStreamUrlFactory.DEFAULT_VOD_EXTENSION)
            .isIn(XtreamStreamUrlFactory.SAFE_VOD_EXTENSIONS)
    }

    @Test
    fun `blank stream ID is rejected`() {
        listOf("", "   ", "\t").forEach { streamId ->
            assertThrows(IllegalArgumentException::class.java) {
                XtreamStreamUrlFactory.buildLiveUrl(credentials(), streamId, "ts")
            }
        }
    }

    @Test
    fun `unsupported or differently cased format is rejected`() {
        listOf("", "mp4", "M3U8", " ts ").forEach { format ->
            assertThrows(IllegalArgumentException::class.java) {
                XtreamStreamUrlFactory.buildLiveUrl(credentials(), "42", format)
            }
        }
    }

    @Test
    fun `invalid server URL is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            XtreamStreamUrlFactory.buildLiveUrl(
                credentials(serverUrl = "not a normalized URL"),
                streamId = "42",
                format = "ts",
            )
        }
    }

    @Test
    fun `without a channel the account alone decides the format`() {
        assertThat(XtreamStreamUrlFactory.selectFormat(account(setOf("m3u8", "ts")))).isEqualTo("m3u8")
        assertThat(XtreamStreamUrlFactory.selectFormat(account(setOf("M3U8")))).isEqualTo("m3u8")
        assertThat(XtreamStreamUrlFactory.selectFormat(account(setOf("ts")))).isEqualTo("ts")
        // A provider that advertises nothing usable still has to be played, and ts is the format
        // every Xtream server serves.
        assertThat(XtreamStreamUrlFactory.selectFormat(account(emptySet()))).isEqualTo("ts")
        assertThat(XtreamStreamUrlFactory.selectFormat(account(setOf("rtmp")))).isEqualTo("ts")
    }

    @Test
    fun `the channel-less answer matches the one a channel without a container gets`() {
        setOf(setOf("m3u8", "ts"), setOf("ts"), setOf("m3u8"), emptySet()).forEach { allowed ->
            assertThat(XtreamStreamUrlFactory.selectFormat(account(allowed)))
                .isEqualTo(select(allowed, channel = null))
        }
    }

    private fun account(allowed: Set<String>) = Account(
        id = "account",
        username = "alice",
        serverUrl = "https://example.com/",
        status = AccountStatus.Active,
        expiresAtEpochSeconds = null,
        activeConnections = null,
        maximumConnections = null,
        serverTimezone = null,
        allowedOutputFormats = allowed,
        lastValidatedAtEpochMillis = 0,
    )

    private fun select(allowed: Set<String>, channel: String?): String =
        XtreamStreamUrlFactory.selectFormat(
            account = Account(
                id = "account",
                username = "alice",
                serverUrl = "https://example.com/",
                status = AccountStatus.Active,
                expiresAtEpochSeconds = null,
                activeConnections = null,
                maximumConnections = null,
                serverTimezone = null,
                allowedOutputFormats = allowed,
                lastValidatedAtEpochMillis = 0,
            ),
            channel = LiveChannel(
                id = "42",
                categoryId = null,
                name = "Channel",
                logoUrl = null,
                epgChannelId = null,
                containerExtension = channel,
                directSource = null,
                providerOrder = 0,
            ),
        )

    private fun credentials(serverUrl: String = "https://example.com/") = XtreamCredentials(
        accountId = "account",
        serverUrl = serverUrl,
        username = "alice",
        password = "secret",
    )
}
