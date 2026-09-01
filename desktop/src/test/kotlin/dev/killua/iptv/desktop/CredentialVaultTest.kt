package dev.killua.iptv.desktop

import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The only file this client has ever written that holds an account.
 *
 * Everything tested here is about the two promises made on the sign-in screen: that nothing is
 * written unless the box is ticked, and that what is written cannot be read as a password.
 */
class CredentialVaultTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val details = StoredSignIn(
        server = "https://provider.example",
        username = "viewer",
        password = "hunter2-not-a-real-one",
    )

    @Test
    fun `what was stored comes back`() {
        val vault = CredentialVault(folder.root, ReversingCipher)

        assertThat(vault.save(details)).isTrue()

        assertThat(vault.load()).isEqualTo(details)
        assertThat(vault.hasStored).isTrue()
    }

    @Test
    fun `a playlist link is kept as a link rather than taken apart`() {
        // Someone who signs in with a link has never seen a username, and asking them for one
        // because that is how the other half of the form works would be the client's problem
        // becoming theirs.
        val vault = CredentialVault(folder.root, ReversingCipher)
        val link = StoredSignIn(link = "https://provider.example/get.php?username=a&password=b")

        vault.save(link)

        assertThat(vault.load()?.isLink).isTrue()
        assertThat(vault.load()?.link).isEqualTo(link.link)
    }

    @Test
    fun `nothing is written where nothing can seal it`() {
        // A Mac, today. Writing a password in the clear because this happens not to be Windows
        // would be worse than asking someone to type it.
        val vault = CredentialVault(folder.root, UnsealableCipher)

        assertThat(vault.save(details)).isFalse()

        assertThat(vault.isSupported).isFalse()
        assertThat(vault.hasStored).isFalse()
        assertThat(vault.load()).isNull()
    }

    @Test
    fun `half a form is not worth keeping`() {
        val vault = CredentialVault(folder.root, ReversingCipher)

        assertThat(vault.save(StoredSignIn(server = "https://provider.example"))).isFalse()
        assertThat(vault.hasStored).isFalse()
    }

    @Test
    fun `forgetting removes the file rather than emptying it`() {
        val vault = CredentialVault(folder.root, ReversingCipher)
        vault.save(details)

        vault.forget()

        assertThat(vault.hasStored).isFalse()
        assertThat(File(folder.root, "credentials.bin").exists()).isFalse()
    }

    @Test
    fun `a file that will never open again is removed rather than retried every launch`() {
        val vault = CredentialVault(folder.root, ReversingCipher)
        vault.save(details)
        File(folder.root, "credentials.bin").writeText("this was sealed for somebody else")

        assertThat(vault.load()).isNull()

        assertThat(vault.hasStored).isFalse()
    }

    @Test
    fun `the real thing seals against this Windows account`() {
        val vault = CredentialVault(folder.root, SecretCipher.forThisMachine())
        assumeTrue(vault.isSupported)

        assertThat(vault.save(details)).isTrue()
        assertThat(vault.load()).isEqualTo(details)

        // The point of the whole exercise: the password is not in the file, and neither is the
        // account it belongs to.
        val written = File(folder.root, "credentials.bin").readBytes().decodeToString()
        assertThat(written).doesNotContain("hunter2")
        assertThat(written).doesNotContain("viewer")
        assertThat(written).doesNotContain("provider.example")
    }

    /** Sealed, in the sense that it is not the plain text. Enough to test everything but Windows. */
    private object ReversingCipher : SecretCipher {
        override val isAvailable = true
        override fun protect(plain: ByteArray) = plain.reversedArray()
        override fun unprotect(sealed: ByteArray) = sealed.reversedArray()
    }

    private object UnsealableCipher : SecretCipher {
        override val isAvailable = false
        override fun protect(plain: ByteArray): ByteArray? = null
        override fun unprotect(sealed: ByteArray): ByteArray? = null
    }
}
