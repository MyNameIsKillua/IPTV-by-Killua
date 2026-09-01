package dev.killua.iptv.desktop

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

/**
 * What the desktop client will and will not install.
 *
 * The private half of the pinned key is the maintainer's and is not in this repository, so no test
 * here can produce a signature that passes. That is the right shape for these tests anyway: what
 * has to be proved is that everything *else* fails, because the failure cases are the ones an
 * attacker gets to choose.
 */
class UpdateSignatureTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `this build carries a usable key, so it is able to refuse`() {
        // If this ever fails, the client would refuse every update - which is safe, and completely
        // broken. It is the one property of the pinned key that can be checked without the private
        // half.
        assertThat(UpdateSignature.isConfigured).isTrue()
    }

    @Test
    fun `a signature made with some other key is refused`() {
        // The attack this exists for: a validly signed installer that is not ours. On Windows
        // nothing in the operating system rejects that, so this check is the only thing that does.
        val installer = fileContaining("pretend this is an installer")
        val stranger = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val signature = Signature.getInstance("Ed25519").apply {
            initSign(stranger.private)
            update(installer.readBytes())
        }.sign()

        assertThat(UpdateSignature.verify(installer, Base64.getEncoder().encodeToString(signature)))
            .isFalse()
    }

    @Test
    fun `nonsense in place of a signature is refused rather than thrown`() {
        // Every one of these is something a malformed or hostile response could contain, and the
        // caller's only sensible answer to all of them is the same one: do not install this.
        val installer = fileContaining("pretend this is an installer")

        assertThat(UpdateSignature.verify(installer, "")).isFalse()
        assertThat(UpdateSignature.verify(installer, "not base64 at all !!")).isFalse()
        assertThat(UpdateSignature.verify(installer, "YWJj")).isFalse()
        assertThat(UpdateSignature.verify(installer, "A".repeat(10_000))).isFalse()
    }

    @Test
    fun `a file that is not there is refused rather than thrown`() {
        val missing = File(folder.root, "never-written.msi")

        assertThat(UpdateSignature.verify(missing, "YWJj")).isFalse()
    }

    private fun fileContaining(text: String): File =
        folder.newFile().apply { writeText(text) }
}
