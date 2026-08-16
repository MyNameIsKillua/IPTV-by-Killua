package dev.killua.iptv.core.database

import androidx.room.Entity
import androidx.room.Index
import dev.killua.iptv.core.text.SearchTextNormalizer

@Entity(tableName = "accounts")
data class AccountEntity(
    @androidx.room.PrimaryKey val accountId: String,
    /**
     * What the viewer calls this account, entered at sign-in. Null means they gave no name, and the
     * provider user name stands in — the provider has no such field, so this is theirs alone.
     */
    val displayName: String? = null,
    val status: String,
    val expiresAtEpochSeconds: Long?,
    val activeConnections: Int?,
    val maximumConnections: Int?,
    val serverTimezone: String?,
    val allowedOutputFormats: String,
    val lastValidatedAtEpochMillis: Long,
    val lastLiveSyncAtEpochMillis: Long?,
)

@Entity(
    tableName = "live_categories",
    primaryKeys = ["accountId", "remoteCategoryId"],
    indices = [
        Index(value = ["accountId", "sortOrder"]),
    ],
)
data class LiveCategoryEntity(
    val accountId: String,
    val remoteCategoryId: String,
    val name: String,
    val sortOrder: Int,
    val syncGeneration: Long,
)

/**
 * Browsing-sized channel metadata from the provider listing.
 *
 * [sortName] is a pre-normalized copy of [name] so alphabetical paging and title search are index
 * backed instead of depending on collation at query time. It replaced an index on the raw name,
 * which no query could use: ordering by the raw name puts every capital letter before every
 * lowercase one, and asking for `COLLATE NOCASE` instead makes the index inapplicable.
 */
@Entity(
    tableName = "live_channels",
    primaryKeys = ["accountId", "remoteStreamId"],
    indices = [
        Index(value = ["accountId", "remoteCategoryId"]),
        Index(value = ["accountId", "providerOrder"]),
        Index(value = ["accountId", "sortName"]),
        Index(value = ["accountId", "languageTag"]),
    ],
)
data class LiveChannelEntity(
    val accountId: String,
    val remoteStreamId: String,
    val remoteCategoryId: String?,
    val name: String,
    val sortName: String,
    val logoUrl: String?,
    val epgChannelId: String?,
    val containerExtension: String?,
    /** Heuristic language from the channel's category or its own name; null when undetected. */
    val languageTag: String?,
    val providerOrder: Int,
    val syncGeneration: Long,
) {
    companion object {
        /**
         * Normalized key for alphabetical paging and search, folded by [SearchTextNormalizer] so
         * a search matches whether or not the viewer typed the provider's punctuation.
         *
         * Unlike a movie title, a leading language or country tag is deliberately kept. `DE | RTL`
         * is how the channel is labelled on screen, and an A-Z list should read the way the list
         * looks; the provider's own categories already separate countries for anyone who wants
         * that grouping. Only the bar itself goes, so the key reads `de rtl` and both `de | rtl`
         * and `de rtl` find it.
         */
        fun sortNameOf(name: String): String = SearchTextNormalizer.normalize(name)
    }
}

@Entity(
    tableName = "recent_channels",
    primaryKeys = ["accountId", "remoteStreamId"],
    indices = [Index(value = ["accountId", "lastWatchedAtEpochMillis"])],
)
data class RecentChannelEntity(
    val accountId: String,
    val remoteStreamId: String,
    val lastWatchedAtEpochMillis: Long,
)

@Entity(
    tableName = "movie_categories",
    primaryKeys = ["accountId", "remoteCategoryId"],
    indices = [
        Index(value = ["accountId", "sortOrder"]),
        Index(value = ["accountId", "languageTag"]),
    ],
)
data class MovieCategoryEntity(
    val accountId: String,
    val remoteCategoryId: String,
    val name: String,
    /** Heuristic language derived from the provider's category name; null when undetected. */
    val languageTag: String?,
    val sortOrder: Int,
    val syncGeneration: Long,
)

