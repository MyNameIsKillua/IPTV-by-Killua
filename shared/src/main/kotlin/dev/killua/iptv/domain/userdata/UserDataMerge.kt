package dev.killua.iptv.domain.userdata

/**
 * What an import should write, given what is already on this device.
 *
 * **Newest wins, and nothing is ever deleted.** One rule for every kind of row, because a second
 * rule would need a second explanation, and an import that removes something the viewer still has is
 * a bug nobody can undo. A row missing from the file simply stays.
 *
 * That makes importing safe to repeat: the same file applied twice writes nothing the second time.
 *
 * The comparison is the row's own timestamp. For a position that is exactly right — the device where
 * you watched most recently knows best. For a favourite or a saved item the timestamp means when it
 * was marked, and preferring the newer one is arbitrary but harmless: the row exists either way, and
 * only its place in a "recently saved" ordering can shift.
 */
object UserDataMerge {

    /** The positions worth writing. A file older than what is here for a title is ignored for it. */
    fun progress(
        local: List<ProgressRecord>,
        imported: List<ProgressRecord>,
    ): List<ProgressRecord> {
        val existing = local.associateBy { it.contentType to it.contentId }
        return imported.filter { candidate ->
            val current = existing[candidate.contentType to candidate.contentId]
            current == null || candidate.updatedAtEpochMillis > current.updatedAtEpochMillis
        }
    }

    fun marks(local: List<MarkRecord>, imported: List<MarkRecord>): List<MarkRecord> {
        val existing = local.associateBy { it.contentId }
        return imported.filter { candidate ->
            val current = existing[candidate.contentId]
            current == null || candidate.atEpochMillis > current.atEpochMillis
        }
    }

    fun watchlist(
        local: List<WatchlistRecord>,
        imported: List<WatchlistRecord>,
    ): List<WatchlistRecord> {
        val existing = local.associateBy { it.contentType to it.contentId }
        return imported.filter { candidate ->
            val current = existing[candidate.contentType to candidate.contentId]
            current == null || candidate.addedAtEpochMillis > current.addedAtEpochMillis
        }
    }
}

/**
 * Why an import was refused, or what it would do.
 *
 * A file is checked against the account **before** anything is written. Merging one account's
 * history into another cannot be undone, and the fingerprint exists precisely so it never happens
 * by accident.
 */
sealed interface UserDataImportPlan {
    data class Ready(
        val export: UserDataExport,
        val progress: List<ProgressRecord>,
        val movieFavorites: List<MarkRecord>,
        val seriesFavorites: List<MarkRecord>,
        val watchlist: List<WatchlistRecord>,
        val recentChannels: List<MarkRecord>,
    ) : UserDataImportPlan {
        /** How many rows this would actually change; zero means the file adds nothing new. */
        val changeCount: Int
            get() = progress.size + movieFavorites.size + seriesFavorites.size +
                watchlist.size + recentChannels.size
    }

    /** The file belongs to a different provider account. Refused rather than merged. */
    data object WrongAccount : UserDataImportPlan

    data class Unreadable(val reason: UserDataImportResult) : UserDataImportPlan
}

/**
 * Reads [document] and works out what importing it would change, without changing anything.
 *
 * The same three answers the phone's importer gives, for a client whose whole state is already an
 * export in memory: the phone assembles this from its database, so its version lives in `:app`,
 * while this one is a pure function and lives here with tests.
 *
 * The receiving export's own fingerprint is what the file is checked against. A file belonging to
 * another account is refused rather than merged, because that merge cannot be undone — the merge
 * rule never deletes, so a wrong import is permanent.
 */
fun UserDataExport.planImportOf(document: String): UserDataImportPlan {
    val decoded = UserDataExportCodec.decode(document)
    if (decoded !is UserDataImportResult.Ok) return UserDataImportPlan.Unreadable(decoded)
    val incoming = decoded.export
    if (incoming.accountFingerprint != accountFingerprint) return UserDataImportPlan.WrongAccount
    return UserDataImportPlan.Ready(
        export = incoming,
        progress = UserDataMerge.progress(watchProgress, incoming.watchProgress),
        movieFavorites = UserDataMerge.marks(movieFavorites, incoming.movieFavorites),
        seriesFavorites = UserDataMerge.marks(seriesFavorites, incoming.seriesFavorites),
        watchlist = UserDataMerge.watchlist(watchlist, incoming.watchlist),
        recentChannels = UserDataMerge.marks(recentChannels, incoming.recentChannels),
    )
}

/**
 * Folds an incoming export into this one, newest wins, nothing removed.
 *
 * The same rules [UserDataMerge] applies row by row, applied to a whole file at once. The desktop
 * client uses it for Import, where there is no database to write into and the merged result simply
 * becomes the new stored state.
 *
 * The fingerprint of the receiving side is kept: a merge does not change whose data this is, and a
 * caller that has not already checked the two agree has skipped the check that matters.
 */
fun UserDataExport.mergedWith(
    incoming: UserDataExport,
    nowEpochMillis: Long = System.currentTimeMillis(),
): UserDataExport = copy(
    exportedAtEpochMillis = nowEpochMillis,
    watchProgress = watchProgress + UserDataMerge.progress(watchProgress, incoming.watchProgress),
    movieFavorites = movieFavorites + UserDataMerge.marks(movieFavorites, incoming.movieFavorites),
    seriesFavorites = seriesFavorites +
        UserDataMerge.marks(seriesFavorites, incoming.seriesFavorites),
    watchlist = watchlist + UserDataMerge.watchlist(watchlist, incoming.watchlist),
    recentChannels = recentChannels +
        UserDataMerge.marks(recentChannels, incoming.recentChannels),
).deduplicated()

/**
 * Keeps the newest row per identity.
 *
 * [mergedWith] appends the winners to the existing rows rather than replacing them in place, so a
 * replaced row is briefly present twice. Collapsing afterwards is simpler to read than threading a
 * replacement through five lists, and the last writer wins because the appended row is the newer.
 */
private fun UserDataExport.deduplicated(): UserDataExport = copy(
    watchProgress = watchProgress.associateBy { it.contentType to it.contentId }.values.toList(),
    movieFavorites = movieFavorites.associateBy { it.contentId }.values.toList(),
    seriesFavorites = seriesFavorites.associateBy { it.contentId }.values.toList(),
    watchlist = watchlist.associateBy { it.contentType to it.contentId }.values.toList(),
    recentChannels = recentChannels.associateBy { it.contentId }.values.toList(),
)
