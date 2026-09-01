package dev.killua.iptv.desktop

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProviderRefusedTest {

    @Test
    fun `a refusal names the account rather than the network`() {
        // The failure a viewer meets when their subscription lapses must not read as a fault in
        // their connection, or they will spend the evening restarting a router.
        assertThat(providerRefusedMessage(403)).contains("provider refused this account")
        assertThat(providerRefusedMessage(401)).contains("did not recognise this account")
    }

    @Test
    fun `it does not guess which refusal it was`() {
        // Expiry, a disabled account and too many connections all arrive as 403. Naming one of them
        // would send the viewer to the wrong place three times out of four.
        val message = providerRefusedMessage(403)
        assertThat(message).contains("may have expired")
        assertThat(message).contains("every connection may be in use")
    }

    @Test
    fun `it carries the code it was built with`() {
        assertThat(ProviderRefused(403).code).isEqualTo(403)
        assertThat(ProviderRefused(401).message).contains("401")
    }
}
