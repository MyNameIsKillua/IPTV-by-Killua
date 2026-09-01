package dev.killua.iptv.data.xtream

import com.google.common.truth.Truth.assertThat
import dev.killua.iptv.domain.model.AccountStatus
import dev.killua.iptv.domain.model.AppFailureException
import dev.killua.iptv.domain.model.FailureKind
import org.junit.Test

class XtreamJsonParserTest {
    private val now = 1_800_000_000L
    private val parser = XtreamJsonParser(nowEpochSeconds = { now })

    @Test
    fun `account accepts common true representations and flexible scalar fields`() {
        listOf("true", "1", "\"1\"", "\"true\"", "\"YES\"").forEach { auth ->
            val account = parser.parseAccount(
                """
                {
                  "user_info": {
                    "auth": $auth,
                    "username": "  alice  ",
                    "status": "ACTIVE",
                    "exp_date": "1800001000000",
                    "active_cons": "2",
                    "max_connections": 4,
                    "allowed_output_formats": ["M3U8", "ts", "mp4", "m3u8"]
                  },
                  "server_info": {"timezone": " Europe/Berlin "}
                }
                """.trimIndent(),
            )

            assertThat(account.username).isEqualTo("alice")
            assertThat(account.status).isEqualTo(AccountStatus.Active)
            assertThat(account.expiresAtEpochSeconds).isEqualTo(1_800_001_000L)
            assertThat(account.activeConnections).isEqualTo(2)
            assertThat(account.maximumConnections).isEqualTo(4)
            assertThat(account.serverTimezone).isEqualTo("Europe/Berlin")
            assertThat(account.allowedOutputFormats).containsExactly("m3u8", "ts").inOrder()
        }
    }

    @Test
    fun `account accepts delimited output formats and ignores null-like metadata`() {
        val account = parser.parseAccount(
            """
            {
              "user_info": {
                "auth": "yes",
                "username": "undefined",
                "status": "unexpected",
                "exp_date": 0,
                "active_cons": "not-a-number",
                "allowed_output_formats": " TS; m3u8,mp4;ts "
              },
              "server_info": {"timezone": "N/A"}
            }
            """.trimIndent(),
        )

        assertThat(account.username).isNull()
        assertThat(account.status).isEqualTo(AccountStatus.Unknown)
        assertThat(account.expiresAtEpochSeconds).isNull()
        assertThat(account.activeConnections).isNull()
        assertThat(account.maximumConnections).isNull()
        assertThat(account.serverTimezone).isNull()
        assertThat(account.allowedOutputFormats).containsExactly("ts", "m3u8").inOrder()
    }

    @Test
    fun `missing status defaults to active and expiry equal to now is still valid`() {
        val account = parser.parseAccount(
            """{"user_info":{"auth":true,"exp_date":$now}}""",
        )

        assertThat(account.status).isEqualTo(AccountStatus.Active)
        assertThat(account.expiresAtEpochSeconds).isEqualTo(now)
    }

    @Test
    fun `false missing and unrecognized auth values fail authentication`() {
        listOf(
            """{"user_info":{"auth":false}}""",
            """{"user_info":{"auth":0}}""",
            """{"user_info":{"auth":"no"}}""",
            """{"user_info":{"auth":"maybe"}}""",
            """{"user_info":{}}""",
        ).forEach { payload ->
            assertFailure(FailureKind.AuthenticationFailed) { parser.parseAccount(payload) }
        }
    }

    @Test
    fun `expired status or past positive expiry fails as expired`() {
        listOf(
            """{"user_info":{"auth":1,"status":"expired"}}""",
            """{"user_info":{"auth":1,"status":"active","exp_date":${now - 1}}}""",
            """{"user_info":{"auth":1,"status":"disabled","exp_date":${now - 1}}}""",
        ).forEach { payload ->
            assertFailure(FailureKind.AccountExpired) { parser.parseAccount(payload) }
        }
    }

    @Test
    fun `disabled and banned statuses fail as disabled`() {
        listOf("disabled", "banned").forEach { status ->
            assertFailure(FailureKind.AccountDisabled) {
                parser.parseAccount(
                    """{"user_info":{"auth":1,"status":"$status","exp_date":${now + 1}}}""",
                )
            }
        }
    }

