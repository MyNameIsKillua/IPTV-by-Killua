package dev.killua.iptv.desktop

import com.google.common.truth.Truth.assertThat
import dev.killua.iptv.domain.model.LiveChannel
import dev.killua.iptv.domain.model.MovieSummary
import dev.killua.iptv.domain.model.SeriesSummary
import dev.killua.iptv.domain.model.XtreamCredentials
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException

/**
 * The first read of a library, and the three rules that keep it from being all-or-nothing.
 *
 * A provider that refuses one listing must not cost the viewer the other two; a listing larger than
 * this client can hold must stop rather than exhaust the heap; and each library must become usable
 * as it lands rather than all three at the end — that last one is what lets the progress panel be
 * skipped instead of waited through.
 */
class LibrarySyncTest {

    private val credentials = XtreamCredentials(
        accountId = "desktop",
        serverUrl = "https://provider.example",
        username = "viewer",
        password = "secret",
    )

    @Test
    fun `it reports each library as it lands`() = runTest {
        val states = mutableListOf<LibrarySyncState>()
        val indexes = mutableListOf<LibraryIndex>()

        val index = loadLibrary(
            client = FakeReader(channels = channels(3), movies = movies(2), series = series(1)),
            credentials = credentials,
            onState = { states += it },
            onIndex = { indexes += it },
        )

        assertThat(index.countOf(LibraryKind.Channels)).isEqualTo(3)
        assertThat(index.countOf(LibraryKind.Movies)).isEqualTo(2)
        assertThat(index.countOf(LibraryKind.Series)).isEqualTo(1)
        assertThat(index.loaded).containsExactlyElementsIn(LibraryKind.entries)
        assertThat(states.last().finished).isTrue()
        assertThat(states.last().step).isNull()

        // Three handovers, one per library, each carrying what was ready by then. The first one is
        // what makes Live usable while the films are still arriving.
        assertThat(indexes).hasSize(3)
        assertThat(indexes.first().has(LibraryKind.Channels)).isTrue()
        assertThat(indexes.first().has(LibraryKind.Movies)).isFalse()
    }

    @Test
    fun `an index handed over early does not change underneath its reader`() = runTest {
        val indexes = mutableListOf<LibraryIndex>()

        loadLibrary(
            client = FakeReader(channels = channels(2), movies = movies(5), series = series(1)),
            credentials = credentials,
            onState = {},
            onIndex = { indexes += it },
        )

        // The lists inside the loader go on growing. If one were handed over by reference rather
        // than copied, this first snapshot would now be reporting the finished library.
        assertThat(indexes.first().countOf(LibraryKind.Channels)).isEqualTo(2)
        assertThat(indexes.first().countOf(LibraryKind.Movies)).isEqualTo(0)
    }

    @Test
    fun `one refused listing does not cost the other two`() = runTest {
        val states = mutableListOf<LibrarySyncState>()

        val index = loadLibrary(
            client = FakeReader(
                channels = channels(2),
                moviesFailure = ProviderRefused(403),
                series = series(2),
            ),
            credentials = credentials,
            onState = { states += it },
            onIndex = {},
        )

        assertThat(index.has(LibraryKind.Channels)).isTrue()
        assertThat(index.has(LibraryKind.Series)).isTrue()
        assertThat(index.has(LibraryKind.Movies)).isFalse()
        assertThat(states.last().failed.keys).containsExactly(LibraryKind.Movies)
        // The account, not the network — the same words the browsing screen uses for a 403.
        assertThat(states.last().failed[LibraryKind.Movies]).contains("provider refused")
    }

    @Test
    fun `a fault says so without quoting the provider`() = runTest {
        val states = mutableListOf<LibrarySyncState>()

        loadLibrary(
            client = FakeReader(channelsFailure = IOException("HTTP 500 at https://provider.example/x")),
            credentials = credentials,
            onState = { states += it },
            onIndex = {},
        )

        val message = states.last().failed.getValue(LibraryKind.Channels)
        assertThat(message).isEqualTo("That listing could not be read.")
        // A raw failure can carry a URL with credentials in it. None of it reaches a viewer.
        assertThat(message).doesNotContain("provider.example")
        assertThat(message).doesNotContain("500")
    }

