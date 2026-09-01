package dev.killua.iptv.domain.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * What the update check is allowed to believe.
 *
 * The response being parsed here decides what the app downloads and hands to the package installer,
 * so most of these tests are about refusal rather than about reading. A parser that repairs a bad
 * response is a parser that will one day install something nobody published.
 */
class ReleaseFeedTest {

    @Test
    fun `an ordinary release is read, with both its assets`() {
        val release = requireNotNull(ReleaseFeed.parse(body()))

        assertThat(release.tag).isEqualTo("v1.0.2")
        assertThat(release.version).isEqualTo(AppVersion(1, 0, 2))
        assertThat(release.androidPackage?.name).isEqualTo("Killua-IPTV-Android-1.0.2.apk")
        assertThat(release.windowsInstaller?.name).isEqualTo("Killua-IPTV-Windows-1.0.2.msi")
        assertThat(release.androidPackage?.sizeBytes).isEqualTo(3_986_432L)
    }

    @Test
    fun `the installer and its signature are told apart, not each other`() {
        val release = requireNotNull(ReleaseFeed.parse(body()))

        // `.msi.sig` does not end in `.msi`, so neither selector can pick up the other. Getting
        // this wrong would hand the signature file to msiexec, or verify the installer against
        // itself.
        assertThat(release.windowsInstaller?.name).isEqualTo("Killua-IPTV-Windows-1.0.2.msi")
        assertThat(release.windowsInstallerSignature?.name)
            .isEqualTo("Killua-IPTV-Windows-1.0.2.msi.sig")
    }

    @Test
    fun `a release with no signature offers none, and the desktop client refuses it`() {
        // The 1.0.1 release, published before signing existed, looks exactly like this. A client
        // that treated a missing signature as "no check needed" would install it unverified.
        val release = requireNotNull(ReleaseFeed.parse(body(signed = false)))

        assertThat(release.windowsInstaller).isNotNull()
        assertThat(release.windowsInstallerSignature).isNull()
    }

    @Test
    fun `an asset served from anywhere but this repository's releases is dropped`() {
        // The one that matters. If a response could name any host, the updater becomes a way to
        // hand someone an arbitrary file and ask Android to install it.
        val hostile = body(
            apkUrl = "https://evil.example/Killua-IPTV-Android-1.0.2.apk",
        )

        val release = requireNotNull(ReleaseFeed.parse(hostile))

        assertThat(release.androidPackage).isNull()
        // The legitimate one beside it survives, so the refusal is targeted rather than total.
        assertThat(release.windowsInstaller).isNotNull()
    }

    @Test
    fun `a lookalike host and a plain-text URL are both refused`() {
        assertThat(ReleaseFeed.isTrustedDownload("https://github.com.evil.example/x.apk")).isFalse()
        assertThat(ReleaseFeed.isTrustedDownload("http://github.com/MyNameIsKillua/IPTV-by-Killua/releases/download/v1/x.apk")).isFalse()
        assertThat(ReleaseFeed.isTrustedDownload("https://github.com/SomeoneElse/IPTV-by-Killua/releases/download/v1/x.apk")).isFalse()
        assertThat(ReleaseFeed.isTrustedDownload("https://github.com/MyNameIsKillua/IPTV-by-Killua/releases/download/v1.0.2/a.apk")).isTrue()
    }

    @Test
    fun `a draft and a pre-release are both refused`() {
        // The endpoint should never return either, but a default someone else owns is not a rule.
        assertThat(ReleaseFeed.parse(body(draft = true))).isNull()
        assertThat(ReleaseFeed.parse(body(prerelease = true))).isNull()
    }

    @Test
    fun `a tag that is not a version, and a body that is not a release, answer null`() {
        assertThat(ReleaseFeed.parse(body(tag = "latest"))).isNull()
        assertThat(ReleaseFeed.parse("")).isNull()
        assertThat(ReleaseFeed.parse("not json at all")).isNull()
        assertThat(ReleaseFeed.parse("""{"message":"Not Found"}""")).isNull()
    }

    @Test
    fun `a zero-length asset is dropped rather than offered as a download`() {
        assertThat(requireNotNull(ReleaseFeed.parse(body(apkSize = 0))).androidPackage).isNull()
    }

