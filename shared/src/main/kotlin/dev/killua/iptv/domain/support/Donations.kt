package dev.killua.iptv.domain.support

/**
 * Where someone can support the project, named once so the two clients cannot disagree.
 *
 * This is here rather than in either client for a reason that has nothing to do with tidiness: a
 * wallet address is a string where one wrong character means money that goes somewhere else and
 * never comes back. Two copies of that string is two chances to get it wrong, and no way to notice.
 * There is one copy, and it is this one.
 *
 * Nothing here is a payment. Neither client can take money; both can only point at a page or hand
 * over an address, which is why this file holds strings and no code that does anything with them.
 */
object Donations {

    /** The Ko-fi page. Their site is the whole of the payment path; this app never sees a cent. */
    const val KO_FI_URL: String = "https://ko-fi.com/mynameiskillua"

    /** What to call it in a button, without the client having to know the host's name. */
    const val KO_FI_LABEL: String = "ko-fi.com/mynameiskillua"

    /**
     * The coins, in the order a client draws them.
     *
     * There is **one EVM address, not one per chain**, because that is how an EVM wallet works: the
     * same address receives on Ethereum, Base, Polygon, Arbitrum and the rest, and receives tokens
     * such as USDT alongside the native coin. Listing Polygon separately would repeat the same
     * string and invite the two copies to drift apart.
     *
     * These are **dedicated donation accounts**, created for this and used for nothing else. That
     * is not tidiness: publishing an address publishes everything that account will ever do, to
     * anyone, permanently - every transfer in and out, the balance, the counterparties, and the
     * timing. An address that also holds personal money publishes that history too. Replacing one
     * of these later does not undo it either, because the old one stays in this repository's
     * history; the account behind it should be abandoned rather than reused.
     */
    private val declared: List<CryptoAddress> = listOf(
        CryptoAddress(
            coin = "Ethereum",
            ticker = "ETH",
            address = "0xf7eb4632aae7a1cc875e0fdbf295cec8d800cbff",
            // The network matters more than the address here. Sending on a chain the recipient
            // cannot reach is the ordinary way crypto is lost, and it is lost quietly.
            note = "Ethereum, Base, Polygon and other EVM networks. ETH or tokens such as USDT.",
        ),
        CryptoAddress(
            coin = "Solana",
            ticker = "SOL",
            address = "2RfUQEPXqA5yAAAJMB4RBe7vTet3pHdLRNYTUoYHdGE7",
            note = "Solana network only.",
        ),
        CryptoAddress(
            coin = "Bitcoin",
            ticker = "BTC",
            address = "bc1qmnhw0pvsxq7jv5f09yxjqhqa0xp3898tklmv8e",
            note = "Bitcoin network only. Native SegWit, so a wallet too old for bech32 will refuse it.",
        ),
    )

    /**
     * The coins a client may actually show.
     *
     * An address that is not real yet, or is damaged, or is a token contract rather than a wallet,
     * eats the payment silently - there is no bounce, no error, and no way to give it back. So the
     * filter is not decoration: it is the difference between a section that is incomplete and a
     * section that is harmful. See [CryptoAddress.isUsable].
     */
    val coins: List<CryptoAddress> = declared.filter { it.isUsable }

    /** Whether there is a crypto section to draw at all. */
    val hasCoins: Boolean = coins.isNotEmpty()
}

/**
 * One coin and the address to send it to.
 *
 * @property coin the name a person recognises, e.g. `Bitcoin`
 * @property ticker the short form, e.g. `BTC`
 * @property address the receiving address, or [PLACEHOLDER] while there is not one yet
 * @property note which networks this address can be reached on, when that is not obvious
 */
data class CryptoAddress(
    val coin: String,
    val ticker: String,
    val address: String,
    val note: String? = null,
) {
    /**
     * Whether this is safe to put in front of someone.
     *
     * Blank and [PLACEHOLDER] are the obvious no. Whitespace is the less obvious one: an address
     * that arrives with a trailing newline or a line break through the middle is the usual damage
     * from copying one out of a wallet app or an email, and it stays invisible in a UI that
     * renders it.
     *
     * [KNOWN_TOKEN_CONTRACTS] is the one that was learned the expensive way. On 29 August 2026 the
     * owner supplied the USDT contract on Polygon as their "USDT address" - which is what a wallet
     * or an explorer shows when you look up *the token* rather than *your account*. Money sent to a
     * token contract is gone. That address was caught by reading it rather than by any rule, so
     * this is the rule.
     *
     * None of this can tell a correct address from a wrong one. Nothing outside the coin's own
     * network can, and the contract list holds a handful of famous ones rather than all of them.
     * It only refuses the shapes that are certainly not a wallet.
     */
    val isUsable: Boolean
        get() = address != PLACEHOLDER &&
            address.isNotBlank() &&
            address.none { it.isWhitespace() } &&
            address.lowercase() !in KNOWN_TOKEN_CONTRACTS

    companion object {
        /** The exact marker for "not filled in yet". Matched exactly, so it cannot be half-edited. */
        const val PLACEHOLDER: String = "TODO"

        /**
         * Token contracts that are routinely mistaken for a receiving address, lowercased.
         *
         * EVM addresses are case-insensitive - the mixed case in a checksummed address is a
         * checksum, not part of the address - so the comparison folds case rather than trusting
         * whichever form was pasted.
         */
        val KNOWN_TOKEN_CONTRACTS: Set<String> = setOf(
            "0xc2132d05d31c914a87c6611c10748aeb04b58e8f", // USDT, Polygon
            "0xdac17f958d2ee523a2206206994597c13d831ec7", // USDT, Ethereum
            "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", // USDC, Ethereum
            "0x2791bca1f2de4661ed88a30c99a7a9449aa84174", // USDC.e, Polygon
        )
    }
}
