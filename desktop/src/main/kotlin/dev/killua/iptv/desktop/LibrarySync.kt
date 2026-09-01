package dev.killua.iptv.desktop

import dev.killua.iptv.domain.model.LiveChannel
import dev.killua.iptv.domain.model.MovieSummary
import dev.killua.iptv.domain.model.SeriesSummary
import dev.killua.iptv.domain.model.XtreamCredentials
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * The three whole-library requests, as the only thing [loadLibrary] needs from a provider.
 *
 * An interface with one real implementation, which is a shape worth justifying: the loader's rules —
 * the item cap, one failed listing not taking the other two down with it, an index handed over after
 * every step — are the part that can go wrong quietly, and they are untestable against a class that
 * opens sockets. This is the seam a fake goes through.
 */
interface LibraryReader {
    /**
     * Which listings this source has at all, as opposed to which ones happen to be empty.
     *
     * A provider has three. A playlist has one, because the M3U format has no films, no series and
     * no guide endpoint - so the loader skips what does not exist rather than reading two empty
     * listings and leaving the screen to show them as libraries that came back with nothing.
     */
    val offers: Set<LibraryKind> get() = LibraryKind.entries.toSet()

    suspend fun <T> withAllChannels(
        credentials: XtreamCredentials,
        block: suspend (Sequence<LiveChannel>) -> T,
    ): T

    suspend fun <T> withAllMovies(
        credentials: XtreamCredentials,
        block: suspend (Sequence<MovieSummary>) -> T,
    ): T

    suspend fun <T> withAllSeries(
        credentials: XtreamCredentials,
        block: suspend (Sequence<SeriesSummary>) -> T,
    ): T
}

/**
 * How far the first load has got, as the screen reads it.
 *
 * One value per library rather than a single percentage, because the three take wildly different
 * times on a real provider and a bar that spends four minutes between 30% and 31% tells a viewer
 * less than a count that is visibly climbing.
 */
data class LibrarySyncState(
    /** Which libraries this source has, and therefore which rows the screen should show at all. */
    val expected: Set<LibraryKind> = LibraryKind.entries.toSet(),
    /** Which library is being read right now, or null once nothing is. */
    val step: LibraryKind? = null,
    val counts: Map<LibraryKind, Int> = emptyMap(),
    val done: Set<LibraryKind> = emptySet(),
    /** What went wrong per library, in the client's own words — never the provider's body. */
    val failed: Map<LibraryKind, String> = emptyMap(),
    /** True while the read is standing aside for playback. See [loadLibrary]. */
    val paused: Boolean = false,
    /** True when nothing was read at all because what was kept on disk was still good. */
    val fromDisk: Boolean = false,
    val finished: Boolean = false,
) {
    fun countOf(kind: LibraryKind): Int = counts[kind] ?: 0
    fun isDone(kind: LibraryKind): Boolean = kind in done
    fun isActive(kind: LibraryKind): Boolean = step == kind
    val hasFailure: Boolean get() = failed.isNotEmpty()
}

/**
 * Reads the whole library, once, reporting as it goes.
 *
 * Three requests, in the order a viewer notices them: channels first because Live is what most
 * people open, then films, then series. Each is independent — a provider that refuses one still
 * gives the other two, and a library that failed simply falls back to being browsed one category at
 * a time, which is how this client worked before it had this screen at all.
 *
 * **Watching wins.** [isPlaying] is asked before each listing, and a read that would start while
 * something is on screen waits instead. This matters because of what these three requests are: a
 * category used to hold a connection for a second, and a whole listing holds one for minutes — on an
 * account that allows a single connection at a time, that is the difference between a film that
 * plays and one that is refused. The library is background work and can wait; an evening cannot.
 *
 * It is asked *between* steps rather than during one, because there is no way to pause a response
 * that is already arriving — abandoning it would throw away minutes of reading, and holding it open
 * would keep exactly the connection this is trying to free.
 *
 * The index is handed over **after every step**, not only at the end. That is what lets the viewer
 * leave this screen immediately: Live becomes whole while films are still arriving, and each library
 * simply appears when it is ready rather than all three appearing together or not at all.
 *
 * Nothing here writes to disk. See [LibraryIndex] for why that is the line being held.
 */
