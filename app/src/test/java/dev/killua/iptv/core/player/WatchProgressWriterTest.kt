package dev.killua.iptv.core.player

import androidx.paging.PagingData
import com.google.common.truth.Truth.assertThat
import dev.killua.iptv.domain.model.RecentlyAddedEntry
import dev.killua.iptv.domain.model.ContinueWatchingEntry
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
import dev.killua.iptv.domain.repository.MovieRepository
import dev.killua.iptv.domain.repository.MovieSyncResult
import dev.killua.iptv.domain.repository.SeriesRepository
import dev.killua.iptv.domain.repository.SeriesSyncResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The rules that decide whether a captured position is worth storing. All fixtures are fictitious.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WatchProgressWriterTest {
    private val movies = RecordingMovieRepository()
    private val series = RecordingSeriesRepository()

    @Test
    fun `a position for the playing title is written`() = runTest {
        val writer = createWriter()

        val accepted = writer.checkpoint(position(positionMs = 61_000L), MOVIE)
        testScheduler.advanceUntilIdle()

        assertThat(accepted).isTrue()
        assertThat(movies.saved).containsExactly(Saved("account-1", "501", 61_000L, 600_000L))
    }

    @Test
    fun `an episode position is routed to the series library, not the movie one`() = runTest {
        val writer = createWriter()

        val accepted = writer.checkpoint(
            position(mediaId = EPISODE, positionMs = 61_000L),
            EPISODE,
        )
        testScheduler.advanceUntilIdle()

        assertThat(accepted).isTrue()
        assertThat(series.saved).containsExactly(Saved("account-1", "9012", 61_000L, 600_000L))
        assertThat(movies.saved).isEmpty()
    }

    @Test
    fun `a movie and an episode with the same content id stay separate`() = runTest {
        val writer = createWriter()

        // Providers number their movies and episodes independently, so the ids do collide.
        writer.checkpoint(position(mediaId = MOVIE, positionMs = 61_000L), MOVIE)
        writer.checkpoint(
            position(mediaId = PlaybackMediaId.Episode("account-1", "501"), positionMs = 61_000L),
            PlaybackMediaId.Episode("account-1", "501"),
        )
        testScheduler.advanceUntilIdle()

        assertThat(movies.saved.map { it.contentId }).containsExactly("501")
        assertThat(series.saved.map { it.contentId }).containsExactly("501")
    }

    @Test
    fun `a position captured from another title is refused`() = runTest {
        val writer = createWriter()

        val accepted = writer.checkpoint(
            position(mediaId = PlaybackMediaId.Movie("account-1", "999")),
            MOVIE,
        )
        testScheduler.advanceUntilIdle()

        assertThat(accepted).isFalse()
        assertThat(movies.saved).isEmpty()
    }

    @Test
    fun `an episode position captured while a movie plays is refused`() = runTest {
        val writer = createWriter()

        // Same account and same content id, but the other content type.
        val accepted = writer.checkpoint(
            position(mediaId = PlaybackMediaId.Episode("account-1", "501")),
            MOVIE,
        )
        testScheduler.advanceUntilIdle()

        assertThat(accepted).isFalse()
        assertThat(movies.saved).isEmpty()
        assertThat(series.saved).isEmpty()
    }

    @Test
    fun `a position from another account is refused even for the same title`() = runTest {
        val writer = createWriter()

        val accepted = writer.checkpoint(
            position(mediaId = PlaybackMediaId.Movie("account-2", "501")),
            MOVIE,
        )
        testScheduler.advanceUntilIdle()

        assertThat(accepted).isFalse()
        assertThat(movies.saved).isEmpty()
    }

    @Test
    fun `a source that has not reported a duration is refused`() = runTest {
        val writer = createWriter()

        val accepted = writer.checkpoint(position(durationMs = 0L), MOVIE)
        testScheduler.advanceUntilIdle()

        assertThat(accepted).isFalse()
        assertThat(movies.saved).isEmpty()
    }

    @Test
    fun `repeating the same position writes once`() = runTest {
        val writer = createWriter()

        assertThat(writer.checkpoint(position(positionMs = 61_000L), MOVIE)).isTrue()
        assertThat(writer.checkpoint(position(positionMs = 61_000L), MOVIE)).isFalse()
        assertThat(writer.checkpoint(position(positionMs = 71_000L), MOVIE)).isTrue()
        testScheduler.advanceUntilIdle()

        assertThat(movies.saved.map { it.positionMs }).containsExactly(61_000L, 71_000L).inOrder()
    }

    @Test
    fun `a finished title stores its full duration so completion is deterministic`() = runTest {
        val writer = createWriter()

        // A player at the end reports slightly short of the duration.
        writer.checkpoint(position(positionMs = 599_100L, hasEnded = true), MOVIE)
        testScheduler.advanceUntilIdle()

        assertThat(movies.saved.single().positionMs).isEqualTo(600_000L)
    }

    @Test
    fun `a position beyond the duration is clamped`() = runTest {
        val writer = createWriter()

        writer.checkpoint(position(positionMs = 700_000L), MOVIE)
        testScheduler.advanceUntilIdle()

        assertThat(movies.saved.single().positionMs).isEqualTo(600_000L)
    }

    @Test
    fun `a repository failure never escapes the writer`() = runTest {
        movies.failure = IllegalStateException("database is busy")
        val writer = createWriter()

        writer.checkpoint(position(positionMs = 61_000L), MOVIE)
        testScheduler.advanceUntilIdle()

        assertThat(movies.saved).isEmpty()
    }

    @Test
    fun `a series failure never escapes the writer either`() = runTest {
        series.failure = IllegalStateException("database is busy")
        val writer = createWriter()

        writer.checkpoint(position(mediaId = EPISODE), EPISODE)
        testScheduler.advanceUntilIdle()

        assertThat(series.saved).isEmpty()
    }

    private fun TestScope.createWriter() = WatchProgressWriter(this, movies, series)

    private fun position(
        mediaId: PlaybackMediaId = MOVIE,
        positionMs: Long = 61_000L,
        durationMs: Long = 600_000L,
        hasEnded: Boolean = false,
    ) = PlaybackPosition(mediaId, positionMs, durationMs, hasEnded)

    private data class Saved(
        val accountId: String,
        val contentId: String,
        val positionMs: Long,
        val durationMs: Long,
    )

    private class RecordingMovieRepository : MovieRepository {
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
        val saved = mutableListOf<Saved>()
        var failure: Throwable? = null

        override suspend fun saveProgress(
            accountId: String,
            movieId: String,
            positionMs: Long,
            durationMs: Long,
        ) {
            failure?.let { throw it }
            saved += Saved(accountId, movieId, positionMs, durationMs)
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

        override suspend fun hasCachedLibrary(accountId: String) = false
        override suspend fun refresh(
            accountId: String,
            onProgress: (Int) -> Unit,
        ): MovieSyncResult = MovieSyncResult(0, 0, 0L)

        override suspend fun progress(accountId: String, movieId: String): WatchProgress? = null
        override fun observeProgress(accountId: String, movieId: String): Flow<WatchProgress?> =
            flowOf(null)
    }

    private class RecordingSeriesRepository : SeriesRepository {
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
        val saved = mutableListOf<Saved>()
        var failure: Throwable? = null

        override suspend fun saveEpisodeProgress(
            accountId: String,
            episodeId: String,
            positionMs: Long,
            durationMs: Long,
        ) {
            failure?.let { throw it }
            saved += Saved(accountId, episodeId, positionMs, durationMs)
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

        override suspend fun getEpisode(accountId: String, episodeId: String): SeriesEpisode? = null
        override suspend fun hasCachedLibrary(accountId: String) = false
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
        val MOVIE = PlaybackMediaId.Movie("account-1", "501")
        val EPISODE = PlaybackMediaId.Episode("account-1", "9012")
    }
}
