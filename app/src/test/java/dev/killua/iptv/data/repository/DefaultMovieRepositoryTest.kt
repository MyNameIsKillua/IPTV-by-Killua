package dev.killua.iptv.data.repository

import com.google.common.truth.Truth.assertThat
import dev.killua.iptv.core.database.MovieEntity
import dev.killua.iptv.core.network.NetworkFailureMapper
import dev.killua.iptv.core.network.NetworkStatus
import dev.killua.iptv.data.xtream.XtreamRemoteDataSource
import dev.killua.iptv.domain.model.AppFailure
import dev.killua.iptv.domain.model.AppFailureException
import dev.killua.iptv.domain.model.FailureKind
import dev.killua.iptv.domain.repository.SessionRepository
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit

/**
 * Covers the account-scoped Movie write path. Provider traffic is served by MockWebServer with
 * fictitious data; no real account is ever contacted.
 */
class DefaultMovieRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: DefaultMovieRepository

    private val transactions = RecordingTransactionRunner()
    private val movieDao = FakeMovieDao()
    private val accountDao = FakeAccountDao()
    private val vault = FakeCredentialVault(accountId = ACCOUNT)
    private lateinit var session: FakeSessionRepository
    private lateinit var coordinator: AccountDataCoordinator

    private var failEveryRequestWith: Int? = null

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        vault.serverUrl = server.url("/").toString()
        session = FakeSessionRepository(vault)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                failEveryRequestWith?.let { return MockResponse().setResponseCode(it) }
                return when (request.requestUrl?.queryParameter("action")) {
                    "get_vod_categories" -> jsonResponse(CATEGORIES_JSON)
                    "get_vod_streams" -> jsonResponse(MOVIES_JSON)
                    "get_vod_info" -> jsonResponse(DETAILS_JSON)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        coordinator = AccountDataCoordinator(
            transactions = transactions,
            accountDao = accountDao,
            credentialVault = vault,
            cleaners = { listOf(repository) },
        )
        repository = DefaultMovieRepository(
            movieDao = movieDao,
            accountData = coordinator,
            sessionRepositoryProvider = { session },
            remote = XtreamRemoteDataSource(
                retrofit = Retrofit.Builder().baseUrl(server.url("/")).build(),
                failureMapper = NetworkFailureMapper(
                    object : NetworkStatus {
                        override fun hasActiveNetwork(): Boolean = true
                    },
                ),
            ),
            nowMillis = { 1_000L },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `a successful refresh commits categories and movies in one transaction`() = runTest {
        val result = repository.refresh(ACCOUNT)

        assertThat(result.categoryCount).isEqualTo(2)
        assertThat(result.movieCount).isEqualTo(4)
        assertThat(movieDao.movies.map { it.remoteStreamId })
            .containsExactly("501", "502", "503", "504")
        assertThat(movieDao.movies.map { it.accountId }.toSet()).containsExactly(ACCOUNT)
        assertThat(transactions.started).isEqualTo(1)
        assertThat(transactions.maximumDepth).isEqualTo(1)
    }

    @Test
    fun `language is taken from the category and falls back to a title tag`() = runTest {
        repository.refresh(ACCOUNT)

        // 501 and 502 sit in the "DE | Action" category.
        assertThat(movie("501").languageTag).isEqualTo("de")
        assertThat(movie("502").languageTag).isEqualTo("de")
        // 503 sits in an untagged category but carries an explicit title tag.
        assertThat(movie("503").languageTag).isEqualTo("en")
    }

    @Test
    fun `sort names drop a language prefix so alphabetical paging uses the real title`() = runTest {
        repository.refresh(ACCOUNT)

        assertThat(movie("501").sortName).isEqualTo("beispielfilm")
        assertThat(movie("503").sortName).isEqualTo("example film")
    }

    @Test
    fun `a refresh whose account was logged out mid-download writes nothing`() = runTest {
        session.onCredentialsIssued = { vault.accountId = null }

        val failure = assertFailure { repository.refresh(ACCOUNT) }

        assertThat(failure.failure.kind).isEqualTo(FailureKind.AuthenticationFailed)
        assertThat(movieDao.movies).isEmpty()
        assertThat(movieDao.categories).isEmpty()
        assertThat(transactions.started).isEqualTo(0)
    }

    @Test
    fun `a failed download preserves the previously cached library`() = runTest {
        repository.refresh(ACCOUNT)
        val cached = movieDao.movies.toList()

        failEveryRequestWith = 503
        val failure = assertFailure { repository.refresh(ACCOUNT) }

        assertThat(failure.failure.kind).isEqualTo(FailureKind.TemporaryServerFailure)
        assertThat(movieDao.movies).isEqualTo(cached)
        assertThat(transactions.started).isEqualTo(1)
    }

    @Test
    fun `a metadata refresh never deletes favorites, details, or progress`() = runTest {
        repository.refresh(ACCOUNT)
        repository.setFavorite(ACCOUNT, "501", favorite = true)
        repository.details(ACCOUNT, "501")
        repository.saveProgress(ACCOUNT, "501", positionMs = 60_000, durationMs = 600_000)

        // The provider drops 501 from its listing on the next sync.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                when (request.requestUrl?.queryParameter("action")) {
                    "get_vod_categories" -> jsonResponse(CATEGORIES_JSON)
                    "get_vod_streams" -> jsonResponse("""[{"stream_id":"502","name":"Zweiter"}]""")
                    else -> MockResponse().setResponseCode(404)
                }
        }
        repository.refresh(ACCOUNT)

        assertThat(movieDao.movies.map { it.remoteStreamId }).containsExactly("502")
        assertThat(movieDao.favorites.map { it.remoteStreamId }).containsExactly("501")
        assertThat(movieDao.details.map { it.remoteStreamId }).containsExactly("501")
        assertThat(movieDao.progress.map { it.contentId }).containsExactly("501")
    }

    @Test
    fun `details are fetched once and then served from the cache`() = runTest {
        repository.refresh(ACCOUNT)
        val requestsAfterRefresh = server.requestCount

        val first = repository.details(ACCOUNT, "501")
        val second = repository.details(ACCOUNT, "501")

        assertThat(first.plot).isEqualTo("Eine kurze Beschreibung.")
        assertThat(first.genre).isEqualTo("Action")
        assertThat(second).isEqualTo(first)
        assertThat(server.requestCount).isEqualTo(requestsAfterRefresh + 1)
    }

    @Test
    fun `details keep listing artwork and identity rather than detail nulls`() = runTest {
        repository.refresh(ACCOUNT)

        val details = repository.details(ACCOUNT, "501")

        assertThat(details.id).isEqualTo("501")
        assertThat(details.posterUrl).isEqualTo("https://images.provider.example/a.jpg")
        assertThat(details.rating).isWithin(1e-9).of(7.4)
    }

    @Test
    fun `details for an unknown movie fail safely without a request`() = runTest {
        val before = server.requestCount

        val failure = assertFailure { repository.details(ACCOUNT, "does-not-exist") }

        assertThat(failure.failure.kind).isEqualTo(FailureKind.StreamUnavailable)
        assertThat(server.requestCount).isEqualTo(before)
    }

    @Test
    fun `favorites toggle and are rejected after logout`() = runTest {
        repository.refresh(ACCOUNT)

        repository.setFavorite(ACCOUNT, "501", favorite = true)
        assertThat(movieDao.favorites).hasSize(1)
        repository.setFavorite(ACCOUNT, "501", favorite = false)
        assertThat(movieDao.favorites).isEmpty()

        vault.accountId = null
        val failure = assertFailure { repository.setFavorite(ACCOUNT, "501", favorite = true) }
        assertThat(failure.failure.kind).isEqualTo(FailureKind.AuthenticationFailed)
        assertThat(movieDao.favorites).isEmpty()
    }

    @Test
    fun `progress is clamped, marked complete by policy, and account scoped`() = runTest {
        repository.refresh(ACCOUNT)

        repository.saveProgress(ACCOUNT, "501", positionMs = 900_000, durationMs = 600_000)
        val clamped = movieDao.progress.single()
        assertThat(clamped.positionMs).isEqualTo(600_000)
        assertThat(clamped.completed).isTrue()
        assertThat(clamped.accountId).isEqualTo(ACCOUNT)
        assertThat(clamped.contentType).isEqualTo("movie")

        repository.saveProgress(ACCOUNT, "501", positionMs = 60_000, durationMs = 600_000)
        assertThat(movieDao.progress.single().completed).isFalse()
        assertThat(repository.progress(ACCOUNT, "501")?.positionMs).isEqualTo(60_000)
    }

    @Test
    fun `implausible progress is ignored rather than stored`() = runTest {
        repository.refresh(ACCOUNT)

        repository.saveProgress(ACCOUNT, "501", positionMs = 1_000, durationMs = 0)
        repository.saveProgress(ACCOUNT, "501", positionMs = -5, durationMs = 600_000)
        // A title that is not in this account's library must never gain progress.
        repository.saveProgress(ACCOUNT, "unknown", positionMs = 1_000, durationMs = 600_000)

        assertThat(movieDao.progress).isEmpty()
    }

    @Test
    fun `marking a movie watched stores completion without a played position`() = runTest {
        repository.refresh(ACCOUNT)

        repository.setWatched(ACCOUNT, "501", watched = true)

        val stored = movieDao.progress.single()
        assertThat(stored.completed).isTrue()
        assertThat(stored.contentType).isEqualTo("movie")
        // Nothing was played, so there is no runtime to claim. Inventing one would make a later
        // resume point a lie; the mark itself is the fact worth storing.
        assertThat(stored.durationMs).isEqualTo(0L)
        assertThat(stored.positionMs).isEqualTo(0L)
    }

    @Test
    fun `marking a partly watched movie watched fills its bar rather than dropping the duration`() =
        runTest {
            repository.refresh(ACCOUNT)
            repository.saveProgress(ACCOUNT, "501", positionMs = 60_000, durationMs = 600_000)

            repository.setWatched(ACCOUNT, "501", watched = true)

            val stored = movieDao.progress.single()
            assertThat(stored.completed).isTrue()
            assertThat(stored.durationMs).isEqualTo(600_000L)
            assertThat(stored.positionMs).isEqualTo(600_000L)
        }

    @Test
    fun `marking a movie unwatched removes the position instead of clearing a flag`() = runTest {
        repository.refresh(ACCOUNT)
        repository.saveProgress(ACCOUNT, "501", positionMs = 590_000, durationMs = 600_000)
        assertThat(movieDao.progress.single().completed).isTrue()

        repository.setWatched(ACCOUNT, "501", watched = false)

        // Leaving the position behind would resume the next Play three minutes from the end of
        // something the viewer just said they had not seen.
        assertThat(movieDao.progress).isEmpty()
        assertThat(repository.progress(ACCOUNT, "501")).isNull()
    }

    @Test
    fun `marking is refused for a title this account does not have, and after logout`() = runTest {
        repository.refresh(ACCOUNT)

        repository.setWatched(ACCOUNT, "unknown", watched = true)
        assertThat(movieDao.progress).isEmpty()

        vault.accountId = null
        val failure = assertFailure { repository.setWatched(ACCOUNT, "501", watched = true) }
        assertThat(failure.failure.kind).isEqualTo(FailureKind.AuthenticationFailed)
        assertThat(movieDao.progress).isEmpty()
    }

    @Test
    fun `progress after logout is rejected`() = runTest {
        repository.refresh(ACCOUNT)
        vault.accountId = null

        val failure = assertFailure {
            repository.saveProgress(ACCOUNT, "501", positionMs = 60_000, durationMs = 600_000)
        }

        assertThat(failure.failure.kind).isEqualTo(FailureKind.AuthenticationFailed)
        assertThat(movieDao.progress).isEmpty()
    }

    @Test
    fun `logout clears every movie table through the shared coordinator`() = runTest {
        repository.refresh(ACCOUNT)
        repository.setFavorite(ACCOUNT, "501", favorite = true)
        repository.details(ACCOUNT, "501")
        repository.saveProgress(ACCOUNT, "501", positionMs = 60_000, durationMs = 600_000)

        coordinator.clearAllAccountData()

        assertThat(movieDao.movies).isEmpty()
        assertThat(movieDao.categories).isEmpty()
        assertThat(movieDao.details).isEmpty()
        assertThat(movieDao.favorites).isEmpty()
        assertThat(movieDao.progress).isEmpty()
    }

    private fun movie(id: String): MovieEntity =
        movieDao.movies.single { it.remoteStreamId == id }

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


    @Test
    fun `global search matches anywhere in the title and is bounded`() = runTest {
        repository.refresh(ACCOUNT)
        // Three fixture titles contain "film", and none of them start with it.

        val section = repository.search(ACCOUNT, "film", limit = 1)

        assertThat(section.items).hasSize(1)
        // More matched than were asked for, without a second counting scan.
        assertThat(section.hasMore).isTrue()
    }

    @Test
    fun `global search refuses a term shorter than the minimum`() = runTest {
        repository.refresh(ACCOUNT)

        val section = repository.search(ACCOUNT, "b", limit = 20)

        assertThat(section.items).isEmpty()
        assertThat(section.hasMore).isFalse()
    }

    @Test
    fun `a wildcard-only term never becomes match-everything`() = runTest {
        repository.refresh(ACCOUNT)

        // Normalization leaves nothing, so no query runs at all. Before it, escaping was what
        // stopped this from returning the whole library; both defences are still in place.
        val section = repository.search(ACCOUNT, "%%", limit = 20)

        assertThat(section.items).isEmpty()
    }

    @Test
    fun `a title with punctuation is found however the viewer spells it`() = runTest {
        repository.refresh(ACCOUNT)

        // The reported bug: only `mr. robot` used to find the cached `Mr. Robot`.
        listOf("mr robot", "mr. robot", "Mr Robot").forEach { term ->
            val section = repository.search(ACCOUNT, term, limit = 20)
            assertThat(section.items.map { it.id }).containsExactly("504")
        }
    }

    @Test
    fun `global search is scoped to the account`() = runTest {
        repository.refresh(ACCOUNT)

        val section = repository.search("another-account", "film", limit = 20)

        assertThat(section.items).isEmpty()
    }

    private companion object {
        const val ACCOUNT = "account-under-test"

        const val CATEGORIES_JSON = """
            [
              {"category_id":"20","category_name":"DE | Action"},
              {"category_id":"21","category_name":"Dokumentationen"}
            ]
        """

        const val MOVIES_JSON = """
            [
              {"stream_id":"501","category_id":"20","name":"DE | Beispielfilm","rating":"7.4",
               "year":"2019","added":"1690000000","container_extension":"mkv",
               "stream_icon":"https://images.provider.example/a.jpg"},
              {"stream_id":"502","category_id":"20","name":"Zweiter Film"},
              {"stream_id":"503","category_id":"21","name":"EN | Example Film"},
              {"stream_id":"504","category_id":"21","name":"Mr. Robot"}
            ]
        """

        const val DETAILS_JSON = """
            {
              "info": {"plot":"Eine kurze Beschreibung.","genre":"Action","duration_secs":6753},
              "movie_data": {"stream_id":"501","name":"DE | Beispielfilm",
                             "container_extension":"mkv"}
            }
        """
    }
}
