package dev.killua.iptv.core.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StreamUrlPolicyTest {
    @Test
    fun `a public https address is allowed and reported as encrypted`() {
        val allowed = assertAllowed("https://stream.example/live/bbc.m3u8")

        assertThat(allowed.url).isEqualTo("https://stream.example/live/bbc.m3u8")
        assertThat(allowed.isCleartext).isFalse()
    }

    @Test
    fun `a public http address is allowed and reported as cleartext rather than refused`() {
        val allowed = assertAllowed("http://stream.example:8080/live/one.ts")

        assertThat(allowed.isCleartext).isTrue()
    }

    @Test
    fun `the address is stored as it was canonicalised, not as it was written`() {
        val allowed = assertAllowed("HTTPS://Stream.Example:443/live/../live/bbc.m3u8")

        assertThat(allowed.url).isEqualTo("https://stream.example/live/bbc.m3u8")
    }

    @Test
    fun `loopback is refused by name and by every spelling of its address`() {
        assertRefused("http://localhost:8080/stream.ts", StreamUrlRefusal.LoopbackAddress)
        assertRefused("http://app.localhost/stream.ts", StreamUrlRefusal.LoopbackAddress)
        assertRefused("http://127.0.0.1/stream.ts", StreamUrlRefusal.LoopbackAddress)
        assertRefused("http://127.9.9.9/stream.ts", StreamUrlRefusal.LoopbackAddress)
        assertRefused("http://[::1]/stream.ts", StreamUrlRefusal.LoopbackAddress)
        assertRefused("http://[::ffff:127.0.0.1]/stream.ts", StreamUrlRefusal.LoopbackAddress)
    }

    @Test
    fun `the private ranges a playlist could use to reach the viewer's own network are refused`() {
        assertRefused("http://192.168.1.1/stream.ts", StreamUrlRefusal.PrivateAddress)
        assertRefused("http://10.0.0.5/stream.ts", StreamUrlRefusal.PrivateAddress)
        assertRefused("http://172.16.0.1/stream.ts", StreamUrlRefusal.PrivateAddress)
        assertRefused("http://172.31.255.254/stream.ts", StreamUrlRefusal.PrivateAddress)
        assertRefused("http://100.64.0.1/stream.ts", StreamUrlRefusal.PrivateAddress)
        assertRefused("http://0.0.0.0/stream.ts", StreamUrlRefusal.PrivateAddress)
        assertRefused("http://printer.local/stream.ts", StreamUrlRefusal.PrivateAddress)
        assertRefused("http://[fe80::1]/stream.ts", StreamUrlRefusal.PrivateAddress)
        assertRefused("http://[fd00::1]/stream.ts", StreamUrlRefusal.PrivateAddress)
    }

    @Test
    fun `the cloud metadata address is refused, being link-local`() {
        assertRefused("http://169.254.169.254/latest/meta-data/", StreamUrlRefusal.PrivateAddress)
    }

    @Test
    fun `neighbours of the private ranges stay allowed, so the check is a range and not a prefix`() {
        assertAllowed("http://172.15.0.1/stream.ts")
        assertAllowed("http://172.32.0.1/stream.ts")
        assertAllowed("http://192.167.1.1/stream.ts")
        assertAllowed("http://100.63.0.1/stream.ts")
        assertAllowed("http://128.0.0.1/stream.ts")
        assertAllowed("http://11.0.0.1/stream.ts")
    }

    @Test
    fun `a host that merely reads like a private address is treated as a name, not an address`() {
        // Four labels, but not four numbers: it resolves like any other name, and refusing it
        // would refuse a legitimate host for looking wrong.
        assertAllowed("https://10.0.0.1.example/stream.ts")
        assertAllowed("https://192.168.1.1.nip.example/stream.ts")
    }

    @Test
    fun `schemes that are not http reach something this program does not speak`() {
        assertRefused("file:///C:/Windows/win.ini", StreamUrlRefusal.UnsupportedScheme)
        assertRefused("rtsp://stream.example/live", StreamUrlRefusal.UnsupportedScheme)
        assertRefused("udp://239.0.0.1:1234", StreamUrlRefusal.UnsupportedScheme)
        assertRefused("javascript:alert(1)", StreamUrlRefusal.UnsupportedScheme)
        assertRefused("stream.example/live/one.ts", StreamUrlRefusal.UnsupportedScheme)
    }

    @Test
    fun `credentials inside the address are refused rather than carried`() {
        assertRefused("https://user:secret@stream.example/one.ts", StreamUrlRefusal.UserInfoNotAllowed)
    }

    @Test
    fun `empty, overlong and control-bearing input is refused before it is parsed`() {
        assertRefused("", StreamUrlRefusal.Empty)
        assertRefused("   ", StreamUrlRefusal.Empty)
        assertRefused("https://stream.example/" + "a".repeat(9_000), StreamUrlRefusal.TooLong)
        assertRefused("https://stream.example/one\u0000.ts", StreamUrlRefusal.ControlCharacter)
        assertRefused("https://stream.example/one\n.ts", StreamUrlRefusal.ControlCharacter)
    }

    @Test
    fun `surrounding whitespace and a byte order mark do not decide the verdict`() {
        val allowed = assertAllowed("\uFEFF  https://stream.example/one.ts  ")

        assertThat(allowed.url).isEqualTo("https://stream.example/one.ts")
    }

    private fun assertAllowed(raw: String): StreamUrlVerdict.Allowed {
        val verdict = StreamUrlPolicy.check(raw)
        assertThat(verdict).isInstanceOf(StreamUrlVerdict.Allowed::class.java)
        return verdict as StreamUrlVerdict.Allowed
    }

    private fun assertRefused(raw: String, expected: StreamUrlRefusal) {
        val verdict = StreamUrlPolicy.check(raw)
        assertThat(verdict).isInstanceOf(StreamUrlVerdict.Refused::class.java)
        assertThat((verdict as StreamUrlVerdict.Refused).reason).isEqualTo(expected)
    }
}