/**
 * Browsing-sized Movie metadata from the provider listing.
 *
 * [sortName] is a pre-normalized copy of [name] so alphabetical paging stays stable and index
 * backed instead of depending on collation at query time.
 */
@Entity(
    tableName = "movies",
    primaryKeys = ["accountId", "remoteStreamId"],
    indices = [
        Index(value = ["accountId", "remoteCategoryId"]),
        Index(value = ["accountId", "providerOrder"]),
        Index(value = ["accountId", "sortName"]),
        Index(value = ["accountId", "rating"]),
        Index(value = ["accountId", "releaseYear"]),
        Index(value = ["accountId", "addedAtEpochSeconds"]),
        Index(value = ["accountId", "languageTag"]),
    ],
)
data class MovieEntity(
    val accountId: String,
    val remoteStreamId: String,
    val remoteCategoryId: String?,
    val name: String,
    val sortName: String,
    val posterUrl: String?,
    val containerExtension: String?,
    val rating: Double?,
    val releaseYear: Int?,
    val addedAtEpochSeconds: Long?,
    /** Heuristic language from the category or title; null when undetected. */
    val languageTag: String?,
    val providerOrder: Int,
    val syncGeneration: Long,
)

/**
 * Rich per-title metadata fetched lazily from `get_vod_info`. Kept in its own table so a listing
 * refresh can never overwrite it with nulls, and deliberately not tied to a sync generation.
 */
@Entity(
    tableName = "movie_details",
    primaryKeys = ["accountId", "remoteStreamId"],
)
data class MovieDetailsEntity(
    val accountId: String,
    val remoteStreamId: String,
    val plot: String?,
    val genre: String?,
    val cast: String?,
    val director: String?,
    val backdropUrl: String?,
    val durationSeconds: Int?,
    val fetchedAtEpochMillis: Long,
)

@Entity(
    tableName = "movie_favorites",
    primaryKeys = ["accountId", "remoteStreamId"],
    indices = [Index(value = ["accountId", "favoritedAtEpochMillis"])],
)
data class MovieFavoriteEntity(
    val accountId: String,
    val remoteStreamId: String,
    val favoritedAtEpochMillis: Long,
)

@Entity(
    tableName = "series_categories",
    primaryKeys = ["accountId", "remoteCategoryId"],
    indices = [Index(value = ["accountId", "sortOrder"])],
)
data class SeriesCategoryEntity(
    val accountId: String,
    val remoteCategoryId: String,
    val name: String,
    val sortOrder: Int,
    val syncGeneration: Long,
)

/**
 * Browsing-sized Series metadata from the provider listing, shaped like [MovieEntity] because the
 * two are browsed the same way. [sortName] strips a recognized leading language tag, as a Movie
 * title does and unlike a channel name.
 */
@Entity(
    tableName = "series",
    primaryKeys = ["accountId", "remoteSeriesId"],
    indices = [
        Index(value = ["accountId", "remoteCategoryId"]),
        Index(value = ["accountId", "providerOrder"]),
        Index(value = ["accountId", "sortName"]),
        Index(value = ["accountId", "rating"]),
        Index(value = ["accountId", "releaseYear"]),
        Index(value = ["accountId", "lastModifiedEpochSeconds"]),
        Index(value = ["accountId", "languageTag"]),
    ],
)
data class SeriesEntity(
    val accountId: String,
    val remoteSeriesId: String,
    val remoteCategoryId: String?,
    val name: String,
    val sortName: String,
    val posterUrl: String?,
    val rating: Double?,
    val releaseYear: Int?,
    val lastModifiedEpochSeconds: Long?,
    /** Heuristic language from the category or the title; null when undetected. */
    val languageTag: String?,
    val providerOrder: Int,
    val syncGeneration: Long,
)

