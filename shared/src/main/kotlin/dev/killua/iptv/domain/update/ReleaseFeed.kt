package dev.killua.iptv.domain.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One downloadable file attached to a release. */
data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
)

/** A published release, reduced to what an update prompt actually needs. */
data class ReleaseInfo(
    val version: AppVersion,
    val tag: String,
    /** The release page, which is where the desktop sends people and where a failed download ends. */
    val pageUrl: String,
    val assets: List<ReleaseAsset>,
) {
    /** The Android package, or null if this release has none. */
    val androidPackage: ReleaseAsset?
        get() = assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }

    /** The Windows installer, or null if this release has none. */
    val windowsInstaller: ReleaseAsset?
        get() = assets.firstOrNull { it.name.endsWith(".msi", ignoreCase = true) }

    /**
     * The detached signature over [windowsInstaller], or null if this release published none.
     *
     * Null is a refusal rather than a shrug: the desktop client will not run an installer it cannot
     * check, because Windows - unlike Android - has no rule of its own about who signed an update.
     *
     * `.msi.sig` does not end in `.msi`, so this and [windowsInstaller] cannot select each other.
     */
    val windowsInstallerSignature: ReleaseAsset?
        get() = assets.firstOrNull { it.name.endsWith(".msi.sig", ignoreCase = true) }
}

/** What the check concluded. Three answers, because "I do not know" is not "you are up to date". */
sealed interface UpdateStatus {
    /** Nothing newer, or the newest is what is already installed. */
    data object UpToDate : UpdateStatus

    /** Something newer exists. [installed] is shown beside [release] so the prompt can say both. */
    data class Available(val installed: AppVersion, val release: ReleaseInfo) : UpdateStatus

    /**
     * The answer could not be established - no network, a malformed response, an unparseable tag.
     * Deliberately distinct from [UpToDate]: a client must not tell someone they are current when
     * it simply failed to ask.
     */
    data object Unknown : UpdateStatus
}

/**
 * Reading GitHub's release feed, and deciding whether it describes an update.
 *
 * The clients do the HTTP themselves - `:app` through its Retrofit/OkHttp stack, `:desktop` through
 * its own - because this module has no HTTP client and is not getting one. What lives here is the
 * part that must be identical on both and is worth testing: which URL, what the response means, and
 * which downloads may be trusted.
 *
 * **It reads the public repository, not the private one.** A private repository's API needs a
 * token, and a token inside a shipped app is a token anyone can extract from it - so the choice is
 * between a public feed and no updater at all.
 */
object ReleaseFeed {

    /**
     * GitHub's own "latest" endpoint, which already excludes drafts and pre-releases.
     *
     * That is why the alpha tags in this project's past cannot reach anyone through this: they were
     * published as pre-releases, and this endpoint does not return them. The guard below is still
     * there, because relying on someone else's default without checking is how defaults change
     * underneath you.
     */
    const val LATEST_RELEASE_URL: String =
        "https://api.github.com/repos/MyNameIsKillua/IPTV-by-Killua/releases/latest"

    /**
     * A fixed User-Agent, which GitHub requires and which says nothing about the device.
     *
     * No version, no model, no locale. The request already reveals an IP address to GitHub and
     * there is no way around that; everything beyond it is a choice, and this is the smallest one.
     */
    const val USER_AGENT: String = "KilluaIPTV"

    /** Where a release asset must come from. Anything else is not downloaded. */
    private const val TRUSTED_DOWNLOAD_PREFIX =
        "https://github.com/MyNameIsKillua/IPTV-by-Killua/releases/download/"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Turns the response body into a release, or null if it is not one this app should act on.
     *
     * Everything here is a refusal rather than a repair. A draft, a pre-release, a tag that is not
     * a version, an asset served from somewhere other than this repository's own release storage -
     * each is dropped, because the alternative is an app that downloads and installs whatever a
     * malformed or substituted response told it to.
     */
    fun parse(body: String): ReleaseInfo? {
        val release = runCatching { json.decodeFromString<GithubRelease>(body) }.getOrNull()
            ?: return null
        if (release.draft || release.prerelease) return null
        val version = AppVersion.parse(release.tagName) ?: return null
        if (!release.htmlUrl.startsWith("https://github.com/")) return null
        val assets = release.assets
            .filter { it.browserDownloadUrl.startsWith(TRUSTED_DOWNLOAD_PREFIX) }
            .filter { it.size > 0 }
            .map { ReleaseAsset(it.name, it.browserDownloadUrl, it.size) }
        return ReleaseInfo(version, release.tagName, release.htmlUrl, assets)
    }

    /**
     * Whether [release] is worth telling someone about, given what they have installed.
     *
     * An unreadable installed version answers [UpdateStatus.Unknown] rather than assuming the
     * worst: a build whose own name this cannot parse is a build that would otherwise be offered
     * every release forever.
     */
    fun statusFor(installedVersionName: String, release: ReleaseInfo?): UpdateStatus {
        if (release == null) return UpdateStatus.Unknown
        val installed = AppVersion.parse(installedVersionName) ?: return UpdateStatus.Unknown
        // Strictly greater. Equal is current, and lower is a downgrade that must never be offered -
        // on Android it would not install anyway, and the prompt would simply never go away.
        return if (release.version > installed) {
            UpdateStatus.Available(installed, release)
        } else {
            UpdateStatus.UpToDate
        }
    }

    /** Whether a URL may be downloaded from. Exposed so the download path can check again. */
    fun isTrustedDownload(url: String): Boolean = url.startsWith(TRUSTED_DOWNLOAD_PREFIX)
}

@Serializable
private data class GithubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("html_url") val htmlUrl: String,
    val assets: List<GithubAsset> = emptyList(),
)

@Serializable
private data class GithubAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long = 0L,
)
