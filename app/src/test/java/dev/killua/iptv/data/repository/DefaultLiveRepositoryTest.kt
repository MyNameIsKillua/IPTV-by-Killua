package dev.killua.iptv.data.repository

import com.google.common.truth.Truth.assertThat
import dev.killua.iptv.core.network.NetworkFailureMapper
import dev.killua.iptv.core.network.NetworkStatus
import dev.killua.iptv.data.xtream.XtreamRemoteDataSource
import dev.killua.iptv.domain.model.Account
import dev.killua.iptv.domain.model.AppFailure
import dev.killua.iptv.domain.model.AppFailureException
import dev.killua.iptv.domain.model.FailureKind
import dev.killua.iptv.domain.model.SessionState
import dev.killua.iptv.domain.model.XtreamCredentials
import dev.killua.iptv.domain.repository.ConnectionTestResult
import dev.killua.iptv.domain.repository.SessionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
 * Covers the account-scoped write path of the live library. Provider traffic is served by
 * MockWebServer with fictitious data; no real account is ever contacted.
 */
class DefaultLiveRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: DefaultLiveRepository

    private val transactions = RecordingTransactionRunner()
    private val liveDao = FakeLiveDao()
    private val accountDao = FakeAccountDao()
    private val vault = FakeCredentialVault(accountId = ACCOUNT)
    private lateinit var session: FakeSessionRepository
    private lateinit var coordinator: AccountDataCoordinator

    /** Set to make every provider call fail, simulating a temporary outage. */
    private var failEveryRequestWith: Int? = null

    /** Movable so the guide cache can be aged past its lifetime. */
    private var clockMillis = 1_000L
    private var epgRequests = 0

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // Categories and channels are fetched concurrently, so responses are routed by action
        // rather than taken from a fixed queue.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                failEveryRequestWith?.let { return MockResponse().setResponseCode(it) }
                return when (request.requestUrl?.queryParameter("action")) {
                    "get_live_categories" -> jsonResponse(CATEGORIES_JSON)
                    "get_live_streams" -> jsonResponse(CHANNELS_JSON)
                    "get_short_epg" -> {
                        epgRequests++
                        jsonResponse(EPG_JSON)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        vault.serverUrl = server.url("/").toString()
        session = FakeSessionRepository(vault)
        coordinator = AccountDataCoordinator(
            transactions = transactions,
            accountDao = accountDao,
            credentialVault = vault,
            cleaners = { listOf(repository) },
        )
        repository = DefaultLiveRepository(
            liveDao = liveDao,
            accountDao = accountDao,
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
            nowMillis = { clockMillis },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `a successful refresh commits categories and channels in one transaction`() = runTest {
        val result = repository.refresh(ACCOUNT)

        assertThat(result.categoryCount).isEqualTo(1)
        assertThat(result.channelCount).isEqualTo(2)
        assertThat(liveDao.categories.map { it.remoteCategoryId }).containsExactly("7")
        assertThat(liveDao.channels.map { it.remoteStreamId }).containsExactly("41", "42")
        assertThat(liveDao.channels.map { it.accountId }.toSet()).containsExactly(ACCOUNT)
        assertThat(transactions.started).isEqualTo(1)
        assertThat(transactions.maximumDepth).isEqualTo(1)
        assertThat(accountDao.lastLiveSyncWrites).containsExactly(ACCOUNT to 1_000L)
        assertThat(session.cachedAccountRefreshes).isEqualTo(1)
    }

    @Test
    fun `a refresh labels channels from their category and falls back to the channel name`() =
        runTest {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when (request.requestUrl?.queryParameter("action")) {
                        "get_live_categories" -> jsonResponse(TAGGED_CATEGORIES_JSON)
                        "get_live_streams" -> jsonResponse(TAGGED_CHANNELS_JSON)
                        else -> MockResponse().setResponseCode(404)
                    }
            }

            repository.refresh(ACCOUNT)

            val languages = liveDao.channels.associate { it.remoteStreamId to it.languageTag }
            // The category carries the tag.
            assertThat(languages["41"]).isEqualTo("de")
            // The category says nothing, so the channel's own leading tag decides.
            assertThat(languages["42"]).isEqualTo("fr")
            // Neither says anything, and a guess would be worse than no label.
            assertThat(languages["43"]).isNull()

            // Unlike a movie title, the sort key keeps the tag: it is part of the channel label.
            // Only the bar itself is folded away, like every other punctuation mark.
            val sortNames = liveDao.channels.associate { it.remoteStreamId to it.sortName }
            assertThat(sortNames["42"]).isEqualTo("fr zweiter kanal")
        }

    @Test
    fun `a refresh whose account was logged out mid-download writes nothing`() = runTest {
        // The vault no longer holds this account by the time the download finishes.
        session.onCredentialsIssued = { vault.accountId = null }

        val failure = assertFailure { repository.refresh(ACCOUNT) }

        assertThat(failure.failure.kind).isEqualTo(FailureKind.AuthenticationFailed)
        assertThat(liveDao.categories).isEmpty()
        assertThat(liveDao.channels).isEmpty()
        assertThat(accountDao.lastLiveSyncWrites).isEmpty()
        assertThat(transactions.started).isEqualTo(0)
        assertThat(session.cachedAccountRefreshes).isEqualTo(0)
    }

    @Test
    fun `a refresh whose account was replaced mid-download writes nothing`() = runTest {
        session.onCredentialsIssued = { vault.accountId = "another-account" }

        val failure = assertFailure { repository.refresh(ACCOUNT) }

        assertThat(failure.failure.kind).isEqualTo(FailureKind.AuthenticationFailed)
        assertThat(liveDao.channels).isEmpty()
        assertThat(transactions.started).isEqualTo(0)
    }

    @Test
    fun `a failed download preserves the previously cached library`() = runTest {
        repository.refresh(ACCOUNT)
        val cachedChannels = liveDao.channels.toList()
        val cachedCategories = liveDao.categories.toList()

        failEveryRequestWith = 503
        val failure = assertFailure { repository.refresh(ACCOUNT) }

        assertThat(failure.failure.kind).isEqualTo(FailureKind.TemporaryServerFailure)
        assertThat(liveDao.channels).isEqualTo(cachedChannels)
        assertThat(liveDao.categories).isEqualTo(cachedCategories)
        assertThat(transactions.started).isEqualTo(1)
    }

    @Test
    fun `marking a channel recent after logout is rejected`() = runTest {
        vault.accountId = null

        val failure = assertFailure { repository.markRecent(ACCOUNT, "42") }

        assertThat(failure.failure.kind).isEqualTo(FailureKind.AuthenticationFailed)
        assertThat(liveDao.recents).isEmpty()
    }

    @Test
    fun `marking a channel recent for the active account is stored account-scoped`() = runTest {
        repository.markRecent(ACCOUNT, "42")

        assertThat(liveDao.recents).hasSize(1)
        assertThat(liveDao.recents.single().accountId).isEqualTo(ACCOUNT)
        assertThat(liveDao.recents.single().remoteStreamId).isEqualTo("42")
        assertThat(liveDao.recents.single().lastWatchedAtEpochMillis).isEqualTo(1_000L)
    }

    @Test
    fun `logout clears the live library through the shared coordinator`() = runTest {
        repository.refresh(ACCOUNT)

        coordinator.clearAllAccountData()

        assertThat(liveDao.channels).isEmpty()
        assertThat(liveDao.categories).isEmpty()
        assertThat(liveDao.recents).isEmpty()
        assertThat(accountDao.deletedAll).isEqualTo(1)
    }

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

    private class FakeSessionRepository(
        private val vault: FakeCredentialVault,
    ) : SessionRepository {
        override val state: StateFlow<SessionState> = MutableStateFlow(SessionState.Booting)
        var cachedAccountRefreshes = 0
            private set

        /** Simulates a logout or account replacement landing while the download is in flight. */
        var onCredentialsIssued: (() -> Unit)? = null

        override suspend fun credentialsFor(accountId: String): XtreamCredentials {
            val credentials = vault.load()
            if (credentials == null || credentials.accountId != accountId) {
                throw AppFailureException(AppFailure(FailureKind.AuthenticationFailed))
            }
            onCredentialsIssued?.invoke()
            return credentials
        }

        override suspend fun renameAccount(displayName: String) = Unit


        override suspend fun refreshCachedAccount() {
            cachedAccountRefreshes++
        }

        override fun start() = Unit

        override suspend fun testConnection(
            server: String,
            username: String,
            password: String,
        ): ConnectionTestResult = throw UnsupportedOperationException()

        override suspend fun login(
            server: String,
            username: String,
            password: String,
            displayName: String,
        ): Account = throw UnsupportedOperationException()

        override suspend fun reconnect(): Account = throw UnsupportedOperationException()

        override suspend fun logout() = Unit
    }

    @Test
    fun `a guide is fetched once and reused while it is fresh`() = runTest {
        val first = repository.epg(ACCOUNT, "41")
        val second = repository.epg(ACCOUNT, "41")

        assertThat(first.map { it.title }).containsExactly("Tagesschau", "Tatort").inOrder()
        assertThat(second).isEqualTo(first)
        assertThat(epgRequests).isEqualTo(1)
    }

    @Test
    fun `a guide is fetched again once it has aged out`() = runTest {
        repository.epg(ACCOUNT, "41")

        clockMillis += 6 * 60 * 1_000L
        repository.epg(ACCOUNT, "41")

        assertThat(epgRequests).isEqualTo(2)
    }

    @Test
    fun `each channel is cached separately`() = runTest {
        repository.epg(ACCOUNT, "41")
        repository.epg(ACCOUNT, "42")

        assertThat(epgRequests).isEqualTo(2)
    }

    @Test
    fun `a provider that cannot answer yields an empty guide rather than a failure`() = runTest {
        failEveryRequestWith = 503

        val entries = repository.epg(ACCOUNT, "41")

        // A missing guide must never stop a channel from playing.
        assertThat(entries).isEmpty()
    }

    @Test
    fun `a failed guide is not cached, so the next attempt tries again`() = runTest {
        failEveryRequestWith = 503
        repository.epg(ACCOUNT, "41")
        val afterFailure = epgRequests

        failEveryRequestWith = null
        val entries = repository.epg(ACCOUNT, "41")

        assertThat(epgRequests).isGreaterThan(afterFailure)
        assertThat(entries).isNotEmpty()
    }

    @Test
    fun `logout drops the cached guide with the rest of the account data`() = runTest {
        repository.epg(ACCOUNT, "41")

        coordinator.clearAllAccountData()
        repository.epg(ACCOUNT, "41")

        // Nothing belonging to a signed-out account may survive in memory either.
        assertThat(epgRequests).isEqualTo(2)
    }

    private companion object {
        const val ACCOUNT = "account-under-test"

        const val EPG_JSON = """
            {"epg_listings":[
              {"title":"VGFnZXNzY2hhdQ==","start_timestamp":"1700000000",
               "stop_timestamp":"1700001800"},
              {"title":"VGF0b3J0","start_timestamp":"1700001800","stop_timestamp":"1700007200"}
            ]}
        """

        const val CATEGORIES_JSON = """[{"category_id":"7","category_name":"Nachrichten"}]"""
        const val CHANNELS_JSON = """
            [
              {"stream_id":"41","category_id":"7","name":"Kanal Ü","container_extension":"ts"},
              {"stream_id":"42","category_id":"7","name":"News HD","container_extension":"m3u8"}
            ]
        """

        const val TAGGED_CATEGORIES_JSON = """
            [
              {"category_id":"7","category_name":"DE | Nachrichten"},
              {"category_id":"8","category_name":"Sport"}
            ]
        """
        const val TAGGED_CHANNELS_JSON = """
            [
              {"stream_id":"41","category_id":"7","name":"Erster Kanal","container_extension":"ts"},
              {"stream_id":"42","category_id":"8","name":"FR | Zweiter Kanal","container_extension":"ts"},
              {"stream_id":"43","category_id":"8","name":"Dritter Kanal","container_extension":"ts"}
            ]
        """
    }
}
