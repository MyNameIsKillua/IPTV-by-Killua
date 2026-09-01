package dev.killua.iptv.data.repository

import dev.killua.iptv.core.database.AccountDao
import dev.killua.iptv.core.database.AccountEntity
import dev.killua.iptv.core.database.ContinueWatchingProjection
import dev.killua.iptv.core.database.LikeSearchTerm
import dev.killua.iptv.core.database.LiveCategoryEntity
import dev.killua.iptv.core.database.LiveChannelEntity
import dev.killua.iptv.core.database.LiveDao
import dev.killua.iptv.core.database.MovieCategoryEntity
import dev.killua.iptv.core.database.MovieDao
import dev.killua.iptv.core.database.MovieDetailsEntity
import dev.killua.iptv.core.database.MovieEntity
import dev.killua.iptv.core.database.MovieFavoriteEntity
import dev.killua.iptv.core.database.RecentChannelEntity
import dev.killua.iptv.core.database.SeriesCategoryEntity
import dev.killua.iptv.core.database.SeriesDao
import dev.killua.iptv.core.database.SeriesDetailsEntity
import dev.killua.iptv.core.database.SeriesEntity
import dev.killua.iptv.core.database.SeriesEpisodeEntity
import dev.killua.iptv.core.database.SeriesFavoriteEntity
import dev.killua.iptv.core.database.TransactionRunner
import dev.killua.iptv.core.database.WatchProgressEntity
import dev.killua.iptv.core.security.CredentialVault
import dev.killua.iptv.domain.model.Account
import dev.killua.iptv.domain.model.AppFailure
import dev.killua.iptv.domain.model.AppFailureException
import dev.killua.iptv.domain.model.FailureKind
import dev.killua.iptv.domain.model.SessionState
import dev.killua.iptv.domain.model.XtreamCredentials
import dev.killua.iptv.domain.repository.ConnectionTestResult
import dev.killua.iptv.domain.repository.SessionRepository
import androidx.paging.PagingSource
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import dev.killua.iptv.domain.model.LibrarySource

/**
 * Deterministic in-memory doubles for the account-scoped write path. They contain no real
 * provider data: every server, user name, and password used here is fictitious.
 */

/**
 * A stand-in for SQLite's `LIKE ... ESCAPE`, good enough for the contains patterns this project
 * builds. It exists so a repository test can exercise the escaping rather than skipping straight
 * past it: an unescaped `%` must not turn a search into "match everything" here either.
 */
internal fun matchesLikePattern(value: String, pattern: String): Boolean {
    require(pattern.startsWith("%") && pattern.endsWith("%")) { "Only contains patterns" }
    val inner = pattern.substring(1, pattern.length - 1)
    val literal = buildString {
        var index = 0
        while (index < inner.length) {
            val character = inner[index]
            if (character == LikeSearchTerm.ESCAPE && index + 1 < inner.length) {
                append(inner[index + 1])
                index += 2
            } else {
                // An unescaped wildcard reaching this far is the bug the escaping prevents.
                require(character != '%' && character != '_') { "Unescaped wildcard in pattern" }
                append(character)
                index++
            }
        }
    }
    return value.contains(literal, ignoreCase = true)
}

/**
 * Session behaviour a data-layer test must not depend on. Tests delegate to this and override
 * only the members they actually exercise, so an unexpected call fails loudly.
 */
object UnusedSessionRepository : SessionRepository {
    override val state: StateFlow<SessionState> = MutableStateFlow(SessionState.Booting)

    override fun start() = Unit

    override suspend fun renameAccount(displayName: String) = Unit


    override suspend fun refreshCachedAccount() = Unit

    override suspend fun logout() = Unit

    override suspend fun testConnection(
        server: String,
        username: String,
        password: String,
    ): ConnectionTestResult = unsupported()

    override suspend fun login(
        server: String,
        username: String,
        password: String,
        displayName: String,
    ): Account = unsupported()

    override suspend fun loginWithPlaylist(url: String, displayName: String): Account =
        error("Playlists are not part of these tests")

    override suspend fun reconnect(): Account = unsupported()

