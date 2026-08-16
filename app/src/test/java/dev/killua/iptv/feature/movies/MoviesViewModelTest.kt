package dev.killua.iptv.feature.movies

import androidx.paging.PagingData
import com.google.common.truth.Truth.assertThat
import dev.killua.iptv.domain.model.Account
import dev.killua.iptv.domain.model.RecentlyAddedEntry
import dev.killua.iptv.domain.model.AccountStatus
import dev.killua.iptv.domain.model.ContinueWatchingEntry
import dev.killua.iptv.domain.model.MovieCategory
import dev.killua.iptv.domain.model.MovieDetails
import dev.killua.iptv.domain.model.MovieFilter
import dev.killua.iptv.domain.model.MovieSortOrder
import dev.killua.iptv.domain.model.MovieSummary
import dev.killua.iptv.domain.model.WatchProgress
import dev.killua.iptv.domain.model.SearchSection
import dev.killua.iptv.domain.repository.MovieRepository
import dev.killua.iptv.domain.repository.MovieSyncResult
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
class MoviesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = FakeMovieRepository()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `an empty cache refreshes once instead of on every category emission`() = runTest {
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        repository.categories.value = emptyList()
        repository.categories.value = emptyList()
        testScheduler.advanceUntilIdle()

        assertThat(repository.refreshCount).isEqualTo(1)
    }

    @Test
    fun `a populated cache is not refreshed automatically`() = runTest {
        repository.categories.value = listOf(MovieCategory("20", "Action", 0))

        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        assertThat(repository.refreshCount).isEqualTo(0)
        assertThat(viewModel.state.value.hasLoadedOnce).isTrue()
    }

    @Test
    fun `search input is debounced before it reaches the filter`() = runTest {
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        viewModel.onSearchInput("m")
        viewModel.onSearchInput("ma")
        viewModel.onSearchInput("mat")
        testScheduler.advanceTimeBy(100)

        // The field updates immediately, the paging filter does not.
        assertThat(viewModel.state.value.searchInput).isEqualTo("mat")
        assertThat(viewModel.state.value.filter.searchQuery).isNull()

        testScheduler.advanceUntilIdle()
        assertThat(viewModel.state.value.filter.searchQuery).isEqualTo("mat")
        assertThat(repository.requestedFilters.count { it.searchQuery == "m" }).isEqualTo(0)
    }

    @Test
    fun `filters and sorting are applied independently`() = runTest {
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        viewModel.selectCategory("20")
        viewModel.setSortOrder(MovieSortOrder.RatingDescending)
        viewModel.toggleFavoritesOnly()
        testScheduler.advanceUntilIdle()

        val filter = viewModel.state.value.filter
        assertThat(filter.categoryId).isEqualTo("20")
        assertThat(filter.sortOrder).isEqualTo(MovieSortOrder.RatingDescending)
        assertThat(filter.favoritesOnly).isTrue()
        assertThat(viewModel.state.value.hasActiveFilter).isTrue()
    }

    @Test
    fun `clearing filters keeps the chosen sort order`() = runTest {
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()
        viewModel.setSortOrder(MovieSortOrder.RecentlyAdded)
        viewModel.selectCategory("20")
        viewModel.onSearchInput("matrix")
        testScheduler.advanceUntilIdle()

        viewModel.clearFilters()
        testScheduler.advanceUntilIdle()

        val filter = viewModel.state.value.filter
        assertThat(filter.sortOrder).isEqualTo(MovieSortOrder.RecentlyAdded)
        assertThat(filter.categoryId).isNull()
        assertThat(filter.searchQuery).isNull()
        assertThat(viewModel.state.value.searchInput).isEmpty()
        assertThat(viewModel.state.value.hasActiveFilter).isFalse()
    }

    @Test
    fun `a language filter that the library no longer offers is dropped`() = runTest {
        repository.categories.value = listOf(MovieCategory("20", "DE | Action", 0))
        repository.languages.value = listOf("de", "en")
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        viewModel.selectLanguage("en")
        testScheduler.advanceUntilIdle()
        assertThat(viewModel.state.value.filter.languageTag).isEqualTo("en")

        // The provider drops every English title on the next sync.
        repository.languages.value = listOf("de")
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.state.value.filter.languageTag).isNull()
        assertThat(viewModel.state.value.languages).containsExactly("de")
    }

    @Test
    fun `a refresh failure surfaces a safe message and clears the busy state`() = runTest {
        repository.categories.value = listOf(MovieCategory("20", "Action", 0))
        repository.refreshFailure = IllegalStateException("boom")
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        viewModel.refresh()
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.isRefreshing).isFalse()
        assertThat(state.errorMessage).isNotNull()
        // Raw exception text must never reach the UI.
        assertThat(state.errorMessage).doesNotContain("boom")
    }

    private fun createViewModel() = MoviesViewModel(ACCOUNT, repository)

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
        val categories = MutableStateFlow<List<MovieCategory>>(emptyList())
        val languages = MutableStateFlow<List<String>>(emptyList())
        val requestedFilters = mutableListOf<MovieFilter>()
        var refreshCount = 0
            private set
        var refreshFailure: Throwable? = null

        override fun observeCategories(accountId: String) = categories

        override fun observeLanguages(accountId: String) = languages

        override fun movies(
            accountId: String,
            filter: MovieFilter,
        ): Flow<PagingData<MovieSummary>> {
            requestedFilters += filter
            return flowOf(PagingData.empty())
        }

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

        override suspend fun hasCachedLibrary(accountId: String) = false

        override suspend fun refresh(
            accountId: String,
            onProgress: (Int) -> Unit,
        ): MovieSyncResult {
            refreshCount++
            refreshFailure?.let { throw it }
            return MovieSyncResult(0, 0, 0L)
        }

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
