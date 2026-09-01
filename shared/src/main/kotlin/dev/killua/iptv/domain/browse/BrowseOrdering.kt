package dev.killua.iptv.domain.browse

import dev.killua.iptv.core.text.SearchTextNormalizer
import dev.killua.iptv.data.xtream.XtreamLanguageTagger
import dev.killua.iptv.domain.model.LiveChannel
import dev.killua.iptv.domain.model.LiveSortOrder
import dev.killua.iptv.domain.model.MovieSortOrder
import dev.killua.iptv.domain.model.MovieSummary
import dev.killua.iptv.domain.model.SeriesSortOrder
import dev.killua.iptv.domain.model.SeriesSummary

/**
 * The browsing orders, for a client that holds a list rather than a database.
 *
 * Android orders in SQL because it pages over a cached six-figure library; the desktop holds one
 * category in memory and orders it there. Two implementations of one idea, as with marks — and as
 * with marks, what has to agree is the **rule**, which is why it lives here with tests rather than
 * being written twice by eye.
 *
 * Two rules run through all of them:
 *
 * **Missing values sort last**, never as zero. A film with no rating is not a film rated 0.0, and
 * putting it there would bury everything unrated under everything bad.
 *
 * **Ties break on the provider's own order.** Without that, two titles the provider rated equally
 * could swap places between one visit and the next, which reads as a list that will not sit still.
 *
 * **A name sorts under the phone's key, not under itself.** Android does not compare display names;
 * it compares a `sortName` column written when the listing was cached, and *A to Z* there is
 * `ORDER BY sortName ASC`. Comparing the raw name here meant the same category came out in a
 * different order on the two clients — `DE | Der Pate` under D on one and under D-for-Deutsch on the
 * other, and a title starting with a bracket after Z on one and under its first letter on the other.
 * That is precisely the disagreement this file's existence is an argument against, so the key is
 * built the same way rather than approximated:
 *
 * - a **film or a series** through [XtreamLanguageTagger.sortNameOf], which drops a recognised
 *   leading language tag so `DE | Avatar` sorts under A, and then folds punctuation;
 * - a **channel** through [SearchTextNormalizer] alone, deliberately keeping its prefix, because a
 *   channel's `DE |` is part of what distinguishes it from the same channel in another language
 *   rather than noise in front of a title.
 *
 * Both are already lowercase and folded by the time they are compared, so the comparison is the
 * plain one — which is also what SQLite does to those columns, and the point is to match it.
 */

fun List<MovieSummary>.orderedBy(order: MovieSortOrder): List<MovieSummary> = when (order) {
    MovieSortOrder.ProviderDefault -> sortedBy { it.providerOrder }
    MovieSortOrder.NameAscending -> orderedByName(
        descending = false,
        keyOf = { XtreamLanguageTagger.sortNameOf(it.name) },
        providerOrderOf = MovieSummary::providerOrder,
    )
    MovieSortOrder.RatingDescending -> sortedWith(
        compareByDescending<MovieSummary> { it.rating ?: Double.NEGATIVE_INFINITY }
            .thenBy { it.providerOrder },
    )
    MovieSortOrder.ReleaseYearDescending -> sortedWith(
        compareByDescending<MovieSummary> { it.releaseYear ?: Int.MIN_VALUE }
            .thenBy { it.providerOrder },
    )
    MovieSortOrder.RecentlyAdded -> sortedWith(
        compareByDescending<MovieSummary> { it.addedAtEpochSeconds ?: Long.MIN_VALUE }
            .thenBy { it.providerOrder },
    )
}

fun List<SeriesSummary>.orderedBy(order: SeriesSortOrder): List<SeriesSummary> = when (order) {
    SeriesSortOrder.ProviderDefault -> sortedBy { it.providerOrder }
    SeriesSortOrder.NameAscending -> orderedByName(
        descending = false,
        keyOf = { XtreamLanguageTagger.sortNameOf(it.name) },
        providerOrderOf = SeriesSummary::providerOrder,
    )
    SeriesSortOrder.RatingDescending -> sortedWith(
        compareByDescending<SeriesSummary> { it.rating ?: Double.NEGATIVE_INFINITY }
            .thenBy { it.providerOrder },
    )
    SeriesSortOrder.ReleaseYearDescending -> sortedWith(
        compareByDescending<SeriesSummary> { it.releaseYear ?: Int.MIN_VALUE }
            .thenBy { it.providerOrder },
    )
    SeriesSortOrder.RecentlyUpdated -> sortedWith(
        compareByDescending<SeriesSummary> { it.lastModifiedEpochSeconds ?: Long.MIN_VALUE }
            .thenBy { it.providerOrder },
    )
}

/**
 * Channels have no rating, year or added date in the listing, so name is the only honest
 * alternative to the provider's order — which is why the enum offers both directions of it instead.
 */
fun List<LiveChannel>.orderedBy(order: LiveSortOrder): List<LiveChannel> = when (order) {
    LiveSortOrder.ProviderDefault -> sortedBy { it.providerOrder }
    LiveSortOrder.NameAscending -> orderedByName(
        descending = false,
        keyOf = { SearchTextNormalizer.normalize(it.name) },
        providerOrderOf = LiveChannel::providerOrder,
    )
    LiveSortOrder.NameDescending -> orderedByName(
        descending = true,
        keyOf = { SearchTextNormalizer.normalize(it.name) },
        providerOrderOf = LiveChannel::providerOrder,
    )
}

/**
 * Sorts by a key computed once per item rather than once per comparison.
 *
 * Folding a name is cheap and a sort does it O(n log n) times; a category of a few thousand titles
 * would spend more of its time normalising the same strings over and over than comparing them.
 *
 * The tie-break stays on the provider's order even when the names run backwards, because a tie is
 * not part of what *Z to A* reverses — two channels with the same name should sit in the same
 * relative order whichever direction the list is read in.
 */
private fun <T> List<T>.orderedByName(
    descending: Boolean,
    keyOf: (T) -> String,
    providerOrderOf: (T) -> Int,
): List<T> {
    val byName = if (descending) {
        compareByDescending<Pair<String, T>> { it.first }
    } else {
        compareBy<Pair<String, T>> { it.first }
    }
    return map { keyOf(it) to it }
        .sortedWith(byName.thenBy { providerOrderOf(it.second) })
        .map { it.second }
}
