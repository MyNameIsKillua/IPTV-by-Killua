package dev.killua.iptv.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class Account(
    val id: String,
    val username: String,
    /** What the viewer named this playlist, or null when they named it nothing. */
    val displayName: String? = null,
    val serverUrl: String,
    val status: AccountStatus,
    val expiresAtEpochSeconds: Long?,
    val activeConnections: Int?,
    val maximumConnections: Int?,
    val serverTimezone: String?,
    val allowedOutputFormats: Set<String>,
    val lastValidatedAtEpochMillis: Long,
    val lastLiveSyncAtEpochMillis: Long? = null,
) {
    /** What to put on screen for this account: the viewer's own name for it, else the provider's. */
    val label: String get() = displayName?.takeIf { it.isNotBlank() } ?: username
}

enum class AccountStatus {
    Active,
    Expired,
    Disabled,
    Unknown,
}

@Immutable
data class LiveCategory(
    val id: String,
    val name: String,
    val sortOrder: Int,
)

@Immutable
data class LiveChannel(
    val id: String,
    val categoryId: String?,
    val name: String,
    val logoUrl: String?,
    val epgChannelId: String?,
    val containerExtension: String?,
    val directSource: String?,
    val providerOrder: Int,
    val lastWatchedAtEpochMillis: Long? = null,
)

@Immutable
data class MovieCategory(
    val id: String,
    val name: String,
    val sortOrder: Int,
)

/**
 * The browsing-sized Movie record returned by a provider's VOD listing. Every descriptive field
 * is optional because Xtream providers populate them inconsistently.
 */
@Immutable
data class MovieSummary(
    val id: String,
    val categoryId: String?,
    val name: String,
    val posterUrl: String?,
    val containerExtension: String?,
    val rating: Double?,
    val releaseYear: Int?,
    val addedAtEpochSeconds: Long?,
    val providerOrder: Int,
)

/** The richer per-title record fetched lazily, so a listing refresh cannot overwrite it with nulls. */
@Immutable
data class MovieDetails(
    val id: String,
    val name: String,
    val categoryId: String?,
    val containerExtension: String?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val plot: String?,
    val genre: String?,
    val cast: String?,
    val director: String?,
    val releaseYear: Int?,
    val rating: Double?,
    val durationSeconds: Int?,
)

/**
 * Browsing order for the Movie library.
 *
 * Every option is backed by a column the provider listing actually supplies, so all of them work
 * across the whole cached library rather than only over titles whose details were fetched.
 * Missing values always sort last instead of masquerading as zero.
 */
enum class MovieSortOrder {
    ProviderDefault,
    NameAscending,
    RatingDescending,
    ReleaseYearDescending,
    RecentlyAdded,
}

/**
 * A Movie browsing selection.
 *
 * [categoryId] doubles as the genre filter: Xtream exposes a genre only through per-title
 * `get_vod_info`, while provider categories are in practice genre names, so filtering by category
 * is both cheaper and more complete than filtering on lazily cached detail genres.
 *
 * [languageTag] filters on a heuristic language derived from category and title text, because the
 * Xtream listing has no language field at all. Titles whose language cannot be detected are never
 * matched by a language filter.
 */
@Immutable
data class MovieFilter(
    val categoryId: String? = null,
    val languageTag: String? = null,
    val favoritesOnly: Boolean = false,
    val inProgressOnly: Boolean = false,
    val searchQuery: String? = null,
    val sortOrder: MovieSortOrder = MovieSortOrder.ProviderDefault,
)

@Immutable
data class SeriesCategory(
    val id: String,
    val name: String,
    val sortOrder: Int,
)

/**
 * The browsing-sized Series record from a provider's `get_series` listing. As with Movies, every
 * descriptive field is optional because providers populate them inconsistently.
 */
@Immutable
data class SeriesSummary(
    val id: String,
    val categoryId: String?,
    val name: String,
    val posterUrl: String?,
    val rating: Double?,
    val releaseYear: Int?,
    /** When the provider last changed the series, which is how "recently updated" is ordered. */
    val lastModifiedEpochSeconds: Long?,
    val providerOrder: Int,
)

