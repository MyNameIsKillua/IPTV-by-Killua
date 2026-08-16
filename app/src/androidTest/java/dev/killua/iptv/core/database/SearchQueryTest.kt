package dev.killua.iptv.core.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.killua.iptv.data.xtream.XtreamLanguageTagger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The global search statements, against real SQLite.
 *
 * These cannot be JVM tests: the DAO fakes match in memory and never parse the SQL, which is
 * exactly how a malformed `ESCAPE` clause reached a device once — the query compiled, every unit
 * test passed, and SQLite rejected it at runtime with "ESCAPE expression must be a single
 * character". Anything about the search *statement* belongs here.
 *
 * All fixtures are fictitious.
 */
@RunWith(AndroidJUnit4::class)
class SearchQueryTest {
    private lateinit var database: IptvDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            IptvDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun searchRunsAndMatchesAnywhereInTheName() = runTest {
        seed()

        val channels = database.liveDao()
            .searchChannels(ACCOUNT, LikeSearchTerm.containsPattern("erst"), 10)
        val movies = database.movieDao()
            .searchMovies(ACCOUNT, LikeSearchTerm.containsPattern("film"), 10)
        val series = database.seriesDao()
            .searchSeries(ACCOUNT, LikeSearchTerm.containsPattern("serie"), 10)

        assertThat(channels.map { it.remoteStreamId }).containsExactly("41")
        assertThat(movies.map { it.remoteStreamId }).containsExactly("501", "502")
        assertThat(series.map { it.remoteSeriesId }).containsExactly("7")
    }

    @Test
    fun aTitleWithPunctuationIsFoundHoweverItIsTyped() = runTest {
        seed()
        database.movieDao().upsertMovies(listOf(movie("504", "Mr. Robot")))

        // The reported bug, against real SQLite: the stored key kept the dot, so only the exact
        // spelling matched. All three of these have to reach the same row now.
        listOf("mr robot", "mr. robot", "Mr Robot").forEach { term ->
            val movies = database.movieDao()
                .searchMovies(ACCOUNT, LikeSearchTerm.containsPattern(term), 10)
            assertThat(movies.map { it.remoteStreamId }).containsExactly("504")
        }
    }

    @Test
    fun anEscapedWildcardStillParsesAndMatchesLiterally() = runTest {
        seed()

        // Folding removes wildcards before they can reach a pattern, but the ESCAPE clause still
        // has to be well formed: a malformed one is a runtime SQLite error, which is how a
        // two-backslash escape once shipped past every unit test.
        val movies = database.movieDao()
            .searchMovies(ACCOUNT, "%${LikeSearchTerm.escape("100%")}%", 10)

        // Unescaped, this pattern would return the whole library.
        assertThat(movies).isEmpty()
    }

    @Test
    fun searchIsScopedToTheAccountAndBoundedByTheLimit() = runTest {
        seed()

        val other = database.movieDao()
            .searchMovies("another-account", LikeSearchTerm.containsPattern("film"), 10)
        val bounded = database.movieDao()
            .searchMovies(ACCOUNT, LikeSearchTerm.containsPattern("film"), 1)

        assertThat(other).isEmpty()
        assertThat(bounded).hasSize(1)
    }

    @Test
    fun theWatchlistUnionOrdersEveryLibraryTogether() = runTest {
        seed()
        val dao = database.watchlistDao()
        // Saved out of order, so the ordering cannot come from insertion order by accident.
        dao.upsert(WatchlistEntity(ACCOUNT, "movie", "501", addedAtEpochMillis = 100))
        dao.upsert(WatchlistEntity(ACCOUNT, "channel", "41", addedAtEpochMillis = 300))
        dao.upsert(WatchlistEntity(ACCOUNT, "series", "7", addedAtEpochMillis = 200))
        // A saved row whose title this account does not have must not surface.
        dao.upsert(WatchlistEntity(ACCOUNT, "movie", "does-not-exist", addedAtEpochMillis = 400))

        val saved = dao.observeSaved(ACCOUNT, "movie", "series", "channel", 10).first()

        assertThat(saved.map { it.contentType }).containsExactly("channel", "series", "movie")
            .inOrder()
        assertThat(saved.map { it.name })
            .containsExactly("DE | Erster Kanal", "Beispielserie", "Beispielfilm").inOrder()
    }

