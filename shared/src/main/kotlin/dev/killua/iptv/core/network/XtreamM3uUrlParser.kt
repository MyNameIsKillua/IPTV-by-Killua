package dev.killua.iptv.core.network

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class ParsedXtreamM3uUrl(
    val server: NormalizedServer,
    val username: String,
    val password: String,
) {
    override fun toString(): String = "ParsedXtreamM3uUrl(REDACTED)"
}

sealed interface XtreamM3uUrlResult {
    data class Valid(val credentials: ParsedXtreamM3uUrl) : XtreamM3uUrlResult
    data class Invalid(val reason: XtreamM3uUrlError) : XtreamM3uUrlResult
}

enum class XtreamM3uUrlError {
    Empty,
    TooLong,
    ControlCharacter,
    InvalidUrl,
    UnsupportedEndpoint,
    MissingUsername,
    BlankUsername,
    RepeatedUsername,
    MissingPassword,
    BlankPassword,
    RepeatedPassword,
    CredentialTooLong,
}

/**
 * Extracts Xtream credentials from the provider-style URLs commonly ending in
 * `get.php` or `player_api.php`. This deliberately does not parse arbitrary M3U files.
 */
object XtreamM3uUrlParser {
    private val supportedEndpoints = setOf("get.php", "player_api.php")
    private const val MAX_URL_CHARACTERS = 32_768
    private const val MAX_CREDENTIAL_CHARACTERS = 4_096

    fun parse(rawInput: String): XtreamM3uUrlResult {
        val trimmed = rawInput.trim { it.isWhitespace() || it == '\uFEFF' }
        if (trimmed.isEmpty()) return invalid(XtreamM3uUrlError.Empty)
        if (trimmed.length > MAX_URL_CHARACTERS) return invalid(XtreamM3uUrlError.TooLong)
        if (trimmed.any(Char::isISOControl)) {
            return invalid(XtreamM3uUrlError.ControlCharacter)
        }

        val urlWithScheme = if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else if (trimmed.contains("://")) {
            return invalid(XtreamM3uUrlError.InvalidUrl)
        } else {
            "https://$trimmed"
        }
        val parsed = urlWithScheme.toHttpUrlOrNull()
            ?: return invalid(XtreamM3uUrlError.InvalidUrl)

        val endpoint = parsed.pathSegments.lastOrNull { it.isNotEmpty() }?.lowercase()
        if (endpoint !in supportedEndpoints) {
            return invalid(XtreamM3uUrlError.UnsupportedEndpoint)
        }
        val normalized = when (val result = ServerUrlNormalizer.normalize(trimmed)) {
            is UrlNormalizationResult.Valid -> result.server
            is UrlNormalizationResult.Invalid -> return invalid(XtreamM3uUrlError.InvalidUrl)
        }

        val usernames = queryValues(parsed.querySize) { index ->
            parsed.queryParameterName(index) to parsed.queryParameterValue(index)
        }.filter { (name, _) -> name.equals("username", ignoreCase = true) }
        val passwords = queryValues(parsed.querySize) { index ->
            parsed.queryParameterName(index) to parsed.queryParameterValue(index)
        }.filter { (name, _) -> name.equals("password", ignoreCase = true) }

        val username = when {
            usernames.isEmpty() -> return invalid(XtreamM3uUrlError.MissingUsername)
            usernames.size > 1 -> return invalid(XtreamM3uUrlError.RepeatedUsername)
            usernames.single().second.isNullOrBlank() -> return invalid(XtreamM3uUrlError.BlankUsername)
            else -> usernames.single().second.orEmpty().trim()
        }
        val password = when {
            passwords.isEmpty() -> return invalid(XtreamM3uUrlError.MissingPassword)
            passwords.size > 1 -> return invalid(XtreamM3uUrlError.RepeatedPassword)
            passwords.single().second.isNullOrBlank() -> return invalid(XtreamM3uUrlError.BlankPassword)
            else -> passwords.single().second.orEmpty()
        }
        if (username.length > MAX_CREDENTIAL_CHARACTERS || password.length > MAX_CREDENTIAL_CHARACTERS) {
            return invalid(XtreamM3uUrlError.CredentialTooLong)
        }
        if (username.any(Char::isISOControl) || password.any(Char::isISOControl)) {
            return invalid(XtreamM3uUrlError.ControlCharacter)
        }

        return XtreamM3uUrlResult.Valid(
            ParsedXtreamM3uUrl(
                server = normalized,
                username = username,
                password = password,
            ),
        )
    }

    private inline fun queryValues(
        querySize: Int,
        valueAt: (Int) -> Pair<String, String?>,
    ): List<Pair<String, String?>> = buildList(querySize) {
        repeat(querySize) { index -> add(valueAt(index)) }
    }

    private fun invalid(reason: XtreamM3uUrlError) = XtreamM3uUrlResult.Invalid(reason)
}
