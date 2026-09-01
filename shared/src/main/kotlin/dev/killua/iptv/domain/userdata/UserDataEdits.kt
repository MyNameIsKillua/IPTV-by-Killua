package dev.killua.iptv.domain.userdata

import dev.killua.iptv.domain.progress.WatchProgressPolicy

/**
 * Marking and unmarking, on the stored state directly.
 *
 * These exist because the desktop client keeps its state *as* an export rather than in a database,
 * so a heart tapped there is an edit to this structure. They are pure and total: every one returns a
 * new export and none of them can half-apply.
 *
 * The Android app does not use them — its marks live in Room, where a favourite is a row and a
 * transaction. Two implementations of the same idea is the price of one client having a database and
 * the other deliberately not; what they share is the *format*, which is what has to agree.
 */

/** Which library a mark belongs to. Watch progress uses these same two words. */
const val MOVIE_CONTENT_TYPE = "movie"
const val SERIES_CONTENT_TYPE = "series"

/**
 * Progress is kept per episode rather than per series, so it needs a word of its own.
 *
 * Both clients already write exactly this string — the phone from its own constant beside its
 * queries — which is what makes an episode half-watched on one of them resumable on the other.
 */
const val EPISODE_CONTENT_TYPE = "episode"

fun UserDataExport.isMovieFavourite(id: String): Boolean = movieFavorites.any { it.contentId == id }

fun UserDataExport.isSeriesFavourite(id: String): Boolean = seriesFavorites.any { it.contentId == id }

fun UserDataExport.isSaved(contentType: String, id: String): Boolean =
    watchlist.any { it.contentType == contentType && it.contentId == id }

/**
 * Adds or removes a film from the favourites.
 *
 * Re-marking something already marked deliberately keeps the **original** timestamp rather than
 * refreshing it. The stamp records when it was first marked, which is what a "recently favourited"
 * ordering is about; bumping it on every render or double click would quietly reorder that list.
 */
fun UserDataExport.toggleMovieFavourite(
    id: String,
    nowEpochMillis: Long = System.currentTimeMillis(),
): UserDataExport = copy(
    exportedAtEpochMillis = nowEpochMillis,
    movieFavorites = movieFavorites.toggleMark(id, nowEpochMillis),
)

fun UserDataExport.toggleSeriesFavourite(
    id: String,
    nowEpochMillis: Long = System.currentTimeMillis(),
): UserDataExport = copy(
    exportedAtEpochMillis = nowEpochMillis,
    seriesFavorites = seriesFavorites.toggleMark(id, nowEpochMillis),
)

fun UserDataExport.toggleSaved(
    contentType: String,
    id: String,
    nowEpochMillis: Long = System.currentTimeMillis(),
): UserDataExport {
    val existing = watchlist.firstOrNull { it.contentType == contentType && it.contentId == id }
    return copy(
        exportedAtEpochMillis = nowEpochMillis,
        watchlist = if (existing != null) {
            watchlist - existing
        } else {
            watchlist + WatchlistRecord(contentType, id, nowEpochMillis)
        },
    )
}

/**
 * Folds one watched position into the stored state.
 *
 * Completion uses the shared [WatchProgressPolicy], so a film counts as finished here at exactly the
 * point it does on the phone — otherwise the same title would be watched on one device and unwatched
 * on the other after the same viewing.
 *
 * A finished title stores its full duration rather than the slightly short position a player
 * reports, which is what makes completion deterministic rather than a matter of how close to the
 * credits the last checkpoint happened to land.
 */
fun UserDataExport.withProgress(
    contentType: String,
    contentId: String,
    positionMs: Long,
    durationMs: Long,
    nowEpochMillis: Long = System.currentTimeMillis(),
    /** Only ever set for an episode; see [ProgressRecord.seriesId] for why it is carried at all. */
    seriesId: String? = null,
): UserDataExport {
    if (durationMs <= 0L) return this
    val completed = WatchProgressPolicy.isCompleted(positionMs, durationMs)
    val record = ProgressRecord(
        contentType = contentType,
        contentId = contentId,
        positionMs = if (completed) durationMs else positionMs.coerceIn(0L, durationMs),
        durationMs = durationMs,
        completed = completed,
        updatedAtEpochMillis = nowEpochMillis,
        seriesId = seriesId,
    )
    return copy(
        exportedAtEpochMillis = nowEpochMillis,
        watchProgress = watchProgress.filterNot {
            it.contentType == contentType && it.contentId == contentId
        } + record,
    )
}

/**
 * Where to start a title, or null to start at the beginning.
 *
 * A completed title deliberately starts over: resuming it would drop the viewer three minutes before
 * the credits, which is the same rule the phone follows.
 */
fun UserDataExport.resumePositionOf(contentType: String, contentId: String): Long? =
    watchProgress
        .firstOrNull { it.contentType == contentType && it.contentId == contentId }
        ?.takeIf { !it.completed && it.positionMs > 0L }
        ?.positionMs

/**
 * Marks something watched without having watched it here.
 *
 * A title seen on another device, in another client, or on television years ago is otherwise stuck
 * halfway forever, and the list keeps offering it. This writes the same row a real viewing would
 * leave, so nothing downstream has to know the difference — completion is a stored fact rather than
 * a second kind of mark.
 *
 * A [durationMs] of zero or less is refused rather than invented. The format's own rule is that a
 * progress row without a duration is not a row at all, and a fabricated one would travel to the
 * other device and be believed there.
 */
