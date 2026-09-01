package dev.killua.iptv.desktop

import dev.killua.iptv.domain.userdata.UserDataExport
import dev.killua.iptv.domain.userdata.UserDataExportCodec
import dev.killua.iptv.domain.userdata.UserDataImportResult
import dev.killua.iptv.domain.userdata.mergedWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI

/**
 * Where the desktop client keeps what the provider cannot give back.
 *
 * **It stores the export format, not a database of its own.** That decision buys three things at
 * once: no schema to design or migrate, reuse of a format already built and tested in `:shared`, and
 * interoperability for nothing — this client's own state file *is* an export, so it can be handed
 * straight to the phone's Import, and a file exported from the phone can be dropped in here.
 *
 * The cost is that saving rewrites the whole file. At a few thousand rows that is a couple of
 * hundred kilobytes, written at most every ten seconds, which is not worth a database to avoid.
 *
 * As with the export, **no credentials are in the file** — the account is identified by the same
 * one-way fingerprint, and a file belonging to another account is refused rather than merged.
 */
class DesktopUserData(private val directory: File = defaultDirectory()) {

    private val file: File get() = File(directory, "user-data.json")

    /**
     * When this client last saw the file, so it can tell its own writes from somebody else's.
     *
     * Nothing stops two copies of this application running at once, and the state file is rewritten
     * whole on every save. Without this, the second window to save would quietly take the first
     * window's evening with it.
     */
    @Volatile
    private var lastSeenModified: Long = 0L

    /**
     * The file a load could not read, moved out of the way. Null when the last load was ordinary.
     *
     * Read once after [load] and shown to the viewer. It is not a fact worth keeping in the state —
     * it describes the launch, not the account.
     */
    var setAside: File? = null
        private set

    /**
     * Reads the stored state for this account, or an empty one.
     *
     * A file whose fingerprint does not match is treated as absent rather than as an error: it means
     * the viewer signed in with a different account, and their previous account's history must
     * neither be shown nor silently overwritten until they actually watch something.
     *
     * A file that cannot be **read at all** is a different case and used to be handled as the same
     * one. Loading returned an empty document, and the first mark afterwards wrote it back over the
     * unreadable file — so a state file damaged by anything at all took every stored position with
     * it, silently, and destroyed the evidence in the same motion. The atomic write exists to make
     * that unlikely; it does not make it impossible, and a half-synced copy or a hand-edited file
     * arrives the same way.
     *
     * So an unreadable file is **moved aside** instead, under a name carrying the moment it
     * happened, and the viewer is told where it went. A file written by a newer build counts as
     * unreadable here for the same reason it is refused on import: it is data from the future, and
     * quietly replacing it with an empty document is the worst of the available answers.
     */
    suspend fun load(fingerprint: String): UserDataExport = withContext(Dispatchers.IO) {
        setAside = null
        lastSeenModified = runCatching { file.lastModified() }.getOrDefault(0L)
        val text = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull()
            ?: return@withContext empty(fingerprint)
        when (val decoded = UserDataExportCodec.decode(text)) {
            is UserDataImportResult.Ok ->
                if (decoded.export.accountFingerprint == fingerprint) {
                    decoded.export
                } else {
                    empty(fingerprint)
                }
            else -> {
                moveAside()
                empty(fingerprint)
            }
        }
    }

    /**
     * Every file a load has ever moved aside, newest last.
     *
     * They are never removed by the client, and that is the point rather than an oversight: each one
     * is the only remaining copy of somebody's history, and an application that tidies away the
     * thing it could not read has completed the loss it was trying to prevent. Deleting one is a
     * decision for whoever owns it.
     *
     * Which leaves the other half of the problem, and the reason this function exists at all: a file
     * named only once, in a banner that can be dismissed, is a file found months later by someone
     * who cannot explain it. Settings lists them so they stay explicable.
     */
    fun keptFiles(): List<File> = directory.listFiles().orEmpty()
        .filter { it.isFile && it.name.startsWith(KEPT_PREFIX) && it.name.endsWith(".json") }
        .sortedBy { it.name }

    /**
     * Renames the unreadable file out of the way so the next save cannot land on top of it.
     *
     * A rename rather than a copy: two files claiming to be the state is its own confusion, and the
     * one being moved is by definition the one nothing can read. If even the rename fails the file
     * stays exactly where it is and [setAside] stays null — which is the honest outcome, since
     * nothing was moved.
     */
    private fun moveAside() {
        val target = File(directory, "$KEPT_PREFIX${System.currentTimeMillis()}.json")
        if (runCatching { file.renameTo(target) }.getOrDefault(false)) {
            setAside = target
            lastSeenModified = 0L
        }
    }

    /**
     * Writes through a temporary file and then renames.
     *
     * A half-written state file is worse than none: it would be refused on the next launch and take
     * every stored position with it. A rename is the closest thing to atomic the filesystem offers.
     */
    /**
     * Writes the state, folding in anything written since this client last looked.
     *
     * A save rewrites the whole file, so a second copy of the application — or a viewer who dropped
     * an exported file in by hand — would otherwise be overwritten by whichever window saved last.
     * The file's own timestamp says whether that happened, and the merge rule from `:shared` is
     * exactly the right answer to it: newest wins per row, nothing is deleted, and applying it twice
     * changes nothing.
     */
    suspend fun save(export: UserDataExport) = withContext(Dispatchers.IO) {
        val written = writeAtomically(directory, "user-data.json") {
            it.writeText(UserDataExportCodec.encode(export.foldingInUnseenChanges()))
        }
        lastSeenModified = written?.lastModified() ?: lastSeenModified
        Unit
    }

    private fun UserDataExport.foldingInUnseenChanges(): UserDataExport {
        val modified = runCatching { file.lastModified() }.getOrDefault(0L)
        if (modified == 0L || modified == lastSeenModified) return this
        val theirs = runCatching { file.readText() }.getOrNull()
            ?.let { UserDataExportCodec.decode(it) } as? UserDataImportResult.Ok
            ?: return this
        // A file belonging to a different account is not ours to merge, exactly as on import.
        if (theirs.export.accountFingerprint != accountFingerprint) return this
        return mergedWith(theirs.export)
    }

    private fun empty(fingerprint: String) = UserDataExport(
        exportedAtEpochMillis = System.currentTimeMillis(),
        accountFingerprint = fingerprint,
    )

    companion object {
        /** Named once, so the writer and the lister cannot drift apart. */
        private const val KEPT_PREFIX = "user-data.unreadable-"

        /**
         * `%LOCALAPPDATA%\KilluaIPTV` on Windows, a dot-directory in the home folder elsewhere.
         *
         * Deliberately not beside the executable and never inside the repository: state does not
         * belong in a working copy, least of all one that is synced.
         */
        fun defaultDirectory(): File {
            val localAppData = System.getenv("LOCALAPPDATA")
            return if (!localAppData.isNullOrBlank()) {
                File(localAppData, "KilluaIPTV")
            } else {
                File(System.getProperty("user.home"), ".killua-iptv")
            }
        }

        /** The fingerprint the export format uses, from the host and username alone. */
        fun fingerprintOf(serverUrl: String, username: String): String =
            UserDataExportCodec.fingerprint(
                serverHost = runCatching { URI(serverUrl).host }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: serverUrl,
                username = username,
            )
    }
}
