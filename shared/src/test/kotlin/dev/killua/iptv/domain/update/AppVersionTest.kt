package dev.killua.iptv.domain.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppVersionTest {

    @Test
    fun `the trap this class exists for - ten is newer than nine`() {
        // As text, "1.0.10" sorts before "1.0.9". An updater that believed that would hide every
        // release from the tenth patch onward.
        assertThat(version("1.0.10")).isGreaterThan(version("1.0.9"))
        assertThat(version("1.10.0")).isGreaterThan(version("1.9.0"))
        assertThat(version("10.0.0")).isGreaterThan(version("9.0.0"))
    }

    @Test
    fun `a v prefix is a tag convention, not part of the version`() {
        assertThat(AppVersion.parse("v1.0.1")).isEqualTo(AppVersion.parse("1.0.1"))
        assertThat(AppVersion.parse("V1.0.1")).isEqualTo(AppVersion.parse("1.0.1"))
        assertThat(AppVersion.parse("  v1.0.1  ")).isEqualTo(AppVersion.parse("1.0.1"))
    }

    @Test
    fun `this project's own past parses`() {
        val alpha = AppVersion.parse("v0.2.0-alpha.39")
        assertThat(alpha).isEqualTo(AppVersion(0, 2, 0, "alpha.39"))
        assertThat(alpha.toString()).isEqualTo("0.2.0-alpha.39")
    }

    @Test
    fun `a pre-release is older than the release it leads to`() {
        // Semver's counter-intuitive rule, and the one that decides whether someone still on an
        // alpha is offered 1.0.1 or told they are current.
        assertThat(version("1.0.0")).isGreaterThan(version("1.0.0-alpha.3"))
        assertThat(version("1.0.1")).isGreaterThan(version("0.2.0-alpha.39"))
    }

    @Test
    fun `pre-release identifiers compare numerically where they are numbers`() {
        assertThat(version("0.2.0-alpha.40")).isGreaterThan(version("0.2.0-alpha.39"))
        // The same lexical trap, one level down.
        assertThat(version("0.2.0-alpha.10")).isGreaterThan(version("0.2.0-alpha.9"))
        // A shorter identifier list ranks lower when the shared prefix is equal.
        assertThat(version("1.0.0-alpha.1")).isGreaterThan(version("1.0.0-alpha"))
    }

    @Test
    fun `equal versions compare equal, whichever way round`() {
        assertThat(version("1.0.1").compareTo(version("1.0.1"))).isEqualTo(0)
        assertThat(version("1.0.1-beta.2").compareTo(version("1.0.1-beta.2"))).isEqualTo(0)
    }

    @Test
    fun `what is not a version answers null rather than something plausible`() {
        // Null is the answer the callers act on: it makes the check say nothing, which is the only
        // safe response when the alternative is offering a downgrade.
        assertThat(AppVersion.parse("")).isNull()
        assertThat(AppVersion.parse("   ")).isNull()
        assertThat(AppVersion.parse("latest")).isNull()
        assertThat(AppVersion.parse("1.0")).isNull()
        assertThat(AppVersion.parse("1.0.1.2")).isNull()
        assertThat(AppVersion.parse("1.0.x")).isNull()
        assertThat(AppVersion.parse("1..1")).isNull()
        // toIntOrNull would accept both of these on its own.
        assertThat(AppVersion.parse("1.0.+1")).isNull()
        assertThat(AppVersion.parse("1.0.-1")).isNull()
    }

    private fun version(raw: String): AppVersion = requireNotNull(AppVersion.parse(raw)) { raw }
}