/** The richer per-series record from `get_series_info`, including every episode it lists. */
@Immutable
data class SeriesDetails(
    val id: String,
    val name: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val plot: String?,
    val genre: String?,
    val cast: String?,
    val director: String?,
    val releaseYear: Int?,
    val rating: Double?,
    val episodes: List<SeriesEpisode>,
)

/**
 * One episode of a series.
 *
 * [id] is the provider's own episode identifier and is the only thing playback and watch progress
 * may key on. Season and episode numbers are display values: providers repeat, renumber, and
 * occasionally omit them, so deriving identity from them would silently mix up episodes.
 */
@Immutable
data class SeriesEpisode(
    val id: String,
    /** Which series this belongs to, which is how the player finds the episode after it. */
    val seriesId: String,
    val seasonNumber: Int,
    val episodeNumber: Int?,
    val title: String,
    val containerExtension: String?,
    val durationSeconds: Int?,
    val plot: String?,
    val stillUrl: String?,
)

/**
 * "S1 E2 · Titel", falling back to the bare title where the provider omitted the numbering.
 *
 * Shared so the episode list, the player screen, and the playback notification cannot disagree
 * about what is playing.
 */
val SeriesEpisode.displayLabel: String
    get() = episodeNumber?.let { "S$seasonNumber E$it · $title" } ?: title

/**
 * Browsing order for the Series library.
 *
 * [RecentlyUpdated] uses the provider's own last-modified stamp, which is the closest thing a
 * listing offers to "a new episode arrived"; the listing carries no episode counts.
 */
enum class SeriesSortOrder {
    ProviderDefault,
    NameAscending,
    RatingDescending,
    ReleaseYearDescending,
    RecentlyUpdated,
}

/**
 * A Series browsing selection. [categoryId] doubles as the genre filter for the same reason it
 * does for Movies: the listing carries no genre, and provider categories are genre names.
 */
@Immutable
data class SeriesFilter(
    val categoryId: String? = null,
    val languageTag: String? = null,
    val favoritesOnly: Boolean = false,
    /** A series counts as in progress when any of its episodes is unfinished. */
    val inProgressOnly: Boolean = false,
    val searchQuery: String? = null,
    val sortOrder: SeriesSortOrder = SeriesSortOrder.ProviderDefault,
)

/** Resumable position for any content type: a Movie or an episode. */
@Immutable
data class WatchProgress(
    val contentId: String,
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val updatedAtEpochMillis: Long,
)

/**
 * One library's slice of a global search result.
 *
 * [hasMore] comes from reading one row beyond the requested limit rather than from a second
 * COUNT over a six-figure table, so it says "there are more" without saying how many.
 */
@Immutable
data class SearchSection<T>(
    val items: List<T> = emptyList(),
    val hasMore: Boolean = false,
) {
    val isEmpty: Boolean get() = items.isEmpty()
}

/**
 * One programme in a channel's guide.
 *
 * Times are epoch seconds taken from the provider's own `*_timestamp` fields rather than its
 * formatted strings. Those strings are in the provider's local time with no offset attached, so
 * reading them would need the account's timezone to be right and would still be ambiguous twice a
 * year; an entry without usable timestamps is dropped instead of being placed at a guessed time.
 */
@Immutable
data class EpgEntry(
    val title: String,
    val description: String?,
    val startEpochSeconds: Long,
    val endEpochSeconds: Long,
)

/** Which library an entry belongs to, so a mixed row knows which screen to open. */
enum class ResumableKind { Movie, Series }

/**
 * One newly added title, from either VOD library.
 *
 * [addedAtEpochSeconds] is the provider's own timestamp. It is carried here rather than being
 * consumed at the query, because whether these timestamps mean anything at all can only be judged
 * by looking at several of them together.
 */
