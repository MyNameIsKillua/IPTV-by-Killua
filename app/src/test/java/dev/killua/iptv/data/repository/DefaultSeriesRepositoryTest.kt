package dev.killua.iptv.data.repository

import com.google.common.truth.Truth.assertThat
import dev.killua.iptv.core.database.SeriesEpisodeEntity
import dev.killua.iptv.core.network.NetworkFailureMapper
import dev.killua.iptv.core.network.NetworkStatus
import dev.killua.iptv.data.xtream.XtreamRemoteDataSource
import dev.killua.iptv.domain.model.AppFailureException
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
 * Covers the account-scoped Series write path. Provider traffic is served by MockWebServer with
 * fictitious data; no real account is ever contacted.
 */
class DefaultSeriesRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: DefaultSeriesRepository

    private val transactions = RecordingTransactionRunner()
    private val seriesDao = FakeSeriesDao()
    private val accountDao = FakeAccountDao()
    private val vault = FakeCredentialVault(accountId = ACCOUNT)
    private lateinit var session: FakeSessionRepository
    private lateinit var coordinator: AccountDataCoordinator

    private var failEveryRequestWith: Int? = null
    private var detailsJson = DETAILS_JSON

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
                    "get_series_categories" -> jsonResponse(CATEGORIES_JSON)
                    "get_series" -> jsonResponse(SERIES_JSON)
                    "get_series_info" -> jsonResponse(detailsJson)
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
        repository = DefaultSeriesRepository(
            seriesDao = seriesDao,
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
    fun `a successful refresh commits categories and series in one transaction`() = runTest {
        val result = repository.refresh(ACCOUNT)

        assertThat(result.categoryCount).isEqualTo(2)
        assertThat(result.seriesCount).isEqualTo(2)
        assertThat(seriesDao.series.map { it.remoteSeriesId }).containsExactly("7", "8")
        assertThat(seriesDao.series.map { it.accountId }.toSet()).containsExactly(ACCOUNT)
        assertThat(transactions.started).isEqualTo(1)
        assertThat(transactions.maximumDepth).isEqualTo(1)
    }

    @Test
    fun `a refresh derives the sort key and language the same way Movies do`() = runTest {
        repository.refresh(ACCOUNT)

        val tagged = seriesDao.series.single { it.remoteSeriesId == "7" }
        // The language comes from the category; the sort key drops the leading tag so the title
        // sorts under its own first letter, unlike a channel name.
        assertThat(tagged.languageTag).isEqualTo("de")
        assertThat(tagged.sortName).isEqualTo("beispielserie")

        val untagged = seriesDao.series.single { it.remoteSeriesId == "8" }
        // Its category says nothing, so the leading tag on the title itself decides.
        assertThat(untagged.languageTag).isEqualTo("fr")
    }

    @Test
    fun `a refresh whose account was logged out mid-download writes nothing`() = runTest {
        session.onCredentialsIssued = { vault.accountId = null }

        val failure = runCatching { repository.refresh(ACCOUNT) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(AppFailureException::class.java)
        assertThat(seriesDao.series).isEmpty()
        assertThat(seriesDao.categories).isEmpty()
    }

    @Test
    fun `a failed download preserves the previously cached library`() = runTest {
        repository.refresh(ACCOUNT)
        val cached = seriesDao.series.toList()

        failEveryRequestWith = 503
        runCatching { repository.refresh(ACCOUNT) }

        assertThat(seriesDao.series).containsExactlyElementsIn(cached)
    }

    @Test
    fun `details cache episodes and are served without a second request`() = runTest {
        repository.refresh(ACCOUNT)
        val requestsAfterRefresh = server.requestCount

        val details = repository.details(ACCOUNT, "7")
        val second = repository.details(ACCOUNT, "7")

        assertThat(details.episodes.map { it.id }).containsExactly("101", "102").inOrder()
        assertThat(details.genre).isEqualTo("Drama")
        assertThat(second.episodes.map { it.id }).containsExactly("101", "102").inOrder()
        // One fetch for the first call, none for the second.
        assertThat(server.requestCount).isEqualTo(requestsAfterRefresh + 1)
    }

    @Test
    fun `a details refresh replaces the episodes as a set`() = runTest {
        repository.refresh(ACCOUNT)
        repository.details(ACCOUNT, "7")
        assertThat(seriesDao.episodes.map { it.remoteEpisodeId }).containsExactly("101", "102")

        // The provider drops one episode and adds another.
        detailsJson = """
            {"info":{"series_id":"7","name":"Beispielserie"},
             "episodes":{"1":[{"id":"101","episode_num":1,"title":"Der Anfang"},
                              {"id":"103","episode_num":3,"title":"Neu"}]}}
        """.trimIndent()
        val refreshed = repository.details(ACCOUNT, "7", forceRefresh = true)

        assertThat(refreshed.episodes.map { it.id }).containsExactly("101", "103").inOrder()
        assertThat(seriesDao.episodes.map { it.remoteEpisodeId }).containsExactly("101", "103")
    }

    @Test
    fun `episodes of another series are untouched by a details refresh`() = runTest {
        repository.refresh(ACCOUNT)
        seriesDao.episodes += EPISODE_OF_ANOTHER_SERIES

        repository.details(ACCOUNT, "7", forceRefresh = true)

        assertThat(seriesDao.episodes.map { it.remoteEpisodeId }).contains("901")
    }

    @Test
    fun `details for an uncached series fail safely instead of inventing one`() = runTest {
        val failure = runCatching { repository.details(ACCOUNT, "999") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(AppFailureException::class.java)
        assertThat(seriesDao.details).isEmpty()
    }

    @Test
    fun `an episode position is stored under the episode content type`() = runTest {
        repository.refresh(ACCOUNT)
        repository.details(ACCOUNT, "7")

        repository.saveEpisodeProgress(ACCOUNT, "101", positionMs = 600_000L, durationMs = 2_700_000L)

        val stored = seriesDao.progress.single()
        assertThat(stored.contentType).isEqualTo("episode")
        assertThat(stored.contentId).isEqualTo("101")
        assertThat(stored.positionMs).isEqualTo(600_000L)
        assertThat(stored.completed).isFalse()
        assertThat(repository.episodeProgress(ACCOUNT, "101")?.positionMs).isEqualTo(600_000L)
    }

    @Test
    fun `a position at the end of an episode marks it completed`() = runTest {
        repository.refresh(ACCOUNT)
        repository.details(ACCOUNT, "7")

        repository.saveEpisodeProgress(
            ACCOUNT,
            "101",
            positionMs = 2_695_000L,
            durationMs = 2_700_000L,
        )

        assertThat(seriesDao.progress.single().completed).isTrue()
    }

    @Test
    fun `neighbouring episodes follow the listed order, including across a season`() = runTest {
        repository.refresh(ACCOUNT)
        val episodes = repository.details(ACCOUNT, "7").episodes
        assertThat(episodes.size).isAtLeast(2)
        val first = episodes.first().id
        val second = episodes[1].id

        assertThat(repository.nextEpisode(ACCOUNT, first)?.id).isEqualTo(second)
        assertThat(repository.previousEpisode(ACCOUNT, second)?.id).isEqualTo(first)
        // The two directions have to agree about the same list, or the player's controls would
        // disagree about what "next" and "previous" mean.
        assertThat(repository.previousEpisode(ACCOUNT, first)).isNull()
        assertThat(repository.nextEpisode(ACCOUNT, episodes.last().id)).isNull()
    }

    @Test
    fun `neighbours of an episode this account does not have are null`() = runTest {
        repository.refresh(ACCOUNT)
        repository.details(ACCOUNT, "7")

        assertThat(repository.previousEpisode(ACCOUNT, "unknown")).isNull()
        assertThat(repository.nextEpisode(ACCOUNT, "unknown")).isNull()
    }

    @Test
    fun `marking an episode watched and unwatched mirrors the Movie rules`() = runTest {
        repository.refresh(ACCOUNT)
        repository.details(ACCOUNT, "7")

        repository.setEpisodeWatched(ACCOUNT, "101", watched = true)
        val marked = seriesDao.progress.single()
        assertThat(marked.contentType).isEqualTo("episode")
        assertThat(marked.contentId).isEqualTo("101")
        assertThat(marked.completed).isTrue()
        assertThat(marked.durationMs).isEqualTo(0L)

        repository.setEpisodeWatched(ACCOUNT, "101", watched = false)
        assertThat(seriesDao.progress).isEmpty()
    }

    @Test
    fun `a partly watched episode keeps its duration when marked watched`() = runTest {
        repository.refresh(ACCOUNT)
        repository.details(ACCOUNT, "7")
        repository.saveEpisodeProgress(
            ACCOUNT,
            "101",
            positionMs = 600_000L,
            durationMs = 2_700_000L,
        )

        repository.setEpisodeWatched(ACCOUNT, "101", watched = true)

        val stored = seriesDao.progress.single()
        assertThat(stored.completed).isTrue()
        assertThat(stored.positionMs).isEqualTo(2_700_000L)
        assertThat(stored.durationMs).isEqualTo(2_700_000L)
    }

    @Test
    fun `marking an episode this account does not have writes nothing`() = runTest {
        repository.refresh(ACCOUNT)
        repository.details(ACCOUNT, "7")

        repository.setEpisodeWatched(ACCOUNT, "unknown", watched = true)

        assertThat(seriesDao.progress).isEmpty()
    }

    @Test
    fun `a position beyond the duration is clamped rather than stored as-is`() = runTest {
        repository.refresh(ACCOUNT)
        repository.details(ACCOUNT, "7")

        repository.saveEpisodeProgress(
            ACCOUNT,
            "101",
            positionMs = 9_000_000L,
            durationMs = 2_700_000L,
        )

        assertThat(seriesDao.progress.single().positionMs).isEqualTo(2_700_000L)
    }

    @Test
    fun `a position for an episode this account does not have is refused`() = runTest {
        repository.refresh(ACCOUNT)
        repository.details(ACCOUNT, "7")

        repository.saveEpisodeProgress(ACCOUNT, "999", positionMs = 60_000L, durationMs = 600_000L)

        assertThat(seriesDao.progress).isEmpty()
    }

    @Test
    fun `implausible player state is refused instead of stored`() = runTest {
        repository.refresh(ACCOUNT)
        repository.details(ACCOUNT, "7")

        repository.saveEpisodeProgress(ACCOUNT, "101", positionMs = 60_000L, durationMs = 0L)
        repository.saveEpisodeProgress(ACCOUNT, "101", positionMs = -1L, durationMs = 600_000L)

        assertThat(seriesDao.progress).isEmpty()
    }

    @Test
    fun `a position written after the account was replaced is rejected`() = runTest {
        repository.refresh(ACCOUNT)
        repository.details(ACCOUNT, "7")
        vault.accountId = "another-account"

        // The coordinator rejects the write; the player's writer is what swallows the failure.
        val failure = runCatching {
            repository.saveEpisodeProgress(ACCOUNT, "101", 60_000L, 600_000L)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(AppFailureException::class.java)
        assertThat(seriesDao.progress).isEmpty()
    }

    @Test
    fun `a favorite is stored and removed through the shared coordinator`() = runTest {
        repository.refresh(ACCOUNT)

        repository.setFavorite(ACCOUNT, "7", favorite = true)
        assertThat(seriesDao.favorites.map { it.remoteSeriesId }).containsExactly("7")

        repository.setFavorite(ACCOUNT, "7", favorite = false)
        assertThat(seriesDao.favorites).isEmpty()
    }

    @Test
    fun `a favorite survives a listing refresh that drops the series`() = runTest {
        repository.refresh(ACCOUNT)
        repository.setFavorite(ACCOUNT, "7", favorite = true)

        // The provider stops listing the series; user state must not be collateral damage.
        seriesDao.deleteStaleSeries(ACCOUNT, generation = -1)

        assertThat(seriesDao.series).isEmpty()
        assertThat(seriesDao.favorites.map { it.remoteSeriesId }).containsExactly("7")
    }

    @Test
    fun `a favorite for a replaced account is rejected`() = runTest {
        repository.refresh(ACCOUNT)
        vault.accountId = "another-account"

        val failure = runCatching {
            repository.setFavorite(ACCOUNT, "7", favorite = true)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(AppFailureException::class.java)
        assertThat(seriesDao.favorites).isEmpty()
    }

    @Test
    fun `the next episode follows the order the details screen shows`() = runTest {
        repository.refresh(ACCOUNT)
        repository.details(ACCOUNT, "7")

        val next = repository.nextEpisode(ACCOUNT, "101")

        assertThat(next?.id).isEqualTo("102")
        assertThat(next?.seriesId).isEqualTo("7")
    }

    @Test
    fun `the last episode of a series has nothing after it`() = runTest {
        repository.refresh(ACCOUNT)
        repository.details(ACCOUNT, "7")

        assertThat(repository.nextEpisode(ACCOUNT, "102")).isNull()
    }

    @Test
    fun `next never crosses into another series`() = runTest {
        repository.refresh(ACCOUNT)
        repository.details(ACCOUNT, "7")
        seriesDao.episodes += EPISODE_OF_ANOTHER_SERIES

        // The other series' episode sorts adjacent by number but belongs to a different show.
        assertThat(repository.nextEpisode(ACCOUNT, "102")).isNull()
    }

    @Test
    fun `an episode this account does not have has no next`() = runTest {
        repository.refresh(ACCOUNT)

        assertThat(repository.nextEpisode(ACCOUNT, "999")).isNull()
    }

    @Test
    fun `logout clears every series table through the shared coordinator`() = runTest {
        repository.refresh(ACCOUNT)
        repository.details(ACCOUNT, "7")

        coordinator.clearAllAccountData()

        assertThat(seriesDao.series).isEmpty()
        assertThat(seriesDao.categories).isEmpty()
        assertThat(seriesDao.details).isEmpty()
        assertThat(seriesDao.episodes).isEmpty()
        assertThat(seriesDao.favorites).isEmpty()
    }

    private fun jsonResponse(body: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private companion object {
        const val ACCOUNT = "account-under-test"

        val EPISODE_OF_ANOTHER_SERIES = SeriesEpisodeEntity(
            accountId = ACCOUNT,
            remoteEpisodeId = "901",
            remoteSeriesId = "8",
            seasonNumber = 1,
            episodeNumber = 1,
            title = "Fremde Folge",
            containerExtension = "mkv",
            durationSeconds = null,
            plot = null,
            stillUrl = null,
        )

        const val CATEGORIES_JSON = """
            [
              {"category_id":"90","category_name":"DE | Serien"},
              {"category_id":"91","category_name":"Doku"}
            ]
        """
        const val SERIES_JSON = """
            [
              {"series_id":"7","name":"DE | Beispielserie","category_id":"90"},
              {"series_id":"8","name":"FR | Andere Serie","category_id":"91"}
            ]
        """
        const val DETAILS_JSON = """
            {"info":{"series_id":"7","name":"Beispielserie","genre":"Drama"},
             "episodes":{"1":[{"id":"101","episode_num":1,"title":"Der Anfang"},
                              {"id":"102","episode_num":2,"title":"Der Weg"}]}}
        """
    }
}
