package dev.killua.iptv.data.repository

import com.google.common.truth.Truth.assertThat
import dev.killua.iptv.core.network.NetworkFailureMapper
import dev.killua.iptv.core.network.NetworkStatus
import dev.killua.iptv.data.playlist.PlaylistProbe
import dev.killua.iptv.data.playlist.PlaylistProbeResult
import dev.killua.iptv.data.xtream.XtreamRemoteDataSource
import dev.killua.iptv.domain.model.AccountStatus
import dev.killua.iptv.domain.model.LibrarySource
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import retrofit2.Retrofit

/**
 * Signing in with a playlist address, which is not a sign-in and does not pretend to be one.
 *
 * There is no account to authenticate: a playlist is a file, and reading it is the whole of the
 * authorisation. What stands in for a provider saying yes is opening the address once, so these
 * tests are mostly about what happens when it says no.
 */
class PlaylistSignInTest {
    private val accountDao = FakeAccountDao()
    private val vault = FakeCredentialVault()
    private var probeResult: PlaylistProbeResult =
        PlaylistProbeResult.Ok("https://playlist.example/index.m3u")

    private val repository = DefaultSessionRepository(
        accountDao = accountDao,
        credentialVault = vault,
        remote = XtreamRemoteDataSource(
            retrofit = Retrofit.Builder().baseUrl("https://provider.example/").build(),
            failureMapper = NetworkFailureMapper(
                object : NetworkStatus {
                    override fun hasActiveNetwork(): Boolean = true
                },
            ),
        ),
        accountData = AccountDataCoordinator(
            transactions = RecordingTransactionRunner(),
            accountDao = accountDao,
            credentialVault = vault,
            cleaners = { emptyList() },
        ),
        applicationScope = TestScope(),
        playlistProbe = PlaylistProbe { probeResult },
        nowMillis = { 1_000L },
    )

    @Test
    fun `a playlist address becomes an account that says it is one`() = runTest {
        val account = repository.loginWithPlaylist("playlist.example/index.m3u", "Freier Test")

        assertThat(account.displayName).isEqualTo("Freier Test")
        // Synthetic, because a file has none of these: it does not expire and is not rate-limited.
        assertThat(account.status).isEqualTo(AccountStatus.Active)
        assertThat(account.expiresAtEpochSeconds).isNull()
        assertThat(account.maximumConnections).isNull()

        // The address the *policy* returned is what is stored, not what was typed.
        val stored = vault.load()
        assertThat(stored?.source).isEqualTo(LibrarySource.Playlist)
        assertThat(stored?.serverUrl).isEqualTo("https://playlist.example/index.m3u")
        // No credentials, because a playlist has none. This is what the refresh reads to decide
        // which of the two listings it is about to fetch.
        assertThat(stored?.username).isEmpty()
        assertThat(stored?.password).isEmpty()
    }

    @Test
    fun `an address that is not a playlist is refused, and nothing is written`() = runTest {
        probeResult = PlaylistProbeResult.NotAPlaylist

        val failure = runCatching { repository.loginWithPlaylist("https://example.com/") }
            .exceptionOrNull()

        assertThat(failure).isNotNull()
        // Neither half of the sign-in may survive a refusal: an account row without a vault record
        // is an account nothing can read, and it would still be there on the next launch.
        assertThat(accountDao.accounts).isEmpty()
        assertThat(vault.load()).isNull()
    }

    @Test
    fun `an address the rule will not open is refused the same way`() = runTest {
        probeResult = PlaylistProbeResult.Refused("PrivateAddress")

        val failure = runCatching { repository.loginWithPlaylist("http://192.168.1.1/list.m3u") }
            .exceptionOrNull()

        assertThat(failure).isNotNull()
        // The reason never reaches the message, because a provider's playlist link is a credential
        // and a failure is a thing people paste into a chat window.
        assertThat(failure?.message.orEmpty()).doesNotContain("192.168")
        assertThat(accountDao.accounts).isEmpty()
    }

    @Test
    fun `a server that does not answer is refused rather than half-signed-in`() = runTest {
        probeResult = PlaylistProbeResult.Unreachable

        val failure = runCatching { repository.loginWithPlaylist("https://nothing.example/a.m3u") }
            .exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(accountDao.accounts).isEmpty()
        assertThat(vault.load()).isNull()
    }

    @Test
    fun `an empty name leaves the account unnamed rather than named with a blank`() = runTest {
        val account = repository.loginWithPlaylist("https://playlist.example/index.m3u", "   ")

        assertThat(account.displayName).isNull()
    }
}
