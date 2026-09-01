package dev.killua.iptv.core.update

import dev.killua.iptv.core.preferences.AppPreferences
import dev.killua.iptv.domain.update.ReleaseFeed
import dev.killua.iptv.domain.update.UpdateStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Asks GitHub, once a day at most, whether a newer release exists.
 *
 * This is the one request this app makes that is not to the viewer's own provider, so it is worth
 * being exact about what it is. It is a `GET` of a public JSON document. It sends no account, no
 * identifier, no library fact and no device detail - the User-Agent is the fixed string GitHub
 * requires and nothing more. What it does reveal, unavoidably, is an IP address and the fact that
 * someone launched this app, which is why it can be switched off and why the prompt says so.
 *
 * It reads the **public** repository. A private one's API needs a token, and a token shipped inside
 * an app is a token anyone can pull back out of it.
 */
class UpdateChecker(
    private val client: OkHttpClient,
    private val preferences: AppPreferences,
    /**
     * What is installed, as `BuildConfig.VERSION_NAME`.
     *
     * A debug build reads `1.0.1-debug`, which parses as a pre-release and therefore sees the
     * matching stable release as newer. That is deliberate rather than tolerated: it is how the
     * prompt gets exercised before a release exists to exercise it with. Installing from there
     * fails, because the debug application ID is a different app, and the installer says so.
     */
    private val installedVersion: String,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    /**
     * The answer, or [UpdateStatus.Unknown] when it did not ask.
     *
     * Switched off and asked-recently both answer Unknown rather than UpToDate, because the caller
     * must never turn "I did not look" into "you are current". Only [UpdateStatus.Available] puts
     * anything on screen, so the three cases collapse to the same silence.
     */
    /**
     * @param force skips the once-a-day interval, for a viewer who asked rather than for a launch.
     *   It does **not** skip the switch: off means off, however the check was reached.
     */
    suspend fun check(force: Boolean = false): UpdateStatus = withContext(Dispatchers.IO) {
        if (!preferences.updateCheckEnabled.first()) return@withContext UpdateStatus.Unknown

        val now = nowMillis()
        val last = preferences.updateCheckedAtMillis.first()
        // `now - last` guards a clock that moved backwards too: a negative age is treated as due
        // rather than as a reason to wait until the clock catches up.
        if (!force && last in 1..now && now - last < CHECK_INTERVAL_MILLIS) {
            return@withContext UpdateStatus.Unknown
        }

        val body = runCatching { fetch() }.getOrNull()
        // Recorded even on failure. An unreachable GitHub must not turn every launch into another
        // attempt, which on a flaky connection is the difference between one request and twenty.
        preferences.setUpdateCheckedAtMillis(now)

        ReleaseFeed.statusFor(installedVersion, body?.let(ReleaseFeed::parse))
    }

    private fun fetch(): String? {
        val request = Request.Builder()
            .url(ReleaseFeed.LATEST_RELEASE_URL)
            .header("User-Agent", ReleaseFeed.USER_AGENT)
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            // Bounded, because this reads a remote document into memory. A release payload is a
            // few kilobytes; anything of this size is not one.
            val source = response.body?.source() ?: return null
            source.request(MAX_BODY_BYTES + 1)
            if (source.buffer.size > MAX_BODY_BYTES) return null
            return source.buffer.snapshot().utf8()
        }
    }

    private companion object {
        const val CHECK_INTERVAL_MILLIS = 24L * 60L * 60L * 1000L
        const val MAX_BODY_BYTES = 512L * 1024L
    }
}