    override suspend fun credentialsFor(accountId: String): XtreamCredentials = unsupported()

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("Not expected in a data-layer test")
}

/** Runs the block directly while recording that exactly one transaction is ever open. */
class RecordingTransactionRunner : TransactionRunner {
    var depth = 0
        private set
    var started = 0
        private set
    var maximumDepth = 0
        private set

    override suspend fun <T> inTransaction(block: suspend () -> T): T {
        started++
        depth++
        maximumDepth = maxOf(maximumDepth, depth)
        try {
            return block()
        } finally {
            depth--
        }
    }
}

class FakeCredentialVault(
    var accountId: String? = null,
    var serverUrl: String = "https://provider.example/",
    /** Which of the two listings this account has; see `LiveListingSource`. */
    var source: LibrarySource = LibrarySource.Xtream,
) : CredentialVault {
    /**
     * What was last written here, when something wrote.
     *
     * It used to remember only the account id and rebuild the rest from the fields above, which
     * made it useless for asking what a sign-in actually stored - a test could not tell a playlist
     * account from an Xtream one, because the answer came from the test's own default rather than
     * from the code under test.
     */
    private var saved: XtreamCredentials? = null

    override suspend fun load(): XtreamCredentials? = saved ?: accountId?.let {
        XtreamCredentials(
            accountId = it,
            serverUrl = serverUrl,
            // A playlist has neither, and the address it reads is the server URL above.
            username = if (source == LibrarySource.Playlist) "" else "demo-user",
            password = if (source == LibrarySource.Playlist) "" else "demo-pass",
            source = source,
        )
    }

    override suspend fun save(credentials: XtreamCredentials) {
        accountId = credentials.accountId
        saved = credentials
    }

    override suspend fun clear() {
        accountId = null
        saved = null
    }
}

class FakeAccountDao : AccountDao {
    override suspend fun setDisplayName(accountId: String, displayName: String?) = Unit

    val accounts = linkedMapOf<String, AccountEntity>()
    val keptAccounts = mutableListOf<String>()
    val lastLiveSyncWrites = mutableListOf<Pair<String, Long>>()
    var deletedAll = 0
        private set

    override suspend fun upsert(account: AccountEntity) {
        accounts[account.accountId] = account
    }

    override suspend fun get(accountId: String): AccountEntity? = accounts[accountId]

    override fun observe(accountId: String): Flow<AccountEntity?> = flowOf(accounts[accountId])

    override suspend fun setLastLiveSync(accountId: String, timestamp: Long) {
        lastLiveSyncWrites += accountId to timestamp
    }

    override suspend fun delete(accountId: String) {
        accounts.remove(accountId)
    }

    override suspend fun deleteAll() {
        deletedAll++
        accounts.clear()
    }

    override suspend fun deleteAllExcept(accountId: String) {
        keptAccounts += accountId
        accounts.keys.retainAll(setOf(accountId))
    }
}

/**
 * Records the writes a live refresh performs. Paging and Flow queries are not exercised by these
 * tests, so they fail loudly rather than returning misleading empty results.
 */
class FakeLiveDao : LiveDao {
    val categories = mutableListOf<LiveCategoryEntity>()
    val channels = mutableListOf<LiveChannelEntity>()
    val recents = mutableListOf<RecentChannelEntity>()
    val deletedStaleGenerations = mutableListOf<Long>()
    var clearedAllAccounts = 0
        private set
    val keptAccounts = mutableListOf<String>()

    override suspend fun upsertCategories(categories: List<LiveCategoryEntity>) {
        categories.forEach { row ->
            this.categories.removeAll {
                it.accountId == row.accountId && it.remoteCategoryId == row.remoteCategoryId
            }
            this.categories += row
        }
    }

    override suspend fun upsertChannels(channels: List<LiveChannelEntity>) {
        channels.forEach { row ->
            this.channels.removeAll {
                it.accountId == row.accountId && it.remoteStreamId == row.remoteStreamId
            }
            this.channels += row
        }
    }

    override suspend fun upsertRecent(recent: RecentChannelEntity) {
        recents.removeAll {
            it.accountId == recent.accountId && it.remoteStreamId == recent.remoteStreamId
        }
        recents += recent
    }

