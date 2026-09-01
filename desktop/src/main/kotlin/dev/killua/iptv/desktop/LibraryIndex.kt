package dev.killua.iptv.desktop

import dev.killua.iptv.core.text.SearchTextNormalizer
import dev.killua.iptv.domain.browse.RecentlyAdded
import dev.killua.iptv.domain.model.LiveChannel
import dev.killua.iptv.domain.model.MovieSummary
import dev.killua.iptv.domain.model.RecentlyAddedEntry
import dev.killua.iptv.domain.model.ResumableKind
import dev.killua.iptv.domain.model.SeriesSummary

/** The three listings, as something that can be counted, named and reported on one at a time. */
enum class LibraryKind(val label: String) {
    Channels("Channels"),
    Movies("Films"),
    Series("Series"),
}

/**
 * Everything the provider lists, held in memory for as long as this sign-in lasts.
 *
 * This is a **deliberate reversal** of the rule the Windows client started with, which was that
 * browsing goes one category at a time and no listing is ever asked for whole. That rule bought the
 * client its lack of a database, and it cost the two things the owner asked for after using it: a
 * library that is simply *there* the way the phone's is, and a search that can find a title without
 * being told which shelf it is on. Neither is reachable one category at a time — `player_api.php`
 * has no search action, so a search over a library nobody has downloaded is a search over nothing.
 *
 * What has **not** changed is that none of this is written down. There is still no cache on disk, no
 * schema, and no migration: this lives and dies with the window, and the next launch asks the
 * provider again. That is the part of the original rule worth keeping — a stale library that has to
 * be reconciled is a database, and a database is a decision this client has not made.
 *
 * The listings are streamed into it through the shared parser rather than read as one string, for
 * the reason the phone had to learn: a six-figure listing arrives as tens of megabytes, and holding
 * the JSON *and* the objects at once doubles the worst moment for no gain.
 *
 * Names are folded **once**, here, rather than per keystroke. A search over three libraries is one
 * pass per library either way; normalizing a hundred thousand titles on every character typed is
 * what turns that from unnoticeable into a stutter.
 */
