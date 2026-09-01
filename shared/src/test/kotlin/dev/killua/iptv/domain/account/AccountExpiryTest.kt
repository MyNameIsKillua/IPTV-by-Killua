package dev.killua.iptv.domain.account

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AccountExpiryTest {

    private val now = 1_700_000_000L
    private val day = 24L * 60L * 60L

    @Test
    fun `an account with weeks left says nothing`() {
        assertThat(expiryWarningFor(now + 30 * day, now)).isNull()
        assertThat(expiryWarningFor(now + EXPIRY_WARNING_DAYS * day, now)).isNull()
    }

    @Test
    fun `inside a week is worth saying`() {
        assertThat(expiryWarningFor(now + 6 * day, now)).isEqualTo(ExpiryWarning.Soon(6))
        assertThat(expiryWarningFor(now + 2 * day, now)).isEqualTo(ExpiryWarning.Soon(2))
    }

    @Test
    fun `days are rounded down`() {
        // "In 1 day" must never mean "in a few hours": someone reading it should be able to trust
        // that they have at least what it says.
        assertThat(expiryWarningFor(now + day + 23 * 60 * 60, now)).isEqualTo(ExpiryWarning.Soon(1))
        assertThat(expiryWarningFor(now + 23 * 60 * 60, now)).isEqualTo(ExpiryWarning.Soon(0))
    }

    @Test
    fun `a date that has passed is expired`() {
        assertThat(expiryWarningFor(now - day, now)).isEqualTo(ExpiryWarning.Expired)
        assertThat(expiryWarningFor(now, now)).isEqualTo(ExpiryWarning.Expired)
    }

    @Test
    fun `no deadline is no warning`() {
        // A provider that reports nothing, or reports zero for "never", is saying there is no date.
        assertThat(expiryWarningFor(null, now)).isNull()
        assertThat(expiryWarningFor(0L, now)).isNull()
        assertThat(expiryWarningFor(-1L, now)).isNull()
    }
}
