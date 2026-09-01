package dev.killua.iptv.core.security

import dev.killua.iptv.domain.model.LibrarySource
import dev.killua.iptv.domain.model.XtreamCredentials
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

internal object CredentialCodec {
    /**
     * 2 adds the library source; 1 is still read.
     *
     * Reading version 1 is not politeness. A record written by an earlier build is the viewer's
     * saved sign-in, and refusing it would sign them out on update to fetch a field that has a
     * correct answer without being stored: everything written before this existed is an Xtream
     * account, because playlists could not be signed into at all.
     */
    private const val VERSION = 2
    private const val FIRST_VERSION = 1
    private const val MAX_FIELD_BYTES = 64 * 1024

    fun encode(credentials: XtreamCredentials): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(VERSION)
            output.writeField(credentials.accountId)
            output.writeField(credentials.serverUrl)
            output.writeField(credentials.username)
            output.writeField(credentials.password)
            output.writeField(credentials.source.name)
        }
        bytes.toByteArray()
    }

    fun decode(payload: ByteArray): XtreamCredentials = DataInputStream(ByteArrayInputStream(payload)).use { input ->
        val version = input.readInt()
        require(version in FIRST_VERSION..VERSION) { "Unsupported credential record" }
        val accountId = input.readField()
        val serverUrl = input.readField()
        val username = input.readField()
        val password = input.readField()
        val source = if (version >= 2) {
            val name = input.readField()
            // A record naming a source this build does not know is read as an Xtream account
            // rather than refused: the fields that matter are all present, and signing someone
            // out over a name is a worse answer than the one this can still give.
            LibrarySource.entries.firstOrNull { it.name == name } ?: LibrarySource.Xtream
        } else {
            LibrarySource.Xtream
        }
        val credentials = XtreamCredentials(accountId, serverUrl, username, password, source)
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
