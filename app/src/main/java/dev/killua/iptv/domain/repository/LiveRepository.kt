package dev.killua.iptv.domain.repository

import androidx.paging.PagingData
import dev.killua.iptv.domain.model.EpgEntry
import dev.killua.iptv.domain.model.LiveCategory
import dev.killua.iptv.domain.model.SearchSection
import dev.killua.iptv.domain.model.LiveChannel
import dev.killua.iptv.domain.model.LiveFilter
import kotlinx.coroutines.flow.Flow

data class LiveSyncResult(
    val categoryCount: Int,
    val channelCount: Int,
    val finishedAtEpochMillis: Long,
)

interface LiveRepository {
    /**
     * The next few programmes on one channel, newest fetch reused for a short while.
     *
     * Returns an empty list rather than failing when the provider has no guide for the channel or
     * cannot answer: a missing guide must never stop a channel from playing.
     */
    suspend fun epg(accountId: String, streamId: String): List<EpgEntry>

    /** Global search over cached channel names, bounded by [limit]. */
    suspend fun search(accountId: String, term: String, limit: Int): SearchSection<LiveChannel>

    fun observeCategories(accountId: String): Flow<List<LiveCategory>>

    /** Heuristic languages actually present in the cached library, for the language filter. */
    fun observeLanguages(accountId: String): Flow<List<String>>

    fun channels(accountId: String, filter: LiveFilter): Flow<PagingData<LiveChannel>>
    fun observeRecent(accountId: String, limit: Int = 12): Flow<List<LiveChannel>>
    suspend fun getChannel(accountId: String, streamId: String): LiveChannel?
    suspend fun hasCachedLibrary(accountId: String): Boolean

    /** [onProgress] reports the running channel count after each written batch. */
    suspend fun refresh(accountId: String, onProgress: (Int) -> Unit = {}): LiveSyncResult
    suspend fun markRecent(accountId: String, streamId: String)
}