fun UserDataExport.markWatched(
    contentType: String,
    contentId: String,
    durationMs: Long,
    nowEpochMillis: Long = System.currentTimeMillis(),
    /** Only ever set for an episode; see [ProgressRecord.seriesId] for why it is carried at all. */
    seriesId: String? = null,
): UserDataExport {
    if (durationMs <= 0L) return this
    return copy(
        exportedAtEpochMillis = nowEpochMillis,
        watchProgress = watchProgress.filterNot {
            it.contentType == contentType && it.contentId == contentId
        } + ProgressRecord(
            contentType = contentType,
            contentId = contentId,
            positionMs = durationMs,
            durationMs = durationMs,
            completed = true,
            updatedAtEpochMillis = nowEpochMillis,
            seriesId = seriesId,
        ),
    )
}

/**
 * Forgets that something was watched at all.
 *
 * The row is removed rather than zeroed, because a position of zero is a title someone started and
 * stopped immediately, which is a different claim. Note that this cannot travel: the merge rule
 * never deletes, so importing an older file elsewhere will not resurrect the row, but importing this
 * file into a device that still has it will not remove it either.
 */
fun UserDataExport.clearProgress(
    contentType: String,
    contentId: String,
    nowEpochMillis: Long = System.currentTimeMillis(),
): UserDataExport = copy(
    exportedAtEpochMillis = nowEpochMillis,
    watchProgress = watchProgress.filterNot {
        it.contentType == contentType && it.contentId == contentId
    },
)

/**
 * Everything started and not finished, newest first.
 *
 * A finished title is deliberately absent: "continue watching" that offers something already watched
 * to the end is the row nobody wants, and the same rule keeps it off the phone's Home screen.
 */
fun UserDataExport.continueWatching(): List<ProgressRecord> = watchProgress
    .filter { !it.completed && it.positionMs > 0L }
    .sortedByDescending { it.updatedAtEpochMillis }

private fun List<MarkRecord>.toggleMark(id: String, nowEpochMillis: Long): List<MarkRecord> {
    val existing = firstOrNull { it.contentId == id }
    return if (existing != null) this - existing else this + MarkRecord(id, nowEpochMillis)
}

/**
 * Records that a channel was watched.
 *
 * Unlike a favourite, the stamp here **is** refreshed: this list is ordered by when something was
 * last watched, so re-watching a channel is exactly the event that should move it to the front.
 * That is the opposite rule to [toggleMovieFavourite], and the difference is the point — one records
 * a decision, the other an occurrence.
 *
 * **Deliberately uncapped**, which is worth stating because it looks like an oversight. The list
 * holds *distinct channels* rather than viewings, so it grows only when somewhere new is watched,
 * and a row is some forty bytes; thousands of them are a file measured in tens of kilobytes. A cap
 * would also fight the merge rule, which never deletes: trimming here would be undone by the next
 * import from a device that kept more, and the two would take turns rewriting each other. The
 * *display* is bounded instead, by [ownChannels], and [withoutRecentChannel] is how a viewer drops
 * one on purpose. The phone does the same: its table is unbounded and its queries carry the limit.
 */
fun UserDataExport.withRecentChannel(
    streamId: String,
    nowEpochMillis: Long = System.currentTimeMillis(),
): UserDataExport = copy(
    exportedAtEpochMillis = nowEpochMillis,
    recentChannels = recentChannels.filterNot { it.contentId == streamId } +
        MarkRecord(streamId, nowEpochMillis),
)

/**
 * Forgets that a channel was ever watched.
 *
 * The counterpart to [withRecentChannel], and the only way a list built from *occurrences* can be
 * corrected: two seconds on the wrong channel is enough to record it, and without this it stays in
 * the guide until forty newer ones have pushed it out.
 *
 * It does not touch the saved list. A bookmark is a decision and has its own switch; this undoes the
 * visit, not the intention.
 */
fun UserDataExport.withoutRecentChannel(
    streamId: String,
    nowEpochMillis: Long = System.currentTimeMillis(),
): UserDataExport = copy(
    exportedAtEpochMillis = nowEpochMillis,
    recentChannels = recentChannels.filterNot { it.contentId == streamId },
)

/**
 * The channels this viewer actually uses: saved first, then recently watched, newest first.
 *
 * This is what a guide should cover. Xtream answers the programme one channel at a time, so a grid
 * over a six-figure library is not a layout problem but a request problem — it would be six figures
 * of requests. Bounding it to what the viewer keeps turns that into tens.
 */
fun UserDataExport.ownChannels(limit: Int = 40): List<String> {
    val saved = watchlist.filter { it.contentType == CHANNEL_CONTENT_TYPE }
        .sortedByDescending { it.addedAtEpochMillis }
        .map { it.contentId }
    val recent = recentChannels.sortedByDescending { it.atEpochMillis }.map { it.contentId }
    return (saved + recent).distinct().take(limit)
}

/**
 * A saved live channel, in the word the **phone** writes.
 *
 * It says `channel` rather than `live` because the Android app has been writing that string into its
 * watchlist since before this format existed, and rows in that shape are already on disk. The export
 * is what the two clients agree on, so where they can differ the phone is right by definition —
 * it is the reference implementation, and it has users.
 *
 * This was wrong for one release cycle in the desktop client, which sent `live`: the phone drops a
 * watchlist row whose type it does not recognise, so a channel bookmarked there arrived and was
 * silently discarded, and one saved on the phone was invisible on the desktop. No desktop build had
 * been distributed, so the word was simply corrected rather than translated on read.
 */
const val CHANNEL_CONTENT_TYPE = "channel"
