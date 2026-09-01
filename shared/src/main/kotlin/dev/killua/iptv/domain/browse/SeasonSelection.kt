package dev.killua.iptv.domain.browse

import dev.killua.iptv.domain.model.SeriesEpisode
import dev.killua.iptv.domain.userdata.EPISODE_CONTENT_TYPE
import dev.killua.iptv.domain.userdata.UserDataExport

/**
 * Which season to open a series on.
 *
 * A provider answers `get_series_info` with every episode of every season at once, so a client that
 * shows them all shows an undifferentiated list two hundred rows long, and one that shows the first
 * season hands the pilot to someone who is four seasons deep. The answer is in the viewer's own
 * stored state rather than anywhere the provider knows about.
 *
 * A **completed** episode counts. Finishing the last episode of a season should still open on that
 * season, because the next thing to watch is one row further down — and on the last season, staying
 * put is better than jumping back to the first.
 */
fun List<SeriesEpisode>.seasonToOpen(data: UserDataExport?): Int? {
    if (isEmpty()) return null
    val seasonById = associate { it.id to it.seasonNumber }
    val lastWatched = data?.watchProgress
        ?.filter { it.contentType == EPISODE_CONTENT_TYPE && it.contentId in seasonById }
        ?.maxByOrNull { it.updatedAtEpochMillis }
    // Nothing watched yet is the one case where the first season is the right answer.
    return lastWatched?.let { seasonById[it.contentId] } ?: minOf { it.seasonNumber }
}

/**
 * Which episode the one big button on a series should start.
 *
 * The same rule the phone's series screen follows, written against the stored export rather than
 * against Room: **the earliest episode begun but not finished**, otherwise the first one never
 * watched, and once every episode is watched, the first — because a series someone has finished is
 * one they are rewatching, and a button that refuses to do anything is worse than one that starts
 * again from the beginning.
 *
 * Order is the provider's own listing order, which is the order episodes were made. Sorting by
 * season and episode number here would be worse, not better: those are display values providers
 * repeat and omit, and the listing already arrived in the right order.
 */
fun List<SeriesEpisode>.nextEpisodeToWatch(data: UserDataExport?): SeriesEpisode? {
    if (isEmpty()) return null
    val progress = data?.watchProgress
        ?.filter { it.contentType == EPISODE_CONTENT_TYPE }
        ?.associateBy { it.contentId }
        .orEmpty()
    return firstOrNull { episode ->
        progress[episode.id]?.let { !it.completed && it.positionMs > 0L } == true
    }
        ?: firstOrNull { progress[it.id]?.completed != true }
        ?: first()
}