    override suspend fun countChannels(accountId: String): Int =
        channels.count { it.accountId == accountId }

    override suspend fun getChannel(accountId: String, streamId: String): LiveChannelEntity? =
        channels.firstOrNull { it.accountId == accountId && it.remoteStreamId == streamId }

    override suspend fun searchChannels(
        accountId: String,
        pattern: String,
        limit: Int,
    ): List<LiveChannelEntity> = channels
        .filter { it.accountId == accountId && matchesLikePattern(it.sortName, pattern) }
        .sortedBy { it.sortName }
        .take(limit)

    override suspend fun getLastWatched(accountId: String, streamId: String): Long? = recents
        .firstOrNull { it.accountId == accountId && it.remoteStreamId == streamId }
        ?.lastWatchedAtEpochMillis

    override suspend fun deleteStaleCategories(accountId: String, generation: Long) {
        deletedStaleGenerations += generation
        categories.removeAll { it.accountId == accountId && it.syncGeneration != generation }
    }

    override suspend fun deleteStaleChannels(accountId: String, generation: Long) {
        channels.removeAll { it.accountId == accountId && it.syncGeneration != generation }
    }

    override suspend fun deleteCategoriesForAccount(accountId: String) {
        categories.removeAll { it.accountId == accountId }
    }

    override suspend fun deleteChannelsForAccount(accountId: String) {
        channels.removeAll { it.accountId == accountId }
    }

    override suspend fun deleteRecentsForAccount(accountId: String) {
        recents.removeAll { it.accountId == accountId }
    }

    override suspend fun deleteAllRecents() {
        clearedAllAccounts++
        recents.clear()
    }

    override suspend fun deleteAllChannels() {
        channels.clear()
    }

    override suspend fun deleteAllCategories() {
        categories.clear()
    }

    override suspend fun deleteAllRecentsExcept(accountId: String) {
        keptAccounts += accountId
        recents.removeAll { it.accountId != accountId }
    }

    override suspend fun deleteAllChannelsExcept(accountId: String) {
        channels.removeAll { it.accountId != accountId }
    }

    override suspend fun deleteAllCategoriesExcept(accountId: String) {
        categories.removeAll { it.accountId != accountId }
    }

    override fun observeCategories(accountId: String): Flow<List<LiveCategoryEntity>> =
        unsupportedQuery()

    override fun observeLanguages(accountId: String): Flow<List<String>> = unsupportedQuery()

    override fun pageChannels(
        query: SupportSQLiteQuery,
    ): PagingSource<Int, LiveChannelEntity> = unsupportedQuery()

    override fun observeRecent(
        accountId: String,
        limit: Int,
    ): Flow<List<LiveChannelEntity>> = unsupportedQuery()

    private fun unsupportedQuery(): Nothing =
        throw UnsupportedOperationException("Browsing queries are covered by instrumented tests")
}

/**
 * Records Movie writes. Paging and Flow queries need a real SQLite database, so they are covered
 * by instrumented tests and fail loudly here instead of returning misleading empty results.
 */
class FakeMovieDao : MovieDao {
    override fun observeRecentlyAdded(accountId: String, limit: Int): Flow<List<MovieEntity>> =
        flowOf(emptyList())

    val categories = mutableListOf<MovieCategoryEntity>()
    val movies = mutableListOf<MovieEntity>()
    val details = mutableListOf<MovieDetailsEntity>()
    val favorites = mutableListOf<MovieFavoriteEntity>()
    val progress = mutableListOf<WatchProgressEntity>()
    var clearedAllAccounts = 0
        private set
    val keptAccounts = mutableListOf<String>()

    override suspend fun upsertCategories(categories: List<MovieCategoryEntity>) {
        categories.forEach { row ->
            this.categories.removeAll {
                it.accountId == row.accountId && it.remoteCategoryId == row.remoteCategoryId
            }
            this.categories += row
        }
    }

    override suspend fun upsertMovies(movies: List<MovieEntity>) {
        movies.forEach { row ->
            this.movies.removeAll {
                it.accountId == row.accountId && it.remoteStreamId == row.remoteStreamId
            }
            this.movies += row
        }
    }

