package dev.killua.iptv.domain.support

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for a file that is mostly constants, which sounds pointless until you ask what breaks.
 *
 * What breaks is an address that is not a wallet, or one damaged by copying, reaching a screen.
 * Money sent to it is gone - silently, with no bounce and no way to return it. So these are not
 * tests of the strings so much as tests of the rule that decides which strings a viewer may see.
 */
class DonationsTest {

    @Test
    fun `the addresses are exactly the ones the owner supplied`() {
        // Pinned on purpose, and the one place this project duplicates an address deliberately.
        // The whole argument for a single copy is that two copies drift apart unnoticed - noticing
        // is precisely this test's job, so a mistyped character in the production file fails here
        // rather than in someone's wallet.
        //
        // These are the second set. The first were replaced on 29 August 2026 when the owner moved
        // to fresh accounts; the old ones are still in this repository's history, which is why the
        // wallets they belong to should stay abandoned rather than reused.
        //
        // Verified the same day: the Bitcoin address passes its bech32 checksum, so it provably
        // has no typo; the Solana address decodes to exactly 32 bytes; the EVM address is 40 hex
        // characters and, being all lower case, carries no checksum to verify at all. All three
        // heads and tails match the owner's wallet screenshot.
        assertThat(Donations.coins.map { it.address }).containsExactly(
            "0xf7eb4632aae7a1cc875e0fdbf295cec8d800cbff",
            "2RfUQEPXqA5yAAAJMB4RBe7vTet3pHdLRNYTUoYHdGE7",
            "bc1qmnhw0pvsxq7jv5f09yxjqhqa0xp3898tklmv8e",
        ).inOrder()
    }

    @Test
    fun `the addresses have the shape their networks require`() {
        val evm = Donations.coins.single { it.ticker == "ETH" }.address
        // 0x and forty hex characters, no more and no less. A truncated paste is still 0x-shaped.
        assertThat(evm).matches("0x[0-9a-fA-F]{40}")

        val solana = Donations.coins.single { it.ticker == "SOL" }.address
        // Base58: no zero, no capital O, no capital I, no lowercase l - the characters it drops
        // are the ones people misread, which is the entire point of the alphabet.
        assertThat(solana).matches("[1-9A-HJ-NP-Za-km-z]{32,44}")

        val bitcoin = Donations.coins.single { it.ticker == "BTC" }.address
        // Native SegWit, and bech32's own alphabet, which drops 1, b, i and o for the same reason
        // base58 drops its lookalikes. Lower case throughout: bech32 allows either, but never both
        // in one address, and mixing them is what a careless edit produces.
        assertThat(bitcoin).matches("bc1[qpzry9x8gf2tvdw0s3jn54khce6mua7l]{39}")
    }

    @Test
    fun `every offered coin says which networks reach it`() {
        // Sending on a chain the recipient cannot reach is the ordinary way crypto is lost, and an
        // address with no network beside it invites exactly that.
        assertThat(Donations.coins.all { !it.note.isNullOrBlank() }).isTrue()
        assertThat(Donations.hasCoins).isTrue()
    }

    @Test
    fun `there is one EVM address rather than one per chain`() {
        // Polygon, Base and Ethereum are the same account. A second entry would be the same string
        // written twice, and the two copies would eventually stop matching.
        val evm = Donations.coins.filter { it.address.startsWith("0x") }
        assertThat(evm).hasSize(1)
        assertThat(evm.single().note).contains("Polygon")
    }

    @Test
    fun `a token contract is refused, however it is capitalised`() {
        // The case this rule exists for. The owner supplied the USDT contract on Polygon as their
        // USDT address on 29 August 2026 - which is what an explorer shows for *the token*, not
        // for *an account*. Anything sent there is absorbed by the contract permanently.
        val usdtOnPolygon = "0xc2132D05D31c914a87C6611C10748AEb04B58e8F"
        assertThat(CryptoAddress("Tether", "USDT", usdtOnPolygon).isUsable).isFalse()
        assertThat(CryptoAddress("Tether", "USDT", usdtOnPolygon.lowercase()).isUsable).isFalse()

        // And none of the real ones is a contract, which is the assertion that protects the app.
        assertThat(Donations.coins.none { it.address.lowercase() in CryptoAddress.KNOWN_TOKEN_CONTRACTS })
            .isTrue()
    }

    @Test
    fun `a placeholder is never offered to a client`() {
        assertThat(Donations.coins.map { it.address }).doesNotContain(CryptoAddress.PLACEHOLDER)
        assertThat(Donations.coins.all { it.isUsable }).isTrue()
    }

    @Test
    fun `an address damaged by copying is refused rather than shown`() {
        val trailingNewline = CryptoAddress("Bitcoin", "BTC", "bc1qexampleaddress\n")
        val brokenInTwo = CryptoAddress("Bitcoin", "BTC", "bc1qexample\naddress")
        val padded = CryptoAddress("Bitcoin", "BTC", " bc1qexampleaddress ")

        assertThat(trailingNewline.isUsable).isFalse()
        assertThat(brokenInTwo.isUsable).isFalse()
        assertThat(padded.isUsable).isFalse()
    }

    @Test
    fun `blank is refused, and so is a placeholder that was only half replaced`() {
        assertThat(CryptoAddress("Bitcoin", "BTC", "").isUsable).isFalse()
        assertThat(CryptoAddress("Bitcoin", "BTC", "   ").isUsable).isFalse()
        assertThat(CryptoAddress("Bitcoin", "BTC", CryptoAddress.PLACEHOLDER).isUsable).isFalse()
    }

    @Test
    fun `an ordinary address passes`() {
        // Documented dummy, not a real wallet. Nothing in this repository ever names one.
        assertThat(CryptoAddress("Monero", "XMR", "4Aexample000address").isUsable).isTrue()
    }

    @Test
    fun `the ko-fi address is https and points at this project's page`() {
        // A support link is a link this app tells someone to trust. Cleartext would be a link
        // anyone on the network could rewrite on the way to a page asking for money.
        assertThat(Donations.KO_FI_URL).startsWith("https://")
        assertThat(Donations.KO_FI_URL).isEqualTo("https://ko-fi.com/mynameiskillua")
        // The label is what a button says, so it must not drift from where the button goes.
        assertThat(Donations.KO_FI_URL).endsWith(Donations.KO_FI_LABEL.substringAfter("ko-fi.com"))
    }
}
