package dev.killua.iptv.core.database

import dev.killua.iptv.domain.model.CategorySelection
import dev.killua.iptv.domain.model.LiveFilter
import dev.killua.iptv.domain.model.LiveSortOrder

/**
 * Assembles the paged live browsing statement through [PagedQueryBuilder].
 *
 * The four selections used to be four declared Room queries; adding a search term and a sort order
 * to each would have multiplied them, so Live now assembles its statement the same way Movies
 * does. Search matches the indexed [LiveChannelEntity.sortName] rather than the display name, so
 * a term is compared against consistently normalized text.
 */
object LiveQueryFactory {
    fun build(accountId: String, filter: LiveFilter): PagedQuery {
        val builder = PagedQueryBuilder("SELECT c.* FROM live_channels c")

        if (filter.selection == CategorySelection.Recent) {
            builder.join(
                "INNER JOIN recent_channels r" +
                    " ON r.accountId = c.accountId AND r.remoteStreamId = c.remoteStreamId",
            )
        }

        builder.where("c.accountId = ?", accountId)
        when (val selection = filter.selection) {
            CategorySelection.All, CategorySelection.Recent -> Unit
            // A channel counts as uncategorized when the provider gave it no category or names one
            // that is missing from the category listing, so nothing silently disappears.
            CategorySelection.Uncategorized -> builder.where(
                "(c.remoteCategoryId IS NULL OR c.remoteCategoryId = ''" +
                    " OR c.remoteCategoryId NOT IN (" +
                    "SELECT remoteCategoryId FROM live_categories WHERE accountId = ?))",
                accountId,
            )
            is CategorySelection.Provider -> builder.where(
                "c.remoteCategoryId = ?",
                selection.id,
            )
        }
        filter.languageTag?.let { builder.where("c.languageTag = ?", it) }
        builder.whereContains("c.sortName", filter.searchQuery)

        return builder.build(orderBy(filter))
    }

    private fun orderBy(filter: LiveFilter): String = when (filter.sortOrder) {
        // Recent is ordered by when it was watched; every other selection by provider order.
        LiveSortOrder.ProviderDefault -> when (filter.selection) {
            CategorySelection.Recent -> "r.lastWatchedAtEpochMillis DESC"
            else -> "c.providerOrder ASC, c.sortName ASC"
        }
        LiveSortOrder.NameAscending -> "c.sortName ASC"
        LiveSortOrder.NameDescending -> "c.sortName DESC"
    } + ", c.remoteStreamId ASC"
}
