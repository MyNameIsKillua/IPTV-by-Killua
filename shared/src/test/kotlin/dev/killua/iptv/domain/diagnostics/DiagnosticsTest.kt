package dev.killua.iptv.domain.diagnostics

import com.google.common.truth.Truth.assertThat
import dev.killua.iptv.domain.model.AccountStatus
import dev.killua.iptv.domain.model.FailureKind
import dev.killua.iptv.domain.model.LibrarySource
import org.junit.Test

/**
 * A report is something a viewer pastes into a public issue, so these tests are mostly about what
 * must not be in one.
 *
 * The design does the real work: every field is a type rather than a message, so an address or an
 * account cannot be expressed. What is tested here is that the design actually holds, and that the
 * guard behind it catches a field somebody adds carelessly later.
 */
class DiagnosticsTest {

    @Test
    fun `a full report reads as a person would want to paste it`() {
        val text = full().render()

        assertThat(text).startsWith("Killua IPTV diagnostics")
        assertThat(text).contains("Client")
        assertThat(text).contains("1.0.4 (build 49)")
        assertThat(text).contains("57198 channels, 179965 films, 47500 series")
        assertThat(text).contains("Xtream, Active")
        assertThat(text).contains("ServerUnavailable")
    }

    @Test
    fun `nothing that could be a credential survives, however a field was filled`() {
        // The guard behind the design. None of these can be produced by the real callers - the
        // fields they fill come from BuildConfig and the operating system - which is exactly why
        // it is worth proving that a careless one later would be caught rather than published.
        val hostile = full().copy(
            appVersion = "http://provider.example:8080/get.php?username=killua&password=hunter2",
            platform = "user@provider.example",
            device = "https://portal.example/live/1.ts",
        )

        val text = hostile.render()

        assertThat(text).doesNotContain("provider.example")
        assertThat(text).doesNotContain("password")
        assertThat(text).doesNotContain("killua")
        assertThat(text).doesNotContain("://")
        assertThat(text).doesNotContain("@")
    }

    @Test
    fun `a signed-out client reports what it has and omits the rest`() {
        // Absent facts are left out rather than printed as null, which would read as something
        // having gone wrong instead of something not applying.
        val text = Diagnostics(
            client = DiagnosticsClient.Windows,
            appVersion = "1.0.4",
            platform = "Windows 11",
        ).render()

        assertThat(text).doesNotContain("Account")
        assertThat(text).doesNotContain("Library")
        assertThat(text).doesNotContain("Last problem")
        assertThat(text).doesNotContain("null")
        // What it does have is still there.
        assertThat(text).contains("Windows 11")
        assertThat(text).contains("Update check")
    }

    @Test
    fun `the failure is a category, never a message`() {
        // FailureKind is an enum, so the report cannot carry the exception text that produced it -
        // and that text routinely contains the authenticated URL that failed.
        val text = full().copy(lastFailure = FailureKind.AuthenticationFailed).render()

        assertThat(text).contains("AuthenticationFailed")
        assertThat(FailureKind.entries.map { it.name }).doesNotContain("")
    }

    @Test
    fun `a television says so, and a phone does not mention it at all`() {
        assertThat(full().copy(television = true).render()).contains("Television")
        assertThat(full().copy(television = false).render()).doesNotContain("Television")
    }

    @Test
    fun `the desktop reports its player and the phone reports its schema`() {
        val desktop = full().copy(
            client = DiagnosticsClient.Windows,
            playerAvailable = false,
            databaseVersion = null,
        ).render()
        assertThat(desktop).contains("libvlc missing")
        assertThat(desktop).doesNotContain("schema")

        val phone = full().copy(databaseVersion = 10, playerAvailable = null).render()
        assertThat(phone).contains("schema 10")
        assertThat(phone).doesNotContain("libvlc")
    }

    private fun full() = Diagnostics(
        client = DiagnosticsClient.Android,
        appVersion = "1.0.4 (build 49)",
        platform = "Android 14 (SDK 34)",
        device = "SM-S918B",
        accountKind = LibrarySource.Xtream,
        accountStatus = AccountStatus.Active,
        library = LibrarySize(channels = 57198, movies = 179965, series = 47500),
        lastFailure = FailureKind.ServerUnavailable,
        updateCheckEnabled = true,
        databaseVersion = 10,
    )
}
