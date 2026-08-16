package dev.killua.iptv.core.database

import dev.killua.iptv.domain.model.MovieFilter
import dev.killua.iptv.domain.model.MovieSortOrder

/**
 * Assembles the paged Movie browsing statement through [PagedQueryBuilder].
 *
 * Ordering always ends with a unique column so paging stays deterministic when the primary sort
 * key ties, and rows with a missing sort value are placed last rather than treated as zero.
 */
object MovieQueryFactory {
    const val MOVIE_CONTENT_TYPE = "movie"

    fun build(accountId: String, filter: MovieFilter): PagedQuery {
        val builder = PagedQueryBuilder("SELECT m.* FROM movies m")

        if (filter.favoritesOnly) {
            builder.join(
                "INNER JOIN movie_favorites fav" +
                    " ON fav.accountId = m.accountId AND fav.remoteStreamId = m.remoteStreamId",
            )
        }
        if (filter.inProgressOnly) {
            builder.join(
                "INNER JOIN watch_progress wp" +
                    " ON wp.accountId = m.accountId AND wp.contentId = m.remoteStreamId" +
                    " AND wp.contentType = ? AND wp.completed = 0 AND wp.positionMs > 0",
                MOVIE_CONTENT_TYPE,
            )
        }

        builder.where("m.accountId = ?", accountId)
        filter.categoryId?.let { builder.where("m.remoteCategoryId = ?", it) }
        filter.languageTag?.let { builder.where("m.languageTag = ?", it) }
        builder.whereContains("m.sortName", filter.searchQuery)

        return builder.build(orderBy(filter.sortOrder))
    }

    private fun orderBy(sortOrder: MovieSortOrder): String = when (sortOrder) {
        MovieSortOrder.ProviderDefault ->
            "m.providerOrder ASC, m.sortName ASC"
        MovieSortOrder.NameAscending ->
            "m.sortName ASC"
        MovieSortOrder.RatingDescending ->
            "m.rating IS NULL, m.rating DESC, m.sortName ASC"
        MovieSortOrder.ReleaseYearDescending ->
            "m.releaseYear IS NULL, m.releaseYear DESC, m.sortName ASC"
        MovieSortOrder.RecentlyAdded ->
            "m.addedAtEpochSeconds IS NULL, m.addedAtEpochSeconds DESC, m.sortName ASC"
    } + ", m.remoteStreamId ASC"
}