    override suspend fun upsertDetails(details: MovieDetailsEntity) {
        this.details.removeAll {
            it.accountId == details.accountId && it.remoteStreamId == details.remoteStreamId
        }
        this.details += details
    }

    override suspend fun upsertFavorite(favorite: MovieFavoriteEntity) {
        favorites.removeAll {
            it.accountId == favorite.accountId && it.remoteStreamId == favorite.remoteStreamId
        }
        favorites += favorite
    }

    override suspend fun upsertProgress(progress: WatchProgressEntity) {
        this.progress.removeAll {
            it.accountId == progress.accountId &&
                it.contentType == progress.contentType &&
                it.contentId == progress.contentId
        }
        this.progress += progress
    }

    override suspend fun deleteProgress(
        accountId: String,
        contentType: String,
        contentId: String,
    ) {
        progress.removeAll {
            it.accountId == accountId &&
                it.contentType == contentType &&
                it.contentId == contentId
        }
    }

    override suspend fun countMovies(accountId: String): Int =
        movies.count { it.accountId == accountId }

    override suspend fun getMovie(accountId: String, movieId: String): MovieEntity? =
        movies.firstOrNull { it.accountId == accountId && it.remoteStreamId == movieId }

    override suspend fun searchMovies(
        accountId: String,
        pattern: String,
        limit: Int,
    ): List<MovieEntity> = movies
        .filter { it.accountId == accountId && matchesLikePattern(it.sortName, pattern) }
        .sortedBy { it.sortName }
        .take(limit)

    override suspend fun getDetails(accountId: String, movieId: String): MovieDetailsEntity? =
        details.firstOrNull { it.accountId == accountId && it.remoteStreamId == movieId }

    override suspend fun deleteFavorite(accountId: String, movieId: String) {
        favorites.removeAll { it.accountId == accountId && it.remoteStreamId == movieId }
    }

    override suspend fun getProgress(
        accountId: String,
        contentType: String,
        contentId: String,
    ): WatchProgressEntity? = progress.firstOrNull {
        it.accountId == accountId && it.contentType == contentType && it.contentId == contentId
    }

    override fun observeProgress(
        accountId: String,
        contentType: String,
        contentId: String,
    ): Flow<WatchProgressEntity?> = unsupportedQuery()

    override suspend fun getProgressFor(
        accountId: String,
        contentType: String,
        contentIds: List<String>,
    ): List<WatchProgressEntity> = progress.filter {
        it.accountId == accountId && it.contentType == contentType && it.contentId in contentIds
    }

    override suspend fun deleteStaleMovies(accountId: String, generation: Long) {
        movies.removeAll { it.accountId == accountId && it.syncGeneration != generation }
    }

    override suspend fun deleteStaleCategories(accountId: String, generation: Long) {
        categories.removeAll { it.accountId == accountId && it.syncGeneration != generation }
    }

    override suspend fun deleteAllMovies() {
        clearedAllAccounts++
        movies.clear()
    }

    override suspend fun deleteAllCategories() {
        categories.clear()
    }

    override suspend fun deleteAllDetails() {
        details.clear()
    }

    override suspend fun deleteAllFavorites() {
        favorites.clear()
    }

    override suspend fun deleteAllProgress() {
        progress.clear()
    }

    override suspend fun deleteAllMoviesExcept(accountId: String) {
        keptAccounts += accountId
        movies.removeAll { it.accountId != accountId }
    }

    override suspend fun deleteAllCategoriesExcept(accountId: String) {
        categories.removeAll { it.accountId != accountId }
    }

    override suspend fun deleteAllDetailsExcept(accountId: String) {
        details.removeAll { it.accountId != accountId }
    }

    override suspend fun deleteAllFavoritesExcept(accountId: String) {
        favorites.removeAll { it.accountId != accountId }
    }

    override suspend fun deleteAllProgressExcept(accountId: String) {
        progress.removeAll { it.accountId != accountId }
    }

    override fun observeCategories(accountId: String): Flow<List<MovieCategoryEntity>> =
        unsupportedQuery()

