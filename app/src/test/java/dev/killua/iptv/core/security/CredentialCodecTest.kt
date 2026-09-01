package dev.killua.iptv.core.security

import com.google.common.truth.Truth.assertThat
import dev.killua.iptv.domain.model.LibrarySource
import dev.killua.iptv.domain.model.XtreamCredentials
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import org.junit.Assert.assertThrows
import org.junit.Test

class CredentialCodecTest {
    @Test
    fun `credentials round trip without exposing assumptions about characters`() {
        val credentials = XtreamCredentials(
            accountId = "konto-\u0000-🔐",
            serverUrl = "https://例.example/iptv/",
            username = " Jörg / юзер ",
            password = "päss\u0000word 🔑",
        )

        val decoded = CredentialCodec.decode(CredentialCodec.encode(credentials))

        assertCredentialsEqual(decoded, credentials)
    }

    @Test
    fun `empty fields round trip`() {
        val credentials = XtreamCredentials("", "", "", "")

        assertCredentialsEqual(
            CredentialCodec.decode(CredentialCodec.encode(credentials)),
            credentials,
        )
    }

    @Test
    fun `encoding is deterministic`() {
        val credentials = XtreamCredentials("id", "https://example.com/", "user", "password")

        assertThat(CredentialCodec.encode(credentials))
            .isEqualTo(CredentialCodec.encode(credentials))
    }

    @Test
    fun `field size limit is measured in UTF-8 bytes`() {
        val exactlyLimit = "🔐".repeat(16 * 1_024)
        val overLimit = exactlyLimit + "a"

        val decoded = CredentialCodec.decode(
            CredentialCodec.encode(XtreamCredentials("id", "server", "user", exactlyLimit)),
        )
        assertThat(decoded.password).isEqualTo(exactlyLimit)
        assertThrows(IllegalArgumentException::class.java) {
            CredentialCodec.encode(XtreamCredentials("id", "server", "user", overLimit))
        }
    }

    @Test
    fun `unsupported record version is rejected`() {
        // Was version 2 until 26 August 2026, when 2 became the current format. Zero covers the
        // lower bound; the upper one has a case of its own.
        val payload = record(version = 0, fields = listOf("id", "server", "user", "password"))

        val error = assertThrows(IllegalArgumentException::class.java) {
            CredentialCodec.decode(payload)
        }
        assertThat(error).hasMessageThat().contains("Unsupported credential record")
    }

    @Test
    fun `negative and oversized field lengths are rejected before allocation`() {
        listOf(-1, 64 * 1_024 + 1, Int.MAX_VALUE).forEach { size ->
            val payload = ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.writeInt(1)
                    output.writeInt(size)
                }
                bytes.toByteArray()
            }

            val error = assertThrows(IllegalArgumentException::class.java) {
                CredentialCodec.decode(payload)
            }
            assertThat(error).hasMessageThat().contains("Invalid credential field size")
        }
    }

    @Test
    fun `truncated records are rejected`() {
        assertThrows(EOFException::class.java) { CredentialCodec.decode(byteArrayOf()) }

        val complete = CredentialCodec.encode(
            XtreamCredentials("id", "https://example.com/", "user", "password"),
        )
        assertThrows(EOFException::class.java) {
            CredentialCodec.decode(complete.copyOf(complete.size - 1))
        }
    }

    @Test
    fun `trailing data is rejected`() {
        val encoded = CredentialCodec.encode(
            XtreamCredentials("id", "https://example.com/", "user", "password"),
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            CredentialCodec.decode(encoded + byteArrayOf(0))
        }
        assertThat(error).hasMessageThat().contains("Trailing credential data")
    }

    @Test
    fun `a playlist account survives the round trip with its source`() {
        val credentials = XtreamCredentials(
            accountId = "account-1",
            serverUrl = "https://playlist.example/index.m3u",
            username = "",
            password = "",
            source = LibrarySource.Playlist,
        )

        val decoded = CredentialCodec.decode(CredentialCodec.encode(credentials))

        assertCredentialsEqual(decoded, credentials)
        assertThat(decoded.source).isEqualTo(LibrarySource.Playlist)
    }

    @Test
    fun `a record written before the source existed is still read, as an Xtream account`() {
        // Exactly what version 1 wrote: four fields and no fifth. Refusing it would sign the
        // viewer out on update to recover a value that has only one possible answer.
        val legacy = record(1, listOf("account-1", "https://provider.example/", "killua", "s3cret"))

        val decoded = CredentialCodec.decode(legacy)

        assertThat(decoded.accountId).isEqualTo("account-1")
        assertThat(decoded.serverUrl).isEqualTo("https://provider.example/")
        assertThat(decoded.username).isEqualTo("killua")
        assertThat(decoded.password).isEqualTo("s3cret")
        assertThat(decoded.source).isEqualTo(LibrarySource.Xtream)
    }

    @Test
    fun `a source this build does not know reads as Xtream rather than refusing the record`() {
        val unknown = record(
            2,
            listOf("account-1", "https://provider.example/", "killua", "s3cret", "SomethingLater"),
        )

        assertThat(CredentialCodec.decode(unknown).source).isEqualTo(LibrarySource.Xtream)
    }

    @Test
    fun `a version from the future is still refused`() {
        val ahead = record(3, listOf("account-1", "https://provider.example/", "killua", "s3cret"))

        assertThrows(IllegalArgumentException::class.java) { CredentialCodec.decode(ahead) }
    }

    private fun record(version: Int, fields: List<String>): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(version)
                fields.forEach { field ->
                    val encoded = field.toByteArray(Charsets.UTF_8)
                    output.writeInt(encoded.size)
                    output.write(encoded)
                }
            }
            bytes.toByteArray()
        }

    private fun assertCredentialsEqual(actual: XtreamCredentials, expected: XtreamCredentials) {
        assertThat(actual.accountId).isEqualTo(expected.accountId)
        assertThat(actual.serverUrl).isEqualTo(expected.serverUrl)
        assertThat(actual.username).isEqualTo(expected.username)
        assertThat(actual.password).isEqualTo(expected.password)
        assertThat(actual.source).isEqualTo(expected.source)
    }
}
