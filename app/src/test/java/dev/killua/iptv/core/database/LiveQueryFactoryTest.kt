package dev.killua.iptv.core.database

import com.google.common.truth.Truth.assertThat
import dev.killua.iptv.domain.model.CategorySelection
import dev.killua.iptv.domain.model.LiveFilter
import dev.killua.iptv.domain.model.LiveSortOrder
import org.junit.Test

class LiveQueryFactoryTest {
    @Test
    fun `the default browse is account scoped and ends with a unique tiebreaker`() {
        val query = LiveQueryFactory.build(ACCOUNT, LiveFilter())

        assertThat(query.sql).contains("c.accountId = ?")
        assertThat(query.arguments).containsExactly(ACCOUNT)
        assertThat(query.sql).contains("ORDER BY c.providerOrder ASC")
        assertThat(query.sql).endsWith("c.remoteStreamId ASC")
    }

    @Test
    fun `every selection and sort combination keeps a deterministic tiebreaker`() {
        SELECTIONS.forEach { selection ->
            LiveSortOrder.entries.forEach { sortOrder ->
                val query = LiveQueryFactory.build(
                    ACCOUNT,
                    LiveFilter(selection = selection, sortOrder = sortOrder),
                )
                assertThat(query.sql).endsWith("c.remoteStreamId ASC")
            }
        }
    }

    @Test
    fun `recent joins the history table and stays ordered by when it was watched`() {
        val query = LiveQueryFactory.build(
            ACCOUNT,
            LiveFilter(selection = CategorySelection.Recent),
        )

        assertThat(query.sql).contains("INNER JOIN recent_channels r")
        assertThat(query.sql).contains("ORDER BY r.lastWatchedAtEpochMillis DESC")
        assertThat(query.arguments).containsExactly(ACCOUNT)
    }

    @Test
    fun `sorting recent by name overrides recency without dropping the join`() {
        val query = LiveQueryFactory.build(
            ACCOUNT,
            LiveFilter(
                selection = CategorySelection.Recent,
                sortOrder = LiveSortOrder.NameAscending,
            ),
        )

        assertThat(query.sql).contains("INNER JOIN recent_channels r")
        assertThat(query.sql).contains("ORDER BY c.sortName ASC")
        assertThat(query.sql).doesNotContain("lastWatchedAtEpochMillis")
    }

    @Test
    fun `a provider category is bound rather than concatenated`() {
        val query = LiveQueryFactory.build(
            ACCOUNT,
            LiveFilter(selection = CategorySelection.Provider("7")),
        )

        assertThat(query.sql).contains("c.remoteCategoryId = ?")
        assertThat(query.sql).doesNotContain("'7'")
        assertThat(query.arguments).containsExactly(ACCOUNT, "7").inOrder()
    }

    @Test
    fun `uncategorized keeps channels whose category is missing from the listing`() {
        val query = LiveQueryFactory.build(
            ACCOUNT,
            LiveFilter(selection = CategorySelection.Uncategorized),
        )

        assertThat(query.sql).contains("c.remoteCategoryId IS NULL")
        assertThat(query.sql).contains("NOT IN (SELECT remoteCategoryId FROM live_categories")
        // The account is bound twice: once for the channel scope and once for the subquery.
        assertThat(query.arguments).containsExactly(ACCOUNT, ACCOUNT).inOrder()
    }

    @Test
    fun `name sorting uses the indexed sort name in both directions`() {
        val ascending = LiveQueryFactory
            .build(ACCOUNT, LiveFilter(sortOrder = LiveSortOrder.NameAscending)).sql
        val descending = LiveQueryFactory
            .build(ACCOUNT, LiveFilter(sortOrder = LiveSortOrder.NameDescending)).sql

        assertThat(ascending).contains("ORDER BY c.sortName ASC")
        assertThat(descending).contains("ORDER BY c.sortName DESC")
        // Ordering by the raw name would put every capital letter before every lowercase one.
        assertThat(ascending).doesNotContain("c.name")
    }

