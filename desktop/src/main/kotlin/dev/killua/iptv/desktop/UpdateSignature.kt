package dev.killua.iptv.desktop

import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * The rule Windows does not have, enforced here instead.
 *
 * Android refuses any update whose signing certificate differs from the installed app's, and that
 * check belongs to the operating system: it is automatic, and nothing in an APK can talk it out of
 * it. Windows has no equivalent, and no certificate authority provides one - Authenticode proves a
 * signature is *valid*, never that it belongs to the same publisher as the program being replaced.
 * A certificate would remove *"Unknown publisher"* from the elevation prompt and, in time, quiet
 * SmartScreen. It would not stop a validly signed installer from somebody else.
 *
 * So the client carries the public half of a key that only the maintainer holds, and refuses any
 * installer that was not signed by the private half. That is the same promise Android makes, made
 * by this code rather than by the system underneath it.
 *
 * The signature is produced by `tools/ReleaseSigning.java` at release time and published beside the
 * installer as `<name>.msi.sig`.
 */
object UpdateSignature {

    /**
     * The public half. Safe to publish - it is published, right here - and the private half cannot
     * be derived from it.
     *
     * Changing this string is not an ordinary edit. Every installed client checks against the key
     * *it* was built with, so a client on an older version will keep demanding the old key: a
     * replacement has to be published while the old key still signs, or installations stop being
     * able to update at all and have to be reinstalled by hand.
     */
    private const val PUBLIC_KEY_BASE64 =
        "MCowBQYDK2VwAyEA4UQ+kv2b6R8SoLmPXQz3aAxHj7X2LJItWgbY/dV2/ak="

    /**
     * Whether [file] was signed by the key this client was built with.
     *
     * Everything that is not a clear yes is a no. A malformed signature, an unreadable key, a file
     * that changed by one byte - each returns false rather than throwing, because the caller's only
     * sensible response to any of them is the same: do not install this.
     */
    fun verify(file: File, signatureBase64: String): Boolean = runCatching {
        val publicKey = KeyFactory.getInstance("Ed25519")
            .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY_BASE64)))
        val signature = Signature.getInstance("Ed25519").apply {
            initVerify(publicKey)
            // Read whole rather than streamed: an installer is around a hundred megabytes, it has
            // already been held in memory once on the way to disk, and a partial read that quietly
            // verified a prefix would be worse than one that failed.
            update(file.readBytes())
        }
        signature.verify(Base64.getDecoder().decode(signatureBase64.trim()))
    }.getOrDefault(false)

    /**
     * Whether this build can check anything at all.
     *
     * A build whose key was never filled in must refuse every installer rather than skip the check.
     * Failing open here would mean an update path with no verification and no sign that anything
     * was missing.
     */
    val isConfigured: Boolean
        get() = PUBLIC_KEY_BASE64.isNotBlank() &&
            runCatching {
                KeyFactory.getInstance("Ed25519")
                    .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY_BASE64)))
            }.isSuccess
}