/**
 * Rich per-series metadata fetched lazily from `get_series_info`. Like [MovieDetailsEntity] it
 * carries no sync generation, so a listing refresh can never replace it with nulls.
 */
@Entity(
    tableName = "series_details",
    primaryKeys = ["accountId", "remoteSeriesId"],
)
data class SeriesDetailsEntity(
    val accountId: String,
    val remoteSeriesId: String,
    val plot: String?,
    val genre: String?,
    val cast: String?,
    val director: String?,
    val backdropUrl: String?,
    val fetchedAtEpochMillis: Long,
)

/**
 * One cached episode.
 *
 * The primary key is the provider's own episode ID, which is also what `watch_progress` will key
 * on. Season and episode numbers are stored for display and ordering only; identity never derives
 * from them, because providers repeat and renumber them.
 *
 * Episodes arrive with the details fetch, so they carry no sync generation either. A details
 * refresh replaces a series' episodes as a set, which is what removes ones the provider dropped.
 */
@Entity(
    tableName = "series_episodes",
    primaryKeys = ["accountId", "remoteEpisodeId"],
    indices = [
        Index(value = ["accountId", "remoteSeriesId", "seasonNumber", "episodeNumber"]),
    ],
)
data class SeriesEpisodeEntity(
    val accountId: String,
    val remoteEpisodeId: String,
    val remoteSeriesId: String,
    val seasonNumber: Int,
    val episodeNumber: Int?,
    val title: String,
    val containerExtension: String?,
    val durationSeconds: Int?,
    val plot: String?,
    val stillUrl: String?,
)

/**
 * Watch position for any resumable content.
 *
 * [contentType] plus [contentId] form a logical identity so episodes can reuse this table later
 * without a further migration. Identity never derives from display text.
 */
@Entity(
    tableName = "watch_progress",
    primaryKeys = ["accountId", "contentType", "contentId"],
    indices = [Index(value = ["accountId", "contentType", "updatedAtEpochMillis"])],
)
data class WatchProgressEntity(
    val accountId: String,
    val contentType: String,
    val contentId: String,
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val updatedAtEpochMillis: Long,
)

/** Mirrors [MovieFavoriteEntity]; carries no sync generation for the same reason. */
@Entity(
    tableName = "series_favorites",
    primaryKeys = ["accountId", "remoteSeriesId"],
    indices = [Index(value = ["accountId", "favoritedAtEpochMillis"])],
)
data class SeriesFavoriteEntity(
    val accountId: String,
    val remoteSeriesId: String,
    val favoritedAtEpochMillis: Long,
)

/**
 * One saved thing, across all three libraries.
 *
 * Deliberately one table rather than a saved-flag per library: the whole point is a single list a
 * viewer can look at, and three tables would have to be merged for every read anyway. [contentType]
 * plus [contentId] form the identity, the same shape `watch_progress` already uses — providers
 * number movies, series, and channels independently, so the type is what keeps them apart.
 *
 * Like the favorites tables it carries **no sync generation**: a saved title has to survive the
 * provider dropping it from a listing for a day. What it does not survive is being missing when the
 * list is read, because the read joins to the library table; the row stays and simply stops being
 * displayed.
 */
@Entity(
    tableName = "watchlist",
    primaryKeys = ["accountId", "contentType", "contentId"],
    indices = [Index(value = ["accountId", "addedAtEpochMillis"])],
)
data class WatchlistEntity(
    val accountId: String,
    val contentType: String,
    val contentId: String,
    val addedAtEpochMillis: Long,
)

/**
 * What a Continue Watching row needs, from any content type.
 *
 * A projection rather than an entity: the row is ordered by when a title was last watched, and
 * that timestamp lives in `watch_progress`, not on the title. Returning full entities would leave
 * the caller unable to merge Movies and Series into one honestly ordered row.
 */
data class ContinueWatchingProjection(
    val remoteId: String,
    val name: String,
    val posterUrl: String?,
    val lastWatchedAtEpochMillis: Long,
)
