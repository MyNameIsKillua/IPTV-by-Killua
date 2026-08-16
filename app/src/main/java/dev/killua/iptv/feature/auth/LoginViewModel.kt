package dev.killua.iptv.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.killua.iptv.core.network.NormalizedServer
import dev.killua.iptv.core.network.ServerUrlNormalizer
import dev.killua.iptv.core.network.UrlNormalizationResult
import dev.killua.iptv.core.network.XtreamM3uUrlError
import dev.killua.iptv.core.network.XtreamM3uUrlParser
import dev.killua.iptv.core.network.XtreamM3uUrlResult
import dev.killua.iptv.domain.model.AppFailure
import dev.killua.iptv.domain.model.AppFailureException
import dev.killua.iptv.domain.model.FailureKind
import dev.killua.iptv.domain.repository.SessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

enum class LoginMethod {
    Credentials,
    M3uUrl,
}

data class LoginUiState(
    val loginMethod: LoginMethod = LoginMethod.Credentials,
    val playlistName: String = "",
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val passwordVisible: Boolean = false,
    val m3uUrl: String = "",
    val m3uUrlVisible: Boolean = false,
    val isTesting: Boolean = false,
    val isConnecting: Boolean = false,
    val connectionTested: Boolean = false,
    val normalizedServer: String? = null,
    val isCleartext: Boolean = false,
    val accountSummary: String? = null,
    val errorMessage: String? = null,
) {
    val canSubmit: Boolean
        get() = when (loginMethod) {
            LoginMethod.Credentials -> server.isNotBlank() && username.isNotBlank() && password.isNotBlank()
            LoginMethod.M3uUrl -> m3uUrl.isNotBlank()
        } && !isTesting && !isConnecting

    override fun toString(): String =
        "LoginUiState(loginMethod=$loginMethod, secretInput=REDACTED, isTesting=$isTesting, " +
            "isConnecting=$isConnecting, connectionTested=$connectionTested, " +
            "isCleartext=$isCleartext)"
}

