package dev.killua.iptv.domain.browse

import dev.killua.iptv.domain.model.RecentlyAddedEntry

/**
 * Whether a provider's "added" timestamps are worth ordering by, and the row to show if they are.
 *
 * Providers are careless with this field. Some stamp an entire import with one value, some send the
 * same constant for every title forever. Ordering by a column where every row is identical does not
 * produce "recently added" — it produces an arbitrary slice of the library wearing that label, which
 * is worse than showing nothing, because the viewer cannot tell the difference.
 *
 * The test is deliberately weak and cheap: **more than one distinct timestamp** among the candidates.
 * It cannot tell a provider that stamped one genuine batch from one that stamped everything, so a
 * wider window than the row needs is examined to make a single batch less likely to look like the
 * whole library. It is a guard against the obviously useless case, not a proof of quality.
 */
object RecentlyAdded {
    /** How many candidates to look at per library, relative to the row length. */
    const val CANDIDATE_FACTOR = 3

    fun rowOf(
        movies: List<RecentlyAddedEntry>,
        series: List<RecentlyAddedEntry>,
        limit: Int,
    ): List<RecentlyAddedEntry> {
        if (limit <= 0) return emptyList()
        val usable = (movies.takeIf { it.isOrdered() }.orEmpty()) +
            (series.takeIf { it.isOrdered() }.orEmpty())
        return usable
            .sortedByDescending { it.addedAtEpochSeconds }
            .take(limit)
    }

    /** True when this library's timestamps distinguish anything at all. */
    private fun List<RecentlyAddedEntry>.isOrdered(): Boolean =
        distinctBy { it.addedAtEpochSeconds }.size > 1
}
