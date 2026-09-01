package dev.killua.iptv.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import dev.killua.iptv.domain.update.ReleaseAsset
import dev.killua.iptv.domain.update.ReleaseFeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Downloads a release package and asks Android to install it.
 *
 * **The strongest protection here is not in this file.** Android refuses to install an update whose
 * certificate differs from the installed app's, so even a download that was substituted somewhere
 * cannot replace Killua IPTV with something else - it can only fail. What this class adds on top is
 * refusing to fetch from anywhere but this project's own release storage, over TLS, within a size
 * it expects.
 *
 * Nothing is verified against a checksum, and that is a deliberate omission rather than an
 * oversight: the digest would have to come from the same response as the file, so it would prove
 * only that the response agrees with itself. The signature check is the one that cannot be forged.
 */
class UpdateInstaller(
    private val context: Context,
    private val client: OkHttpClient,
) {

    sealed interface Result {
        /** The system installer is on screen. Whether the viewer confirms is theirs to decide. */
        data object Handed : Result

        /**
         * Android will not let this app install packages yet. [intent] opens the one screen where
         * that is granted - it cannot be granted from inside the app.
         */
        data class PermissionNeeded(val intent: Intent) : Result

        /** Nothing was installed. [reason] is safe to show; it names no URL. */
        data class Failed(val reason: String) : Result
    }

    suspend fun install(
        asset: ReleaseAsset,
        onProgress: (Float) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        // Checked again here, and not only where the feed was parsed. This is the last point before
        // bytes are fetched and handed to the package installer, so it is the one that has to hold
        // even if some future caller assembles an asset another way.
        if (!ReleaseFeed.isTrustedDownload(asset.downloadUrl)) {
            return@withContext Result.Failed("That download did not come from the project's releases.")
        }
        if (asset.sizeBytes !in 1..MAX_PACKAGE_BYTES) {
            return@withContext Result.Failed("That download is not the size an update should be.")
        }
        if (!canInstallPackages()) {
            return@withContext Result.PermissionNeeded(unknownSourcesIntent())
        }

        val target = runCatching { download(asset, onProgress) }.getOrNull()
            ?: return@withContext Result.Failed("The download did not finish.")

        runCatching { hand(target) }
            .fold(onSuccess = { Result.Handed }, onFailure = {
                Result.Failed("This device would not open the installer.")
            })
    }

    private suspend fun download(asset: ReleaseAsset, onProgress: (Float) -> Unit): File {
        val directory = File(context.cacheDir, "updates").apply {
            // Cleared rather than added to, so a half-written file from a cancelled attempt can
            // never be the one handed to the installer.
            deleteRecursively()
            mkdirs()
        }
        val target = File(directory, "update.apk")
        val request = Request.Builder()
            .url(asset.downloadUrl)
            .header("User-Agent", ReleaseFeed.USER_AGENT)
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body
            if (!response.isSuccessful || body == null) error("unsuccessful")
            target.outputStream().use { sink ->
                body.byteStream().use { source ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        // A download is long enough that leaving the screen must actually stop it.
                        coroutineContext.ensureActive()
                        val read = source.read(buffer)
                        if (read <= 0) break
                        written += read
                        if (written > MAX_PACKAGE_BYTES) error("longer than it claimed")
                        sink.write(buffer, 0, read)
                        onProgress((written.toFloat() / asset.sizeBytes).coerceIn(0f, 1f))
                    }
                    // A truncated APK would be rejected by the installer with a confusing message,
                    // so it is caught here where the reason can be said plainly.
                    if (written != asset.sizeBytes) error("shorter than it claimed")
                }
            }
        }
        return target
    }

    private fun hand(file: File) {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * Whether this app may install packages at all.
     *
     * The manifest permission only makes the request possible; the viewer has to allow this
     * specific app in system settings, and there is no in-app way to ask for it.
     *
     * No version guard, deliberately. `canRequestPackageInstalls` arrived in Android 8, which is
     * this project's minimum - a check for anything older would be a branch that cannot run, and
     * lint says so.
     */
    private fun canInstallPackages(): Boolean =
        context.packageManager.canRequestPackageInstalls()

    private fun unknownSourcesIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private companion object {
        /** Generous for a package this size - the 1.0.1 APK is under 4 MB - and still bounded. */
        const val MAX_PACKAGE_BYTES = 100L * 1024L * 1024L
    }
}
