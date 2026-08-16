package dev.killua.iptv.core.security

import dev.killua.iptv.domain.model.XtreamCredentials
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

internal object CredentialCodec {
    private const val VERSION = 1
    private const val MAX_FIELD_BYTES = 64 * 1024

    fun encode(credentials: XtreamCredentials): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(VERSION)
            output.writeField(credentials.accountId)
            output.writeField(credentials.serverUrl)
            output.writeField(credentials.username)
            output.writeField(credentials.password)
        }
        bytes.toByteArray()
    }

    fun decode(payload: ByteArray): XtreamCredentials = DataInputStream(ByteArrayInputStream(payload)).use { input ->
        require(input.readInt() == VERSION) { "Unsupported credential record" }
        val credentials = XtreamCredentials(
            accountId = input.readField(),
            serverUrl = input.readField(),
            username = input.readField(),
            password = input.readField(),
        )
        require(input.available() == 0) { "Trailing credential data" }
        credentials
    }

    private fun DataOutputStream.writeField(value: String) {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        require(encoded.size <= MAX_FIELD_BYTES) { "Credential field is too large" }
        writeInt(encoded.size)
        write(encoded)
    }

    private fun DataInputStream.readField(): String {
        val size = readInt()
        require(size in 0..MAX_FIELD_BYTES) { "Invalid credential field size" }
        val encoded = ByteArray(size)
        readFully(encoded)
        return String(encoded, StandardCharsets.UTF_8)
    }
}