    @Test
    fun `a partial listing is dropped rather than shown as the whole library`() = runTest {
        // Half a film library looks exactly like a small one, and a viewer would go looking for a
        // title that is simply missing. Falling back to browsing by category is the honest answer.
        val index = loadLibrary(
            client = FakeReader(
                movies = movies(10),
                moviesFailure = ProviderRefused(403),
            ),
            credentials = credentials,
            onState = {},
            onIndex = {},
        )

        assertThat(index.movies).isEmpty()
        assertThat(index.has(LibraryKind.Movies)).isFalse()
    }

    @Test
    fun `it waits for the picture rather than taking a second connection`() = runTest {
        // A category held a connection for a second; a whole listing holds one for minutes. On an
        // account that allows a single connection at a time, reading through a film is the
        // difference between one that plays and one that is refused.
        var playing = true
        val states = mutableListOf<LibrarySyncState>()

        val reading = async {
            loadLibrary(
                client = FakeReader(channels = channels(2), movies = movies(2), series = series(2)),
                credentials = credentials,
                onState = { states += it },
                onIndex = {},
                isPlaying = { playing },
            )
        }

        // Long enough that a loader which ignored the question would have finished by now.
        advanceTimeBy(10_000)
        assertThat(reading.isCompleted).isFalse()
        assertThat(states.last().paused).isTrue()
        assertThat(states.last().counts).isEmpty()

        playing = false
        val index = reading.await()

        assertThat(index.loaded).containsExactlyElementsIn(LibraryKind.entries)
        // And it says when it is waiting rather than looking stalled.
        assertThat(states.last().paused).isFalse()
    }

    @Test
    fun `a listing larger than the cap stops at it and says so`() = runTest {
        val index = loadLibrary(
            client = FakeReader(movies = movies(LibraryIndex.MAX_ITEMS + 50)),
            credentials = credentials,
            onState = {},
            onIndex = {},
        )

        assertThat(index.countOf(LibraryKind.Movies)).isEqualTo(LibraryIndex.MAX_ITEMS)
        assertThat(index.truncated).contains(LibraryKind.Movies)
        // Still usable: most of a library beats none of it, and Settings names it as partial.
        assertThat(index.has(LibraryKind.Movies)).isTrue()
    }

    private fun channels(count: Int) = (1..count).map {
        LiveChannel(
            id = it.toString(),
            categoryId = "1",
            name = "Channel $it",
            logoUrl = null,
            epgChannelId = null,
            containerExtension = null,
            directSource = null,
            providerOrder = it,
        )
    }

    private fun movies(count: Int) = (1..count).map {
        MovieSummary(
            id = it.toString(),
            categoryId = "1",
            name = "Film $it",
            posterUrl = null,
            containerExtension = "mkv",
            rating = null,
            releaseYear = null,
            addedAtEpochSeconds = it.toLong(),
            providerOrder = it,
        )
    }

    private fun series(count: Int) = (1..count).map {
        SeriesSummary(
            id = it.toString(),
            categoryId = "1",
            name = "Series $it",
            posterUrl = null,
            rating = null,
            releaseYear = null,
            lastModifiedEpochSeconds = it.toLong(),
            providerOrder = it,
        )
    }

    /** A provider that answers from a list, or refuses. No sockets, no JSON, no timing. */
    private class FakeReader(
        val channels: List<LiveChannel> = emptyList(),
        val movies: List<MovieSummary> = emptyList(),
        val series: List<SeriesSummary> = emptyList(),
        val channelsFailure: Throwable? = null,
        val moviesFailure: Throwable? = null,
        val seriesFailure: Throwable? = null,
    ) : LibraryReader {

        override suspend fun <T> withAllChannels(
            credentials: XtreamCredentials,
            block: suspend (Sequence<LiveChannel>) -> T,
        ): T = answer(channels, channelsFailure, block)

        override suspend fun <T> withAllMovies(
            credentials: XtreamCredentials,
            block: suspend (Sequence<MovieSummary>) -> T,
        ): T = answer(movies, moviesFailure, block)

        override suspend fun <T> withAllSeries(
            credentials: XtreamCredentials,
            block: suspend (Sequence<SeriesSummary>) -> T,
        ): T = answer(series, seriesFailure, block)

        /**
         * Hands the sequence over and *then* fails, where a failure was asked for.
         *
         * That is the order a real one fails in: a listing arrives in pieces, and the connection
         * drops in the middle of it. Failing before the block ran would never exercise the rule that
         * half a library is thrown away rather than shown.
         */
        private suspend fun <E, T> answer(
            items: List<E>,
            failure: Throwable?,
            block: suspend (Sequence<E>) -> T,
        ): T {
            val result = block(items.asSequence())
            if (failure != null) throw failure
            return result
        }
    }
}
