package dev.killua.iptv.data.repository

import dev.killua.iptv.core.database.AccountDao
import dev.killua.iptv.core.database.AccountEntity
import dev.killua.iptv.core.database.toDomain
import dev.killua.iptv.core.network.ServerUrlNormalizer
import dev.killua.iptv.core.network.UrlNormalizationResult
import dev.killua.iptv.core.security.CredentialVault
import dev.killua.iptv.data.xtream.XtreamRemoteDataSource
import dev.killua.iptv.domain.model.Account
import dev.killua.iptv.domain.model.AppFailure
import dev.killua.iptv.domain.model.AppFailureException
import dev.killua.iptv.domain.model.FailureKind
import dev.killua.iptv.domain.model.RemoteAccount
import dev.killua.iptv.domain.model.SessionState
import dev.killua.iptv.domain.model.XtreamCredentials
import dev.killua.iptv.domain.repository.ConnectionTestResult
import dev.killua.iptv.domain.repository.SessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class DefaultSessionRepository(
    private val accountDao: AccountDao,
    private val credentialVault: CredentialVault,
    private val remote: XtreamRemoteDataSource,
    private val accountData: AccountDataCoordinator,
    private val applicationScope: CoroutineScope,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : SessionRepository {
    private val mutableState = MutableStateFlow<SessionState>(SessionState.Booting)
    override val state: StateFlow<SessionState> = mutableState.asStateFlow()
    private var startJob: Job? = null

    // Lock ordering is session mutex -> account-data coordinator lock. Nothing reached while the
    // coordinator lock is held may take this mutex, so the two cannot deadlock.
    private val sessionMutationMutex = Mutex()

    override fun start() {
        if (startJob != null) return
        startJob = applicationScope.launch { restoreSession() }
    }

    override suspend fun testConnection(
        server: String,
        username: String,
        password: String,
    ): ConnectionTestResult {
        val normalized = normalize(server)
        requireCredentials(username, password)
        val temporaryCredentials = XtreamCredentials(
            accountId = "connection-test",
            serverUrl = normalized.baseUrl,
            username = username.trim(),
            password = password,
        )
        return ConnectionTestResult(normalized, remote.authenticate(temporaryCredentials))
    }

    override suspend fun login(
        server: String,
        username: String,
        password: String,
        displayName: String,
    ): Account = sessionMutationMutex.withLock {
        val normalized = normalize(server)
        requireCredentials(username, password)
        val credentials = XtreamCredentials(
            accountId = UUID.randomUUID().toString(),
            serverUrl = normalized.baseUrl,
            username = username.trim(),
            password = password,
        )
        val remoteAccount = remote.authenticate(credentials)
        val entity = remoteAccount.toEntity(
            accountId = credentials.accountId,
            validatedAt = nowMillis(),
            displayName = displayName.trim().takeIf { it.isNotBlank() },
        )
        accountDao.upsert(entity)
        try {
            credentialVault.save(credentials)
        } catch (error: Throwable) {
            accountDao.delete(credentials.accountId)
            throw error
        }
        val account = entity.toDomain(credentials)
        mutableState.value = SessionState.Authenticated(account)
        // The new vault record is the commit point. Cleanup is recoverable on startup/logout,
        // so a storage cleanup failure must not report a successful login as failed.
        runCatching { accountData.clearAllAccountDataExcept(credentials.accountId) }
        account
    }

    override suspend fun reconnect(): Account = sessionMutationMutex.withLock {
        val credentials = credentialVault.load()
            ?: throw AppFailureException(AppFailure(FailureKind.AuthenticationFailed))
        val remoteAccount = remote.authenticate(credentials)
        val existing = accountDao.get(credentials.accountId)
        val entity = remoteAccount.toEntity(
            accountId = credentials.accountId,
            validatedAt = nowMillis(),
            lastSyncAt = existing?.lastLiveSyncAtEpochMillis,
            displayName = existing?.displayName,
        )
        accountDao.upsert(entity)
        entity.toDomain(credentials).also {
            mutableState.value = SessionState.Authenticated(it)
        }
    }

    override suspend fun credentialsFor(accountId: String): XtreamCredentials {
        val credentials = credentialVault.load()
            ?: throw AppFailureException(AppFailure(FailureKind.AuthenticationFailed))
        if (credentials.accountId != accountId) {
            throw AppFailureException(AppFailure(FailureKind.AuthenticationFailed))
        }
        return credentials
    }

    override suspend fun renameAccount(displayName: String) = sessionMutationMutex.withLock {
        val credentials = credentialVault.load() ?: return@withLock
        // Blank clears it rather than storing an empty string, so `label` has one empty case to
        // reason about instead of two.
        accountDao.setDisplayName(credentials.accountId, displayName.trim().takeIf { it.isNotBlank() })
        refreshCachedAccount()
    }

    override suspend fun refreshCachedAccount() {
        val credentials = credentialVault.load() ?: return
        val entity = accountDao.get(credentials.accountId) ?: return
        mutableState.value = SessionState.Authenticated(entity.toDomain(credentials))
    }

    override suspend fun logout() = sessionMutationMutex.withLock {
        accountData.clearAllAccountData()
        credentialVault.clear()
        mutableState.value = SessionState.SignedOut
    }

    private suspend fun restoreSession() = sessionMutationMutex.withLock {
        val credentials = try {
            credentialVault.load()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: AppFailureException) {
            accountData.clearAllAccountData()
            mutableState.value = SessionState.SignedOut
            return@withLock
        }
        if (credentials == null) {
            accountData.clearAllAccountData()
            mutableState.value = SessionState.SignedOut
            return@withLock
        }

        accountData.clearAllAccountDataExcept(credentials.accountId)

        val cached = accountDao.get(credentials.accountId)
        if (cached != null) {
            mutableState.value = SessionState.Authenticated(cached.toDomain(credentials))
        }

        try {
            val remoteAccount = remote.authenticate(credentials)
            val updated = remoteAccount.toEntity(
                credentials.accountId,
                nowMillis(),
                cached?.lastLiveSyncAtEpochMillis,
            )
            accountDao.upsert(updated)
            mutableState.value = SessionState.Authenticated(updated.toDomain(credentials))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: AppFailureException) {
            val failure = error.failure
            if (cached != null && failure.kind in OFFLINE_COMPATIBLE_FAILURES) {
                mutableState.value = SessionState.Authenticated(cached.toDomain(credentials), failure)
            } else {
                mutableState.value = SessionState.ReconnectRequired(
                    account = cached?.toDomain(credentials),
                    reason = failure,
                )
            }
        }
    }

    private fun normalize(server: String) = when (val result = ServerUrlNormalizer.normalize(server)) {
        is UrlNormalizationResult.Valid -> result.server
        is UrlNormalizationResult.Invalid -> throw AppFailureException(
            AppFailure(FailureKind.InvalidServerUrl),
        )
    }

    private fun requireCredentials(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            throw AppFailureException(AppFailure(FailureKind.AuthenticationFailed))
        }
    }

    private fun RemoteAccount.toEntity(
        accountId: String,
        validatedAt: Long,
        lastSyncAt: Long? = null,
        displayName: String? = null,
    ) = AccountEntity(
        accountId = accountId,
        displayName = displayName,
        status = status.name,
        expiresAtEpochSeconds = expiresAtEpochSeconds,
        activeConnections = activeConnections,
        maximumConnections = maximumConnections,
        serverTimezone = serverTimezone,
        allowedOutputFormats = allowedOutputFormats.sorted().joinToString(","),
        lastValidatedAtEpochMillis = validatedAt,
        lastLiveSyncAtEpochMillis = lastSyncAt,
    )

    private companion object {
        val OFFLINE_COMPATIBLE_FAILURES = setOf(
            FailureKind.NoNetwork,
            FailureKind.ServerUnavailable,
            FailureKind.Timeout,
            FailureKind.TemporaryServerFailure,
        )
    }
}