    @Test
    fun theWatchlistIsScopedToTheAccountAndBoundedByTheLimit() = runTest {
        seed()
        val dao = database.watchlistDao()
        dao.upsert(WatchlistEntity(ACCOUNT, "movie", "501", addedAtEpochMillis = 100))
        dao.upsert(WatchlistEntity(ACCOUNT, "series", "7", addedAtEpochMillis = 200))

        assertThat(dao.observeSaved("another-account", "movie", "series", "channel", 10).first())
            .isEmpty()
        assertThat(dao.observeSaved(ACCOUNT, "movie", "series", "channel", 1).first()).hasSize(1)
        assertThat(dao.observeIsSaved(ACCOUNT, "movie", "501").first()).isTrue()
        assertThat(dao.observeIsSaved(ACCOUNT, "movie", "502").first()).isFalse()
    }

    /**
     * The set the paged channel list marks its rows from. It deliberately does **not** join the
     * library, unlike the saved list itself: a channel the provider has temporarily dropped must
     * still show as saved, otherwise the bookmark would appear to have been forgotten.
     */
    @Test
    fun savedIdsAreReturnedPerKindAndWithoutJoiningTheLibrary() = runTest {
        seed()
        val dao = database.watchlistDao()
        dao.upsert(WatchlistEntity(ACCOUNT, "channel", "41", addedAtEpochMillis = 100))
        dao.upsert(WatchlistEntity(ACCOUNT, "channel", "vanished", addedAtEpochMillis = 200))
        dao.upsert(WatchlistEntity(ACCOUNT, "movie", "501", addedAtEpochMillis = 300))
        dao.upsert(WatchlistEntity("another-account", "channel", "99", addedAtEpochMillis = 400))

        val channels = dao.observeSavedIds(ACCOUNT, "channel").first()

        // "vanished" has no row in live_channels and is still reported.
        assertThat(channels).containsExactly("41", "vanished")
        assertThat(dao.observeSavedIds(ACCOUNT, "movie").first()).containsExactly("501")
        assertThat(dao.observeSavedIds(ACCOUNT, "series").first()).isEmpty()
        assertThat(dao.observeSavedIds("another-account", "channel").first()).containsExactly("99")
    }

    private suspend fun seed() {
        database.liveDao().upsertChannels(
            listOf(
                LiveChannelEntity(
                    accountId = ACCOUNT,
                    remoteStreamId = "41",
                    remoteCategoryId = "7",
                    name = "DE | Erster Kanal",
                    sortName = LiveChannelEntity.sortNameOf("DE | Erster Kanal"),
                    logoUrl = null,
                    epgChannelId = null,
                    containerExtension = "ts",
                    languageTag = "de",
                    providerOrder = 1,
                    syncGeneration = 1,
                ),
            ),
        )
        database.movieDao().upsertMovies(
            listOf(movie("501", "Beispielfilm"), movie("502", "Zweiter Film")),
        )
        database.seriesDao().upsertSeries(
            listOf(
                SeriesEntity(
                    accountId = ACCOUNT,
                    remoteSeriesId = "7",
                    remoteCategoryId = "90",
                    name = "Beispielserie",
                    sortName = XtreamLanguageTagger.sortNameOf("Beispielserie"),
                    posterUrl = null,
                    rating = null,
                    releaseYear = null,
                    lastModifiedEpochSeconds = null,
                    languageTag = "de",
                    providerOrder = 1,
                    syncGeneration = 1,
                ),
            ),
        )
    }

    private fun movie(id: String, name: String) = MovieEntity(
        accountId = ACCOUNT,
        remoteStreamId = id,
        remoteCategoryId = "20",
        name = name,
        // The production write path, so the fixtures cannot drift from what the app stores.
        sortName = XtreamLanguageTagger.sortNameOf(name),
        posterUrl = null,
        containerExtension = "mkv",
        rating = null,
        releaseYear = null,
        addedAtEpochSeconds = null,
        languageTag = null,
        providerOrder = 1,
        syncGeneration = 1,
    )

    private companion object {
        const val ACCOUNT = "account-under-test"
    }
}
