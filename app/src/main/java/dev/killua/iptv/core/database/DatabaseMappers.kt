package dev.killua.iptv.core.database

import dev.killua.iptv.domain.model.Account
import dev.killua.iptv.domain.model.AccountStatus
import dev.killua.iptv.domain.model.ContinueWatchingEntry
import dev.killua.iptv.domain.model.LiveCategory
import dev.killua.iptv.domain.model.LiveChannel
import dev.killua.iptv.domain.model.MovieCategory
import dev.killua.iptv.domain.model.MovieDetails
import dev.killua.iptv.domain.model.MovieSummary
import dev.killua.iptv.domain.model.ResumableKind
import dev.killua.iptv.domain.model.SeriesCategory
import dev.killua.iptv.domain.model.SeriesDetails
import dev.killua.iptv.domain.model.SeriesEpisode
import dev.killua.iptv.domain.model.SeriesSummary
import dev.killua.iptv.domain.model.WatchProgress
import dev.killua.iptv.domain.model.XtreamCredentials

fun AccountEntity.toDomain(credentials: XtreamCredentials): Account = Account(
    id = accountId,
    username = credentials.username,
    displayName = displayName,
    serverUrl = credentials.serverUrl,
    status = runCatching { AccountStatus.valueOf(status) }.getOrDefault(AccountStatus.Unknown),
    expiresAtEpochSeconds = expiresAtEpochSeconds,
    activeConnections = activeConnections,
    maximumConnections = maximumConnections,
    serverTimezone = serverTimezone,
    allowedOutputFormats = allowedOutputFormats.split(',').filter(String::isNotBlank).toSet(),
    lastValidatedAtEpochMillis = lastValidatedAtEpochMillis,
    lastLiveSyncAtEpochMillis = lastLiveSyncAtEpochMillis,
)

fun LiveCategoryEntity.toDomain(): LiveCategory = LiveCategory(
    id = remoteCategoryId,
    name = name,
    sortOrder = sortOrder,
)

fun LiveChannelEntity.toDomain(lastWatchedAt: Long? = null): LiveChannel = LiveChannel(
    id = remoteStreamId,
    categoryId = remoteCategoryId,
    name = name,
    logoUrl = logoUrl,
    epgChannelId = epgChannelId,
    containerExtension = containerExtension,
    directSource = null,
    providerOrder = providerOrder,
    lastWatchedAtEpochMillis = lastWatchedAt,
)

fun MovieCategoryEntity.toDomain(): MovieCategory = MovieCategory(
    id = remoteCategoryId,
    name = name,
    sortOrder = sortOrder,
)

fun MovieEntity.toDomain(): MovieSummary = MovieSummary(
    id = remoteStreamId,
    categoryId = remoteCategoryId,
    name = name,
    posterUrl = posterUrl,
    containerExtension = containerExtension,
    rating = rating,
    releaseYear = releaseYear,
    addedAtEpochSeconds = addedAtEpochSeconds,
    providerOrder = providerOrder,
)

/**
 * Rebuilds the full detail view from the two tables that hold it. Listing columns stay
 * authoritative for identity and artwork, so a lazily cached detail row can never blank them.
 */
fun MovieDetailsEntity.toDomain(movie: MovieEntity): MovieDetails = MovieDetails(
    id = movie.remoteStreamId,
    name = movie.name,
    categoryId = movie.remoteCategoryId,
    containerExtension = movie.containerExtension,
    posterUrl = movie.posterUrl,
    backdropUrl = backdropUrl,
    plot = plot,
    genre = genre,
    cast = cast,
    director = director,
    releaseYear = movie.releaseYear,
    rating = movie.rating,
    durationSeconds = durationSeconds,
)

fun SeriesCategoryEntity.toDomain(): SeriesCategory = SeriesCategory(
    id = remoteCategoryId,
    name = name,
    sortOrder = sortOrder,
)

fun SeriesEntity.toDomain(): SeriesSummary = SeriesSummary(
    id = remoteSeriesId,
    categoryId = remoteCategoryId,
    name = name,
    posterUrl = posterUrl,
    rating = rating,
    releaseYear = releaseYear,
    lastModifiedEpochSeconds = lastModifiedEpochSeconds,
    providerOrder = providerOrder,
)

fun SeriesEpisodeEntity.toDomain(): SeriesEpisode = SeriesEpisode(
    id = remoteEpisodeId,
    seriesId = remoteSeriesId,
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    title = title,
    containerExtension = containerExtension,
    durationSeconds = durationSeconds,
    plot = plot,
    stillUrl = stillUrl,
)

/**
 * Rebuilds the full series view from the tables that hold it. Listing columns stay authoritative
 * for identity and artwork, so a lazily cached detail row can never blank them.
 */
fun SeriesDetailsEntity.toDomain(
    series: SeriesEntity,
    episodes: List<SeriesEpisodeEntity>,
): SeriesDetails = SeriesDetails(
    id = series.remoteSeriesId,
    name = series.name,
    posterUrl = series.posterUrl,
    backdropUrl = backdropUrl,
    plot = plot,
    genre = genre,
    cast = cast,
    director = director,
    releaseYear = series.releaseYear,
    rating = series.rating,
    episodes = episodes.map { it.toDomain() },
)

fun WatchProgressEntity.toDomain(): WatchProgress = WatchProgress(
    contentId = contentId,
    positionMs = positionMs,
    durationMs = durationMs,
    completed = completed,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

/** [kind] comes from the query the row was read by; the projection itself is content-agnostic. */
fun ContinueWatchingProjection.toDomain(kind: ResumableKind): ContinueWatchingEntry =
    ContinueWatchingEntry(
        contentId = remoteId,
        kind = kind,
        title = name,
        posterUrl = posterUrl,
        lastWatchedAtEpochMillis = lastWatchedAtEpochMillis,
    )
