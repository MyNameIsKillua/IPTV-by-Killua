package dev.killua.iptv.feature.search

import androidx.paging.PagingData
import com.google.common.truth.Truth.assertThat
import dev.killua.iptv.domain.model.Account
import dev.killua.iptv.domain.model.RecentlyAddedEntry
import dev.killua.iptv.domain.model.AccountStatus
import dev.killua.iptv.domain.model.AppFailure
import dev.killua.iptv.domain.model.AppFailureException
import dev.killua.iptv.domain.model.ContinueWatchingEntry
import dev.killua.iptv.domain.model.FailureKind
import dev.killua.iptv.domain.model.EpgEntry
import dev.killua.iptv.domain.model.LiveCategory
import dev.killua.iptv.domain.model.LiveChannel
import dev.killua.iptv.domain.model.LiveFilter
import dev.killua.iptv.domain.model.MovieCategory
import dev.killua.iptv.domain.model.MovieDetails
import dev.killua.iptv.domain.model.MovieFilter
import dev.killua.iptv.domain.model.MovieSummary
import dev.killua.iptv.domain.model.SearchSection
import dev.killua.iptv.domain.model.SeriesCategory
import dev.killua.iptv.domain.model.SeriesDetails
import dev.killua.iptv.domain.model.SeriesEpisode
import dev.killua.iptv.domain.model.SeriesFilter
import dev.killua.iptv.domain.model.SeriesSummary
import dev.killua.iptv.domain.model.WatchProgress
import dev.killua.iptv.domain.repository.LiveRepository
import dev.killua.iptv.domain.repository.LiveSyncResult
import dev.killua.iptv.domain.repository.MovieRepository
import dev.killua.iptv.domain.repository.MovieSyncResult
import dev.killua.iptv.domain.repository.SeriesRepository
import dev.killua.iptv.domain.repository.SeriesSyncResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Global search across the three cached libraries. All fixtures are fictitious.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val live = FakeLiveRepository()
    private val movies = FakeMovieRepository()
    private val series = FakeSeriesRepository()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `typing is debounced before any library is scanned`() = runTest {
        val viewModel = createViewModel()

        viewModel.onInput("t")
        viewModel.onInput("ta")
        viewModel.onInput("tat")
        testScheduler.advanceTimeBy(100)

        // One keystroke costs three table scans, so nothing may run yet.
        assertThat(live.terms).isEmpty()
        assertThat(viewModel.state.value.input).isEqualTo("tat")

        testScheduler.advanceUntilIdle()
        assertThat(live.terms).containsExactly("tat")
        assertThat(movies.terms).containsExactly("tat")
        assertThat(series.terms).containsExactly("tat")
    }

    @Test
    fun `a single character is refused instead of scanning three tables`() = runTest {
        val viewModel = createViewModel()

        viewModel.onInput("a")
        testScheduler.advanceUntilIdle()

        assertThat(live.terms).isEmpty()
        assertThat(movies.terms).isEmpty()
        assertThat(series.terms).isEmpty()
        assertThat(viewModel.state.value.isTermTooShort).isTrue()
    }

    @Test
    fun `a term of nothing but punctuation is refused the same way`() = runTest {
        val viewModel = createViewModel()

        // Three keystrokes, nothing to match on. Measuring the raw length would send this to all
        // three tables and bind a pattern that matches every row in the cache.
        viewModel.onInput("...")
        testScheduler.advanceUntilIdle()

        assertThat(live.terms).isEmpty()
        assertThat(movies.terms).isEmpty()
        assertThat(series.terms).isEmpty()
        assertThat(viewModel.state.value.isTermTooShort).isTrue()
    }

    @Test
    fun `punctuation the viewer left out still reaches the libraries`() = runTest {
        val viewModel = createViewModel()

        viewModel.onInput("mr robot")
        testScheduler.advanceUntilIdle()

        // The term is passed through as typed; the folding that makes it match happens in the
        // repository, against the same rule the stored keys were written with.
        assertThat(movies.terms).containsExactly("mr robot")
    }

    @Test
    fun `results from all three libraries land in their own sections`() = runTest {
        live.result = SearchSection(listOf(channel("41")), hasMore = true)
        movies.result = SearchSection(listOf(movie("501")))
        series.result = SearchSection(listOf(seriesSummary("7")))

        val viewModel = createViewModel()
        viewModel.onInput("tatort")
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.channels.items.map { it.id }).containsExactly("41")
        assertThat(state.movies.items.map { it.id }).containsExactly("501")
        assertThat(state.series.items.map { it.id }).containsExactly("7")
        assertThat(state.channels.hasMore).isTrue()
        assertThat(state.movies.hasMore).isFalse()
        assertThat(state.submittedTerm).isEqualTo("tatort")
        assertThat(state.isSearching).isFalse()
    }

    @Test
    fun `shortening the term back below the minimum clears the previous results`() = runTest {
        movies.result = SearchSection(listOf(movie("501")))
        val viewModel = createViewModel()
        viewModel.onInput("tatort")
        testScheduler.advanceUntilIdle()
        assertThat(viewModel.state.value.hasAnyResult).isTrue()

        viewModel.onInput("t")
        testScheduler.advanceUntilIdle()

        // Leaving the old hits on screen would label them with a term that no longer produced them.
        val state = viewModel.state.value
        assertThat(state.hasAnyResult).isFalse()
        assertThat(state.submittedTerm).isEmpty()
        assertThat(state.isEmptyResult).isFalse()
    }

    @Test
    fun `an empty result is only reported once a search has actually run`() = runTest {
        val viewModel = createViewModel()
        assertThat(viewModel.state.value.isEmptyResult).isFalse()

        viewModel.onInput("tatort")
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.state.value.isEmptyResult).isTrue()
    }

    @Test
    fun `show more raises only that section's limit`() = runTest {
        movies.result = SearchSection(listOf(movie("501")), hasMore = true)
        val viewModel = createViewModel()
        viewModel.onInput("tatort")
        testScheduler.advanceUntilIdle()
        val firstMovieLimit = movies.limits.last()

        viewModel.showMoreMovies()
        testScheduler.advanceUntilIdle()

        assertThat(movies.limits.last()).isGreaterThan(firstMovieLimit)
        assertThat(live.limits.toSet()).hasSize(1)
    }

    @Test
    fun `a new term resets an expanded section`() = runTest {
        movies.result = SearchSection(listOf(movie("501")), hasMore = true)
        val viewModel = createViewModel()
        viewModel.onInput("tatort")
        testScheduler.advanceUntilIdle()
        viewModel.showMoreMovies()
        testScheduler.advanceUntilIdle()
        val expanded = movies.limits.last()

        viewModel.onInput("polizei")
        testScheduler.advanceUntilIdle()

        assertThat(movies.limits.last()).isLessThan(expanded)
    }

    @Test
    fun `a failure surfaces a safe message and keeps the field usable`() = runTest {
        movies.failure = AppFailureException(AppFailure(FailureKind.Unknown))
        val viewModel = createViewModel()

        viewModel.onInput("tatort")
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.isSearching).isFalse()
        assertThat(state.errorMessage).isNotNull()
        assertThat(state.errorMessage).doesNotContain("Exception")
        assertThat(state.isEmptyResult).isFalse()
    }

    @Test
    fun `clearing resets everything to the starting state`() = runTest {
        movies.result = SearchSection(listOf(movie("501")))
        val viewModel = createViewModel()
        viewModel.onInput("tatort")
        testScheduler.advanceUntilIdle()

        viewModel.clear()
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.input).isEmpty()
        assertThat(state.submittedTerm).isEmpty()
        assertThat(state.hasAnyResult).isFalse()
    }

    private fun createViewModel() = SearchViewModel(ACCOUNT, live, movies, series)

    private fun channel(id: String) = LiveChannel(
        id = id,
        categoryId = "7",
        name = "DE | Kanal $id",
        logoUrl = null,
        epgChannelId = null,
        containerExtension = "ts",
        directSource = null,
        providerOrder = 1,
    )

    private fun movie(id: String) = MovieSummary(
        id = id,
        categoryId = "20",
        name = "Beispielfilm $id",
        posterUrl = null,
        containerExtension = "mkv",
        rating = null,
        releaseYear = null,
        addedAtEpochSeconds = null,
        providerOrder = 1,
    )

    private fun seriesSummary(id: String) = SeriesSummary(
        id = id,
        categoryId = "90",
        name = "Beispielserie $id",
        posterUrl = null,
        rating = null,
        releaseYear = null,
        lastModifiedEpochSeconds = null,
        providerOrder = 1,
    )

    private class FakeLiveRepository : LiveRepository {
        override suspend fun epg(accountId: String, streamId: String): List<EpgEntry> = emptyList()
        val terms = mutableListOf<String>()
        val limits = mutableListOf<Int>()
        var result = SearchSection<LiveChannel>()

        override suspend fun search(
            accountId: String,
            term: String,
            limit: Int,
        ): SearchSection<LiveChannel> {
            terms += term
            limits += limit
            return result
        }

        override fun observeCategories(accountId: String) = flowOf(emptyList<LiveCategory>())
        override fun observeLanguages(accountId: String) = flowOf(emptyList<String>())
        override fun channels(
            accountId: String,
            filter: LiveFilter,
        ): Flow<PagingData<LiveChannel>> = flowOf(PagingData.empty())

        override fun observeRecent(accountId: String, limit: Int) = flowOf(emptyList<LiveChannel>())
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
        val terms = mutableListOf<String>()
        val limits = mutableListOf<Int>()
        var result = SearchSection<MovieSummary>()
        var failure: Throwable? = null

        override suspend fun search(
            accountId: String,
            term: String,
            limit: Int,
        ): SearchSection<MovieSummary> {
            terms += term
            limits += limit
            failure?.let { throw it }
            return result
        }

        override fun observeCategories(accountId: String) = flowOf(emptyList<MovieCategory>())
        override fun observeLanguages(accountId: String) = flowOf(emptyList<String>())
        override fun movies(
            accountId: String,
            filter: MovieFilter,
        ): Flow<PagingData<MovieSummary>> = flowOf(PagingData.empty())

        override fun observeContinueWatching(accountId: String, limit: Int) =
            flowOf(emptyList<ContinueWatchingEntry>())

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
        val terms = mutableListOf<String>()
        val limits = mutableListOf<Int>()
        var result = SearchSection<SeriesSummary>()

        override suspend fun search(
            accountId: String,
            term: String,
            limit: Int,
        ): SearchSection<SeriesSummary> {
            terms += term
            limits += limit
            return result
        }

        override fun observeCategories(accountId: String) = flowOf(emptyList<SeriesCategory>())
        override fun observeLanguages(accountId: String) = flowOf(emptyList<String>())
        override fun series(
            accountId: String,
            filter: SeriesFilter,
        ): Flow<PagingData<SeriesSummary>> = flowOf(PagingData.empty())

        override fun observeContinueWatching(accountId: String, limit: Int) =
            flowOf(emptyList<ContinueWatchingEntry>())

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