class LoginViewModel(private val repository: SessionRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = mutableState.asStateFlow()
    private var activeRequest: Job? = null
    private var requestGeneration = 0L

    fun selectLoginMethod(value: LoginMethod) {
        if (mutableState.value.loginMethod == value) return
        updateFields {
            when (value) {
                LoginMethod.Credentials -> copy(loginMethod = value, m3uUrl = "", m3uUrlVisible = false)
                LoginMethod.M3uUrl -> copy(
                    loginMethod = value,
                    server = "",
                    username = "",
                    password = "",
                    passwordVisible = false,
                )
            }
        }
    }

    fun setPlaylistName(value: String) = updateFields { copy(playlistName = value) }
    fun setServer(value: String) = updateFields { copy(server = value) }
    fun setUsername(value: String) = updateFields { copy(username = value) }
    fun setPassword(value: String) = updateFields { copy(password = value) }
    fun setM3uUrl(value: String) = updateFields { copy(m3uUrl = value) }
    fun togglePasswordVisibility() = mutableState.update { it.copy(passwordVisible = !it.passwordVisible) }
    fun toggleM3uUrlVisibility() = mutableState.update { it.copy(m3uUrlVisible = !it.m3uUrlVisible) }

    fun testConnection() {
        launchRequest(isTest = true) { attempt, generation ->
            coroutineContext.ensureActive()
            if (generation != requestGeneration) return@launchRequest
            val result = repository.testConnection(
                attempt.server.baseUrl,
                attempt.username,
                attempt.password,
            )
            coroutineContext.ensureActive()
            if (generation != requestGeneration) return@launchRequest
            val account = result.account
            mutableState.update {
                it.copy(
                    isTesting = false,
                    connectionTested = true,
                    normalizedServer = result.server.baseUrl,
                    isCleartext = result.server.isCleartext,
                    accountSummary = listOfNotNull(
                        account.status.name,
                        account.maximumConnections?.let { count ->
                            "$count connection${if (count == 1) "" else "s"}"
                        },
                    ).joinToString(" · "),
                )
            }
        }
    }

    fun connect() {
        launchRequest(isTest = false) { attempt, generation ->
            coroutineContext.ensureActive()
            if (generation != requestGeneration) return@launchRequest
            if (attempt.server.isCleartext && !mutableState.value.connectionTested) {
                if (generation == requestGeneration) {
                    mutableState.update {
                        it.copy(
                            isConnecting = false,
                            normalizedServer = attempt.server.baseUrl,
                            isCleartext = true,
                            errorMessage = "This provider uses unencrypted HTTP. Test the connection once to acknowledge the risk before connecting.",
                        )
                    }
                }
                return@launchRequest
            }
            repository.login(
                server = attempt.server.baseUrl,
                username = attempt.username,
                password = attempt.password,
                displayName = mutableState.value.playlistName,
            )
            coroutineContext.ensureActive()
            if (generation != requestGeneration) return@launchRequest
            // Clear every secret-bearing input immediately after the repository commits the login.
            mutableState.value = LoginUiState(loginMethod = mutableState.value.loginMethod)
        }
    }

    private fun launchRequest(
        isTest: Boolean,
        block: suspend (LoginAttempt, Long) -> Unit,
    ) {
        activeRequest?.cancel()
        val generation = ++requestGeneration
        val snapshot = mutableState.value
        val resolved = resolveAttempt(snapshot)
        if (resolved is LoginAttemptResult.Invalid) {
            mutableState.update {
                it.copy(
                    isTesting = false,
                    isConnecting = false,
                    errorMessage = resolved.message,
                )
            }
            return
        }
        val attempt = (resolved as LoginAttemptResult.Valid).attempt
        mutableState.update {
            it.copy(
                isTesting = isTest,
                isConnecting = !isTest,
                errorMessage = null,
            )
        }
        activeRequest = viewModelScope.launch {
            try {
                block(attempt, generation)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: AppFailureException) {
                showFailure(failure.failure, generation)
            } catch (_: Exception) {
                showFailure(AppFailure(FailureKind.Unknown), generation)
            }
        }
    }

    private fun resolveAttempt(state: LoginUiState): LoginAttemptResult = when (state.loginMethod) {
        LoginMethod.Credentials -> resolveCredentialFields(state)
        LoginMethod.M3uUrl -> when (val parsed = XtreamM3uUrlParser.parse(state.m3uUrl)) {
            is XtreamM3uUrlResult.Valid -> LoginAttemptResult.Valid(
                LoginAttempt(
                    server = parsed.credentials.server,
                    username = parsed.credentials.username,
                    password = parsed.credentials.password,
                ),
            )
            is XtreamM3uUrlResult.Invalid -> LoginAttemptResult.Invalid(parsed.reason.userMessage())
        }
    }

    private fun resolveCredentialFields(state: LoginUiState): LoginAttemptResult {
        if (state.server.isBlank()) return LoginAttemptResult.Invalid("Enter your provider's server address.")
        val normalized = when (val result = ServerUrlNormalizer.normalize(state.server)) {
            is UrlNormalizationResult.Valid -> result.server
            is UrlNormalizationResult.Invalid -> return LoginAttemptResult.Invalid(
                AppFailure(FailureKind.InvalidServerUrl).userMessage(),
            )
        }
        if (state.username.isBlank()) return LoginAttemptResult.Invalid("Enter your username.")
        if (state.password.isBlank()) return LoginAttemptResult.Invalid("Enter your password.")
        return LoginAttemptResult.Valid(
            LoginAttempt(
                server = normalized,
                username = state.username.trim(),
                password = state.password,
            ),
        )
    }

    private fun showFailure(failure: AppFailure, generation: Long) {
        if (generation != requestGeneration) return
        mutableState.update {
            it.copy(
                isTesting = false,
                isConnecting = false,
                errorMessage = failure.userMessage(),
            )
        }
    }

    private fun updateFields(transform: LoginUiState.() -> LoginUiState) {
        // Once Connect starts, keep the exact credential tuple immutable until the repository's
        // vault/session commit either succeeds or fails. Cancellation cannot roll back that commit.
        if (mutableState.value.isConnecting) return
        requestGeneration += 1
        activeRequest?.cancel()
        activeRequest = null
        mutableState.update {
            it.transform().copy(
                isTesting = false,
                isConnecting = false,
                connectionTested = false,
                normalizedServer = null,
                isCleartext = false,
                accountSummary = null,
                errorMessage = null,
            )
        }
    }

    override fun onCleared() {
        requestGeneration += 1
        activeRequest?.cancel()
        mutableState.value = LoginUiState()
        super.onCleared()
    }

    private class LoginAttempt(
        val server: NormalizedServer,
        val username: String,
        val password: String,
    ) {
        override fun toString(): String = "LoginAttempt(REDACTED)"
    }

    private sealed interface LoginAttemptResult {
        class Valid(val attempt: LoginAttempt) : LoginAttemptResult
        class Invalid(val message: String) : LoginAttemptResult
    }
}

private fun XtreamM3uUrlError.userMessage(): String = when (this) {
    XtreamM3uUrlError.Empty -> "Paste your Xtream M3U URL."
    XtreamM3uUrlError.UnsupportedEndpoint ->
        "Only Xtream get.php or player_api.php links are supported. Arbitrary M3U playlists are not supported yet."
    XtreamM3uUrlError.MissingUsername -> "The Xtream link does not contain a username parameter."
    XtreamM3uUrlError.BlankUsername -> "The Xtream link contains a blank username."
    XtreamM3uUrlError.RepeatedUsername ->
        "For safety, use a link containing exactly one username parameter."
    XtreamM3uUrlError.MissingPassword -> "The Xtream link does not contain a password parameter."
    XtreamM3uUrlError.BlankPassword -> "The Xtream link contains a blank password."
    XtreamM3uUrlError.RepeatedPassword ->
        "For safety, use a link containing exactly one password parameter."
    XtreamM3uUrlError.TooLong,
    XtreamM3uUrlError.ControlCharacter,
    XtreamM3uUrlError.InvalidUrl,
    XtreamM3uUrlError.CredentialTooLong,
    -> "Paste a valid Xtream get.php or player_api.php URL."
}