    @Test
    fun `search text is bound lowercased and never concatenated`() {
        val query = LiveQueryFactory.build(ACCOUNT, LiveFilter(searchQuery = "  RTL  "))

        assertThat(query.sql).contains("c.sortName LIKE ?")
        assertThat(query.sql).doesNotContain("RTL")
        assertThat(query.arguments).containsExactly(ACCOUNT, "%rtl%").inOrder()
    }

    @Test
    fun `a blank search is ignored rather than matching everything`() {
        listOf(null, "", "   ").forEach { search ->
            val query = LiveQueryFactory.build(ACCOUNT, LiveFilter(searchQuery = search))
            assertThat(query.sql).doesNotContain("LIKE")
            assertThat(query.arguments).containsExactly(ACCOUNT)
        }
    }

    @Test
    fun `LIKE wildcards in a search term cannot survive normalization`() {
        val query = LiveQueryFactory.build(ACCOUNT, LiveFilter(searchQuery = "100%_x"))

        // Both wildcards fold to a space before escaping ever sees them, and the ESCAPE clause
        // stays on the statement as the second line of defence.
        assertThat(query.sql).contains("ESCAPE")
        assertThat(query.arguments.last()).isEqualTo("%100 x%")
    }

    @Test
    fun `a quote in a search term stays an argument and cannot alter the statement`() {
        val query = LiveQueryFactory.build(
            ACCOUNT,
            LiveFilter(searchQuery = "'; DROP TABLE live_channels; --"),
        )

        assertThat(query.sql).doesNotContain("DROP")
        assertThat(query.arguments.last()).isEqualTo("%drop table live channels%")
    }

    @Test
    fun `a term of nothing but punctuation is dropped rather than matching everything`() {
        val query = LiveQueryFactory.build(ACCOUNT, LiveFilter(searchQuery = "..."))

        assertThat(query.sql).doesNotContain("LIKE")
        assertThat(query.arguments).containsExactly(ACCOUNT)
    }

    @Test
    fun `search combines with a selection instead of replacing it`() {
        val query = LiveQueryFactory.build(
            ACCOUNT,
            LiveFilter(
                selection = CategorySelection.Provider("12"),
                searchQuery = "news",
                sortOrder = LiveSortOrder.NameAscending,
            ),
        )

        assertThat(query.sql).contains("c.remoteCategoryId = ?")
        assertThat(query.sql).contains("c.sortName LIKE ?")
        assertThat(query.arguments).containsExactly(ACCOUNT, "12", "%news%").inOrder()
    }

    @Test
    fun `the language filter is bound and combines with a search`() {
        val query = LiveQueryFactory.build(
            ACCOUNT,
            LiveFilter(languageTag = "de", searchQuery = "news"),
        )

        assertThat(query.sql).contains("c.languageTag = ?")
        assertThat(query.arguments).containsExactly(ACCOUNT, "de", "%news%").inOrder()
    }

    @Test
    fun `every selection filter and sort combination binds one argument per placeholder`() {
        SELECTIONS.forEach { selection ->
            LiveSortOrder.entries.forEach { sortOrder ->
                listOf(null, "news").forEach { search ->
                    listOf(null, "de").forEach { language ->
                        val query = LiveQueryFactory.build(
                            ACCOUNT,
                            LiveFilter(selection, search, language, sortOrder),
                        )
                        assertThat(query.sql.count { it == '?' }).isEqualTo(query.arguments.size)
                    }
                }
            }
        }
    }

    @Test
    fun `the sort name normalization lowercases and collapses whitespace`() {
        assertThat(LiveChannelEntity.sortNameOf("  RTL   HD  ")).isEqualTo("rtl hd")
    }

    @Test
    fun `a leading country tag is kept so A to Z reads like the channel list looks`() {
        // The tag stays and only its bar folds away, so `de | rtl` and `de rtl` both find it.
        assertThat(LiveChannelEntity.sortNameOf("DE | RTL HD")).isEqualTo("de rtl hd")
    }

    private companion object {
        const val ACCOUNT = "account-under-test"

        val SELECTIONS = listOf(
            CategorySelection.All,
            CategorySelection.Recent,
            CategorySelection.Uncategorized,
            CategorySelection.Provider("7"),
        )
    }
}
