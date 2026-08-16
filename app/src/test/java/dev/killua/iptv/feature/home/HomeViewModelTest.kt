package dev.killua.iptv.feature.home

import androidx.paging.PagingData
import com.google.common.truth.Truth.assertThat
import dev.killua.iptv.domain.model.Account
import dev.killua.iptv.domain.model.AccountStatus
import dev.killua.iptv.domain.model.ContinueWatchingEntry
import dev.killua.iptv.domain.model.EpgEntry
import dev.killua.iptv.domain.model.LiveCategory
import dev.killua.iptv.domain.model.LiveChannel
import dev.killua.iptv.domain.model.LiveFilter
import dev.killua.iptv.domain.model.MovieCategory
import dev.killua.iptv.domain.model.MovieDetails
import dev.killua.iptv.domain.model.MovieFilter
import dev.killua.iptv.domain.model.MovieSummary
import dev.killua.iptv.domain.model.ResumableKind
import dev.killua.iptv.domain.model.RecentlyAddedEntry
import dev.killua.iptv.domain.model.WatchlistKind
import dev.killua.iptv.domain.model.SeriesCategory
import dev.killua.iptv.domain.model.SeriesDetails
import dev.killua.iptv.domain.model.SeriesEpisode
import dev.killua.iptv.domain.model.SeriesFilter
import dev.killua.iptv.domain.model.SeriesSummary
import dev.killua.iptv.domain.model.WatchProgress
import dev.killua.iptv.domain.model.SearchSection
import dev.killua.iptv.domain.repository.LiveRepository
import dev.killua.iptv.domain.repository.LiveSyncResult
import dev.killua.iptv.domain.repository.MovieRepository
import dev.killua.iptv.domain.repository.MovieSyncResult
import dev.killua.iptv.domain.repository.FakeWatchlistRepository
import dev.killua.iptv.domain.repository.SeriesRepository
import dev.killua.iptv.domain.repository.SeriesSyncResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Home merges two Continue Watching sources into one row. All fixtures are fictitious.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val live = FakeLiveRepository()
    private val movies = FakeMovieRepository()
    private val series = FakeSeriesRepository()
    private val watchlist = FakeWatchlistRepository()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `both libraries appear in one row ordered by when they were last watched`() = runTest {
        movies.entries.value = listOf(
            entry("501", ResumableKind.Movie, lastWatched = 3_000L),
            entry("502", ResumableKind.Movie, lastWatched = 1_000L),
        )
        series.entries.value = listOf(
            entry("7", ResumableKind.Series, lastWatched = 4_000L),
            entry("8", ResumableKind.Series, lastWatched = 2_000L),
        )

        val viewModel = createViewModel()
        val collected = collectContinueWatching(viewModel)

        // Interleaved by recency, not one library after the other.
        assertThat(collected.map { it.contentId })
            .containsExactly("7", "501", "8", "502")
            .inOrder()
        assertThat(collected.map { it.kind }).containsExactly(
            ResumableKind.Series,
            ResumableKind.Movie,
            ResumableKind.Series,
            ResumableKind.Movie,
        ).inOrder()
    }

    @Test
    fun `the merged row is trimmed after sorting, not before`() = runTest {
        // Twelve older movies, then one episode watched moments ago. Taking the movie row first
        // and appending would push the newest entry out of a twelve-slot row entirely.
        movies.entries.value = (1..12).map {
            entry("movie-$it", ResumableKind.Movie, lastWatched = it.toLong())
        }
        series.entries.value = listOf(entry("7", ResumableKind.Series, lastWatched = 9_999L))

        val viewModel = createViewModel()
        val collected = collectContinueWatching(viewModel)

        assertThat(collected).hasSize(12)
        assertThat(collected.first().contentId).isEqualTo("7")
    }

    @Test
    fun `an empty series library leaves the movie row intact`() = runTest {
        movies.entries.value = listOf(entry("501", ResumableKind.Movie, lastWatched = 1_000L))

        val viewModel = createViewModel()
        val collected = collectContinueWatching(viewModel)

        assertThat(collected.map { it.contentId }).containsExactly("501")
    }

    @Test
    fun `the saved list is surfaced newest first and bounded like the other rows`() = runTest {
        watchlist.titles[WatchlistKind.Movie to "501"] = "Ein Film"
        watchlist.titles[WatchlistKind.Series to "7"] = "Eine Serie"
        watchlist.titles[WatchlistKind.Channel to "3"] = "Ein Kanal"
        watchlist.setSaved(ACCOUNT.id, WatchlistKind.Movie, "501", true)
        watchlist.setSaved(ACCOUNT.id, WatchlistKind.Series, "7", true)
        watchlist.setSaved(ACCOUNT.id, WatchlistKind.Channel, "3", true)

        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.watchlist.collect { } }
        testScheduler.advanceUntilIdle()

        // All three libraries in one row, most recently saved first.
        assertThat(viewModel.watchlist.value.map { it.contentId })
            .containsExactly("3", "7", "501").inOrder()
        assertThat(watchlist.observedLimit).isEqualTo(12)
    }

    @Test
    fun `removing something from the saved list takes it out of the row`() = runTest {
        watchlist.titles[WatchlistKind.Movie to "501"] = "Ein Film"
        watchlist.setSaved(ACCOUNT.id, WatchlistKind.Movie, "501", true)
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.watchlist.collect { } }
        testScheduler.advanceUntilIdle()

        watchlist.setSaved(ACCOUNT.id, WatchlistKind.Movie, "501", false)
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.watchlist.value).isEmpty()
    }

    @Test
    fun `a saved title the provider has dropped is left out rather than shown blank`() = runTest {
        // No entry in `titles`, which is what the real query's join produces for a vanished title.
        watchlist.setSaved(ACCOUNT.id, WatchlistKind.Movie, "gone", true)
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.watchlist.collect { } }
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.watchlist.value).isEmpty()
    }

    private fun TestScope.collectContinueWatching(
        viewModel: HomeViewModel,
    ): List<ContinueWatchingEntry> {
        // The flow is shared only while subscribed, so it has to be collected to produce a value.
        backgroundScope.launch { viewModel.continueWatching.collect { } }
        testScheduler.advanceUntilIdle()
        return viewModel.continueWatching.value
    }

    private fun createViewModel() = HomeViewModel(ACCOUNT, live, movies, series, watchlist)

    private fun entry(id: String, kind: ResumableKind, lastWatched: Long) = ContinueWatchingEntry(
        contentId = id,
        kind = kind,
        title = "Titel $id",
        posterUrl = null,
        lastWatchedAtEpochMillis = lastWatched,
    )

    private class FakeLiveRepository : LiveRepository {
        override suspend fun epg(accountId: String, streamId: String): List<EpgEntry> = emptyList()
        override suspend fun search(
            accountId: String,
            term: String,
            limit: Int,
        ): SearchSection<LiveChannel> = SearchSection()
        override fun observeRecent(accountId: String, limit: Int) = flowOf(emptyList<LiveChannel>())
        override fun observeCategories(accountId: String) = flowOf(emptyList<LiveCategory>())
        override fun observeLanguages(accountId: String) = flowOf(emptyList<String>())
        override fun channels(
            accountId: String,
            filter: LiveFilter,
        ): Flow<PagingData<LiveChannel>> = flowOf(PagingData.empty())

        override suspend fun getChannel(accountId: String, streamId: String): LiveChannel? = null
        override suspend fun markRecent(accountId: String, streamId: String) = Unit
        override suspend fun hasCachedLibrary(accountId: String) = true
        override suspend fun refresh(
            accountId: String,
            onProgress: (Int) -> Unit,
        ): LiveSyncResult = LiveSyncResult(0, 0, 0L)
    }

    private class FakeMovieRepository : MovieRepository {
        override fun observeRecentlyAdded(
            accountId: String,
            limit: Int,
        ): Flow<List<RecentlyAddedEntry>> = flowOf(emptyList())

        override suspend fun setWatched(
            accountId: String,
            movieId: String,
            watched: Boolean,
        ) = Unit
        override suspend fun search(
            accountId: String,
            term: String,
            limit: Int,
        ): SearchSection<MovieSummary> = SearchSection()
        val entries = MutableStateFlow<List<ContinueWatchingEntry>>(emptyList())

        override fun observeContinueWatching(accountId: String, limit: Int) = entries

        override fun observeCategories(accountId: String) = flowOf(emptyList<MovieCategory>())
        override fun observeLanguages(accountId: String) = flowOf(emptyList<String>())
        override fun movies(
            accountId: String,
            filter: MovieFilter,
        ): Flow<PagingData<MovieSummary>> = flowOf(PagingData.empty())

        override fun observeIsFavorite(accountId: String, movieId: String) = flowOf(false)
        override suspend fun getMovie(accountId: String, movieId: String): MovieSummary? = null
        override suspend fun details(
            accountId: String,
            movieId: String,
            forceRefresh: Boolean,
        ): MovieDetails = throw UnsupportedOperationException()

        override suspend fun setFavorite(
            accountId: String,
            movieId: String,
            favorite: Boolean,
        ) = Unit

        override suspend fun hasCachedLibrary(accountId: String) = true
        override suspend fun refresh(
            accountId: String,
            onProgress: (Int) -> Unit,
        ): MovieSyncResult = MovieSyncResult(0, 0, 0L)

        override suspend fun progress(accountId: String, movieId: String): WatchProgress? = null
        override fun observeProgress(accountId: String, movieId: String): Flow<WatchProgress?> =
            flowOf(null)

        override suspend fun saveProgress(
            accountId: String,
            movieId: String,
            positionMs: Long,
            durationMs: Long,
        ) = Unit
    }

    private class FakeSeriesRepository : SeriesRepository {
        override fun observeRecentlyAdded(
            accountId: String,
            limit: Int,
        ): Flow<List<RecentlyAddedEntry>> = flowOf(emptyList())

        override suspend fun previousEpisode(accountId: String, episodeId: String): SeriesEpisode? =
            null
        override suspend fun setEpisodeWatched(
            accountId: String,
            episodeId: String,
            watched: Boolean,
        ) = Unit
        override suspend fun nextEpisode(accountId: String, episodeId: String): SeriesEpisode? =
            null
        override suspend fun search(
            accountId: String,
            term: String,
            limit: Int,
        ): SearchSection<SeriesSummary> = SearchSection()
        val entries = MutableStateFlow<List<ContinueWatchingEntry>>(emptyList())

        override fun observeContinueWatching(accountId: String, limit: Int) = entries

        override fun observeCategories(accountId: String) = flowOf(emptyList<SeriesCategory>())
        override fun observeLanguages(accountId: String) = flowOf(emptyList<String>())
        override fun series(
            accountId: String,
            filter: SeriesFilter,
        ): Flow<PagingData<SeriesSummary>> = flowOf(PagingData.empty())

        override fun observeIsFavorite(accountId: String, seriesId: String) = flowOf(false)
        override suspend fun setFavorite(
            accountId: String,
            seriesId: String,
            favorite: Boolean,
        ) = Unit

        override suspend fun getSeries(accountId: String, seriesId: String): SeriesSummary? = null
        override suspend fun details(
            accountId: String,
            seriesId: String,
            forceRefresh: Boolean,
        ): SeriesDetails = throw UnsupportedOperationException()

        override suspend fun getEpisode(accountId: String, episodeId: String): SeriesEpisode? = null
        override suspend fun hasCachedLibrary(accountId: String) = true
        override suspend fun refresh(
            accountId: String,
            onProgress: (Int) -> Unit,
        ): SeriesSyncResult = SeriesSyncResult(0, 0, 0L)

        override suspend fun episodeProgress(
            accountId: String,
            episodeId: String,
        ): WatchProgress? = null

        override fun observeEpisodeProgress(
            accountId: String,
            seriesId: String,
        ): Flow<Map<String, WatchProgress>> = flowOf(emptyMap())

        override suspend fun saveEpisodeProgress(
            accountId: String,
            episodeId: String,
            positionMs: Long,
            durationMs: Long,
        ) = Unit
    }

    private companion object {
        val ACCOUNT = Account(
            id = "account-under-test",
            username = "demo-user",
            serverUrl = "https://provider.example/",
            status = AccountStatus.Active,
            expiresAtEpochSeconds = null,
            activeConnections = null,
            maximumConnections = null,
            serverTimezone = null,
            allowedOutputFormats = setOf("m3u8"),
            lastValidatedAtEpochMillis = 0L,
        )
    }
}
