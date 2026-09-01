package dev.killua.iptv.desktop

import dev.killua.iptv.domain.update.ReleaseFeed
import dev.killua.iptv.domain.update.UpdateStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Asks GitHub, once a day at most, whether a newer release exists.
 *
 * The phone's counterpart downloads and installs; this one does not, and the difference is the
 * installer rather than an omission. An MSI cannot replace a running program from inside it - the
 * files it would overwrite are open - and the installer that could is unsigned, so Windows would
 * meet it with SmartScreen anyway. Sending someone to the release page is the honest version of
 * what this can actually do.
 *
 * The request itself is the same as the phone's: a public JSON document, a fixed User-Agent, and
 * nothing about the machine, the account or the library.
 */
class DesktopUpdateChecker(
    private val installedVersion: String,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        // A redirect out of HTTPS is refused, exactly as on the provider path.
        .followSslRedirects(false)
        .build()

    /**
     * The answer, plus the timestamp to remember.
     *
     * The timestamp comes back rather than being written here because this class owns no storage -
     * the caller holds [DesktopPreferences] and is the only thing that may write the file. Null
     * means nothing was asked, so nothing needs recording.
     */
    data class Outcome(val status: UpdateStatus, val checkedAtMillis: Long?)

    suspend fun check(preferences: DesktopPreferences): Outcome = withContext(Dispatchers.IO) {
        if (!preferences.updateCheckEnabled) return@withContext Outcome(UpdateStatus.Unknown, null)

        val now = nowMillis()
        val last = preferences.updateCheckedAtMillis
        // A clock that moved backwards reads as due rather than as a reason to wait for it.
        if (last in 1..now && now - last < CHECK_INTERVAL_MILLIS) {
            return@withContext Outcome(UpdateStatus.Unknown, null)
        }

        val body = runCatching { fetch() }.getOrNull()
        // Recorded even when the request failed, so an unreachable GitHub costs one attempt a day
        // rather than one per launch.
        Outcome(ReleaseFeed.statusFor(installedVersion, body?.let(ReleaseFeed::parse)), now)
    }

    private fun fetch(): String? {
        val request = Request.Builder()
            .url(ReleaseFeed.LATEST_RELEASE_URL)
            .header("User-Agent", ReleaseFeed.USER_AGENT)
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val source = response.body?.source() ?: return null
            source.request(MAX_BODY_BYTES + 1)
            if (source.buffer.size > MAX_BODY_BYTES) return null
            return source.buffer.snapshot().utf8()
        }
    }

    companion object {
        private const val CHECK_INTERVAL_MILLIS = 24L * 60L * 60L * 1000L
        private const val MAX_BODY_BYTES = 512L * 1024L

        /**
         * What is running, read from the resource the build generates from `appVersion`.
         *
         * Falling back to a string that cannot be parsed is deliberate: an installation that
         * cannot say what it is answers [UpdateStatus.Unknown] and stays quiet, rather than being
         * offered every release for ever.
         */
        fun installedVersion(): String =
            DesktopUpdateChecker::class.java.getResourceAsStream("/app-version.txt")
                ?.bufferedReader()
                ?.use { it.readText().trim() }
                ?.takeIf { it.isNotEmpty() }
                ?: "unknown"
    }
}
