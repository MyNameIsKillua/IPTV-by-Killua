package dev.killua.iptv.desktop

import dev.killua.iptv.domain.update.ReleaseAsset
import dev.killua.iptv.domain.update.ReleaseFeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Downloads the installer and hands it to Windows, then the client closes itself.
 *
 * The first version of this sent people to the release page, on the reasoning that an MSI cannot
 * overwrite a program that is running. The reasoning was right and the conclusion was not: the
 * program closes first, which is what every self-updating Windows application does. Nothing here
 * needs the viewer to download anything, and nothing needs the old version uninstalled - the MSI's
 * `upgradeUuid` is pinned, so Windows replaces the installation in place.
 *
 * **What the viewer still sees is one UAC prompt.** The installer writes its uninstall entry under
 * `HKLM`, which needs elevation; that is a property of the package, not of this code, and it does
 * not go away until the package becomes a real per-user one. What they no longer see is
 * SmartScreen's *"Windows protected your PC"* - that comes from the zone marker a browser attaches
 * to a downloaded file, and a file fetched here carries none.
 *
 * **The installer is checked against a key only the maintainer holds.** Windows itself never asks
 * whether an update comes from the same publisher as the program it replaces - Authenticode proves
 * a signature is valid, not that it is *ours*, and no certificate authority changes that. So the
 * client carries the public half of an Ed25519 key and refuses anything the private half did not
 * sign. See [UpdateSignature]. That is the same promise Android gets from its operating system,
 * kept here by this code instead.
 *
 * TLS to github.com and the strict download prefix still apply. They narrow who could serve the
 * file; the signature decides whether the maintainer actually made it.
 */
class DesktopUpdateInstaller(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.MINUTES)
        .followRedirects(true)
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .build(),
) {

    sealed interface Result {
        /** Windows has the installer. The caller must now close the app so it can replace it. */
        data object Started : Result

        /** Nothing was installed. [reason] is safe to show and names no URL. */
        data class Failed(val reason: String) : Result
    }

    suspend fun install(
        asset: ReleaseAsset,
        /** The detached signature published beside it. Absent means nothing is installed. */
        signatureAsset: ReleaseAsset?,
        onProgress: (Float) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        // A build with no key checks nothing, so it installs nothing. Failing open here would be an
        // update path with no verification and no sign that any was missing.
        if (!UpdateSignature.isConfigured) {
            return@withContext Result.Failed("This build cannot verify an update, so it will not install one.")
        }
        // Checked again at the last point before bytes are fetched, not only where the feed was
        // read, so a future caller that assembles an asset some other way is still covered.
        if (!ReleaseFeed.isTrustedDownload(asset.downloadUrl)) {
            return@withContext Result.Failed("That download did not come from the project's releases.")
        }
        if (asset.sizeBytes !in 1..MAX_INSTALLER_BYTES) {
            return@withContext Result.Failed("That download is not the size an installer should be.")
        }
        if (signatureAsset == null || !ReleaseFeed.isTrustedDownload(signatureAsset.downloadUrl)) {
            return@withContext Result.Failed("That release published no signature, so it will not be installed.")
        }

        val expected = runCatching { fetchText(signatureAsset) }.getOrNull()
            ?: return@withContext Result.Failed("The signature could not be read.")

        val file = runCatching { download(asset, onProgress) }.getOrNull()
            ?: return@withContext Result.Failed("The download did not finish.")

        // The gate. Everything above narrows who could have served this file; this decides whether
        // the maintainer actually signed it, which is the question Windows never asks on its own.
        if (!UpdateSignature.verify(file, expected)) {
            // Deleted rather than left lying about, so no later step can pick it up by accident.
            file.delete()
            return@withContext Result.Failed(
                "That installer is not signed by this project. Nothing was installed.",
            )
        }

        runCatching { launch(file) }
            .fold(
                onSuccess = { Result.Started },
                onFailure = { Result.Failed("Windows would not start the installer.") },
            )
    }

    /** Reads a small text asset - the signature - straight into memory, with a hard cap. */
    private fun fetchText(asset: ReleaseAsset): String? {
        if (asset.sizeBytes !in 1..MAX_SIGNATURE_BYTES) return null
        val request = Request.Builder()
            .url(asset.downloadUrl)
            .header("User-Agent", ReleaseFeed.USER_AGENT)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val source = response.body?.source() ?: return null
            source.request(MAX_SIGNATURE_BYTES + 1)
            if (source.buffer.size > MAX_SIGNATURE_BYTES) return null
            return source.buffer.snapshot().utf8()
        }
    }

    private suspend fun download(asset: ReleaseAsset, onProgress: (Float) -> Unit): File {
        val directory = File(System.getProperty("java.io.tmpdir"), "KilluaIPTV-update").apply {
            // Cleared rather than added to, so a half-written file from a cancelled attempt can
            // never be the one Windows is asked to run.
            deleteRecursively()
            mkdirs()
        }
        val target = File(directory, "Killua-IPTV-update.msi")
        val request = Request.Builder()
            .url(asset.downloadUrl)
            .header("User-Agent", ReleaseFeed.USER_AGENT)
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body
            if (!response.isSuccessful || body == null) error("unsuccessful")
            target.outputStream().use { sink ->
                body.byteStream().use { source ->
                    val buffer = ByteArray(256 * 1024)
                    var written = 0L
                    while (true) {
                        // A hundred megabytes takes a while; closing the dialog must actually stop.
                        coroutineContext.ensureActive()
                        val read = source.read(buffer)
                        if (read <= 0) break
                        written += read
                        if (written > MAX_INSTALLER_BYTES) error("longer than it claimed")
                        sink.write(buffer, 0, read)
                        onProgress((written.toFloat() / asset.sizeBytes).coerceIn(0f, 1f))
                    }
                    // A truncated MSI fails inside the installer with a message about a corrupt
                    // package, which sends people looking in the wrong place. Caught here instead.
                    if (written != asset.sizeBytes) error("shorter than it claimed")
                }
            }
        }
        return target
    }

    /**
     * `msiexec` with a basic progress window, started detached so it survives this process exiting.
     *
     * `/qb` rather than the full interface: an update should not ask again where to install or
     * which components are wanted. The elevation prompt still comes from Windows, and declining it
     * simply leaves the installation as it was.
     */
    private fun launch(installer: File) {
        ProcessBuilder("msiexec", "/i", installer.absolutePath, "/qb")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }

    private companion object {
        /** Generous for a bundle of this shape - 1.0.1 is about 101 MB - and still bounded. */
        const val MAX_INSTALLER_BYTES = 400L * 1024L * 1024L

        /** A base64 Ed25519 signature is 88 characters. Anything of this size is not one. */
        const val MAX_SIGNATURE_BYTES = 4L * 1024L
    }
}
