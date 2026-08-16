package dev.killua.iptv.feature.series

import androidx.paging.PagingData
import com.google.common.truth.Truth.assertThat
import dev.killua.iptv.domain.model.Account
import dev.killua.iptv.domain.model.RecentlyAddedEntry
import dev.killua.iptv.domain.model.AccountStatus
import dev.killua.iptv.domain.model.ContinueWatchingEntry
import dev.killua.iptv.domain.model.SeriesCategory
import dev.killua.iptv.domain.model.SeriesDetails
import dev.killua.iptv.domain.model.SeriesEpisode
import dev.killua.iptv.domain.model.SeriesFilter
import dev.killua.iptv.domain.model.SeriesSortOrder
import dev.killua.iptv.domain.model.SeriesSummary
import dev.killua.iptv.domain.model.WatchProgress
import dev.killua.iptv.domain.model.SearchSection
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
class SeriesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = FakeSeriesRepository()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `an empty cache refreshes once instead of on every category emission`() = runTest {
        createViewModel()
        testScheduler.advanceUntilIdle()

        repository.categories.value = emptyList()
        repository.categories.value = emptyList()
        testScheduler.advanceUntilIdle()

        assertThat(repository.refreshCount).isEqualTo(1)
    }

    @Test
    fun `a populated cache is not refreshed automatically`() = runTest {
        repository.categories.value = listOf(SeriesCategory("90", "Serien", 0))

        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        assertThat(repository.refreshCount).isEqualTo(0)
        assertThat(viewModel.state.value.hasLoadedOnce).isTrue()
    }

    @Test
    fun `search input is debounced before it reaches the filter`() = runTest {
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        viewModel.onSearchInput("t")
        viewModel.onSearchInput("ta")
        viewModel.onSearchInput("tat")
        testScheduler.advanceTimeBy(100)

        assertThat(viewModel.state.value.searchInput).isEqualTo("tat")
        assertThat(viewModel.state.value.filter.searchQuery).isNull()

        testScheduler.advanceUntilIdle()
        assertThat(viewModel.state.value.filter.searchQuery).isEqualTo("tat")
    }

    @Test
    fun `filters and sorting are applied independently`() = runTest {
        repository.languages.value = listOf("de")
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        viewModel.selectCategory("90")
        viewModel.selectLanguage("de")
        viewModel.setSortOrder(SeriesSortOrder.RecentlyUpdated)
        testScheduler.advanceUntilIdle()

        val filter = viewModel.state.value.filter
        assertThat(filter.categoryId).isEqualTo("90")
        assertThat(filter.languageTag).isEqualTo("de")
        assertThat(filter.sortOrder).isEqualTo(SeriesSortOrder.RecentlyUpdated)
        assertThat(viewModel.state.value.hasActiveFilter).isTrue()
    }

    @Test
    fun `clearing filters keeps the chosen sort order`() = runTest {
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()
        viewModel.setSortOrder(SeriesSortOrder.NameAscending)
        viewModel.selectCategory("90")
        viewModel.onSearchInput("tatort")
        testScheduler.advanceUntilIdle()

        viewModel.clearFilters()
        testScheduler.advanceUntilIdle()

        val filter = viewModel.state.value.filter
        assertThat(filter.sortOrder).isEqualTo(SeriesSortOrder.NameAscending)
        assertThat(filter.categoryId).isNull()
        assertThat(filter.searchQuery).isNull()
        assertThat(viewModel.state.value.hasActiveFilter).isFalse()
    }

    @Test
    fun `a language filter that the library no longer offers is dropped`() = runTest {
        repository.categories.value = listOf(SeriesCategory("90", "DE | Serien", 0))
        repository.languages.value = listOf("de", "en")
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        viewModel.selectLanguage("en")
        testScheduler.advanceUntilIdle()
        assertThat(viewModel.state.value.filter.languageTag).isEqualTo("en")

        repository.languages.value = listOf("de")
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.state.value.filter.languageTag).isNull()
    }

    @Test
    fun `the favorites and continue filters toggle independently`() = runTest {
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        viewModel.toggleFavoritesOnly()
        testScheduler.advanceUntilIdle()
        assertThat(viewModel.state.value.filter.favoritesOnly).isTrue()
        assertThat(viewModel.state.value.filter.inProgressOnly).isFalse()

        viewModel.toggleInProgressOnly()
        testScheduler.advanceUntilIdle()
        assertThat(viewModel.state.value.filter.favoritesOnly).isTrue()
        assertThat(viewModel.state.value.filter.inProgressOnly).isTrue()

        viewModel.toggleFavoritesOnly()
        testScheduler.advanceUntilIdle()
        assertThat(viewModel.state.value.filter.favoritesOnly).isFalse()
        assertThat(viewModel.state.value.hasActiveFilter).isTrue()
    }

    @Test
    fun `clearing filters drops the favorites and continue toggles too`() = runTest {
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()
        viewModel.toggleFavoritesOnly()
        viewModel.toggleInProgressOnly()
        testScheduler.advanceUntilIdle()

        viewModel.clearFilters()
        testScheduler.advanceUntilIdle()

        val filter = viewModel.state.value.filter
        assertThat(filter.favoritesOnly).isFalse()
        assertThat(filter.inProgressOnly).isFalse()
        assertThat(viewModel.state.value.hasActiveFilter).isFalse()
    }

    @Test
    fun `a refresh failure surfaces a safe message and clears the busy state`() = runTest {
        repository.categories.value = listOf(SeriesCategory("90", "Serien", 0))
        repository.refreshFailure = IllegalStateException("boom")
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        viewModel.refresh()
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.isRefreshing).isFalse()
        assertThat(state.errorMessage).isNotNull()
        assertThat(state.errorMessage).doesNotContain("boom")
    }

    private fun createViewModel() = SeriesViewModel(ACCOUNT, repository)

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
        val categories = MutableStateFlow<List<SeriesCategory>>(emptyList())
        val languages = MutableStateFlow<List<String>>(emptyList())
        var refreshCount = 0
            private set
        var refreshFailure: Throwable? = null

        override fun observeCategories(accountId: String) = categories

        override fun observeLanguages(accountId: String) = languages

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

        override suspend fun hasCachedLibrary(accountId: String) = false

        override suspend fun refresh(
            accountId: String,
            onProgress: (Int) -> Unit,
        ): SeriesSyncResult {
            refreshCount++
            refreshFailure?.let { throw it }
            return SeriesSyncResult(0, 0, 0L)
        }

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
