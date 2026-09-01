package dev.killua.iptv.feature.auth

import com.google.common.truth.Truth.assertThat
import dev.killua.iptv.core.network.NormalizedServer
import dev.killua.iptv.domain.model.Account
import dev.killua.iptv.domain.model.AccountStatus
import dev.killua.iptv.domain.model.RemoteAccount
import dev.killua.iptv.domain.model.SessionState
import dev.killua.iptv.domain.model.XtreamCredentials
import dev.killua.iptv.domain.repository.ConnectionTestResult
import dev.killua.iptv.domain.repository.SessionRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `login state string representation redacts both credential input forms`() {
        val rendered = LoginUiState(
            server = "https://private.example",
            username = "private-user",
            password = "private-password",
            providerLink = "https://private.example/get.php?username=private-user&password=private-password",
        ).toString()

        assertThat(rendered).contains("REDACTED")
        assertThat(rendered).doesNotContain("private.example")
        assertThat(rendered).doesNotContain("private-user")
        assertThat(rendered).doesNotContain("private-password")
    }

    @Test
    fun `M3U test passes only extracted values to repository`() = runTest(dispatcher.scheduler) {
        val repository = FakeSessionRepository()
        val viewModel = LoginViewModel(repository)
        viewModel.selectLoginMethod(LoginMethod.ProviderLink)
        viewModel.setProviderLink(
            "https://example.com/provider/get.php?output=ts&password=p%40ss%2Bword&username=Killua%20Zoldyck",
        )

        viewModel.testConnection()
        advanceUntilIdle()

        assertThat(repository.lastTestAttempt?.server).isEqualTo("https://example.com/provider/")
        assertThat(repository.lastTestAttempt?.username).isEqualTo("Killua Zoldyck")
        assertThat(repository.lastTestAttempt?.password).isEqualTo("p@ss+word")
        assertThat(viewModel.state.value.connectionTested).isTrue()
    }

    @Test
    fun `the playlist name reaches the repository, trimmed of nothing it did not type`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeSessionRepository()
            val viewModel = LoginViewModel(repository)
            viewModel.setServer("https://provider.example/")
            viewModel.setUsername("demo-user")
            viewModel.setPassword("demo-pass")
            viewModel.setPlaylistName("Wohnzimmer")

            viewModel.testConnection()
            advanceUntilIdle()
            viewModel.connect()
            advanceUntilIdle()

            assertThat(repository.lastDisplayName).isEqualTo("Wohnzimmer")
        }

    @Test
    fun `leaving the playlist name empty is allowed and passes nothing on`() =
        runTest(dispatcher.scheduler) {
            // The field is optional; the account falls back to the provider user name.
            val repository = FakeSessionRepository()
            val viewModel = LoginViewModel(repository)
            viewModel.setServer("https://provider.example/")
            viewModel.setUsername("demo-user")
            viewModel.setPassword("demo-pass")

            viewModel.testConnection()
            advanceUntilIdle()
            viewModel.connect()
            advanceUntilIdle()

            assertThat(repository.lastDisplayName).isEmpty()
        }

    @Test
    fun `editing input invalidates a noncooperative in flight test`() = runTest(dispatcher.scheduler) {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeSessionRepository().apply {
            testOverride = { server, _, _ ->
                withContext(NonCancellable) { gate.await() }
                connectionResult(server)
            }
        }
        val viewModel = LoginViewModel(repository)
        viewModel.selectLoginMethod(LoginMethod.ProviderLink)
        viewModel.setProviderLink("https://example.com/get.php?username=old&password=secret")
        viewModel.testConnection()
        runCurrent()
        assertThat(viewModel.state.value.isTesting).isTrue()

        viewModel.setProviderLink("https://example.com/get.php?username=new&password=secret")
        gate.complete(Unit)
        advanceUntilIdle()

        assertThat(viewModel.state.value.isTesting).isFalse()
        assertThat(viewModel.state.value.connectionTested).isFalse()
        assertThat(viewModel.state.value.accountSummary).isNull()
    }

    @Test
    fun `successful M3U login clears the raw secret link`() = runTest(dispatcher.scheduler) {
        val repository = FakeSessionRepository()
        val viewModel = LoginViewModel(repository)
        viewModel.selectLoginMethod(LoginMethod.ProviderLink)
        viewModel.setProviderLink("https://example.com/get.php?username=user&password=secret")

        viewModel.connect()
        advanceUntilIdle()

        assertThat(repository.lastLoginAttempt?.server).isEqualTo("https://example.com/")
        assertThat(viewModel.state.value.providerLink).isEmpty()
        assertThat(viewModel.state.value.password).isEmpty()
        assertThat(viewModel.state.value.server).isEmpty()
        assertThat(viewModel.state.value.username).isEmpty()
    }

    @Test
    fun `connect locks the exact credential input until repository commit completes`() =
        runTest(dispatcher.scheduler) {
            val gate = CompletableDeferred<Unit>()
            val repository = FakeSessionRepository().apply {
                loginOverride = { server, username, _ ->
                    gate.await()
                    account(server, username)
                }
            }
            val viewModel = LoginViewModel(repository)
            val original = "https://example.com/get.php?username=old&password=secret"
            val replacement = "https://example.com/get.php?username=new&password=other"
            viewModel.selectLoginMethod(LoginMethod.ProviderLink)
            viewModel.setProviderLink(original)

            viewModel.connect()
            assertThat(viewModel.state.value.isConnecting).isTrue()
            viewModel.setProviderLink(replacement)
            viewModel.selectLoginMethod(LoginMethod.Credentials)
            assertThat(viewModel.state.value.loginMethod).isEqualTo(LoginMethod.ProviderLink)
            assertThat(viewModel.state.value.providerLink).isEqualTo(original)

            runCurrent()
            gate.complete(Unit)
            advanceUntilIdle()

            assertThat(repository.lastLoginAttempt?.username).isEqualTo("old")
            assertThat(viewModel.state.value.isConnecting).isFalse()
            assertThat(viewModel.state.value.providerLink).isEmpty()
        }

    @Test
    fun `cleartext acknowledgement is invalidated by any link edit`() = runTest(dispatcher.scheduler) {
        val repository = FakeSessionRepository()
        val viewModel = LoginViewModel(repository)
        viewModel.selectLoginMethod(LoginMethod.ProviderLink)
        viewModel.setProviderLink("http://example.com/get.php?username=user&password=secret&output=ts")
        viewModel.testConnection()
        advanceUntilIdle()
        assertThat(viewModel.state.value.connectionTested).isTrue()

        viewModel.setProviderLink("http://example.com/get.php?username=user&password=secret&output=m3u8")
        viewModel.connect()
        advanceUntilIdle()

        assertThat(repository.loginCallCount).isEqualTo(0)
        assertThat(viewModel.state.value.connectionTested).isFalse()
        assertThat(viewModel.state.value.errorMessage).contains("Test the connection once")
    }

    @Test
    fun `generic playlist gets explicit local error without network call`() = runTest(dispatcher.scheduler) {
        val repository = FakeSessionRepository()
        val viewModel = LoginViewModel(repository)
        viewModel.selectLoginMethod(LoginMethod.ProviderLink)
        viewModel.setProviderLink("https://example.com/channels.m3u")

        viewModel.testConnection()
        advanceUntilIdle()

        assertThat(repository.testCallCount).isEqualTo(0)
        assertThat(viewModel.state.value.errorMessage).contains("Arbitrary M3U playlists are not supported yet")
    }

    @Test
    fun `switching to a playlist leaves no secret from either other way in`() =
        runTest(dispatcher.scheduler) {
        val repository = FakeSessionRepository()
        val viewModel = LoginViewModel(repository)
        viewModel.setServer("https://provider.example/")
        viewModel.setUsername("killua")
        viewModel.setPassword("s3cret")
        viewModel.selectLoginMethod(LoginMethod.ProviderLink)
        viewModel.setProviderLink("https://provider.example/get.php?username=killua&password=s3cret")

        viewModel.selectLoginMethod(LoginMethod.Playlist)

        // Nothing typed for one way in may survive into another: the form is one screen, and a
        // password left in a field is a password on screen.
        val state = viewModel.state.value
        assertThat(state.server).isEmpty()
        assertThat(state.username).isEmpty()
        assertThat(state.password).isEmpty()
        assertThat(state.providerLink).isEmpty()
        assertThat(state.providerLinkVisible).isFalse()
    }

    @Test
    fun `a playlist address reaches the repository and the field is cleared afterwards`() =
        runTest(dispatcher.scheduler) {
        val repository = FakeSessionRepository()
        val viewModel = LoginViewModel(repository)
        viewModel.selectLoginMethod(LoginMethod.Playlist)
        viewModel.setPlaylistName("Freier Test")
        viewModel.setPlaylistUrl("  https://playlist.example/index.m3u  ")

        viewModel.connectPlaylist()
        dispatcher.scheduler.advanceUntilIdle()

        assertThat(repository.playlistLoginCount).isEqualTo(1)
        // Trimmed, because a pasted address brings whitespace with it.
        assertThat(repository.lastPlaylistUrl).isEqualTo("https://playlist.example/index.m3u")
        assertThat(repository.lastDisplayName).isEqualTo("Freier Test")
        assertThat(viewModel.state.value.playlistUrl).isEmpty()
    }

    private class FakeSessionRepository : SessionRepository {
        override val state: StateFlow<SessionState> = MutableStateFlow(SessionState.SignedOut)
        var testCallCount = 0
        var loginCallCount = 0
        var lastTestAttempt: Attempt? = null
        var lastLoginAttempt: Attempt? = null
        var testOverride: (suspend (String, String, String) -> ConnectionTestResult)? = null
        var loginOverride: (suspend (String, String, String) -> Account)? = null

        override fun start() = Unit

        override suspend fun testConnection(
            server: String,
            username: String,
            password: String,
        ): ConnectionTestResult {
            testCallCount += 1
            lastTestAttempt = Attempt(server, username, password)
            return testOverride?.invoke(server, username, password) ?: connectionResult(server)
        }

        var lastDisplayName: String? = null

        override suspend fun login(
            server: String,
            username: String,
            password: String,
            displayName: String,
        ): Account {
            loginCallCount += 1
            lastDisplayName = displayName
            lastLoginAttempt = Attempt(server, username, password)
            return loginOverride?.invoke(server, username, password) ?: account(server, username)
        }

        var lastPlaylistUrl: String? = null
        var playlistLoginCount = 0

        override suspend fun loginWithPlaylist(url: String, displayName: String): Account {
            playlistLoginCount += 1
            lastPlaylistUrl = url
            lastDisplayName = displayName
            return account(url, "")
        }

        override suspend fun reconnect(): Account = error("Not used")
        override suspend fun credentialsFor(accountId: String): XtreamCredentials = error("Not used")
        override suspend fun renameAccount(displayName: String) = Unit

        override suspend fun refreshCachedAccount() = Unit
        override suspend fun logout() = Unit

        fun connectionResult(server: String) = ConnectionTestResult(
            server = NormalizedServer(
                baseUrl = server,
                isCleartext = server.startsWith("http://"),
                warnings = emptySet(),
            ),
            account = RemoteAccount(
                username = "user",
                status = AccountStatus.Active,
                expiresAtEpochSeconds = null,
                activeConnections = 0,
                maximumConnections = 2,
                serverTimezone = null,
                allowedOutputFormats = setOf("ts"),
            ),
        )

        fun account(server: String, username: String) = Account(
            id = "account",
            username = username,
            serverUrl = server,
            status = AccountStatus.Active,
            expiresAtEpochSeconds = null,
            activeConnections = 0,
            maximumConnections = 2,
            serverTimezone = null,
            allowedOutputFormats = setOf("ts"),
            lastValidatedAtEpochMillis = 0,
        )

        class Attempt(
            val server: String,
            val username: String,
            val password: String,
        ) {
            override fun toString(): String = "Attempt(REDACTED)"
        }
    }
}
