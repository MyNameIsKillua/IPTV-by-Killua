package dev.killua.iptv.domain.model

sealed interface SessionState {
    data object Booting : SessionState
    data object SignedOut : SessionState
    data class Authenticated(
        val account: Account,
        val validationWarning: AppFailure? = null,
    ) : SessionState

    data class ReconnectRequired(
        val account: Account?,
        val reason: AppFailure,
    ) : SessionState
}

enum class FailureKind {
    InvalidServerUrl,
    NoNetwork,
    ServerUnavailable,
    Timeout,
    TlsFailure,
    CleartextBlocked,
    AuthenticationFailed,
    AccountExpired,
    AccountDisabled,
    IncompatibleServer,
    InvalidServerResponse,
    TemporaryServerFailure,
    ConnectionLimitReached,
    StreamUnavailable,
    LibraryTooLarge,
    UnsupportedStreamFormat,
    DecoderFailure,
    SecureStorageUnavailable,
    Unknown,
}

data class AppFailure(
    val kind: FailureKind,
    val retryable: Boolean = false,
) {
    fun userMessage(): String = when (kind) {
        FailureKind.InvalidServerUrl -> "Enter a valid HTTP or HTTPS server address."
        FailureKind.NoNetwork -> "No network connection is available."
        FailureKind.ServerUnavailable -> "The IPTV server could not be reached."
        FailureKind.Timeout -> "The server took too long to respond."
        FailureKind.TlsFailure -> "A secure connection could not be verified."
        FailureKind.CleartextBlocked -> "This HTTP server is blocked by Android security settings."
        FailureKind.AuthenticationFailed -> "The username or password was not accepted."
        FailureKind.AccountExpired -> "This IPTV account has expired."
        FailureKind.AccountDisabled -> "This IPTV account is disabled."
        FailureKind.IncompatibleServer -> "This server does not expose a compatible Xtream API."
        FailureKind.InvalidServerResponse -> "The server returned an unreadable response."
        FailureKind.TemporaryServerFailure -> "The server is temporarily unavailable."
        FailureKind.ConnectionLimitReached -> "The account connection limit may have been reached."
        FailureKind.StreamUnavailable -> "This stream is currently unavailable."
        FailureKind.LibraryTooLarge ->
            "This library is too large to load on this device. Try a narrower category."
        FailureKind.UnsupportedStreamFormat -> "This stream format is not supported on this device."
        FailureKind.DecoderFailure -> "The device could not decode this stream."
        FailureKind.SecureStorageUnavailable -> "Secure storage is unavailable. Please sign in again."
        FailureKind.Unknown -> "Something went wrong. Please try again."
    }
}

class AppFailureException(val failure: AppFailure) : Exception(failure.kind.name)
