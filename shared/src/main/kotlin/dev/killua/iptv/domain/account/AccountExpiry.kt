package dev.killua.iptv.domain.account

/**
 * Whether an account's expiry is worth saying something about, and what.
 *
 * The expiry is the one piece of account state with a deadline attached: everything else a provider
 * reports is a fact about now, while this one becomes a problem on a date. Showing it only on a
 * settings screen means it is read once, when the account is new and the date is far away.
 *
 * Nothing here can renew anything, so the answer is a sentence rather than an action — which is
 * exactly why it must not be shouted. Inside a week is close enough to matter; beyond that, a viewer
 * who wants the date can go and look at it.
 */
sealed interface ExpiryWarning {
    /** The date has passed. Whether playback still works is the provider's business, not ours. */
    data object Expired : ExpiryWarning

    /** [days] is rounded **down**, so "in 1 day" never means "in a few hours". */
    data class Soon(val days: Int) : ExpiryWarning
}

/** Days from which a warning is worth the space it takes. */
const val EXPIRY_WARNING_DAYS = 7

fun expiryWarningFor(
    expiresAtEpochSeconds: Long?,
    nowEpochSeconds: Long,
): ExpiryWarning? {
    val expiry = expiresAtEpochSeconds ?: return null
    // A provider that reports no expiry, or reports zero for "never", is saying there is no deadline.
    if (expiry <= 0L) return null
    val remaining = expiry - nowEpochSeconds
    if (remaining <= 0L) return ExpiryWarning.Expired
    val days = (remaining / SECONDS_PER_DAY).toInt()
    return if (days < EXPIRY_WARNING_DAYS) ExpiryWarning.Soon(days) else null
}

private const val SECONDS_PER_DAY = 24L * 60L * 60L
