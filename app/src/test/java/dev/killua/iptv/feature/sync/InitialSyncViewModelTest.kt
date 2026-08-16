package dev.killua.iptv.feature.sync

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

@OptIn(ExperimentalCoroutinesApi::class)
class InitialSyncViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val live = FakeLiveRepository()
    private val movies = FakeMovieRepository()
    private val series = FakeSeriesRepository()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `all three libraries sync in order and report running counts`() = runTest {
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.channelCount).isEqualTo(34_000)
        assertThat(state.movieCount).isEqualTo(153_000)
        assertThat(state.seriesCount).isEqualTo(9_000)
        assertThat(state.isFinished).isTrue()
        assertThat(state.errorMessage).isNull()
        // Progress must be observed while running, not only at the end.
        assertThat(live.reportedProgress).isNotEmpty()
        assertThat(movies.reportedProgress).isNotEmpty()
        assertThat(series.reportedProgress).isNotEmpty()
        assertThat(live.reportedProgress.last()).isEqualTo(34_000)
    }

    @Test
    fun `an already cached library is not downloaded again`() = runTest {
        // What an update that adds a library looks like: the other two are already here.
        live.hasCache = true
        movies.hasCache = true

        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        assertThat(live.refreshCount).isEqualTo(0)
        assertThat(movies.refreshCount).isEqualTo(0)
        assertThat(series.refreshCount).isEqualTo(1)
        val state = viewModel.state.value
        assertThat(state.isFinished).isTrue()
        assertThat(state.wasAlreadyCached(SyncStep.Channels)).isTrue()
        assertThat(state.wasAlreadyCached(SyncStep.Series)).isFalse()
        // A skipped library still reads as done rather than as pending.
        assertThat(state.isDone(SyncStep.Channels)).isTrue()
    }

    @Test
    fun `a retry resumes instead of downloading what already succeeded`() = runTest {
        series.error = IllegalStateException("provider hiccup")
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()
        assertThat(viewModel.state.value.errorMessage).isNotNull()

        // The first two committed their caches; only the failed one should run again.
        live.hasCache = true
        movies.hasCache = true
        series.error = null
        viewModel.start()
        testScheduler.advanceUntilIdle()

        assertThat(live.refreshCount).isEqualTo(1)
        assertThat(movies.refreshCount).isEqualTo(1)
        assertThat(series.refreshCount).isEqualTo(2)
        assertThat(viewModel.state.value.isFinished).isTrue()
    }

    @Test
    fun `a series failure still leaves the earlier libraries usable`() = runTest {
        series.error = IllegalStateException("provider hiccup")
        movies.hasCache = false

        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.isFinished).isFalse()
        assertThat(state.errorMessage).isNotNull()
        assertThat(state.channelCount).isEqualTo(34_000)
        assertThat(state.movieCount).isEqualTo(153_000)
    }

    @Test
    fun `a failure stops the sync and offers the cached library`() = runTest {
        live.failure = AppFailureException(AppFailure(FailureKind.Timeout, retryable = true))
        // Some other library is cached, which is what makes continuing worth offering.
        series.hasCache = true

        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.isRunning).isFalse()
        assertThat(state.isFinished).isFalse()
        assertThat(state.errorMessage).isNotNull()
        assertThat(state.canContinueWithCache).isTrue()
        // Movies must not run after channels failed.
        assertThat(movies.refreshCount).isEqualTo(0)
    }

    @Test
    fun `an oversized library reports a safe message instead of crashing`() = runTest {
        movies.error = OutOfMemoryError()

        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.errorMessage)
            .isEqualTo(AppFailure(FailureKind.LibraryTooLarge).userMessage())
        assertThat(state.isFinished).isFalse()
    }

    @Test
    fun `retry runs the sync again`() = runTest {
        live.failure = AppFailureException(AppFailure(FailureKind.Timeout))
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()
        assertThat(viewModel.state.value.errorMessage).isNotNull()

        live.failure = null
        viewModel.start()
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.state.value.isFinished).isTrue()
        assertThat(viewModel.state.value.errorMessage).isNull()
        assertThat(live.refreshCount).isEqualTo(2)
    }

    @Test
    fun `skipping finishes without a successful sync`() = runTest {
        live.failure = AppFailureException(AppFailure(FailureKind.NoNetwork))
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        viewModel.skip()

        assertThat(viewModel.state.value.isFinished).isTrue()
        assertThat(viewModel.state.value.isRunning).isFalse()
    }

    private fun createViewModel() = InitialSyncViewModel(ACCOUNT, live, movies, series)

    private class FakeLiveRepository : LiveRepository {
        override suspend fun epg(accountId: String, streamId: String): List<EpgEntry> = emptyList()
        override suspend fun search(
            accountId: String,
            term: String,
            limit: Int,
        ): SearchSection<LiveChannel> = SearchSection()
        val reportedProgress = mutableListOf<Int>()
        var refreshCount = 0
            private set
        var failure: AppFailureException? = null
        var hasCache = false

        override suspend fun refresh(accountId: String, onProgress: (Int) -> Unit): LiveSyncResult {
            refreshCount++
            failure?.let { throw it }
            listOf(500, 20_000, 34_000).forEach {
                reportedProgress += it
                onProgress(it)
            }
            return LiveSyncResult(120, 34_000, 0L)
        }

        override suspend fun hasCachedLibrary(accountId: String) = hasCache

        override fun observeCategories(accountId: String): Flow<List<LiveCategory>> =
            flowOf(emptyList())

        override fun observeLanguages(accountId: String): Flow<List<String>> = flowOf(emptyList())

        override fun channels(
            accountId: String,
            filter: LiveFilter,
        ): Flow<PagingData<LiveChannel>> = flowOf(PagingData.empty())

        override fun observeRecent(accountId: String, limit: Int): Flow<List<LiveChannel>> =
            flowOf(emptyList())

        override suspend fun getChannel(accountId: String, streamId: String): LiveChannel? = null

        override suspend fun markRecent(accountId: String, streamId: String) = Unit
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
        val reportedProgress = mutableListOf<Int>()
        var refreshCount = 0
            private set
        var error: Throwable? = null
        var hasCache = false

        override suspend fun refresh(
            accountId: String,
            onProgress: (Int) -> Unit,
        ): MovieSyncResult {
            refreshCount++
            error?.let { throw it }
            listOf(500, 90_000, 153_000).forEach {
                reportedProgress += it
                onProgress(it)
            }
            return MovieSyncResult(40, 153_000, 0L)
        }

        override suspend fun hasCachedLibrary(accountId: String) = hasCache

        override fun observeCategories(accountId: String): Flow<List<MovieCategory>> =
            flowOf(emptyList())

        override fun observeLanguages(accountId: String): Flow<List<String>> = flowOf(emptyList())

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

        override suspend fun progress(accountId: String, movieId: String): WatchProgress? = null

        override fun observeProgress(
            accountId: String,
            movieId: String,
        ): Flow<WatchProgress?> = flowOf(null)

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
        val reportedProgress = mutableListOf<Int>()
        var refreshCount = 0
            private set
        var error: Throwable? = null
        var hasCache = false

        override suspend fun refresh(
            accountId: String,
            onProgress: (Int) -> Unit,
        ): SeriesSyncResult {
            refreshCount++
            error?.let { throw it }
            listOf(500, 5_000, 9_000).forEach {
                reportedProgress += it
                onProgress(it)
            }
            return SeriesSyncResult(30, 9_000, 0L)
        }

        override suspend fun hasCachedLibrary(accountId: String) = hasCache

        override fun observeCategories(accountId: String): Flow<List<SeriesCategory>> =
            flowOf(emptyList())

        override fun observeLanguages(accountId: String): Flow<List<String>> = flowOf(emptyList())

        override fun series(
            accountId: String,
            filter: SeriesFilter,
        ): Flow<PagingData<SeriesSummary>> = flowOf(PagingData.empty())

        override suspend fun getSeries(accountId: String, seriesId: String): SeriesSummary? = null

        override suspend fun details(
            accountId: String,
            seriesId: String,
            forceRefresh: Boolean,
        ): SeriesDetails = throw UnsupportedOperationException()

        override suspend fun getEpisode(accountId: String, episodeId: String): SeriesEpisode? = null

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

        override fun observeContinueWatching(
            accountId: String,
            limit: Int,
        ): Flow<List<ContinueWatchingEntry>> = flowOf(emptyList())

        override fun observeIsFavorite(accountId: String, seriesId: String): Flow<Boolean> =
            flowOf(false)

        override suspend fun setFavorite(
            accountId: String,
            seriesId: String,
            favorite: Boolean,
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
