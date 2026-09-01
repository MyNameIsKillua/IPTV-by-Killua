package dev.killua.iptv.domain.diagnostics

import dev.killua.iptv.domain.model.AccountStatus
import dev.killua.iptv.domain.model.FailureKind
import dev.killua.iptv.domain.model.LibrarySource

/** Which program produced a report, since the two behave differently enough to matter. */
enum class DiagnosticsClient(val label: String) {
    Android("Android"),
    Windows("Windows"),
}

/** How much is in the library. The single most useful number for this app's class of bug. */
data class LibrarySize(val channels: Int, val movies: Int, val series: Int)

/**
 * What this installation can safely say about itself when something goes wrong.
 *
 * Someone reporting a problem with a sideloaded app has nothing to offer but "it does not work".
 * There is no crash reporter here and there is not going to be one, so the alternative is to let
 * the app state its own facts and let the viewer decide whether to pass them on.
 *
 * **Every field is a type rather than a message, and that is the whole design.** The failure is an
 * enum, not an exception; the account is a kind and a status, not an address; the library is three
 * integers. There is no free-text field anywhere, so there is no path by which a server URL, a user
 * name or a raw response body can arrive in a report - not because something filters them out
 * afterwards, but because nothing can put them in.
 *
 * What is deliberately absent, having been considered:
 *
 * - **The account.** No server, no user name, no password, no account URL, and not the one-way
 *   fingerprint either - it identifies nothing useful in a bug report and is still an identifier.
 * - **Exception messages.** They routinely carry the authenticated URL that failed. The enum says
 *   what kind of thing went wrong, which is what anyone reading a report actually needs.
 * - **Titles.** What is in somebody's library is their business, and the counts answer the
 *   questions that scale bugs raise.
 *
 * Nothing here is ever sent anywhere. It is rendered on screen, and copying it is an action the
 * viewer takes.
 */
data class Diagnostics(
    val client: DiagnosticsClient,
    /** As the app knows itself, e.g. `1.0.4 (build 49)`. */
    val appVersion: String,
    /** The operating system, e.g. `Android 14 (SDK 34)` or `Windows 11`. */
    val platform: String,
    /** The hardware, where the platform names it. Null on the desktop, which does not. */
    val device: String? = null,
    /** Whether the app decided it is running on a television. */
    val television: Boolean = false,
    /** Xtream or a playlist, or null when nobody is signed in. */
    val accountKind: LibrarySource? = null,
    val accountStatus: AccountStatus? = null,
    val library: LibrarySize? = null,
    /** The kind of the last failure the app converted, never its message. */
    val lastFailure: FailureKind? = null,
    val updateCheckEnabled: Boolean = true,
    /** The Room schema in use. Android only; the desktop has no database. */
    val databaseVersion: Int? = null,
    /** Whether libvlc was found. Desktop only; the phone's player is part of the app. */
    val playerAvailable: Boolean? = null,
) {

    /**
     * The report as someone would paste it into an issue.
     *
     * Aligned rather than pretty-printed, because it is read by a person in a text box rather than
     * parsed. Absent facts are left out entirely instead of appearing as `null`, which reads as
     * something having failed rather than as something not applying.
     */
    fun render(): String {
        val rows = buildList {
            add("Client" to client.label)
            add("Version" to appVersion)
            add("Platform" to platform)
            device?.let { add("Device" to it) }
            if (television) add("Television" to "yes")
            accountKind?.let { kind ->
                add("Account" to listOfNotNull(kind.name, accountStatus?.name).joinToString(", "))
            }
            library?.let {
                add("Library" to "${it.channels} channels, ${it.movies} films, ${it.series} series")
            }
            databaseVersion?.let { add("Database" to "schema $it") }
            playerAvailable?.let { add("Player" to if (it) "libvlc found" else "libvlc missing") }
            add("Update check" to if (updateCheckEnabled) "on" else "off")
            lastFailure?.let { add("Last problem" to it.name) }
        }
        val width = rows.maxOf { it.first.length }
        return buildString {
            appendLine(TITLE)
            rows.forEach { (label, value) ->
                appendLine(label.padEnd(width + 2) + redacted(value))
            }
        }.trimEnd()
    }

    private companion object {
        const val TITLE = "Killua IPTV diagnostics"

        /**
         * The belt to the design's braces.
         *
         * The typed fields make a credential impossible to express, so this should never fire. It
         * exists because *should never* is a claim, and a value that looks like an address or an
         * account is worth losing rather than printing - a future field added carelessly is caught
         * here rather than in somebody's public issue.
         */
        fun redacted(value: String): String = when {
            value.contains("://") -> "(withheld)"
            value.contains('@') -> "(withheld)"
            else -> value
        }
    }
}
