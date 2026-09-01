package dev.killua.iptv.domain.repository

import dev.killua.iptv.core.network.NormalizedServer
import dev.killua.iptv.domain.model.Account
import dev.killua.iptv.domain.model.RemoteAccount
import dev.killua.iptv.domain.model.SessionState
import dev.killua.iptv.domain.model.XtreamCredentials
import kotlinx.coroutines.flow.StateFlow

data class ConnectionTestResult(
    val server: NormalizedServer,
    val account: RemoteAccount,
)

interface SessionRepository {
    val state: StateFlow<SessionState>

    fun start()
    suspend fun testConnection(server: String, username: String, password: String): ConnectionTestResult
    /** [displayName] is the viewer's own name for the playlist; blank means they gave none. */
    suspend fun login(
        server: String,
        username: String,
        password: String,
        displayName: String = "",
    ): Account
    /**
     * Signs in with a playlist address instead of an account.
     *
     * There is nothing to authenticate against - a playlist is a file, and reading it is the whole
     * of the authorisation - so what stands in for a sign-in is opening the address once and
     * checking that what comes back looks like the format. The account this produces is synthetic:
     * no expiry and no connection limit, because a file has neither.
     */
    suspend fun loginWithPlaylist(url: String, displayName: String = ""): Account

    suspend fun reconnect(): Account
    suspend fun credentialsFor(accountId: String): XtreamCredentials
    /** Renames the playlist. A blank name clears it, and the provider user name stands in. */
    suspend fun renameAccount(displayName: String)

    suspend fun refreshCachedAccount()
    suspend fun logout()
}
