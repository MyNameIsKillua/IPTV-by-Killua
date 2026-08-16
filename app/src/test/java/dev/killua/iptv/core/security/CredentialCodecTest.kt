package dev.killua.iptv.core.security

import com.google.common.truth.Truth.assertThat
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
        val payload = record(version = 2, fields = listOf("id", "server", "user", "password"))

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
    }
}