    override fun observeLanguages(accountId: String): Flow<List<String>> = unsupportedQuery()

    override fun pageMovies(query: SupportSQLiteQuery): PagingSource<Int, MovieEntity> =
        unsupportedQuery()

    override fun observeIsFavorite(accountId: String, movieId: String): Flow<Boolean> =
        unsupportedQuery()

    override fun observeContinueWatching(
        accountId: String,
        contentType: String,
        limit: Int,
    ): Flow<List<ContinueWatchingProjection>> = unsupportedQuery()

    private fun unsupportedQuery(): Nothing =
        throw UnsupportedOperationException("Browsing queries are covered by instrumented tests")
}

/**
 * Records Series writes. Paging and Flow queries need a real SQLite database, so they are covered
 * by instrumented tests and fail loudly here instead of returning misleading empty results.
 */
class FakeSeriesDao : SeriesDao {
    override fun observeRecentlyAdded(accountId: String, limit: Int): Flow<List<SeriesEntity>> =
        flowOf(emptyList())

    val categories = mutableListOf<SeriesCategoryEntity>()
    val series = mutableListOf<SeriesEntity>()
    val details = mutableListOf<SeriesDetailsEntity>()
    val episodes = mutableListOf<SeriesEpisodeEntity>()
    val progress = mutableListOf<WatchProgressEntity>()
    val favorites = mutableListOf<SeriesFavoriteEntity>()
    var clearedAllAccounts = 0
        private set
    val keptAccounts = mutableListOf<String>()

    override suspend fun upsertCategories(categories: List<SeriesCategoryEntity>) {
        categories.forEach { row ->
            this.categories.removeAll {
                it.accountId == row.accountId && it.remoteCategoryId == row.remoteCategoryId
            }
            this.categories += row
        }
    }

    override suspend fun upsertSeries(series: List<SeriesEntity>) {
        series.forEach { row ->
            this.series.removeAll {
                it.accountId == row.accountId && it.remoteSeriesId == row.remoteSeriesId
            }
            this.series += row
        }
    }

    override suspend fun upsertDetails(details: SeriesDetailsEntity) {
        this.details.removeAll {
            it.accountId == details.accountId && it.remoteSeriesId == details.remoteSeriesId
        }
        this.details += details
    }

    override suspend fun upsertEpisodes(episodes: List<SeriesEpisodeEntity>) {
        episodes.forEach { row ->
            this.episodes.removeAll {
                it.accountId == row.accountId && it.remoteEpisodeId == row.remoteEpisodeId
            }
            this.episodes += row
        }
    }

    override suspend fun upsertProgress(progress: WatchProgressEntity) {
        this.progress.removeAll {
            it.accountId == progress.accountId &&
                it.contentType == progress.contentType &&
                it.contentId == progress.contentId
        }
        this.progress += progress
    }

    override suspend fun deleteProgress(
        accountId: String,
        contentType: String,
        contentId: String,
    ) {
        progress.removeAll {
            it.accountId == accountId &&
                it.contentType == contentType &&
                it.contentId == contentId
        }
    }

    override suspend fun getEpisodeProgress(
        accountId: String,
        contentType: String,
        episodeId: String,
    ): WatchProgressEntity? = progress.firstOrNull {
        it.accountId == accountId && it.contentType == contentType && it.contentId == episodeId
    }

    override fun observeProgressForSeries(
        accountId: String,
        contentType: String,
        seriesId: String,
    ): Flow<List<WatchProgressEntity>> = unsupportedQuery()

    override suspend fun upsertFavorite(favorite: SeriesFavoriteEntity) {
        favorites.removeAll {
            it.accountId == favorite.accountId && it.remoteSeriesId == favorite.remoteSeriesId
        }
        favorites += favorite
    }

    override suspend fun deleteFavorite(accountId: String, seriesId: String) {
        favorites.removeAll { it.accountId == accountId && it.remoteSeriesId == seriesId }
    }

    override fun observeIsFavorite(accountId: String, seriesId: String): Flow<Boolean> =
        unsupportedQuery()

