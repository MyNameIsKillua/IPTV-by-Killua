package dev.killua.iptv.desktop

import com.google.common.truth.Truth.assertThat
import dev.killua.iptv.core.network.StreamUrlPolicy
import dev.killua.iptv.core.network.StreamUrlRefusal
import dev.killua.iptv.core.network.StreamUrlVerdict
import dev.killua.iptv.domain.model.LiveChannel
import dev.killua.iptv.domain.model.XtreamCredentials
import java.io.IOException
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Test

class PlaylistLibraryReaderTest {
    private val server = MockWebServer().apply { start() }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `a playlist offers channels and says it has nothing else`() {
        val reader = PlaylistLibraryReader("https://playlist.example/index.m3u")

        assertThat(reader.offers).containsExactly(LibraryKind.Channels)
    }

    @Test
    fun `the films and series a playlist does not have come back empty rather than failing`() =
        runTest {
            val reader = PlaylistLibraryReader("https://playlist.example/index.m3u")

            assertThat(reader.withAllMovies(CREDENTIALS) { it.toList() }).isEmpty()
            assertThat(reader.withAllSeries(CREDENTIALS) { it.toList() }).isEmpty()
        }

    @Test
    fun `a served playlist becomes channels`() = runTest {
        server.enqueue(
            MockResponse(
                body = """
                #EXTM3U
                #EXTINF:-1 tvg-logo="https://images.example/a.png" group-title="News",Channel A
                https://stream.example/a.m3u8
                #EXTINF:-1 http-user-agent="Mozilla/5.0",Channel B
                https://stream.example/b.m3u8
                """.trimIndent(),
            ),
        )
        val reader = readerFor("/index.m3u")

        val channels = reader.withAllChannels(CREDENTIALS) { it.toList() }

        assertThat(channels.map(LiveChannel::name)).containsExactly("Channel A", "Channel B").inOrder()
        assertThat(channels[0].categoryId).isEqualTo("News")
        assertThat(channels[1].streamHeaders?.userAgent).isEqualTo("Mozilla/5.0")
        assertThat(reader.lastReport.accepted).isEqualTo(2)
    }

    @Test
    fun `a redirect is followed, because the useful playlist addresses do`() = runTest {
        server.enqueue(MockResponse(code = 302, headers = redirectTo("/real.m3u")))
        server.enqueue(MockResponse(body = "#EXTM3U\n#EXTINF:-1,Moved\nhttps://stream.example/a.m3u8"))
        val reader = readerFor("/start.m3u")

        val channels = reader.withAllChannels(CREDENTIALS) { it.toList() }

        assertThat(channels.map(LiveChannel::name)).containsExactly("Moved")
    }

    @Test
    fun `each hop goes back through the address rule, so a redirect cannot reach a refused host`() =
        runTest {
            server.enqueue(MockResponse(code = 302, headers = redirectTo("http://10.0.0.1/secret.m3u")))
            // The real rule, not the loopback-permitting one: this is the case it exists for.
            val reader = PlaylistLibraryReader(
                playlistUrl = server.url("/start.m3u").toString(),
                checkAddress = { url ->
                    if (url.contains("10.0.0.1")) StreamUrlPolicy.check(url) else allowLoopback(url)
                },
            )

            val failure = runCatching { reader.withAllChannels(CREDENTIALS) { it.toList() } }
                .exceptionOrNull()

            assertThat(failure).isInstanceOf(IOException::class.java)
            assertThat(failure).hasMessageThat().contains(StreamUrlRefusal.PrivateAddress.name)
        }

    @Test
    fun `a refusal names the reason and never the address`() = runTest {
        server.enqueue(MockResponse(code = 302, headers = redirectTo("http://192.168.1.1/admin.m3u")))
        val reader = PlaylistLibraryReader(
            playlistUrl = server.url("/start.m3u").toString(),
            checkAddress = { url ->
                if (url.contains("192.168")) StreamUrlPolicy.check(url) else allowLoopback(url)
            },
        )

        val message = runCatching { reader.withAllChannels(CREDENTIALS) { it.toList() } }
            .exceptionOrNull()
            ?.message
            .orEmpty()

        assertThat(message).doesNotContain("192.168")
        assertThat(message).doesNotContain("admin.m3u")
    }

    @Test
    fun `a chain of redirects is bounded rather than followed forever`() = runTest {
        repeat(8) { server.enqueue(MockResponse(code = 302, headers = redirectTo("/again.m3u"))) }
        val reader = readerFor("/start.m3u")

        val failure = runCatching { reader.withAllChannels(CREDENTIALS) { it.toList() } }
            .exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("Too many redirects")
    }

    @Test
    fun `a redirect with no destination fails rather than retrying the same address`() = runTest {
        server.enqueue(MockResponse(code = 302))
        val reader = readerFor("/start.m3u")

        val failure = runCatching { reader.withAllChannels(CREDENTIALS) { it.toList() } }
            .exceptionOrNull()

        assertThat(failure).hasMessageThat().contains("Redirect without a destination")
    }

    @Test
    fun `a server that refuses is a failure and not an empty library`() = runTest {
        server.enqueue(MockResponse(code = 404))
        val reader = readerFor("/missing.m3u")

        val failure = runCatching { reader.withAllChannels(CREDENTIALS) { it.toList() } }
            .exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(failure).hasMessageThat().contains("404")
    }

    @Test
    fun `the reading is lazy, so a caller that stops early does not parse the rest`() = runTest {
        val body = buildString {
            appendLine("#EXTM3U")
            repeat(5_000) { index ->
                appendLine("#EXTINF:-1,Channel $index")
                appendLine("https://stream.example/$index.m3u8")
            }
        }
        server.enqueue(MockResponse(body = body))
        val reader = readerFor("/big.m3u")

        val firstThree = reader.withAllChannels(CREDENTIALS) { it.take(3).toList() }

        assertThat(firstThree).hasSize(3)
        assertThat(reader.lastReport.accepted).isEqualTo(3)
    }

    private fun readerFor(path: String) = PlaylistLibraryReader(
        playlistUrl = server.url(path).toString(),
        checkAddress = ::allowLoopback,
    )

    private fun redirectTo(location: String) = okhttp3.Headers.headersOf("Location", location)

    /**
     * The real rule, with loopback permitted so a local server can be reached at all.
     *
     * Everything else - the scheme list, credentials in the address, the private ranges - is still
     * [StreamUrlPolicy]'s answer, so a test that passes here is not passing because the rule was
     * turned off.
     */
    private fun allowLoopback(url: String): StreamUrlVerdict =
        when (val verdict = StreamUrlPolicy.check(url)) {
            is StreamUrlVerdict.Allowed -> verdict
            is StreamUrlVerdict.Refused ->
                if (verdict.reason == StreamUrlRefusal.LoopbackAddress) {
                    StreamUrlVerdict.Allowed(url, isCleartext = url.startsWith("http://"))
                } else {
                    verdict
                }
        }

    private companion object {
        val CREDENTIALS = XtreamCredentials(
            accountId = "desktop",
            serverUrl = "https://playlist.example/",
            username = "",
            password = "",
        )
    }
}
