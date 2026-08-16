package dev.killua.iptv.feature.player

import androidx.media3.common.Player
import androidx.paging.PagingData
import com.google.common.truth.Truth.assertThat
import dev.killua.iptv.core.player.PlaybackCommands
import dev.killua.iptv.core.player.PlaybackMediaId
import dev.killua.iptv.core.player.PlaybackPosition
import dev.killua.iptv.core.player.PlaybackRequest
import dev.killua.iptv.core.player.PlaybackSnapshot
import dev.killua.iptv.core.player.PlaybackStateSource
import dev.killua.iptv.core.player.PlayerPresentationState
import dev.killua.iptv.core.player.WatchProgressWriter
import dev.killua.iptv.domain.model.Account
import dev.killua.iptv.domain.model.RecentlyAddedEntry
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Resume, restart, and checkpoint behaviour, exercised without Media3.
 *
 * `MediaController` cannot exist on the JVM, so the ViewModel talks to `PlaybackCommands` and
 * `PlaybackStateSource` and both are faked here. All fixtures are fictitious.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val events = mutableListOf<String>()
    private val commands = FakePlaybackCommands(events)
    private val source = FakePlaybackStateSource(events)
    private val liveRepository = FakeLiveRepository()
    private val movieRepository = FakeMovieRepository()
    private val seriesRepository = FakeSeriesRepository()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a movie starts at its stored position`() = runTest {
        movieRepository.progress = WatchProgress("501", 125_000L, 600_000L, false, 0L)

        createMovieViewModel(scope = this)
        testScheduler.advanceUntilIdle()

        assertThat(commands.startedMovies).containsExactly("501" to 125_000L)
    }

    @Test
    fun `restart ignores the stored position`() = runTest {
        movieRepository.progress = WatchProgress("501", 125_000L, 600_000L, false, 0L)

        createMovieViewModel(scope = this, restart = true)
        testScheduler.advanceUntilIdle()

        assertThat(commands.startedMovies).containsExactly("501" to 0L)
    }

    @Test
    fun `a finished movie starts from the beginning instead of at the credits`() = runTest {
        movieRepository.progress = WatchProgress("501", 595_000L, 600_000L, true, 0L)

        createMovieViewModel(scope = this)
        testScheduler.advanceUntilIdle()

        assertThat(commands.startedMovies).containsExactly("501" to 0L)
    }

    @Test
    fun `a movie without stored progress starts from the beginning`() = runTest {
        createMovieViewModel(scope = this)
        testScheduler.advanceUntilIdle()

        assertThat(commands.startedMovies).containsExactly("501" to 0L)
    }

    @Test
    fun `pausing writes a checkpoint immediately`() = runTest {
        createMovieViewModel(scope = this)
        testScheduler.advanceUntilIdle()

        source.emitPlaying()
        testScheduler.runCurrent()
        source.positionMs = 90_000L
        source.emitPaused()
        testScheduler.runCurrent()

        assertThat(movieRepository.saved.map { it.second }).contains(90_000L)
    }

    @Test
    fun `playing checkpoints on an interval rather than continuously`() = runTest {
        val viewModel = createMovieViewModel(scope = this)
        testScheduler.advanceUntilIdle()
        source.emitPlaying()

        // Two intervals of playback, with the position advancing as it would on a real player.
        repeat(2) {
            source.positionMs += 10_000L
            testScheduler.advanceTimeBy(10_000L)
            testScheduler.runCurrent()
        }

        assertThat(movieRepository.saved.map { it.second }).containsExactly(10_000L, 20_000L)
        viewModel.stop()
    }

    @Test
    fun `stopping captures the position before the item is cleared`() = runTest {
        val viewModel = createMovieViewModel(scope = this)
        testScheduler.advanceUntilIdle()
        source.emitPlaying()
        testScheduler.runCurrent()
        source.positionMs = 130_000L
        events.clear()

        viewModel.stop()
        testScheduler.runCurrent()

        // Reading a position after the item is gone would silently save nothing.
        assertThat(events).containsExactly("capture", "stopAndClear").inOrder()
        assertThat(movieRepository.saved.map { it.second }).contains(130_000L)
    }

    @Test
    fun `leaving the screen checkpoints, which is what a background or PiP switch does`() =
        runTest {
            val viewModel = createMovieViewModel(scope = this)
            testScheduler.advanceUntilIdle()
            source.emitPlaying()
            testScheduler.runCurrent()
            source.positionMs = 45_000L

            viewModel.onScreenHidden()
            testScheduler.runCurrent()

            assertThat(movieRepository.saved.map { it.second }).contains(45_000L)
            viewModel.stop()
        }

    @Test
    fun `a position captured from another title is never stored`() = runTest {
        val viewModel = createMovieViewModel(scope = this)
        testScheduler.advanceUntilIdle()
        source.emitPlaying()
        testScheduler.runCurrent()

        // The user moved on and the controller already carries the next title.
        source.mediaId = PlaybackMediaId.Movie(ACCOUNT.id, "777")
        source.positionMs = 5_000L
        viewModel.stop()
        testScheduler.runCurrent()

        assertThat(movieRepository.saved).isEmpty()
    }

    @Test
    fun `a missing movie reports a safe failure instead of starting playback`() = runTest {
        movieRepository.movie = null

        val viewModel = createMovieViewModel(scope = this)
        testScheduler.advanceUntilIdle()

        assertThat(commands.startedMovies).isEmpty()
        assertThat(viewModel.state.value.startError).isNotNull()
        assertThat(viewModel.state.value.isStarting).isFalse()
    }

    @Test
    fun `an episode starts at its stored position`() = runTest {
        seriesRepository.progress = WatchProgress("9012", 300_000L, 2_700_000L, false, 0L)

        createEpisodeViewModel(scope = this)
        testScheduler.advanceUntilIdle()

        assertThat(commands.startedEpisodes).containsExactly("9012" to 300_000L)
        assertThat(commands.startedMovies).isEmpty()
    }

    @Test
    fun `restarting an episode ignores the stored position`() = runTest {
        seriesRepository.progress = WatchProgress("9012", 300_000L, 2_700_000L, false, 0L)

        createEpisodeViewModel(scope = this, restart = true)
        testScheduler.advanceUntilIdle()

        assertThat(commands.startedEpisodes).containsExactly("9012" to 0L)
    }

    @Test
    fun `a finished episode starts from the beginning instead of at the credits`() = runTest {
        seriesRepository.progress = WatchProgress("9012", 2_690_000L, 2_700_000L, true, 0L)

        createEpisodeViewModel(scope = this)
        testScheduler.advanceUntilIdle()

        assertThat(commands.startedEpisodes).containsExactly("9012" to 0L)
    }

    @Test
    fun `an episode checkpoints into the series library`() = runTest {
        source.mediaId = PlaybackMediaId.Episode(ACCOUNT.id, "9012")
        source.durationMs = 2_700_000L
        val viewModel = createEpisodeViewModel(scope = this)
        testScheduler.advanceUntilIdle()
        source.emitPlaying()
        testScheduler.runCurrent()
        source.positionMs = 420_000L

        viewModel.stop()
        testScheduler.runCurrent()

        assertThat(seriesRepository.saved).contains("9012" to 420_000L)
        assertThat(movieRepository.saved).isEmpty()
    }

    @Test
    fun `the next episode is resolved while the current one starts`() = runTest {
        seriesRepository.next = nextEpisode()

        val viewModel = createEpisodeViewModel(scope = this)
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.state.value.nextEpisode?.id).isEqualTo("9013")
    }

    @Test
    fun `the last episode of a series offers nothing to advance to`() = runTest {
        seriesRepository.next = null

        val viewModel = createEpisodeViewModel(scope = this)
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.state.value.nextEpisode).isNull()
        assertThat(viewModel.state.value.autoAdvanceTo).isNull()
    }

    @Test
    fun `finishing an episode advances once, after the position was written`() = runTest {
        seriesRepository.next = nextEpisode()
        source.mediaId = PlaybackMediaId.Episode(ACCOUNT.id, "9012")
        source.durationMs = 2_700_000L
        val viewModel = createEpisodeViewModel(scope = this)
        testScheduler.advanceUntilIdle()

        source.positionMs = 2_700_000L
        source.hasEnded = true
        source.emitEnded()
        testScheduler.runCurrent()

        // The finished episode must be marked watched before the next one starts, or the series
        // would keep offering the one just watched. That happens at the ending itself, not when
        // the countdown expires.
        assertThat(seriesRepository.saved.map { it.first }).contains("9012")

        testScheduler.advanceTimeBy(9_000L)
        testScheduler.runCurrent()
        assertThat(viewModel.state.value.autoAdvanceTo?.id).isEqualTo("9013")

        // Consuming it is what stops a recomposition from starting the episode twice.
        viewModel.consumeAutoAdvance()
        assertThat(viewModel.state.value.autoAdvanceTo).isNull()
        viewModel.stop()
    }

    @Test
    fun `the countdown runs down and only then advances`() = runTest {
        seriesRepository.next = nextEpisode()
        source.mediaId = PlaybackMediaId.Episode(ACCOUNT.id, "9012")
        source.durationMs = 2_700_000L
        val viewModel = createEpisodeViewModel(scope = this)
        testScheduler.advanceUntilIdle()

        source.positionMs = 2_700_000L
        source.hasEnded = true
        source.emitEnded()
        testScheduler.runCurrent()

        // The ending starts a countdown rather than navigating out from under the viewer.
        assertThat(viewModel.state.value.autoAdvanceCountdownSeconds).isEqualTo(8)
        assertThat(viewModel.state.value.autoAdvanceTo).isNull()

        testScheduler.advanceTimeBy(3_000L)
        testScheduler.runCurrent()
        assertThat(viewModel.state.value.autoAdvanceCountdownSeconds).isEqualTo(5)
        assertThat(viewModel.state.value.autoAdvanceTo).isNull()

        testScheduler.advanceTimeBy(6_000L)
        testScheduler.runCurrent()
        assertThat(viewModel.state.value.autoAdvanceTo?.id).isEqualTo("9013")
        assertThat(viewModel.state.value.autoAdvanceCountdownSeconds).isNull()
        viewModel.stop()
    }

    @Test
    fun `cancelling the countdown keeps the viewer where they are`() = runTest {
        seriesRepository.next = nextEpisode()
        source.mediaId = PlaybackMediaId.Episode(ACCOUNT.id, "9012")
        source.durationMs = 2_700_000L
        val viewModel = createEpisodeViewModel(scope = this)
        testScheduler.advanceUntilIdle()
        source.positionMs = 2_700_000L
        source.hasEnded = true
        source.emitEnded()
        testScheduler.runCurrent()

        viewModel.cancelAutoAdvance()
        testScheduler.advanceTimeBy(20_000L)
        testScheduler.runCurrent()

        assertThat(viewModel.state.value.autoAdvanceCountdownSeconds).isNull()
        assertThat(viewModel.state.value.autoAdvanceTo).isNull()
        // Declining the next episode says nothing about the one just finished.
        assertThat(seriesRepository.saved.map { it.first }).contains("9012")
        // And the control is still there for a viewer who changes their mind.
        assertThat(viewModel.state.value.nextEpisode?.id).isEqualTo("9013")
        viewModel.stop()
    }

    @Test
    fun `a repeated end event does not restart the countdown`() = runTest {
        seriesRepository.next = nextEpisode()
        source.mediaId = PlaybackMediaId.Episode(ACCOUNT.id, "9012")
        source.durationMs = 2_700_000L
        val viewModel = createEpisodeViewModel(scope = this)
        testScheduler.advanceUntilIdle()
        source.positionMs = 2_700_000L
        source.hasEnded = true
        source.emitEnded()
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(5_000L)
        testScheduler.runCurrent()
        assertThat(viewModel.state.value.autoAdvanceCountdownSeconds).isEqualTo(3)

        // Media3 can report the same ending more than once; restarting here would mean the timer
        // never reaches zero.
        source.emitEnded()
        testScheduler.runCurrent()

        assertThat(viewModel.state.value.autoAdvanceCountdownSeconds).isEqualTo(3)
        viewModel.stop()
    }

    @Test
    fun `both neighbouring episodes are resolved while the current one starts`() = runTest {
        seriesRepository.next = nextEpisode()
        seriesRepository.previous = previousEpisode()

        val viewModel = createEpisodeViewModel(scope = this)
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.state.value.nextEpisode?.id).isEqualTo("9013")
        assertThat(viewModel.state.value.previousEpisode?.id).isEqualTo("9011")
    }

    @Test
    fun `the first episode of a series offers nothing to go back to`() = runTest {
        seriesRepository.previous = null

        val viewModel = createEpisodeViewModel(scope = this)
        testScheduler.advanceUntilIdle()

        assertThat(viewModel.state.value.previousEpisode).isNull()
    }

    @Test
    fun `autoplay off leaves the ending alone`() = runTest {
        seriesRepository.next = nextEpisode()
        source.mediaId = PlaybackMediaId.Episode(ACCOUNT.id, "9012")
        val viewModel = createEpisodeViewModel(scope = this, autoPlayNextEpisode = false)
        testScheduler.advanceUntilIdle()

        source.emitEnded()
        testScheduler.runCurrent()

        assertThat(viewModel.state.value.autoAdvanceTo).isNull()
        // The button stays available; only the automatic step is off.
        assertThat(viewModel.state.value.nextEpisode?.id).isEqualTo("9013")
        viewModel.stop()
    }

    @Test
    fun `a finished movie never advances anywhere`() = runTest {
        seriesRepository.next = nextEpisode()
        val viewModel = createMovieViewModel(scope = this)
        testScheduler.advanceUntilIdle()

        source.emitEnded()
        testScheduler.runCurrent()

        assertThat(viewModel.state.value.autoAdvanceTo).isNull()
        assertThat(viewModel.state.value.nextEpisode).isNull()
        viewModel.stop()
    }

    @Test
    fun `every content type names what is playing`() = runTest {
        val live = createViewModel(scope = this, request = PlaybackRequest.Live("41"))
        testScheduler.advanceUntilIdle()
        assertThat(live.state.value.title).isEqualTo("Kanal 41")
        live.stop()

        val movie = createMovieViewModel(scope = this)
        testScheduler.advanceUntilIdle()
        assertThat(movie.state.value.title).isEqualTo("Beispielfilm")
        movie.stop()

        // An episode is named the way the series list names it, so the overlay and the row agree.
        val episode = createEpisodeViewModel(scope = this)
        testScheduler.advanceUntilIdle()
        assertThat(episode.state.value.title).isEqualTo("S1 E3 · Beispielfolge")
        episode.stop()
    }

    private fun previousEpisode() = SeriesEpisode(
        id = "9011",
        seriesId = "7",
        seasonNumber = 1,
        episodeNumber = 2,
        title = "Vorige Folge",
        containerExtension = "mkv",
        durationSeconds = 2_700,
        plot = null,
        stillUrl = null,
    )

    private fun nextEpisode() = SeriesEpisode(
        id = "9013",
        seriesId = "7",
        seasonNumber = 1,
        episodeNumber = 4,
        title = "Naechste Folge",
        containerExtension = "mkv",
        durationSeconds = 2_700,
        plot = null,
        stillUrl = null,
    )

    @Test
    fun `a missing episode reports a safe failure instead of starting playback`() = runTest {
        seriesRepository.episode = null

        val viewModel = createEpisodeViewModel(scope = this)
        testScheduler.advanceUntilIdle()

        assertThat(commands.startedEpisodes).isEmpty()
        assertThat(viewModel.state.value.startError).isNotNull()
        assertThat(viewModel.state.value.isStarting).isFalse()
    }

    @Test
    fun `a live channel still marks itself recent after two seconds and stores no progress`() =
        runTest {
            val viewModel = createViewModel(scope = this, request = PlaybackRequest.Live("41"))
            testScheduler.advanceUntilIdle()

            source.mediaId = PlaybackMediaId.Live(ACCOUNT.id, "41")
            source.emitPlaying()
            testScheduler.advanceTimeBy(2_100L)
            testScheduler.runCurrent()

            assertThat(liveRepository.recent).containsExactly("41")
            assertThat(movieRepository.saved).isEmpty()
            viewModel.stop()
        }

    @Test
    fun `the outgoing episode's teardown does not blank the one that replaced it`() = runTest {
        // What an episode change actually looks like: the new screen starts while the old one is
        // still alive, and the old one is torn down afterwards. Picture-in-Picture reads this state,
        // so a late hide from the finished episode switched PiP off for the one now playing.
        val presentation = PlayerPresentationState()
        val leaving = createViewModel(
            scope = this,
            request = PlaybackRequest.Episode("9012", false),
            presentationState = presentation,
        )
        testScheduler.advanceUntilIdle()
        leaving.onScreenVisible()
        val arriving = createViewModel(
            scope = this,
            request = PlaybackRequest.Episode("9013", false),
            presentationState = presentation,
        )
        testScheduler.advanceUntilIdle()
        arriving.onScreenVisible()
        source.emitPlaying()
        testScheduler.runCurrent()

        leaving.onScreenHidden()
        testScheduler.runCurrent()

        assertThat(presentation.state.value.isPlayerScreenVisible).isTrue()
        leaving.stop()
        arriving.stop()
    }

    private fun createMovieViewModel(scope: TestScope, restart: Boolean = false) =
        createViewModel(scope, PlaybackRequest.Movie("501", restart))

    private fun createEpisodeViewModel(
        scope: TestScope,
        restart: Boolean = false,
        autoPlayNextEpisode: Boolean = true,
    ) = createViewModel(
        scope = scope,
        request = PlaybackRequest.Episode("9012", restart),
        autoPlayNextEpisode = autoPlayNextEpisode,
    )

    private fun createViewModel(
        scope: TestScope,
        request: PlaybackRequest,
        autoPlayNextEpisode: Boolean = true,
        presentationState: PlayerPresentationState = PlayerPresentationState(),
    ) = PlayerViewModel(
        account = ACCOUNT,
        request = request,
        liveRepository = liveRepository,
        movieRepository = movieRepository,
        seriesRepository = seriesRepository,
        coordinator = commands,
        connection = source,
        presentationState = presentationState,
        // The real writer is used: its ownership and duplicate rules are part of what is checked.
        progressWriter = WatchProgressWriter(scope, movieRepository, seriesRepository),
        autoPlayNextEpisode = autoPlayNextEpisode,
    )

    private class FakePlaybackCommands(private val events: MutableList<String>) : PlaybackCommands {
        val startedMovies = mutableListOf<Pair<String, Long>>()
        val startedEpisodes = mutableListOf<Pair<String, Long>>()
        val startedChannels = mutableListOf<String>()

        override suspend fun playLive(
            account: Account,
            channel: LiveChannel,
            requestedFormat: String?,
        ): String {
            startedChannels += channel.id
            return "ts"
        }

        override suspend fun playMovie(
            account: Account,
            movie: MovieSummary,
            startPositionMs: Long,
        ): String {
            startedMovies += movie.id to startPositionMs
            return "mkv"
        }

        override suspend fun playEpisode(
            account: Account,
            episode: SeriesEpisode,
            startPositionMs: Long,
        ): String {
            startedEpisodes += episode.id to startPositionMs
            return "mkv"
        }

        override fun retry() = Unit
        override fun reconnect() = Unit
        override fun togglePlayPause() = true
        override fun seekBy(deltaMs: Long) = true
        override fun beginTemporarySpeed(speed: Float) = true
        override fun endTemporarySpeed() = Unit
        override fun stopAndClear() {
            events += "stopAndClear"
        }
    }

    private class FakePlaybackStateSource(
        private val events: MutableList<String>,
    ) : PlaybackStateSource {
        private val mutableSnapshot = MutableStateFlow(PlaybackSnapshot())
        override val snapshot: StateFlow<PlaybackSnapshot> = mutableSnapshot.asStateFlow()

        var mediaId: PlaybackMediaId = PlaybackMediaId.Movie(ACCOUNT.id, "501")
        var positionMs = 0L
        var durationMs = 600_000L
        var hasEnded = false

        override fun capturePosition(): PlaybackPosition? {
            events += "capture"
            return PlaybackPosition(mediaId, positionMs, durationMs, hasEnded)
        }

        fun emitPlaying() {
            mutableSnapshot.value = PlaybackSnapshot(
                mediaId = mediaId.encode(),
                isPlaying = true,
                playWhenReady = true,
                playbackState = Player.STATE_READY,
            )
        }

        fun emitPaused() {
            mutableSnapshot.value = PlaybackSnapshot(
                mediaId = mediaId.encode(),
                isPlaying = false,
                playWhenReady = false,
                playbackState = Player.STATE_READY,
            )
        }

        fun emitEnded() {
            mutableSnapshot.value = PlaybackSnapshot(
                mediaId = mediaId.encode(),
                isPlaying = false,
                playWhenReady = false,
                playbackState = Player.STATE_ENDED,
            )
        }
    }

    private class FakeLiveRepository : LiveRepository {
        override suspend fun epg(accountId: String, streamId: String): List<EpgEntry> = emptyList()
        override suspend fun search(
            accountId: String,
            term: String,
            limit: Int,
        ): SearchSection<LiveChannel> = SearchSection()
        val recent = mutableListOf<String>()

        override suspend fun getChannel(accountId: String, streamId: String) = LiveChannel(
            id = streamId,
            categoryId = "7",
            name = "Kanal $streamId",
            logoUrl = null,
            epgChannelId = null,
            containerExtension = "ts",
            directSource = null,
            providerOrder = 1,
        )

        override suspend fun markRecent(accountId: String, streamId: String) {
            recent += streamId
        }

        override fun observeCategories(accountId: String) = flowOf(emptyList<LiveCategory>())
        override fun observeLanguages(accountId: String) = flowOf(emptyList<String>())
        override fun channels(
            accountId: String,
            filter: LiveFilter,
        ): Flow<PagingData<LiveChannel>> = flowOf(PagingData.empty())

        override fun observeRecent(accountId: String, limit: Int) =
            flowOf(emptyList<LiveChannel>())

        override suspend fun hasCachedLibrary(accountId: String) = true
        override suspend fun refresh(
            accountId: String,
            onProgress: (Int) -> Unit,
        ): LiveSyncResult = LiveSyncResult(0, 0, 0L)
    }

    private class FakeSeriesRepository : SeriesRepository {
        override fun observeRecentlyAdded(
            accountId: String,
            limit: Int,
        ): Flow<List<RecentlyAddedEntry>> = flowOf(emptyList())

        override suspend fun previousEpisode(accountId: String, episodeId: String): SeriesEpisode? =
            previous
        override suspend fun setEpisodeWatched(
            accountId: String,
            episodeId: String,
            watched: Boolean,
        ) = Unit
        override suspend fun search(
            accountId: String,
            term: String,
            limit: Int,
        ): SearchSection<SeriesSummary> = SearchSection()
        val saved = mutableListOf<Pair<String, Long>>()
        var progress: WatchProgress? = null
        var episode: SeriesEpisode? = SeriesEpisode(
            id = "9012",
            seriesId = "7",
            seasonNumber = 1,
            episodeNumber = 3,
            title = "Beispielfolge",
            containerExtension = "mkv",
            durationSeconds = 2_700,
            plot = null,
            stillUrl = null,
        )

        var next: SeriesEpisode? = null
        var previous: SeriesEpisode? = null

        override suspend fun getEpisode(accountId: String, episodeId: String) = episode

        override suspend fun nextEpisode(accountId: String, episodeId: String) = next

        override suspend fun episodeProgress(accountId: String, episodeId: String) = progress

        override suspend fun saveEpisodeProgress(
            accountId: String,
            episodeId: String,
            positionMs: Long,
            durationMs: Long,
        ) {
            saved += episodeId to positionMs
        }

        override fun observeCategories(accountId: String) = flowOf(emptyList<SeriesCategory>())
        override fun observeLanguages(accountId: String) = flowOf(emptyList<String>())
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

        override suspend fun hasCachedLibrary(accountId: String) = true
        override suspend fun refresh(
            accountId: String,
            onProgress: (Int) -> Unit,
        ): SeriesSyncResult = SeriesSyncResult(0, 0, 0L)

        override fun observeEpisodeProgress(
            accountId: String,
            seriesId: String,
        ): Flow<Map<String, WatchProgress>> = flowOf(emptyMap())


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
        val saved = mutableListOf<Pair<String, Long>>()
        var progress: WatchProgress? = null
        var movie: MovieSummary? = MovieSummary(
            id = "501",
            categoryId = "20",
            name = "Beispielfilm",
            posterUrl = null,
            containerExtension = "mkv",
            rating = null,
            releaseYear = null,
            addedAtEpochSeconds = null,
            providerOrder = 1,
        )

        override suspend fun getMovie(accountId: String, movieId: String) = movie

        override suspend fun progress(accountId: String, movieId: String) = progress

        override suspend fun saveProgress(
            accountId: String,
            movieId: String,
            positionMs: Long,
            durationMs: Long,
        ) {
            saved += movieId to positionMs
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

        override fun observeProgress(accountId: String, movieId: String): Flow<WatchProgress?> =
            flowOf(progress)
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
