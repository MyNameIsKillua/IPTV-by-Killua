package dev.killua.iptv.desktop

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * How a viewer signed in, kept only if they asked for it to be.
 *
 * Two shapes because there are two ways in, and replaying the wrong one would mean asking someone
 * who signs in with a playlist link to produce a username they have never seen.
 */
@Serializable
data class StoredSignIn(
    val server: String = "",
    val username: String = "",
    val password: String = "",
    /** The whole `get.php` line, for the viewers who sign in with one. */
    val link: String = "",
) {
    val isLink: Boolean get() = link.isNotBlank()

    val isUsable: Boolean
        get() = isLink || (server.isNotBlank() && username.isNotBlank() && password.isNotBlank())
}

/**
 * Something that can seal bytes so that only this machine's owner can read them back.
 *
 * An interface with one real implementation, and the reason is the same as everywhere else in this
 * module: the rules worth testing — what is written, what happens to a damaged file, that a password
 * never appears in it — must be testable without calling into Windows.
 */
interface SecretCipher {
    val isAvailable: Boolean
    fun protect(plain: ByteArray): ByteArray?
    fun unprotect(sealed: ByteArray): ByteArray?

    companion object {
        /** DPAPI on Windows, and nothing anywhere else — see [DpapiCipher]. */
        fun forThisMachine(): SecretCipher =
            if (System.getProperty("os.name").orEmpty().startsWith("Windows")) {
                DpapiCipher
            } else {
                UnavailableCipher
            }
    }
}

/**
 * Windows' own data protection, which is what "properly" meant.
 *
 * `CryptProtectData` seals the bytes against the **logged-in Windows account**: nobody else on the
 * machine can read them back, and neither can the same file carried to another machine. It is not a
 * safe against someone who is already sitting at an unlocked session as this user — nothing on this
 * side of a login is — and the settings screen says exactly that rather than implying more.
 *
 * The call goes through JNA, which is already here for libvlc, so this costs no new dependency.
 */
private object DpapiCipher : SecretCipher {
    override val isAvailable: Boolean = true

    override fun protect(plain: ByteArray): ByteArray? = runCatching {
        com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(plain)
    }.getOrNull()

    override fun unprotect(sealed: ByteArray): ByteArray? = runCatching {
        com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(sealed)
    }.getOrNull()
}

/**
 * What every other platform gets.
 *
 * Refusing is the honest answer: writing a password to disk in the clear because this happens to be
 * a Mac would be worse than asking someone to type it. macOS has a Keychain and it will want its own
 * implementation of this interface; until then the checkbox is simply not offered there.
 */
private object UnavailableCipher : SecretCipher {
    override val isAvailable: Boolean = false
    override fun protect(plain: ByteArray): ByteArray? = null
    override fun unprotect(sealed: ByteArray): ByteArray? = null
}

/**
 * The one file in this client that holds an account, and the only one that ever has.
 *
 * Everything else it writes is deliberately free of credentials — the state file is the export
 * format, the sidecars hold names and window furniture — and that is not changing: this is a
 * separate file, written **only** when the viewer ticks a box, and deleted the moment they untick
 * it or sign out.
 *
 * What is *not* here is worth saying too. The watch history has never depended on this and still
 * does not: it is keyed by a one-way fingerprint of the server and username, so signing in again by
 * hand brings it all back. Ticking the box buys the typing, nothing else.
 */
class CredentialVault(
    private val directory: File = DesktopUserData.defaultDirectory(),
    private val cipher: SecretCipher = SecretCipher.forThisMachine(),
) {
    private val file: File get() = File(directory, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true }

    /** False where nothing can seal the bytes; the checkbox is then not offered at all. */
    val isSupported: Boolean get() = cipher.isAvailable

    val hasStored: Boolean get() = file.isFile

    fun load(): StoredSignIn? {
        if (!isSupported) return null
        val sealed = runCatching { file.takeIf { it.isFile }?.readBytes() }.getOrNull() ?: return null
        // Sealed for somebody else, damaged, or written by a version that meant something else by
        // it. Every one of those will still be unopenable next launch, so the file goes rather than
        // being retried for ever — and a client that silently fails to sign in every time is the
        // thing this is avoiding.
        val plain = cipher.unprotect(sealed)
        val stored = plain
            ?.let { runCatching { json.decodeFromString<StoredSignIn>(it.decodeToString()) }.getOrNull() }
            ?.takeIf { it.isUsable }
        if (stored == null) forget()
        return stored
    }

    /** Returns whether it was actually written, so the screen can stop claiming that it was. */
    fun save(signIn: StoredSignIn): Boolean {
        if (!isSupported || !signIn.isUsable) return false
        val sealed = cipher.protect(json.encodeToString(signIn).toByteArray()) ?: return false
        return runCatching {
            writeAtomically(directory, FILE_NAME) { it.writeBytes(sealed) }
            true
        }.getOrDefault(false)
    }

    fun forget() {
        runCatching { file.delete() }
    }

    private companion object {
        const val FILE_NAME = "credentials.bin"
    }
}