suspend fun loadLibrary(
    client: LibraryReader,
    credentials: XtreamCredentials,
    onState: (LibrarySyncState) -> Unit,
    onIndex: (LibraryIndex) -> Unit,
    isPlaying: () -> Boolean = { false },
): LibraryIndex {
    var state = LibrarySyncState(expected = client.offers)
    fun publish(next: LibrarySyncState) {
        state = next
        onState(next)
    }

    val channels = ArrayList<LiveChannel>()
    val movies = ArrayList<MovieSummary>()
    val series = ArrayList<SeriesSummary>()
    val truncated = mutableSetOf<LibraryKind>()

    // Copied rather than wrapped: the lists above go on growing, and an index holding a reference to
    // one of them would be a snapshot that changes underneath whatever is reading it.
    fun indexSoFar() = LibraryIndex(
        channels = channels.toList(),
        movies = movies.toList(),
        series = series.toList(),
        loaded = state.done,
        truncated = truncated.toSet(),
    )

    /** Stands aside while something is playing, and says so rather than looking stalled. */
    suspend fun waitForTheScreen() {
        if (!isPlaying()) return
        publish(state.copy(paused = true))
        while (isPlaying()) delay(PAUSE_POLL_MS)
        publish(state.copy(paused = false))
    }

    suspend fun <T> step(
        kind: LibraryKind,
        into: MutableList<T>,
        request: suspend (suspend (Sequence<T>) -> Unit) -> Unit,
    ) {
        waitForTheScreen()
        publish(state.copy(step = kind))
        catchingExceptCancellation {
            request { sequence ->
                val iterator = sequence.iterator()
                while (iterator.hasNext()) {
                    // The one place a cancelled sign-out can interrupt a listing that takes
                    // minutes. Without it, a window closed mid-load goes on parsing into a list
                    // nothing will ever read.
                    coroutineContext.ensureActive()
                    into += iterator.next()
                    if (into.size >= LibraryIndex.MAX_ITEMS) {
                        truncated += kind
                        break
                    }
                    // Often enough to look continuous, rarely enough not to recompose per title.
                    if (into.size % PROGRESS_EVERY == 0) {
                        publish(state.copy(counts = state.counts + (kind to into.size)))
                    }
                }
            }
        }.onFailure { failure ->
            into.clear()
            publish(
                state.copy(
                    failed = state.failed + (kind to (
                        (failure as? ProviderRefused)?.let { providerRefusedMessage(it.code) }
                            ?: "That listing could not be read."
                        )),
                ),
            )
        }.onSuccess {
            publish(
                state.copy(
                    counts = state.counts + (kind to into.size),
                    done = state.done + kind,
                ),
            )
            onIndex(indexSoFar())
        }
    }

    if (LibraryKind.Channels in client.offers) {
        step(LibraryKind.Channels, channels) { consume ->
            client.withAllChannels(credentials) { consume(it) }
        }
    }
    if (LibraryKind.Movies in client.offers) {
        step(LibraryKind.Movies, movies) { consume ->
            client.withAllMovies(credentials) { consume(it) }
        }
    }
    if (LibraryKind.Series in client.offers) {
        step(LibraryKind.Series, series) { consume ->
            client.withAllSeries(credentials) { consume(it) }
        }
    }

    publish(state.copy(step = null, finished = true))
    return indexSoFar()
}

/** How many titles pass before the count on screen moves again. */
private const val PROGRESS_EVERY = 500

/** How often a paused read looks up to see whether the picture has stopped. */
private const val PAUSE_POLL_MS = 1_000L
