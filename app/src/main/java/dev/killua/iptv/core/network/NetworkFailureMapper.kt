package dev.killua.iptv.core.network

import android.content.Context
import android.net.ConnectivityManager
import dev.killua.iptv.domain.model.AppFailure
import dev.killua.iptv.domain.model.FailureKind
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

interface NetworkStatus {
    fun hasActiveNetwork(): Boolean
}

class AndroidNetworkStatus(context: Context) : NetworkStatus {
    private val connectivityManager =
        context.getSystemService(ConnectivityManager::class.java)

    override fun hasActiveNetwork(): Boolean = connectivityManager?.activeNetwork != null
}

class NetworkFailureMapper(private val networkStatus: NetworkStatus) {
    fun fromException(error: IOException): AppFailure = when {
        error is SSLException -> AppFailure(FailureKind.TlsFailure)
        error is SocketTimeoutException -> AppFailure(FailureKind.Timeout, retryable = true)
        error.message?.contains("CLEARTEXT", ignoreCase = true) == true ->
            AppFailure(FailureKind.CleartextBlocked)
        !networkStatus.hasActiveNetwork() -> AppFailure(FailureKind.NoNetwork, retryable = true)
        error is UnknownHostException || error is ConnectException ->
            AppFailure(FailureKind.ServerUnavailable, retryable = true)
        else -> AppFailure(FailureKind.ServerUnavailable, retryable = true)
    }

    fun fromHttpStatus(statusCode: Int, endpointIsAuthentication: Boolean): AppFailure = when (statusCode) {
        401, 403 -> AppFailure(FailureKind.AuthenticationFailed)
        404 -> AppFailure(
            if (endpointIsAuthentication) FailureKind.IncompatibleServer else FailureKind.StreamUnavailable,
        )
        408, 429, 502, 503, 504 -> AppFailure(FailureKind.TemporaryServerFailure, retryable = true)
        509 -> AppFailure(FailureKind.ConnectionLimitReached)
        in 500..599 -> AppFailure(FailureKind.ServerUnavailable, retryable = true)
        else -> AppFailure(FailureKind.InvalidServerResponse)
    }
}
