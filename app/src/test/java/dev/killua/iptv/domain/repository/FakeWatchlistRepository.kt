package dev.killua.iptv.domain.repository

import dev.killua.iptv.domain.model.WatchlistEntry
import dev.killua.iptv.domain.model.WatchlistKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * An in-memory saved list, shared by the screens that read one.
 *
 * Ordering and trimming are reproduced because callers depend on them; the real implementation does
 * both in SQL. Titles come from a lookup rather than being stored on the entry, mirroring the real
 * query's join against the three libraries — an id whose title the provider dropped is not returned.
 */
class FakeWatchlistRepository : WatchlistRepository {
    private val saved = MutableStateFlow<List<Saved>>(emptyList())

    /** What the libraries would supply for a saved id. Missing means the title is gone. */
    val titles = mutableMapOf<Pair<WatchlistKind, String>, String>()

    /** Every [setSaved] call, in order, so a screen's write can be checked. */
    val writes = mutableListOf<Triple<WatchlistKind, String, Boolean>>()

    /** The largest `limit` [observe] was asked for, so a caller's bound can be checked. */
    var observedLimit: Int? = null
        private set

    var failure: Exception? = null

    private var clock = 0L

    override fun observe(accountId: String, limit: Int): Flow<List<WatchlistEntry>> {
        observedLimit = limit
        return saved.map { entries ->
            entries
                .filter { it.accountId == accountId }
                .sortedByDescending { it.addedAt }
                .mapNotNull { entry ->
                    val title = titles[entry.kind to entry.contentId] ?: return@mapNotNull null
                    WatchlistEntry(
                        contentId = entry.contentId,
                        kind = entry.kind,
                        title = title,
                        artworkUrl = null,
                        addedAtEpochMillis = entry.addedAt,
                    )
                }
                .take(limit)
        }
    }

    override fun observeIsSaved(
        accountId: String,
        kind: WatchlistKind,
        contentId: String,
    ): Flow<Boolean> = saved.map { entries ->
        entries.any { it.accountId == accountId && it.kind == kind && it.contentId == contentId }
    }

    override fun observeSavedIds(accountId: String, kind: WatchlistKind): Flow<Set<String>> =
        saved.map { entries ->
            entries
                .filter { it.accountId == accountId && it.kind == kind }
                .map { it.contentId }
                .toSet()
        }

    override suspend fun setSaved(
        accountId: String,
        kind: WatchlistKind,
        contentId: String,
        saved: Boolean,
    ) {
        writes += Triple(kind, contentId, saved)
        failure?.let { throw it }
        this.saved.value = this.saved.value
            .filterNot { it.accountId == accountId && it.kind == kind && it.contentId == contentId }
            .let { rest ->
                if (saved) rest + Saved(accountId, kind, contentId, ++clock) else rest
            }
    }

    private data class Saved(
        val accountId: String,
        val kind: WatchlistKind,
        val contentId: String,
        val addedAt: Long,
    )
}
