package dev.killua.iptv.desktop

import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

/**
 * How hard this client leans on a provider's image server.
 *
 * This became worth a test the day a poster grid stopped being one category of a few hundred and
 * became the whole library. A lazy grid only composes what is on screen, so the danger was never how
 * many tiles exist — it is what a **scroll** does: every tile that passes starts a fetch, and a
 * synchronous HTTP call does not stop because the coroutine around it was cancelled.
 *
 * The server here counts how many requests are in its hands at once, which is the only number that
 * matters to whoever is being asked.
 */
class ArtworkLoaderTest {

    private lateinit var server: HttpServer
    private val concurrent = AtomicInteger()
    private val peak = AtomicInteger()
    private val served = AtomicInteger()

    @Before
    fun start() {
        val png = ByteArrayOutputStream().also {
            ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", it)
        }.toByteArray()

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val now = concurrent.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            // Long enough that a client without a bound would pile up visibly here.
            Thread.sleep(120)
            served.incrementAndGet()
            exchange.sendResponseHeaders(200, png.size.toLong())
            exchange.responseBody.use { it.write(png) }
            concurrent.decrementAndGet()
        }
        // A pool wider than the bound, or this would prove the server's limit rather than the
        // client's.
        server.executor = java.util.concurrent.Executors.newFixedThreadPool(32)
        server.start()
    }

    @After
    fun stop() {
        server.stop(0)
        runBlocking { ArtworkLoader.clearCache() }
    }

    @Test
    fun `it never asks for more than a handful of posters at once`() = runBlocking {
        val urls = (1..40).map { "http://127.0.0.1:${server.address.port}/poster-$it.png" }

        coroutineScope {
            urls.map { async { ArtworkLoader.load(it) } }.awaitAll()
        }

        assertThat(served.get()).isEqualTo(urls.size)
        assertThat(peak.get()).isAtMost(IN_FLIGHT)
    }

    @Test
    fun `a poster already fetched is not fetched again`() = runBlocking {
        val url = "http://127.0.0.1:${server.address.port}/one.png"

        assertThat(ArtworkLoader.load(url)).isNotNull()
        assertThat(ArtworkLoader.load(url)).isNotNull()

        assertThat(served.get()).isEqualTo(1)
    }

    @Test
    fun `the same poster wanted by two tiles at once is allowed to be fetched twice`() = runBlocking {
        // Deliberate, and the alternative is worse. Sharing one fetch between callers means the
        // fetch has to outlive them - and the property that matters far more on a six-figure grid
        // is that a poster scrolled past is *abandoned*. The duplicate is bounded by the permit
        // count, costs one small image, and only happens when the same picture is on screen twice.
        val url = "http://127.0.0.1:${server.address.port}/two.png"

        coroutineScope {
            (1..4).map { async { ArtworkLoader.load(url) } }.awaitAll()
        }

        assertThat(served.get()).isAtMost(IN_FLIGHT)
        assertThat(ArtworkLoader.load(url)).isNotNull()
    }

    @Test
    fun `a poster that is not there is not asked for twice`() = runBlocking {
        val url = "http://127.0.0.1:${server.address.port}/nothing"
        server.createContext("/nothing") { exchange ->
            served.incrementAndGet()
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }

        assertThat(ArtworkLoader.load(url)).isNull()
        assertThat(ArtworkLoader.load(url)).isNull()

        // A missing poster is extremely common on this kind of provider, and asking again on every
        // scroll would be a retry storm over something nobody can fix.
        assertThat(served.get()).isEqualTo(1)
    }
}
