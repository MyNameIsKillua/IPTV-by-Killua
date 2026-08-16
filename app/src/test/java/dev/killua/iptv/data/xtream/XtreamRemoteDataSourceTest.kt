package dev.killua.iptv.data.xtream

import com.google.common.truth.Truth.assertThat
import dev.killua.iptv.core.network.NetworkFailureMapper
import dev.killua.iptv.core.network.NetworkStatus
import dev.killua.iptv.domain.model.AppFailureException
import dev.killua.iptv.domain.model.FailureKind
import dev.killua.iptv.domain.model.XtreamCredentials
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

class XtreamRemoteDataSourceTest {
    private lateinit var server: MockWebServer
    private lateinit var dataSource: XtreamRemoteDataSource
    private lateinit var credentials: XtreamCredentials

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        dataSource = XtreamRemoteDataSource(
            retrofit = Retrofit.Builder()
                .baseUrl(server.url("/"))
                .build(),
            failureMapper = NetworkFailureMapper(
                object : NetworkStatus {
                    override fun hasActiveNetwork(): Boolean = true
                },
            ),
        )
        credentials = XtreamCredentials(
            accountId = "account",
            serverUrl = server.url("/provider/root/").toString(),
            username = "user+name@example.com",
            password = "p&ss word?",
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `authentication uses the player endpoint encodes credentials and omits action`() = runTest {
        server.enqueue(
            jsonResponse(
                """{"user_info":{"auth":1,"username":"alice","status":"active"}}""",
            ),
        )

        val account = dataSource.authenticate(credentials)
        val request = server.takeRequest(1, TimeUnit.SECONDS)

        assertThat(account.username).isEqualTo("alice")
        assertThat(request).isNotNull()
        val url = request!!.requestUrl!!
        assertThat(url.encodedPath).isEqualTo("/provider/root/player_api.php")
        assertThat(url.queryParameter("username")).isEqualTo(credentials.username)
        assertThat(url.queryParameter("password")).isEqualTo(credentials.password)
        assertThat(url.queryParameter("action")).isNull()
    }

    @Test
    fun `live endpoints send the correct actions and parse their responses`() = runTest {
        server.enqueue(jsonResponse("""[{"category_id":"7","category_name":"News"}]"""))
        server.enqueue(
            jsonResponse(
                """[{"stream_id":"42","category_id":"7","name":"News HD","container_extension":"m3u8"}]""",
            ),
        )

        val categories = dataSource.liveCategories(credentials)
        val channels = collectChannels()

        assertThat(categories.single().name).isEqualTo("News")
        assertThat(channels.single().name).isEqualTo("News HD")
        assertThat(server.takeRequest().requestUrl!!.queryParameter("action"))
            .isEqualTo("get_live_categories")
        assertThat(server.takeRequest().requestUrl!!.queryParameter("action"))
            .isEqualTo("get_live_streams")
    }

    @Test
    fun `movie endpoints send the correct actions and omit the vod id`() = runTest {
        server.enqueue(jsonResponse("""[{"category_id":"20","category_name":"Action"}]"""))
        server.enqueue(jsonResponse("""[{"stream_id":"501","name":"Beispielfilm"}]"""))

        val categories = dataSource.movieCategories(credentials)
        val movies = collectMovies()

        assertThat(categories.single().name).isEqualTo("Action")
        assertThat(movies.single().name).isEqualTo("Beispielfilm")
        server.takeRequest().requestUrl!!.let { url ->
            assertThat(url.queryParameter("action")).isEqualTo("get_vod_categories")
            assertThat(url.queryParameter("vod_id")).isNull()
        }
        server.takeRequest().requestUrl!!.let { url ->
            assertThat(url.queryParameter("action")).isEqualTo("get_vod_streams")
            assertThat(url.queryParameter("vod_id")).isNull()
        }
    }

    @Test
    fun `movie details send an encoded vod id alongside the action`() = runTest {
        server.enqueue(jsonResponse("""{"movie_data":{"stream_id":"a b&c=1","name":"Film"}}"""))

        val details = dataSource.movieDetails(credentials, movieId = "a b&c=1")
        val request = server.takeRequest(1, TimeUnit.SECONDS)

        assertThat(details.name).isEqualTo("Film")
        val url = request!!.requestUrl!!
        assertThat(url.encodedPath).isEqualTo("/provider/root/player_api.php")
        assertThat(url.queryParameter("action")).isEqualTo("get_vod_info")
        assertThat(url.queryParameter("vod_id")).isEqualTo("a b&c=1")
        // The value must stay one parameter instead of injecting `c=1` into the query.
        assertThat(url.querySize).isEqualTo(4)
        assertThat(url.encodedQuery).doesNotContain("a b&c=1")
    }

    @Test
    fun `series listing and categories use their own actions`() = runTest {
        server.enqueue(jsonResponse("""[{"category_id":"90","category_name":"Serien"}]"""))
        server.enqueue(jsonResponse("""[{"series_id":"7","name":"Beispielserie"}]"""))

        val categories = dataSource.seriesCategories(credentials)
        val series = dataSource.withSeriesSummaries(credentials) { it.toList() }

        assertThat(categories.single().name).isEqualTo("Serien")
        assertThat(series.single().name).isEqualTo("Beispielserie")
        server.takeRequest().requestUrl!!.let { url ->
            assertThat(url.queryParameter("action")).isEqualTo("get_series_categories")
            assertThat(url.queryParameter("series_id")).isNull()
        }
        server.takeRequest().requestUrl!!.let { url ->
            assertThat(url.queryParameter("action")).isEqualTo("get_series")
            assertThat(url.queryParameter("series_id")).isNull()
        }
    }

    @Test
    fun `series details send an encoded series id alongside the action`() = runTest {
        server.enqueue(jsonResponse("""{"info":{"name":"Serie"},"episodes":{}}"""))

        val details = dataSource.seriesDetails(credentials, seriesId = "a b&c=1")
        val request = server.takeRequest(1, TimeUnit.SECONDS)

        assertThat(details.name).isEqualTo("Serie")
        val url = request!!.requestUrl!!
        assertThat(url.queryParameter("action")).isEqualTo("get_series_info")
        assertThat(url.queryParameter("series_id")).isEqualTo("a b&c=1")
        // The value must stay one parameter instead of injecting `c=1` into the query.
        assertThat(url.querySize).isEqualTo(4)
        assertThat(url.encodedQuery).doesNotContain("a b&c=1")
    }

    @Test
    fun `a blank series id never reaches the provider`() = runTest {
        listOf("", "   ").forEach { seriesId ->
            try {
                dataSource.seriesDetails(credentials, seriesId)
                error("Expected a rejection before any request")
            } catch (_: IllegalArgumentException) {
                // Expected: the guard runs before the network call.
            }
        }
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `a blank movie id never reaches the provider`() = runTest {
        listOf("", "   ").forEach { movieId ->
            try {
                dataSource.movieDetails(credentials, movieId)
                throw AssertionError("Expected IllegalArgumentException")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun `large listings stream instead of being buffered whole`() = runTest {
        // 40k entries is far past the point where buffering the response into a String plus a
        // JsonElement tree exhausted Android's heap and crashed the app.
        val movies = (1..40_000).joinToString(",", prefix = "[", postfix = "]") { index ->
            """{"stream_id":"$index","name":"Film $index","category_id":"20",""" +
                """"stream_icon":"https://images.provider.example/poster-$index.jpg"}"""
        }
        server.enqueue(jsonResponse(movies))

        // Counted without retaining, exactly how the repository writes it to the database.
        assertThat(countMovies()).isEqualTo(40_000)
    }

    @Test
    fun `a listing keyed by object still parses through the buffered fallback`() = runTest {
        server.enqueue(
            jsonResponse("""{"a":{"stream_id":"1","name":"Erster"},"b":{"stream_id":"2"}}"""),
        )

        val parsed = collectMovies()

        assertThat(parsed.map { it.id }).containsExactly("1", "2").inOrder()
        assertThat(parsed.first().name).isEqualTo("Erster")
    }

    @Test
    fun `a byte order mark before the array does not break streaming`() = runTest {
        server.enqueue(jsonResponse("﻿[{\"stream_id\":\"7\",\"name\":\"Mit BOM\"}]"))

        val parsed = collectMovies()

        assertThat(parsed.single().name).isEqualTo("Mit BOM")
    }

    @Test
    fun `a streamed listing still rejects HTML and malformed payloads`() = runTest {
        server.enqueue(jsonResponse("<html>error</html>"))
        assertThat(assertFailure { collectMovies() }.failure.kind)
            .isEqualTo(FailureKind.InvalidServerResponse)

        server.enqueue(jsonResponse("""[{"stream_id":"1"}"""))
        assertThat(assertFailure { collectChannels() }.failure.kind)
            .isEqualTo(FailureKind.InvalidServerResponse)
    }

    @Test
    fun `retryable HTTP failures succeed on the third attempt`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("temporarily unavailable"))
        server.enqueue(MockResponse().setResponseCode(429).setBody("slow down"))
        server.enqueue(jsonResponse("""[{"category_id":"1","category_name":"News"}]"""))

        val categories = dataSource.liveCategories(credentials)

        assertThat(categories).hasSize(1)
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun `retry budget is bounded to three total attempts`() = runTest {
        repeat(3) {
            server.enqueue(MockResponse().setResponseCode(503).setBody("temporarily unavailable"))
        }

        val failure = assertFailure { dataSource.liveCategories(credentials) }

        assertThat(failure.failure.kind).isEqualTo(FailureKind.TemporaryServerFailure)
        assertThat(failure.failure.retryable).isTrue()
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun `non-retryable authentication errors fail immediately`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))

        val failure = assertFailure { dataSource.authenticate(credentials) }

        assertThat(failure.failure.kind).isEqualTo(FailureKind.AuthenticationFailed)
        assertThat(failure.failure.retryable).isFalse()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `404 is mapped differently for authentication and library calls`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        val authFailure = assertFailure { dataSource.authenticate(credentials) }

        server.enqueue(MockResponse().setResponseCode(404))
        val libraryFailure = assertFailure { collectChannels() }

        assertThat(authFailure.failure.kind).isEqualTo(FailureKind.IncompatibleServer)
        assertThat(libraryFailure.failure.kind).isEqualTo(FailureKind.StreamUnavailable)
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `connection limit response is not retried`() = runTest {
        server.enqueue(MockResponse().setResponseCode(509))

        val failure = assertFailure { collectChannels() }

        assertThat(failure.failure.kind).isEqualTo(FailureKind.ConnectionLimitReached)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `successful HTTP response with malformed JSON fails without retry`() = runTest {
        server.enqueue(jsonResponse("<html>not Xtream JSON</html>"))

        val failure = assertFailure { dataSource.liveCategories(credentials) }

        assertThat(failure.failure.kind).isEqualTo(FailureKind.InvalidServerResponse)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `empty successful response is classified as invalid server response`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        val failure = assertFailure { dataSource.authenticate(credentials) }

        assertThat(failure.failure.kind).isEqualTo(FailureKind.InvalidServerResponse)
        assertThat(server.requestCount).isEqualTo(1)
    }

    /**
     * The listings are streamed, so a test that wants the whole result has to drain the sequence
     * before the response body closes. Production code consumes it in batches instead.
     */
    private suspend fun collectChannels() =
        dataSource.withLiveChannels(credentials) { channels -> channels.toList() }

    private suspend fun collectMovies() =
        dataSource.withMovieSummaries(credentials) { movies -> movies.toList() }

    /** Counts without retaining, the way the repository consumes a very large listing. */
    private suspend fun countMovies() =
        dataSource.withMovieSummaries(credentials) { movies -> movies.count() }

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private suspend fun assertFailure(block: suspend () -> Unit): AppFailureException {
        try {
            block()
            throw AssertionError("Expected AppFailureException")
        } catch (failure: AppFailureException) {
            return failure
        }
    }
}