@Immutable
data class RecentlyAddedEntry(
    val contentId: String,
    val kind: ResumableKind,
    val title: String,
    val posterUrl: String?,
    val addedAtEpochSeconds: Long,
)

/**
 * One unfinished title in a Continue Watching row.
 *
 * It carries [lastWatchedAtEpochMillis] because Home merges Movies and Series into a single row,
 * and two separately ordered lists cannot be interleaved honestly without it.
 */
@Immutable
data class ContinueWatchingEntry(
    val contentId: String,
    val kind: ResumableKind,
    val title: String,
    val posterUrl: String?,
    val lastWatchedAtEpochMillis: Long,
)

/**
 * Which library a saved entry belongs to.
 *
 * Separate from [ResumableKind] because this one includes channels, which are not resumable: a
 * shared enum would put a case into the playback code that can never occur there.
 */
enum class WatchlistKind { Movie, Series, Channel }

/** One saved thing, from any of the three libraries, newest first. */
@Immutable
data class WatchlistEntry(
    val contentId: String,
    val kind: WatchlistKind,
    val title: String,
    val artworkUrl: String?,
    val addedAtEpochMillis: Long,
)

sealed interface CategorySelection {
    data object All : CategorySelection
    data object Recent : CategorySelection
    data object Uncategorized : CategorySelection
    data class Provider(val id: String) : CategorySelection
}

/**
 * Browsing order for the live library.
 *
 * The provider listing carries no rating, year, or added timestamp for channels, so the honest
 * options are the provider's own order and the channel name. Both are backed by an index.
 *
 * [ProviderDefault] keeps each selection's natural order: recency under [CategorySelection.Recent]
 * and the provider's own order everywhere else.
 */
enum class LiveSortOrder {
    ProviderDefault,
    NameAscending,
    NameDescending,
}

/**
 * A live browsing selection.
 *
 * [searchQuery] matches channel titles. It is a contains match rather than a prefix match because
 * providers routinely prefix a channel with a country or quality tag, so `RTL` has to find
 * `DE | RTL HD`.
 *
 * [languageTag] filters on a heuristic language taken from the channel's category name, falling
 * back to a tag on the channel name itself, because the Xtream listing has no language field.
 * Channels whose language cannot be detected are never matched by a language filter.
 */
@Immutable
data class LiveFilter(
    val selection: CategorySelection = CategorySelection.All,
    val searchQuery: String? = null,
    val languageTag: String? = null,
    val sortOrder: LiveSortOrder = LiveSortOrder.ProviderDefault,
)

enum class ThemeMode {
    Dark,
    Light,
    System,
}

/**
 * How the picture fills the player.
 *
 * A provider's stream rarely matches the screen. [Fit] is honest but letterboxes; [Zoom] fills the
 * screen and loses the edges; [Fill] fills it without losing anything and distorts instead. Which
 * trade is acceptable is the viewer's call, not the app's, so all three are offered and the choice
 * is remembered.
 */
enum class VideoScaleMode {
    Fit,
    Zoom,
    Fill,
    ;

    /** The next mode, for a control that cycles rather than opening a menu. */
    fun next(): VideoScaleMode = entries[(ordinal + 1) % entries.size]

    /** Named for the on-screen cue and the accessibility description. */
    val label: String
        get() = when (this) {
            Fit -> "Fit"
            Zoom -> "Zoom"
            Fill -> "Stretch"
        }
}

class XtreamCredentials(
    val accountId: String,
    val serverUrl: String,
    val username: String,
    val password: String,
) {
    override fun toString(): String = "XtreamCredentials(REDACTED)"
}

data class RemoteAccount(
    val username: String?,
    val status: AccountStatus,
    val expiresAtEpochSeconds: Long?,
    val activeConnections: Int?,
    val maximumConnections: Int?,
    val serverTimezone: String?,
    val allowedOutputFormats: Set<String>,
)

data class RemoteLiveLibrary(
    val categories: List<LiveCategory>,
    val channels: List<LiveChannel>,
)
