package dev.killua.iptv.feature.series

import androidx.paging.PagingData
import com.google.common.truth.Truth.assertThat
import dev.killua.iptv.domain.model.Account
import dev.killua.iptv.domain.model.RecentlyAddedEntry
import dev.killua.iptv.domain.model.AccountStatus
import dev.killua.iptv.domain.model.AppFailure
import dev.killua.iptv.domain.model.AppFailureException
import dev.killua.iptv.domain.model.ContinueWatchingEntry
import dev.killua.iptv.domain.model.FailureKind
import dev.killua.iptv.domain.model.SeriesDetails
import dev.killua.iptv.domain.model.SeriesEpisode
import dev.killua.iptv.domain.model.SeriesFilter
import dev.killua.iptv.domain.model.SeriesSummary
import dev.killua.iptv.domain.model.WatchProgress
import dev.killua.iptv.domain.model.WatchlistKind
import dev.killua.iptv.domain.model.SearchSection
import dev.killua.iptv.domain.repository.FakeWatchlistRepository
import dev.killua.iptv.domain.repository.SeriesRepository
import dev.killua.iptv.domain.repository.SeriesSyncResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SeriesDetailsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = FakeSeriesRepository()
    private val watchlist = FakeWatchlistRepository()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `seasons are derived from the episodes and the first one opens`() = runTest {
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.seasons).containsExactly(1, 2).inOrder()
        assertThat(state.selectedSeason).isEqualTo(1)
        assertThat(state.episodesOfSelectedSeason.map { it.id }).containsExactly("101", "102")
    }

    @Test
    fun `selecting a season narrows the episode list`() = runTest {
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        viewModel.selectSeason(2)

        assertThat(viewModel.state.value.episodesOfSelectedSeason.map { it.id })
            .containsExactly("201")
    }

    @Test
    fun `a reload keeps the chosen season when it still exists`() = runTest {
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()
        viewModel.selectSeason(2)

        viewModel.load()
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.state.value.selectedSeason).isEqualTo(2)
    }

    @Test
    fun `a season the provider dropped falls back to the first one`() = runTest {
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()
        viewModel.selectSeason(2)

        // The provider now lists only the first season.
        repository.details = repository.details.copy(
            episodes = repository.details.episodes.filter { it.seasonNumber == 1 },
        )
        viewModel.load()
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.state.value.selectedSeason).isEqualTo(1)
    }

    @Test
    fun `without any progress the first episode is the one to play`() = runTest {
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.nextEpisode?.id).isEqualTo("101")
        assertThat(state.canResume).isFalse()
    }

    @Test
    fun `a half-watched episode is preferred over the first unwatched one`() = runTest {
        repository.progress.value = mapOf(
            "101" to progress("101", positionMs = 2_700_000L, completed = true),
            "102" to progress("102", positionMs = 600_000L, completed = false),
        )

        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.nextEpisode?.id).isEqualTo("102")
        assertThat(state.canResume).isTrue()
    }

    @Test
    fun `a finished episode advances to the next unwatched one`() = runTest {
        repository.progress.value = mapOf(
            "101" to progress("101", positionMs = 2_700_000L, completed = true),
        )

        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.nextEpisode?.id).isEqualTo("102")
        assertThat(state.canResume).isFalse()
    }

    @Test
    fun `a fully watched series offers the first episode again rather than nothing`() = runTest {
        repository.progress.value = repository.details.episodes.associate { episode ->
            episode.id to progress(episode.id, positionMs = 2_700_000L, completed = true)
        }

        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.nextEpisode?.id).isEqualTo("101")
        assertThat(state.canResume).isFalse()
    }

    @Test
    fun `a position written while the screen was in the back stack shows up`() = runTest {
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()
        assertThat(viewModel.state.value.episodeProgress).isEmpty()

        repository.progress.value = mapOf("102" to progress("102", positionMs = 90_000L))
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.state.value.episodeProgress.keys).containsExactly("102")
        assertThat(viewModel.state.value.nextEpisode?.id).isEqualTo("102")
    }

    @Test
    fun `a failed detail fetch keeps the cached summary and shows a safe message`() = runTest {
        repository.detailsFailure =
            AppFailureException(AppFailure(FailureKind.InvalidServerResponse))

        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.summary).isNotNull()
        assertThat(state.title).isEqualTo("Beispielserie")
        assertThat(state.errorMessage).isNotNull()
        assertThat(state.errorMessage).doesNotContain("Exception")
        assertThat(state.isLoading).isFalse()
    }

    @Test
    fun `the watched toggle asks for the opposite of what is stored`() = runTest {
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        // Nothing stored yet, so the tap means "I have seen this".
        viewModel.toggleEpisodeWatched("101")
        testScheduler.advanceUntilIdle()
        assertThat(repository.watchedCalls).containsExactly("101" to true)

        // A finished episode toggles the other way.
        repository.progress.value =
            mapOf("101" to progress("101", positionMs = 600_000L, completed = true))
        testScheduler.advanceUntilIdle()
        viewModel.toggleEpisodeWatched("101")
        testScheduler.advanceUntilIdle()
        assertThat(repository.watchedCalls.last()).isEqualTo("101" to false)
    }

    @Test
    fun `marking an episode watched moves the primary button on`() = runTest {
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()
        assertThat(viewModel.state.value.nextEpisode?.id).isEqualTo("101")

        // The write itself is observed rather than applied locally, which is what makes the
        // button follow a mark the same way it follows real playback.
        repository.progress.value =
            mapOf("101" to progress("101", positionMs = 600_000L, completed = true))
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.state.value.nextEpisode?.id).isEqualTo("102")
    }

    @Test
    fun `the saved mark follows the store rather than the tap`() = runTest {
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()
        assertThat(viewModel.state.value.isSaved).isFalse()

        viewModel.toggleSaved()
        testScheduler.advanceUntilIdle()

        assertThat(watchlist.writes).containsExactly(Triple(WatchlistKind.Series, "7", true))
        assertThat(viewModel.state.value.isSaved).isTrue()
    }

    @Test
    fun `tapping the saved mark again removes it`() = runTest {
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()
        viewModel.toggleSaved()
        testScheduler.advanceUntilIdle()

        viewModel.toggleSaved()
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.state.value.isSaved).isFalse()
        assertThat(watchlist.writes.last()).isEqualTo(Triple(WatchlistKind.Series, "7", false))
    }

    @Test
    fun `a refused save leaves the mark alone and reports it`() = runTest {
        // What a logout or account swap does: the write is rejected, so the mark must not flip.
        watchlist.failure = IllegalStateException("account gone")
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        viewModel.toggleSaved()
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.state.value.isSaved).isFalse()
        assertThat(viewModel.state.value.errorMessage).isNotNull()
    }

    private fun createViewModel() = SeriesDetailsViewModel(ACCOUNT, "7", repository, watchlist)

    private class FakeSeriesRepository : SeriesRepository {
        override fun observeRecentlyAdded(
            accountId: String,
            limit: Int,
        ): Flow<List<RecentlyAddedEntry>> = flowOf(emptyList())

        override suspend fun previousEpisode(accountId: String, episodeId: String): SeriesEpisode? =
            null
        val watchedCalls = mutableListOf<Pair<String, Boolean>>()

        override suspend fun setEpisodeWatched(
            accountId: String,
            episodeId: String,
            watched: Boolean,
        ) {
            watchedCalls += episodeId to watched
        }

        override suspend fun nextEpisode(accountId: String, episodeId: String): SeriesEpisode? =
            null
        override suspend fun search(
            accountId: String,
            term: String,
            limit: Int,
        ): SearchSection<SeriesSummary> = SearchSection()
        val progress = MutableStateFlow<Map<String, WatchProgress>>(emptyMap())
        var detailsFailure: Throwable? = null
        var details = SeriesDetails(
            id = "7",
            name = "Beispielserie",
            posterUrl = null,
            backdropUrl = null,
            plot = "Eine synthetische Beschreibung.",
            genre = "Drama",
            cast = null,
            director = null,
            releaseYear = 2019,
            rating = 8.1,
            episodes = listOf(
                episode("101", season = 1, number = 1),
                episode("102", season = 1, number = 2),
                episode("201", season = 2, number = 1),
            ),
        )

        override suspend fun getSeries(accountId: String, seriesId: String) = SeriesSummary(
            id = seriesId,
            categoryId = "90",
            name = "Beispielserie",
            posterUrl = null,
            rating = 8.1,
            releaseYear = 2019,
            lastModifiedEpochSeconds = null,
            providerOrder = 1,
        )

        override suspend fun details(
            accountId: String,
            seriesId: String,
            forceRefresh: Boolean,
        ): SeriesDetails {
            detailsFailure?.let { throw it }
            return details
        }

        override fun observeCategories(accountId: String) = flowOf(emptyList<Nothing>())

        override fun observeLanguages(accountId: String) = flowOf(emptyList<String>())

        override fun series(
            accountId: String,
            filter: SeriesFilter,
        ): Flow<PagingData<SeriesSummary>> = flowOf(PagingData.empty())

        override suspend fun getEpisode(accountId: String, episodeId: String): SeriesEpisode? = null

        override suspend fun hasCachedLibrary(accountId: String) = true

        override suspend fun refresh(
            accountId: String,
            onProgress: (Int) -> Unit,
        ): SeriesSyncResult = SeriesSyncResult(0, 0, 0L)

        override suspend fun episodeProgress(accountId: String, episodeId: String): WatchProgress? =
            progress.value[episodeId]

        override fun observeEpisodeProgress(
            accountId: String,
            seriesId: String,
        ): Flow<Map<String, WatchProgress>> = progress

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
        fun progress(
            contentId: String,
            positionMs: Long,
            completed: Boolean = false,
        ) = WatchProgress(
            contentId = contentId,
            positionMs = positionMs,
            durationMs = 2_700_000L,
            completed = completed,
            updatedAtEpochMillis = 0L,
        )

        fun episode(id: String, season: Int, number: Int) = SeriesEpisode(
            id = id,
            seriesId = "7",
            seasonNumber = season,
            episodeNumber = number,
            title = "Folge $number",
            containerExtension = "mkv",
            durationSeconds = 2_700,
            plot = null,
            stillUrl = null,
        )

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