    @Test
    fun `malformed account responses are classified as invalid server responses`() {
        listOf(
            "",
            "  <html>login</html>",
            "{broken",
            "[]",
            "null",
            "{}",
            """{"user_info":[]}""",
        ).forEach { payload ->
            assertFailure(FailureKind.InvalidServerResponse) { parser.parseAccount(payload) }
        }
    }

    @Test
    fun `categories tolerate mixed records apply fallbacks and keep first duplicate`() {
        val categories = parser.parseCategories(
            """
            [
              {"category_id":" news ","category_name":" News "},
              "ignored",
              {"category_name":"Missing id"},
              {"category_id":7,"category_name":"null"},
              {"category_id":"news","category_name":"Duplicate"}
            ]
            """.trimIndent(),
        )

        assertThat(categories).hasSize(2)
        assertThat(categories[0].id).isEqualTo("news")
        assertThat(categories[0].name).isEqualTo("News")
        assertThat(categories[0].sortOrder).isEqualTo(0)
        assertThat(categories[1].id).isEqualTo("7")
        assertThat(categories[1].name).isEqualTo("Category 7")
        // Non-object elements are discarded before provider ordering is assigned.
        assertThat(categories[1].sortOrder).isEqualTo(2)
    }

    @Test
    fun `categories support keyed object responses and null responses`() {
        val categories = parser.parseCategories(
            """{"first":{"category_id":"1","category_name":"One"},"other":42,"second":{"category_id":"2"}}""",
        )

        assertThat(categories.map { it.id }).containsExactly("1", "2").inOrder()
        assertThat(categories.map { it.name }).containsExactly("One", "Category 2").inOrder()
        assertThat(parser.parseCategories("null")).isEmpty()
    }

    @Test
    fun `channels sanitize metadata preserve first duplicate and use provider order fallbacks`() {
        val channels = parser.parseChannels(
            """
            [
              {"name":"missing id"},
              {
                "stream_id":101,
                "category_id":" 5 ",
                "name":" News HD ",
                "stream_icon":"https://cdn.example/logo image.png",
                "epg_channel_id":"news.de",
                "container_extension":"M3U8",
                "direct_source":"http://stream.example/live.ts",
                "num":"12"
              },
              {
                "stream_id":"102",
                "name":"undefined",
                "stream_icon":"ftp://example.com/logo.png",
                "epg_channel_id":"N/A",
                "container_extension":"mp4",
                "direct_source":"not a URL",
                "num":"too-large"
              },
              {"stream_id":"101","name":"Duplicate"}
            ]
            """.trimIndent(),
        )

        assertThat(channels).hasSize(2)
        with(channels[0]) {
            assertThat(id).isEqualTo("101")
            assertThat(categoryId).isEqualTo("5")
            assertThat(name).isEqualTo("News HD")
            assertThat(logoUrl).isEqualTo("https://cdn.example/logo%20image.png")
            assertThat(epgChannelId).isEqualTo("news.de")
            assertThat(containerExtension).isEqualTo("m3u8")
            assertThat(directSource).isEqualTo("http://stream.example/live.ts")
            assertThat(providerOrder).isEqualTo(12)
        }
        with(channels[1]) {
            assertThat(id).isEqualTo("102")
            assertThat(categoryId).isNull()
            assertThat(name).isEqualTo("Channel 102")
            assertThat(logoUrl).isNull()
            assertThat(epgChannelId).isNull()
            assertThat(containerExtension).isNull()
            assertThat(directSource).isNull()
            assertThat(providerOrder).isEqualTo(2)
        }
    }

    @Test
    fun `channel and category primitive roots are invalid while null is empty`() {
        listOf("true", "42", "\"items\"").forEach { payload ->
            assertFailure(FailureKind.InvalidServerResponse) { parser.parseCategories(payload) }
            assertFailure(FailureKind.InvalidServerResponse) { parser.parseChannels(payload) }
        }
        assertThat(parser.parseChannels("null")).isEmpty()
    }

    private fun assertFailure(expected: FailureKind, block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected $expected")
        } catch (failure: AppFailureException) {
            assertThat(failure.failure.kind).isEqualTo(expected)
        }
    }
}
