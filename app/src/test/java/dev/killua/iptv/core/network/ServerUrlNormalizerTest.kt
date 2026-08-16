package dev.killua.iptv.core.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ServerUrlNormalizerTest {
    @Test
    fun `blank input including a byte order mark is rejected`() {
        assertInvalid(" \t\uFEFF ", UrlError.Empty)
    }

    @Test
    fun `scheme-less server gets https and a trailing slash`() {
        val server = assertValid("  example.com/iptv  ")

        assertThat(server.baseUrl).isEqualTo("https://example.com/iptv/")
        assertThat(server.isCleartext).isFalse()
        assertThat(server.warnings).containsExactly(UrlWarning.HttpsAdded)
    }

    @Test
    fun `scheme-less host with port is accepted`() {
        val server = assertValid("example.com:8080")

        assertThat(server.baseUrl).isEqualTo("https://example.com:8080/")
        assertThat(server.warnings).containsExactly(UrlWarning.HttpsAdded)
    }

    @Test
    fun `scheme-less local network hostname with port is accepted`() {
        val server = assertValid("iptv-box:8080/portal")

        assertThat(server.baseUrl).isEqualTo("https://iptv-box:8080/portal/")
        assertThat(server.warnings).containsExactly(UrlWarning.HttpsAdded)
    }

    @Test
    fun `explicit http is retained and warned about`() {
        val server = assertValid("http://example.com:8080/base")

        assertThat(server.baseUrl).isEqualTo("http://example.com:8080/base/")
        assertThat(server.isCleartext).isTrue()
        assertThat(server.warnings).containsExactly(UrlWarning.CleartextConnection)
    }

    @Test
    fun `explicit https is normalized without warnings`() {
        val server = assertValid("HTTPS://Example.COM:443/base///")

        assertThat(server.baseUrl).isEqualTo("https://example.com/base/")
        assertThat(server.warnings).isEmpty()
    }

    @Test
    fun `known Xtream endpoints and sensitive queries are removed`() {
        listOf("player_api.php", "get.php", "xmltv.php").forEach { endpoint ->
            val server = assertValid(
                "https://example.com/sub/$endpoint?username=user&password=secret",
            )

            assertThat(server.baseUrl).isEqualTo("https://example.com/sub/")
            assertThat(server.warnings).containsExactly(
                UrlWarning.EndpointRemoved,
                UrlWarning.SensitiveQueryRemoved,
            )
        }
    }

    @Test
    fun `known endpoint matching is case insensitive and works with a trailing slash`() {
        val server = assertValid("example.com/PLAYER_API.PHP/")

        assertThat(server.baseUrl).isEqualTo("https://example.com/")
        assertThat(server.warnings).containsExactly(
            UrlWarning.HttpsAdded,
            UrlWarning.EndpointRemoved,
        )
    }

    @Test
    fun `query on a regular path is rejected`() {
        assertInvalid("https://example.com/portal?foo=bar", UrlError.QueryNotAllowed)
    }

    @Test
    fun `userinfo is rejected`() {
        assertInvalid("https://user:secret@example.com/", UrlError.UserInfoNotAllowed)
    }

    @Test
    fun `fragment is rejected`() {
        assertInvalid("https://example.com/#account", UrlError.FragmentNotAllowed)
    }

    @Test
    fun `interior control character is rejected`() {
        assertInvalid("https://exam\u0000ple.com", UrlError.ControlCharacter)
    }

    @Test
    fun `unsupported schemes are rejected`() {
        listOf("ftp://example.com", "intent://example.com", "mailto:user@example.com").forEach {
            assertInvalid(it, UrlError.UnsupportedScheme)
        }
    }

    @Test
    fun `malformed HTTP URLs are rejected`() {
        listOf("https://", "http://exa mple.com", "https://example.com:99999").forEach {
            assertInvalid(it, UrlError.Malformed)
        }
    }

    private fun assertValid(input: String): NormalizedServer {
        val result = ServerUrlNormalizer.normalize(input)
        assertThat(result).isInstanceOf(UrlNormalizationResult.Valid::class.java)
        return (result as UrlNormalizationResult.Valid).server
    }

    private fun assertInvalid(input: String, expected: UrlError) {
        val result = ServerUrlNormalizer.normalize(input)
        assertThat(result).isEqualTo(UrlNormalizationResult.Invalid(expected))
    }
}
