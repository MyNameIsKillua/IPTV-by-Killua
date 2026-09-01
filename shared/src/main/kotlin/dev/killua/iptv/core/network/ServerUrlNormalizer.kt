package dev.killua.iptv.core.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class NormalizedServer(
    val baseUrl: String,
    val isCleartext: Boolean,
    val warnings: Set<UrlWarning>,
)

enum class UrlWarning {
    HttpsAdded,
    EndpointRemoved,
    SensitiveQueryRemoved,
    CleartextConnection,
}

sealed interface UrlNormalizationResult {
    data class Valid(val server: NormalizedServer) : UrlNormalizationResult
    data class Invalid(val reason: UrlError) : UrlNormalizationResult
}

enum class UrlError {
    Empty,
    ControlCharacter,
    UnsupportedScheme,
    MissingHost,
    UserInfoNotAllowed,
    QueryNotAllowed,
    FragmentNotAllowed,
    Malformed,
}

object ServerUrlNormalizer {
    private val endpointNames = setOf("player_api.php", "get.php", "xmltv.php")
    private val explicitScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
    private val schemeLikePrefix = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    fun normalize(rawInput: String): UrlNormalizationResult {
        val trimmed = rawInput.trim { it.isWhitespace() || it == '\uFEFF' }
        if (trimmed.isEmpty()) return UrlNormalizationResult.Invalid(UrlError.Empty)
        if (trimmed.any(Char::isISOControl)) {
            return UrlNormalizationResult.Invalid(UrlError.ControlCharacter)
        }

        val warnings = linkedSetOf<UrlWarning>()
        val withScheme = when {
            explicitScheme.containsMatchIn(trimmed) -> trimmed
            schemeLikePrefix.containsMatchIn(trimmed) && !trimmed.substringBefore(':').all(Char::isDigit) -> {
                val prefix = trimmed.substringBefore(':')
                val numericPort = trimmed.substringAfter(':')
                    .substringBefore('/')
                    .toIntOrNull()
                    ?.takeIf { it in 1..65_535 }
                if (prefix.contains('.') || prefix.equals("localhost", true) || numericPort != null) {
                    warnings += UrlWarning.HttpsAdded
                    "https://$trimmed"
                } else {
                    return UrlNormalizationResult.Invalid(UrlError.UnsupportedScheme)
                }
            }
            else -> {
                warnings += UrlWarning.HttpsAdded
                "https://$trimmed"
            }
        }

        val scheme = withScheme.substringBefore(':').lowercase()
        if (scheme !in setOf("http", "https")) {
            return UrlNormalizationResult.Invalid(UrlError.UnsupportedScheme)
        }

        val parsed = withScheme.toHttpUrlOrNull()
            ?: return UrlNormalizationResult.Invalid(UrlError.Malformed)
        if (parsed.host.isBlank()) return UrlNormalizationResult.Invalid(UrlError.MissingHost)
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            return UrlNormalizationResult.Invalid(UrlError.UserInfoNotAllowed)
        }
        if (parsed.fragment != null) {
            return UrlNormalizationResult.Invalid(UrlError.FragmentNotAllowed)
        }

        val lastSegment = parsed.pathSegments.lastOrNull { it.isNotEmpty() }
        val hasKnownEndpoint = lastSegment?.lowercase() in endpointNames
        if (parsed.query != null && !hasKnownEndpoint) {
            return UrlNormalizationResult.Invalid(UrlError.QueryNotAllowed)
        }

        var encodedPath = parsed.encodedPath.trimEnd('/')
        if (hasKnownEndpoint) {
            encodedPath = encodedPath.substringBeforeLast('/', "")
            warnings += UrlWarning.EndpointRemoved
            if (parsed.query != null) warnings += UrlWarning.SensitiveQueryRemoved
        }
        encodedPath = if (encodedPath.isBlank()) "/" else "$encodedPath/"

        val normalized = parsed.newBuilder()
            .encodedPath(encodedPath)
            .query(null)
            .fragment(null)
            .build()

        if (normalized.scheme == "http") warnings += UrlWarning.CleartextConnection
        return UrlNormalizationResult.Valid(
            NormalizedServer(
                baseUrl = normalized.toString(),
                isCleartext = normalized.scheme == "http",
                warnings = warnings,
            ),
        )
    }
}
