package dev.killua.iptv.feature.live

import androidx.paging.PagingData
import com.google.common.truth.Truth.assertThat
import dev.killua.iptv.domain.model.Account
import dev.killua.iptv.domain.model.AccountStatus
import dev.killua.iptv.domain.model.CategorySelection
import dev.killua.iptv.domain.model.EpgEntry
import dev.killua.iptv.domain.model.LiveCategory
import dev.killua.iptv.domain.model.LiveChannel
import dev.killua.iptv.domain.model.LiveFilter
import dev.killua.iptv.domain.model.LiveSortOrder
import dev.killua.iptv.domain.model.SearchSection
import dev.killua.iptv.domain.model.WatchlistKind
import dev.killua.iptv.domain.repository.FakeWatchlistRepository
import dev.killua.iptv.domain.repository.LiveRepository
import dev.killua.iptv.domain.repository.LiveSyncResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = FakeLiveRepository()
    private val watchlist = FakeWatchlistRepository()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `an unsynced account refreshes once on creation`() = runTest {
        createViewModel(lastSyncAtEpochMillis = null)
        testScheduler.advanceUntilIdle()

        assertThat(repository.refreshCount).isEqualTo(1)
    }

    @Test
    fun `a synced account is not refreshed automatically`() = runTest {
        createViewModel(lastSyncAtEpochMillis = 1_000L)
        testScheduler.advanceUntilIdle()

        assertThat(repository.refreshCount).isEqualTo(0)
    }

    @Test
    fun `search input is debounced before it reaches the paged filter`() = runTest {
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.channels.collect { } }
        testScheduler.advanceUntilIdle()

        viewModel.onSearchInput("r")
        viewModel.onSearchInput("rt")
        viewModel.onSearchInput("rtl")
        testScheduler.advanceTimeBy(100)

        // The field updates immediately, the paging filter does not.
        assertThat(viewModel.state.value.searchInput).isEqualTo("rtl")
        assertThat(viewModel.state.value.filter.searchQuery).isNull()

        testScheduler.advanceUntilIdle()
        assertThat(viewModel.state.value.filter.searchQuery).isEqualTo("rtl")
        // Typing must not rebuild the paging source once per character.
        assertThat(repository.requestedFilters.map { it.searchQuery })
            .containsNoneOf("r", "rt")
        assertThat(repository.requestedFilters.last().searchQuery).isEqualTo("rtl")
    }

    @Test
    fun `a blank search clears the term instead of filtering on emptiness`() = runTest {
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        viewModel.onSearchInput("rtl")
        testScheduler.advanceUntilIdle()
        viewModel.onSearchInput("   ")
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.state.value.filter.searchQuery).isNull()
        assertThat(viewModel.state.value.hasActiveFilter).isFalse()
    }

    @Test
    fun `selection and sorting are applied independently`() = runTest {
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.channels.collect { } }
        testScheduler.advanceUntilIdle()

        viewModel.select(CategorySelection.Provider("7"))
        viewModel.setSortOrder(LiveSortOrder.NameAscending)
        testScheduler.advanceUntilIdle()

        val filter = viewModel.state.value.filter
        assertThat(filter.selection).isEqualTo(CategorySelection.Provider("7"))
        assertThat(filter.sortOrder).isEqualTo(LiveSortOrder.NameAscending)
        assertThat(repository.requestedFilters.last()).isEqualTo(
            LiveFilter(
                selection = CategorySelection.Provider("7"),
                sortOrder = LiveSortOrder.NameAscending,
            ),
        )
    }

    @Test
    fun `a language filter reaches the paged filter and counts as narrowing`() = runTest {
        repository.languages.value = listOf("de", "en")
        val viewModel = createViewModel()
        backgroundScope.launch { viewModel.channels.collect { } }
        testScheduler.advanceUntilIdle()

        viewModel.selectLanguage("de")
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.state.value.filter.languageTag).isEqualTo("de")
        assertThat(viewModel.state.value.hasActiveFilter).isTrue()
        assertThat(repository.requestedFilters.last().languageTag).isEqualTo("de")
    }

    @Test
    fun `a language filter that the library no longer offers is dropped`() = runTest {
        repository.languages.value = listOf("de", "en")
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        viewModel.selectLanguage("en")
        testScheduler.advanceUntilIdle()
        assertThat(viewModel.state.value.filter.languageTag).isEqualTo("en")

        // The provider drops every English channel on the next refresh.
        repository.languages.value = listOf("de")
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.state.value.filter.languageTag).isNull()
        assertThat(viewModel.state.value.languages).containsExactly("de")
    }

    @Test
    fun `a sort order alone does not count as a filter that hides channels`() = runTest {
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        viewModel.setSortOrder(LiveSortOrder.NameDescending)
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.state.value.hasActiveFilter).isFalse()
    }

    @Test
    fun `clearing filters returns to all channels while keeping the chosen order`() = runTest {
        repository.languages.value = listOf("de")
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()
        viewModel.setSortOrder(LiveSortOrder.NameAscending)
        viewModel.select(CategorySelection.Recent)
        viewModel.selectLanguage("de")
        viewModel.onSearchInput("news")
        testScheduler.advanceUntilIdle()

        viewModel.clearFilters()
        testScheduler.advanceUntilIdle()

        val filter = viewModel.state.value.filter
        assertThat(filter.sortOrder).isEqualTo(LiveSortOrder.NameAscending)
        assertThat(filter.selection).isEqualTo(CategorySelection.All)
        assertThat(filter.languageTag).isNull()
        assertThat(filter.searchQuery).isNull()
        assertThat(viewModel.state.value.searchInput).isEmpty()
        assertThat(viewModel.state.value.hasActiveFilter).isFalse()
    }

    @Test
    fun `clearing the search keeps the current category`() = runTest {
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()
        viewModel.select(CategorySelection.Provider("7"))
        viewModel.onSearchInput("news")
        testScheduler.advanceUntilIdle()

        viewModel.clearSearch()
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.state.value.filter.selection)
            .isEqualTo(CategorySelection.Provider("7"))
        assertThat(viewModel.state.value.filter.searchQuery).isNull()
    }

    @Test
    fun `a refresh failure surfaces a safe message and clears the busy state`() = runTest {
        repository.refreshFailure = IllegalStateException("boom")
        val viewModel = createViewModel(lastSyncAtEpochMillis = 1_000L)
        testScheduler.advanceUntilIdle()

        viewModel.refresh()
        testScheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.isRefreshing).isFalse()
        assertThat(state.errorMessage).isNotNull()
        // Raw exception text must never reach the UI.
        assertThat(state.errorMessage).doesNotContain("boom")
    }

    @Test
    fun `a channel's programme is fetched once no matter how often the row asks`() = runTest {
        val now = System.currentTimeMillis() / 1_000L
        repository.epgEntries = listOf(entry("Tagesschau", now - 60, now + 600))
        val viewModel = createViewModel()

        // A row leaving and returning to the screen must not re-ask.
        viewModel.requestEpg("41")
        viewModel.requestEpg("41")
        testScheduler.advanceUntilIdle()

        assertThat(repository.epgRequests).containsExactly("41")
        assertThat(viewModel.state.value.nowPlaying["41"]?.title).isEqualTo("Tagesschau")
    }

    @Test
    fun `many rows at once stay within the request cap`() = runTest {
        val now = System.currentTimeMillis() / 1_000L
        repository.epgEntries = listOf(entry("Laeuft", now - 60, now + 600))
        val viewModel = createViewModel()

        // What a fast scroll through a six-figure list would otherwise do to a provider.
        (1..40).forEach { viewModel.requestEpg("channel-$it") }
        testScheduler.advanceUntilIdle()

        assertThat(repository.epgRequests).hasSize(40)
        assertThat(repository.peakConcurrentEpgRequests).isAtMost(4)
    }

    @Test
    fun `a channel with no current programme is left out of the map`() = runTest {
        val now = System.currentTimeMillis() / 1_000L
        // The guide exists but ran out hours ago.
        repository.epgEntries = listOf(entry("Vorbei", now - 7_200, now - 3_600))
        val viewModel = createViewModel()

        viewModel.requestEpg("41")
        testScheduler.advanceUntilIdle()

        // Absent rather than present-and-null, so the row keeps its own subtitle.
        assertThat(viewModel.state.value.nowPlaying).doesNotContainKey("41")
    }

    @Test
    fun `an empty guide leaves the row untouched`() = runTest {
        val viewModel = createViewModel()

        viewModel.requestEpg("41")
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.state.value.nowPlaying).isEmpty()
    }

    private fun entry(title: String, start: Long, end: Long) = EpgEntry(
        title = title,
        description = null,
        startEpochSeconds = start,
        endEpochSeconds = end,
    )

    @Test
    fun `saved channels arrive as one set rather than a query per row`() = runTest {
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        viewModel.toggleSaved("41")
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.state.value.savedChannelIds).containsExactly("41")
        assertThat(watchlist.writes).containsExactly(Triple(WatchlistKind.Channel, "41", true))

        viewModel.toggleSaved("41")
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.state.value.savedChannelIds).isEmpty()
    }

    @Test
    fun `a refused save leaves the row unmarked`() = runTest {
        watchlist.failure = IllegalStateException("account gone")
        val viewModel = createViewModel()
        testScheduler.advanceUntilIdle()

        viewModel.toggleSaved("41")
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.state.value.savedChannelIds).isEmpty()
    }

    private fun createViewModel(lastSyncAtEpochMillis: Long? = 1_000L) =
        LiveViewModel(account(lastSyncAtEpochMillis), repository, watchlist)

    private class FakeLiveRepository : LiveRepository {
        val epgRequests = mutableListOf<String>()
        var concurrentEpgRequests = 0
        var peakConcurrentEpgRequests = 0
            private set
        var epgEntries: List<EpgEntry> = emptyList()

        override suspend fun epg(accountId: String, streamId: String): List<EpgEntry> {
            epgRequests += streamId
            concurrentEpgRequests++
            peakConcurrentEpgRequests = maxOf(peakConcurrentEpgRequests, concurrentEpgRequests)
            // Yielding lets the other requests reach this point, so the cap is really measured.
            kotlinx.coroutines.yield()
            concurrentEpgRequests--
            return epgEntries
        }
        override suspend fun search(
            accountId: String,
            term: String,
            limit: Int,
        ): SearchSection<LiveChannel> = SearchSection()
        val categories = MutableStateFlow<List<LiveCategory>>(emptyList())
        val languages = MutableStateFlow<List<String>>(emptyList())
        val requestedFilters = mutableListOf<LiveFilter>()
        var refreshCount = 0
            private set
        var refreshFailure: Throwable? = null

        override fun observeCategories(accountId: String) = categories

        override fun observeLanguages(accountId: String) = languages

        override fun channels(
            accountId: String,
            filter: LiveFilter,
        ): Flow<PagingData<LiveChannel>> {
            requestedFilters += filter
            return flowOf(PagingData.empty())
        }

        override fun observeRecent(accountId: String, limit: Int) =
            flowOf(emptyList<LiveChannel>())

        override suspend fun getChannel(accountId: String, streamId: String): LiveChannel? = null

        override suspend fun hasCachedLibrary(accountId: String) = false

        override suspend fun refresh(
            accountId: String,
            onProgress: (Int) -> Unit,
        ): LiveSyncResult {
            refreshCount++
            refreshFailure?.let { throw it }
            return LiveSyncResult(0, 0, 0L)
        }

        override suspend fun markRecent(accountId: String, streamId: String) = Unit
    }

    private companion object {
        fun account(lastSyncAtEpochMillis: Long?) = Account(
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
            lastLiveSyncAtEpochMillis = lastSyncAtEpochMillis,
        )
    }
}
