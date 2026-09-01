package dev.killua.iptv.core.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class XtreamM3uUrlParserTest {
    @Test
    fun `get php link extracts credentials and preserves port and provider subpath`() {
        val credentials = assertValid(
            "https://iptv.example:8443/provider/root/get.php?type=m3u_plus&password=s3cret&output=ts&username=killua",
        )

        assertThat(credentials.server.baseUrl).isEqualTo("https://iptv.example:8443/provider/root/")
        assertThat(credentials.server.isCleartext).isFalse()
        assertThat(credentials.username).isEqualTo("killua")
        assertThat(credentials.password).isEqualTo("s3cret")
    }

    @Test
    fun `player api endpoint and credential parameter names are case insensitive`() {
        val credentials = assertValid(
            "example.com/portal/PLAYER_API.PHP/?PASSWORD=secret&UserName=alice",
        )

        assertThat(credentials.server.baseUrl).isEqualTo("https://example.com/portal/")
        assertThat(credentials.server.warnings).contains(UrlWarning.HttpsAdded)
        assertThat(credentials.username).isEqualTo("alice")
        assertThat(credentials.password).isEqualTo("secret")
    }

    @Test
    fun `encoded names and values are decoded exactly once`() {
        val credentials = assertValid(
            "https://example.com/get.php?%75sername=Killua%20Zoldyck&pass%77ord=%20p%40ss%2Bword%20",
        )

        assertThat(credentials.username).isEqualTo("Killua Zoldyck")
        assertThat(credentials.password).isEqualTo(" p@ss+word ")
    }

    @Test
    fun `common playlist options and unknown nonsensitive options are ignored`() {
        val credentials = assertValid(
            "http://example.com:8080/get.php?output=m3u8&username=u&token=anything&type=m3u_plus&password=p",
        )

        assertThat(credentials.server.baseUrl).isEqualTo("http://example.com:8080/")
        assertThat(credentials.server.isCleartext).isTrue()
        assertThat(credentials.username).isEqualTo("u")
        assertThat(credentials.password).isEqualTo("p")
    }

    @Test
    fun `duplicate usernames are rejected even with different capitalization`() {
        assertInvalid(
            "https://example.com/get.php?username=first&UserName=second&password=secret",
            XtreamM3uUrlError.RepeatedUsername,
        )
    }

    @Test
    fun `duplicate passwords are rejected even when their values match`() {
        assertInvalid(
            "https://example.com/get.php?username=user&password=secret&PASSWORD=secret",
            XtreamM3uUrlError.RepeatedPassword,
        )
    }

    @Test
    fun `missing username and password are reported separately`() {
        assertInvalid(
            "https://example.com/get.php?password=secret",
            XtreamM3uUrlError.MissingUsername,
        )
        assertInvalid(
            "https://example.com/get.php?username=user",
            XtreamM3uUrlError.MissingPassword,
        )
    }

    @Test
    fun `blank decoded username and password are rejected`() {
        assertInvalid(
            "https://example.com/get.php?username=%20%20&password=secret",
            XtreamM3uUrlError.BlankUsername,
        )
        assertInvalid(
            "https://example.com/get.php?username=user&password=%09",
            XtreamM3uUrlError.BlankPassword,
        )
    }

    @Test
    fun `generic playlists and unrelated endpoints are not treated as Xtream login links`() {
        listOf(
            "https://example.com/channels.m3u",
            "https://example.com/channels.m3u8?username=user&password=secret",
            "https://example.com/xmltv.php?username=user&password=secret",
            "https://example.com/?username=user&password=secret",
        ).forEach { link ->
            assertInvalid(link, XtreamM3uUrlError.UnsupportedEndpoint)
        }
    }

    @Test
    fun `userinfo and fragments are rejected instead of being folded into credentials`() {
        assertInvalid(
            "https://owner:server-secret@example.com/get.php?username=user&password=secret",
            XtreamM3uUrlError.InvalidUrl,
        )
        assertInvalid(
            "https://example.com/get.php?username=user&password=secret#fragment",
            XtreamM3uUrlError.InvalidUrl,
        )
    }

    @Test
    fun `unsupported schemes malformed URLs and interior controls are rejected`() {
        listOf(
            "ftp://example.com/get.php?username=user&password=secret",
            "https://example.com:99999/get.php?username=user&password=secret",
            "https://exam\u0000ple.com/get.php?username=user&password=secret",
        ).forEach { link ->
            val result = XtreamM3uUrlParser.parse(link)
            assertThat(result).isInstanceOf(XtreamM3uUrlResult.Invalid::class.java)
        }
    }

    @Test
    fun `encoded credential controls are rejected`() {
        assertInvalid(
            "https://example.com/get.php?username=user%0Aname&password=secret",
            XtreamM3uUrlError.ControlCharacter,
        )
        assertInvalid(
            "https://example.com/get.php?username=user&password=sec%00ret",
            XtreamM3uUrlError.ControlCharacter,
        )
    }

    @Test
    fun `oversized links and credentials are bounded`() {
        assertInvalid("x".repeat(32_769), XtreamM3uUrlError.TooLong)
        assertInvalid(
            "https://example.com/get.php?username=${"u".repeat(4_097)}&password=secret",
            XtreamM3uUrlError.CredentialTooLong,
        )
    }

    @Test
    fun `result string representations redact the link credentials`() {
        val result = XtreamM3uUrlParser.parse(
            "https://private.example/get.php?username=private-user&password=private-secret",
        )
        val rendered = result.toString()

        assertThat(rendered).contains("REDACTED")
        assertThat(rendered).doesNotContain("private-user")
        assertThat(rendered).doesNotContain("private-secret")
        assertThat(rendered).doesNotContain("private.example")
    }

    private fun assertValid(input: String): ParsedXtreamM3uUrl {
        val result = XtreamM3uUrlParser.parse(input)
        assertThat(result).isInstanceOf(XtreamM3uUrlResult.Valid::class.java)
        return (result as XtreamM3uUrlResult.Valid).credentials
    }

    private fun assertInvalid(input: String, expected: XtreamM3uUrlError) {
        assertThat(XtreamM3uUrlParser.parse(input))
            .isEqualTo(XtreamM3uUrlResult.Invalid(expected))
    }
}