    override fun observeContinueWatching(
        accountId: String,
        contentType: String,
        limit: Int,
    ): Flow<List<ContinueWatchingProjection>> = unsupportedQuery()

    override suspend fun countSeries(accountId: String): Int =
        series.count { it.accountId == accountId }

    override suspend fun getSeries(accountId: String, seriesId: String): SeriesEntity? =
        series.firstOrNull { it.accountId == accountId && it.remoteSeriesId == seriesId }

    override suspend fun searchSeries(
        accountId: String,
        pattern: String,
        limit: Int,
    ): List<SeriesEntity> = series
        .filter { it.accountId == accountId && matchesLikePattern(it.sortName, pattern) }
        .sortedBy { it.sortName }
        .take(limit)

    override suspend fun getDetails(accountId: String, seriesId: String): SeriesDetailsEntity? =
        details.firstOrNull { it.accountId == accountId && it.remoteSeriesId == seriesId }

    override suspend fun getEpisodes(
        accountId: String,
        seriesId: String,
    ): List<SeriesEpisodeEntity> = episodes
        .filter { it.accountId == accountId && it.remoteSeriesId == seriesId }
        .sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber ?: Int.MAX_VALUE }))

    override suspend fun getEpisode(accountId: String, episodeId: String): SeriesEpisodeEntity? =
        episodes.firstOrNull { it.accountId == accountId && it.remoteEpisodeId == episodeId }

    override suspend fun deleteEpisodesForSeries(accountId: String, seriesId: String) {
        episodes.removeAll { it.accountId == accountId && it.remoteSeriesId == seriesId }
    }

    override suspend fun deleteStaleSeries(accountId: String, generation: Long) {
        series.removeAll { it.accountId == accountId && it.syncGeneration != generation }
    }

    override suspend fun deleteStaleCategories(accountId: String, generation: Long) {
        categories.removeAll { it.accountId == accountId && it.syncGeneration != generation }
    }

    override suspend fun deleteAllSeries() {
        clearedAllAccounts++
        series.clear()
    }

    override suspend fun deleteAllCategories() {
        categories.clear()
    }

    override suspend fun deleteAllDetails() {
        details.clear()
    }

    override suspend fun deleteAllEpisodes() {
        episodes.clear()
    }

    override suspend fun deleteAllFavorites() {
        favorites.clear()
    }

    override suspend fun deleteAllSeriesExcept(accountId: String) {
        keptAccounts += accountId
        series.removeAll { it.accountId != accountId }
    }

    override suspend fun deleteAllCategoriesExcept(accountId: String) {
        categories.removeAll { it.accountId != accountId }
    }

    override suspend fun deleteAllDetailsExcept(accountId: String) {
        details.removeAll { it.accountId != accountId }
    }

    override suspend fun deleteAllEpisodesExcept(accountId: String) {
        episodes.removeAll { it.accountId != accountId }
    }

    override suspend fun deleteAllFavoritesExcept(accountId: String) {
        favorites.removeAll { it.accountId != accountId }
    }

    override fun observeCategories(accountId: String): Flow<List<SeriesCategoryEntity>> =
        unsupportedQuery()

    override fun observeLanguages(accountId: String): Flow<List<String>> = unsupportedQuery()

    override fun pageSeries(query: SupportSQLiteQuery): PagingSource<Int, SeriesEntity> =
        unsupportedQuery()

    private fun unsupportedQuery(): Nothing =
        throw UnsupportedOperationException("Browsing queries are covered by instrumented tests")
}

/**
 * A session that hands out the vault's current credentials, and lets a test change the vault at
 * the exact moment they are issued — which is how a logout or account swap mid-download is
 * simulated deterministically.
 */
class FakeSessionRepository(
    private val vault: FakeCredentialVault,
) : SessionRepository by UnusedSessionRepository {
    var onCredentialsIssued: (() -> Unit)? = null

    override suspend fun credentialsFor(accountId: String) = vault.load()
        ?.takeIf { it.accountId == accountId }
        ?.also { onCredentialsIssued?.invoke() }
        ?: throw AppFailureException(AppFailure(FailureKind.AuthenticationFailed))
}