class LibraryIndex(
    val channels: List<LiveChannel> = emptyList(),
    val movies: List<MovieSummary> = emptyList(),
    val series: List<SeriesSummary> = emptyList(),
    /** Which listings were actually fetched, as opposed to empty because nothing asked yet. */
    val loaded: Set<LibraryKind> = emptySet(),
    /** Which listings hit [MAX_ITEMS] and are therefore only most of themselves. */
    val truncated: Set<LibraryKind> = emptySet(),
) {
    private val channelNames: List<String> = channels.map { SearchTextNormalizer.normalize(it.name) }
    private val movieNames: List<String> = movies.map { SearchTextNormalizer.normalize(it.name) }
    private val seriesNames: List<String> = series.map { SearchTextNormalizer.normalize(it.name) }

    /**
     * By id, built on first use.
     *
     * Lazily, because most of what this class is for never needs them: browsing walks the lists and
     * searching scans the folded names. The lookups exist for the one job the lists are bad at —
     * turning a stored id back into a title — and building three maps over a six-figure library on
     * the chance that someone opens My list would be work most sessions never use.
     */
    private val channelsById by lazy { channels.associateBy { it.id } }
    private val moviesById by lazy { movies.associateBy { it.id } }
    private val seriesById by lazy { series.associateBy { it.id } }

    fun channel(id: String): LiveChannel? = channelsById[id]
    fun movie(id: String): MovieSummary? = moviesById[id]
    fun series(id: String): SeriesSummary? = seriesById[id]

    fun has(kind: LibraryKind): Boolean = kind in loaded

    fun countOf(kind: LibraryKind): Int = when (kind) {
        LibraryKind.Channels -> channels.size
        LibraryKind.Movies -> movies.size
        LibraryKind.Series -> series.size
    }

    val isEmpty: Boolean get() = loaded.isEmpty()

    /**
     * What one library shows: a category, a typed term, both, or neither.
     *
     * Both narrowings in one pass, and the term is matched against the **folded names this class
     * already holds**. That is the whole reason it holds them. Filtering by re-normalizing every
     * title on each keystroke is a hundred thousand string builders and a regex per letter typed,
     * which is the difference between a filter box and a stutter — and it is exactly what the old
     * per-category code did, harmlessly, on lists of two hundred.
     */
    fun channelsIn(categoryId: String? = null, query: String = ""): List<LiveChannel> =
        narrow(channels, channelNames, categoryId, query) { it.categoryId }

    fun moviesIn(categoryId: String? = null, query: String = ""): List<MovieSummary> =
        narrow(movies, movieNames, categoryId, query) { it.categoryId }

    fun seriesIn(categoryId: String? = null, query: String = ""): List<SeriesSummary> =
        narrow(series, seriesNames, categoryId, query) { it.categoryId }

    private inline fun <T> narrow(
        items: List<T>,
        names: List<String>,
        categoryId: String?,
        query: String,
        categoryOf: (T) -> String?,
    ): List<T> {
        val needle = SearchTextNormalizer.normalize(query)
        // The whole library, unchanged and uncopied, which is the common case.
        if (categoryId == null && needle.isEmpty()) return items
        val kept = ArrayList<T>()
        for (index in items.indices) {
            val item = items[index]
            if (categoryId != null && categoryOf(item) != categoryId) continue
            if (needle.isNotEmpty() && !names[index].contains(needle)) continue
            kept += item
        }
        return kept
    }

    /**
     * What matches [query], across every listing that has been loaded.
     *
     * Titles that **start** with the term come first. Someone typing "the office" means the show
     * called that, not the forty films with "the office" somewhere in a subtitle, and a search that
     * buries the obvious answer is a search people stop using.
     *
     * A term under two characters is refused, exactly as the phone refuses it: one letter matches a
     * third of a library, which is not a result but a redraw.
     */
    fun search(query: String, limitPerKind: Int = SEARCH_LIMIT): LibraryHits {
        val needle = SearchTextNormalizer.normalize(query)
        if (needle.length < MIN_SEARCH_LENGTH) return LibraryHits()
        val channelHits = matches(channels, channelNames, needle, limitPerKind)
        val movieHits = matches(movies, movieNames, needle, limitPerKind)
        val seriesHits = matches(series, seriesNames, needle, limitPerKind)
        return LibraryHits(
            channels = channelHits.first,
            movies = movieHits.first,
            series = seriesHits.first,
            totals = mapOf(
                LibraryKind.Channels to channelHits.second,
                LibraryKind.Movies to movieHits.second,
                LibraryKind.Series to seriesHits.second,
            ),
        )
    }

    /**
     * The newest titles across both video libraries, or nothing when the timestamps are useless.
     *
     * The judgement of "useless" is the shared one the phone uses, and it is worth keeping in one
     * place: plenty of providers stamp an entire import with a single value, and a row ordered by a
     * column where every row is identical is an arbitrary slice of the library wearing a label.
     */
    fun recentlyAdded(limit: Int = RECENTLY_ADDED_LIMIT): List<RecentlyAddedEntry> {
        val candidates = limit * RecentlyAdded.CANDIDATE_FACTOR
        return RecentlyAdded.rowOf(
            movies = movies.asSequence()
                .mapNotNull { movie ->
                    movie.addedAtEpochSeconds?.let {
                        RecentlyAddedEntry(movie.id, ResumableKind.Movie, movie.name, movie.posterUrl, it)
                    }
                }
                .sortedByDescending { it.addedAtEpochSeconds }
                .take(candidates)
                .toList(),
            series = series.asSequence()
                .mapNotNull { show ->
                    show.lastModifiedEpochSeconds?.let {
                        RecentlyAddedEntry(show.id, ResumableKind.Series, show.name, show.posterUrl, it)
                    }
                }
                .sortedByDescending { it.addedAtEpochSeconds }
                .take(candidates)
                .toList(),
            limit = limit,
        )
    }

    /**
     * Hits and how many there were, with prefix matches ahead of the rest.
     *
     * Both lists are capped while scanning rather than after it. A one-word term on a large library
     * matches thousands of titles, and building all of them to show twenty is work nobody sees. The
     * count keeps going, because "20 of 3,400" is what tells a viewer to type more.
     */
    private fun <T> matches(
        items: List<T>,
        names: List<String>,
        needle: String,
        limit: Int,
    ): Pair<List<T>, Int> {
        val leading = ArrayList<T>(limit)
        val anywhere = ArrayList<T>(limit)
        var total = 0
        for (index in items.indices) {
            val name = names[index]
            if (!name.contains(needle)) continue
            total++
            if (name.startsWith(needle)) {
                if (leading.size < limit) leading += items[index]
            } else if (anywhere.size < limit) {
                anywhere += items[index]
            }
        }
        return (leading + anywhere).take(limit) to total
    }

    companion object {
        /**
         * How much of one listing is kept.
         *
         * Not a number any real provider reaches — the largest this project has seen is a six-figure
         * film library well under it — but the difference between a client that says "this listing
         * is larger than I can hold" and one that runs out of memory with no explanation. The heap
         * this runs in is whatever the JVM chose from the machine's RAM, and nothing here gets to
         * assume that is generous.
         */
        const val MAX_ITEMS = 250_000

        /** Below this a term is a redraw rather than a search. The phone refuses the same. */
        const val MIN_SEARCH_LENGTH = 2

        /** How many hits one library shows before the viewer is asked to narrow. */
        const val SEARCH_LIMIT = 24

        const val RECENTLY_ADDED_LIMIT = 20
    }
}

/** What a search found, per library, and how much of it is being shown. */
data class LibraryHits(
    val channels: List<LiveChannel> = emptyList(),
    val movies: List<MovieSummary> = emptyList(),
    val series: List<SeriesSummary> = emptyList(),
    val totals: Map<LibraryKind, Int> = emptyMap(),
) {
    val isEmpty: Boolean get() = channels.isEmpty() && movies.isEmpty() && series.isEmpty()

    /** How many were found in [kind], which is usually more than is on screen. */
    fun totalOf(kind: LibraryKind): Int = totals[kind] ?: 0
}