    @Test
    fun `something newer is offered, and both versions are carried to the prompt`() {
        val status = ReleaseFeed.statusFor("1.0.1", ReleaseFeed.parse(body()))

        assertThat(status).isInstanceOf(UpdateStatus.Available::class.java)
        val available = status as UpdateStatus.Available
        assertThat(available.installed.toString()).isEqualTo("1.0.1")
        assertThat(available.release.version.toString()).isEqualTo("1.0.2")
    }

    @Test
    fun `the same version is up to date, and an older one is never offered as an update`() {
        assertThat(ReleaseFeed.statusFor("1.0.2", ReleaseFeed.parse(body())))
            .isEqualTo(UpdateStatus.UpToDate)
        // A downgrade. Android would refuse to install it anyway, so a prompt offering it would
        // simply reappear forever.
        assertThat(ReleaseFeed.statusFor("1.5.0", ReleaseFeed.parse(body())))
            .isEqualTo(UpdateStatus.UpToDate)
    }

    @Test
    fun `not knowing is its own answer, never reported as being up to date`() {
        // No response at all - offline, or a request that failed.
        assertThat(ReleaseFeed.statusFor("1.0.1", null)).isEqualTo(UpdateStatus.Unknown)
        // A build whose own version string cannot be read. Reporting UpToDate would be a guess;
        // reporting Available would offer it every release forever.
        assertThat(ReleaseFeed.statusFor("weird-build", ReleaseFeed.parse(body())))
            .isEqualTo(UpdateStatus.Unknown)
    }

    @Test
    fun `someone still on an alpha is offered the stable release`() {
        val status = ReleaseFeed.statusFor("0.2.0-alpha.39", ReleaseFeed.parse(body()))

        assertThat(status).isInstanceOf(UpdateStatus.Available::class.java)
    }

    @Test
    fun `the feed points at the public repository and the agent says nothing about the device`() {
        assertThat(ReleaseFeed.LATEST_RELEASE_URL).startsWith("https://")
        assertThat(ReleaseFeed.LATEST_RELEASE_URL).contains("IPTV-by-Killua")
        // The private repository would need a token, and a token in a shipped app is public.
        assertThat(ReleaseFeed.LATEST_RELEASE_URL).doesNotContain("Killua-IPTV/")
        assertThat(ReleaseFeed.USER_AGENT).isEqualTo("KilluaIPTV")
    }

    private fun body(
        tag: String = "v1.0.2",
        draft: Boolean = false,
        prerelease: Boolean = false,
        apkUrl: String = "$DOWNLOAD/v1.0.2/Killua-IPTV-Android-1.0.2.apk",
        apkSize: Long = 3_986_432L,
        /** False reproduces a release from before signing existed, such as 1.0.1. */
        signed: Boolean = true,
    ): String {
        val signature = if (!signed) "" else """
            ,
            {
              "name": "Killua-IPTV-Windows-1.0.2.msi.sig",
              "browser_download_url": "$DOWNLOAD/v1.0.2/Killua-IPTV-Windows-1.0.2.msi.sig",
              "size": 89
            }
        """.trimIndent()
        return """
        {
          "tag_name": "$tag",
          "name": "1.0.2",
          "draft": $draft,
          "prerelease": $prerelease,
          "html_url": "https://github.com/MyNameIsKillua/IPTV-by-Killua/releases/tag/$tag",
          "published_at": "2026-08-30T10:00:00Z",
          "body": "Release notes go here.",
          "assets": [
            {
              "name": "Killua-IPTV-Android-1.0.2.apk",
              "browser_download_url": "$apkUrl",
              "size": $apkSize,
              "content_type": "application/vnd.android.package-archive"
            },
            {
              "name": "Killua-IPTV-Windows-1.0.2.msi",
              "browser_download_url": "$DOWNLOAD/v1.0.2/Killua-IPTV-Windows-1.0.2.msi",
              "size": 106074524
            }$signature
          ]
        }
        """.trimIndent()
    }

    private companion object {
        const val DOWNLOAD = "https://github.com/MyNameIsKillua/IPTV-by-Killua/releases/download"
    }
}
