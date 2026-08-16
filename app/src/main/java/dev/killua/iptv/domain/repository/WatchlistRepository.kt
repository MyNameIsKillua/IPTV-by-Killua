package dev.killua.iptv.domain.repository

import dev.killua.iptv.domain.model.WatchlistEntry
import dev.killua.iptv.domain.model.WatchlistKind
import kotlinx.coroutines.flow.Flow

/**
 * The one saved list, across Movies, Series, and channels.
 *
 * It sits beside the per-library favorites rather than replacing them. Favorites mark a title
 * inside its own library and drive that library's filter chip; this is the cross-library list a
 * viewer opens to decide what to watch. Folding the two together would mean migrating existing
 * favorite rows, which is user data that cannot be re-derived from the provider — worth doing
 * deliberately later, not as a side effect of adding a list.
 */
interface WatchlistRepository {
    /** Newest first, bounded. Entries whose title the provider dropped are simply not returned. */
    fun observe(accountId: String, limit: Int = 60): Flow<List<WatchlistEntry>>

    fun observeIsSaved(accountId: String, kind: WatchlistKind, contentId: String): Flow<Boolean>

    /**
     * Every saved id of one kind, for a list that marks many rows at once.
     *
     * The paged channel list cannot observe each row separately; saved ids are few, so the set is
     * held whole. Unlike [observe] this does not join the library, so it answers for a title the
     * provider has temporarily dropped as well.
     */
    fun observeSavedIds(accountId: String, kind: WatchlistKind): Flow<Set<String>>

    /**
     * Adds or removes one entry. Rejected after logout or an account swap, like every other
     * account-scoped write.
     */
    suspend fun setSaved(
        accountId: String,
        kind: WatchlistKind,
        contentId: String,
        saved: Boolean,
    )
}
