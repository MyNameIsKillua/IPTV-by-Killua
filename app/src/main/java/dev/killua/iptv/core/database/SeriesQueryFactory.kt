package dev.killua.iptv.core.database

import dev.killua.iptv.domain.model.SeriesFilter
import dev.killua.iptv.domain.model.SeriesSortOrder

/**
 * Assembles the paged Series browsing statement through [PagedQueryBuilder].
 *
 * Ordering always ends with a unique column so paging stays deterministic when the primary sort
 * key ties, and rows with a missing sort value are placed last rather than treated as zero.
 */
object SeriesQueryFactory {
    const val EPISODE_CONTENT_TYPE = "episode"

    fun build(accountId: String, filter: SeriesFilter): PagedQuery {
        val builder = PagedQueryBuilder("SELECT s.* FROM series s")

        if (filter.favoritesOnly) {
            builder.join(
                "INNER JOIN series_favorites fav" +
                    " ON fav.accountId = s.accountId AND fav.remoteSeriesId = s.remoteSeriesId",
            )
        }

        builder.where("s.accountId = ?", accountId)
        filter.categoryId?.let { builder.where("s.remoteCategoryId = ?", it) }
        filter.languageTag?.let { builder.where("s.languageTag = ?", it) }
        // A series is in progress when *any* episode is, which is a set test rather than a join:
        // joining would repeat the series once per unfinished episode and paging would then show
        // duplicates. EXISTS also stops at the first match instead of collecting every episode.
        if (filter.inProgressOnly) {
            builder.where(
                "EXISTS (SELECT 1 FROM series_episodes e" +
                    " INNER JOIN watch_progress wp" +
                    " ON wp.accountId = e.accountId AND wp.contentId = e.remoteEpisodeId" +
                    " WHERE e.accountId = s.accountId AND e.remoteSeriesId = s.remoteSeriesId" +
                    " AND wp.contentType = ? AND wp.completed = 0 AND wp.positionMs > 0)",
                EPISODE_CONTENT_TYPE,
            )
        }
        builder.whereContains("s.sortName", filter.searchQuery)

        return builder.build(orderBy(filter.sortOrder))
    }

    private fun orderBy(sortOrder: SeriesSortOrder): String = when (sortOrder) {
        SeriesSortOrder.ProviderDefault ->
            "s.providerOrder ASC, s.sortName ASC"
        SeriesSortOrder.NameAscending ->
            "s.sortName ASC"
        SeriesSortOrder.RatingDescending ->
            "s.rating IS NULL, s.rating DESC, s.sortName ASC"
        SeriesSortOrder.ReleaseYearDescending ->
            "s.releaseYear IS NULL, s.releaseYear DESC, s.sortName ASC"
        SeriesSortOrder.RecentlyUpdated ->
            "s.lastModifiedEpochSeconds IS NULL, s.lastModifiedEpochSeconds DESC, s.sortName ASC"
    } + ", s.remoteSeriesId ASC"
}
