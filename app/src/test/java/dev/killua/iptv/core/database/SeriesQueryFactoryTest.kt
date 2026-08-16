package dev.killua.iptv.core.database

import com.google.common.truth.Truth.assertThat
import dev.killua.iptv.domain.model.SeriesFilter
import dev.killua.iptv.domain.model.SeriesSortOrder
import org.junit.Test

class SeriesQueryFactoryTest {
    @Test
    fun `the default browse is account scoped and ends with a unique tiebreaker`() {
        val query = SeriesQueryFactory.build(ACCOUNT, SeriesFilter())

        assertThat(query.sql).contains("s.accountId = ?")
        assertThat(query.arguments).containsExactly(ACCOUNT)
        assertThat(query.sql).contains("ORDER BY s.providerOrder ASC")
        assertThat(query.sql).endsWith("s.remoteSeriesId ASC")
    }

    @Test
    fun `every sort order keeps a deterministic tiebreaker`() {
        SeriesSortOrder.entries.forEach { sortOrder ->
            val query = SeriesQueryFactory.build(ACCOUNT, SeriesFilter(sortOrder = sortOrder))
            assertThat(query.sql).endsWith("s.remoteSeriesId ASC")
        }
    }

    @Test
    fun `missing sort values are ordered last instead of counting as zero`() {
        val rating = SeriesQueryFactory
            .build(ACCOUNT, SeriesFilter(sortOrder = SeriesSortOrder.RatingDescending)).sql
        val year = SeriesQueryFactory
            .build(ACCOUNT, SeriesFilter(sortOrder = SeriesSortOrder.ReleaseYearDescending)).sql
        val updated = SeriesQueryFactory
            .build(ACCOUNT, SeriesFilter(sortOrder = SeriesSortOrder.RecentlyUpdated)).sql

        assertThat(rating).contains("s.rating IS NULL, s.rating DESC")
        assertThat(year).contains("s.releaseYear IS NULL, s.releaseYear DESC")
        assertThat(updated)
            .contains("s.lastModifiedEpochSeconds IS NULL, s.lastModifiedEpochSeconds DESC")
    }

    @Test
    fun `category and language filters are bound as arguments in order`() {
        val query = SeriesQueryFactory.build(
            ACCOUNT,
            SeriesFilter(categoryId = "90", languageTag = "de"),
        )

        assertThat(query.sql).contains("s.remoteCategoryId = ?")
        assertThat(query.sql).contains("s.languageTag = ?")
        assertThat(query.arguments).containsExactly(ACCOUNT, "90", "de").inOrder()
    }

    @Test
    fun `search text is bound lowercased and never concatenated`() {
        val query = SeriesQueryFactory.build(ACCOUNT, SeriesFilter(searchQuery = "  Tatort  "))

        assertThat(query.sql).contains("s.sortName LIKE ?")
        assertThat(query.sql).doesNotContain("Tatort")
        assertThat(query.arguments).containsExactly(ACCOUNT, "%tatort%").inOrder()
    }

    @Test
    fun `a blank search is ignored rather than matching everything`() {
        // "..." belongs here too: it is three keystrokes and nothing to match on.
        listOf(null, "", "   ", "...").forEach { search ->
            val query = SeriesQueryFactory.build(ACCOUNT, SeriesFilter(searchQuery = search))
            assertThat(query.sql).doesNotContain("LIKE")
            assertThat(query.arguments).containsExactly(ACCOUNT)
        }
    }

    @Test
    fun `a quote in a search term stays an argument and cannot alter the statement`() {
        val query = SeriesQueryFactory.build(
            ACCOUNT,
            SeriesFilter(searchQuery = "'; DROP TABLE series; --"),
        )

        assertThat(query.sql).doesNotContain("DROP")
        assertThat(query.arguments.last()).isEqualTo("%drop table series%")
    }

    @Test
    fun `the favorites filter joins rather than testing a column`() {
        val query = SeriesQueryFactory.build(ACCOUNT, SeriesFilter(favoritesOnly = true))

        assertThat(query.sql).contains("INNER JOIN series_favorites fav")
        assertThat(query.arguments).containsExactly(ACCOUNT)
    }

    @Test
    fun `the in-progress filter uses EXISTS so a series cannot appear twice`() {
        val query = SeriesQueryFactory.build(ACCOUNT, SeriesFilter(inProgressOnly = true))

        // A join through series_episodes would repeat the series once per unfinished episode.
        assertThat(query.sql).doesNotContain("INNER JOIN series_episodes e ON")
        assertThat(query.sql).contains("EXISTS (SELECT 1 FROM series_episodes e")
        assertThat(query.sql).contains("wp.completed = 0")
        assertThat(query.arguments).containsExactly(ACCOUNT, "episode").inOrder()
    }

    @Test
    fun `the in-progress filter matches episode progress, never movie progress`() {
        val query = SeriesQueryFactory.build(ACCOUNT, SeriesFilter(inProgressOnly = true))

        // Providers number movies and episodes independently, so without the content type a
        // Movie position could mark an unrelated series as started.
        assertThat(query.arguments).contains(SeriesQueryFactory.EPISODE_CONTENT_TYPE)
        assertThat(query.arguments).doesNotContain("movie")
    }

    @Test
    fun `join arguments bind before condition arguments`() {
        val query = SeriesQueryFactory.build(
            ACCOUNT,
            SeriesFilter(
                categoryId = "90",
                favoritesOnly = true,
                inProgressOnly = true,
                searchQuery = "tatort",
            ),
        )

        // The statement order is join, then WHERE; SQLite binds positionally, so the argument
        // list has to follow that order rather than the order the filters were declared in.
        assertThat(query.arguments)
            .containsExactly(ACCOUNT, "90", "episode", "%tatort%")
            .inOrder()
        assertThat(query.sql.count { it == '?' }).isEqualTo(query.arguments.size)
    }

    @Test
    fun `every filter and sort combination binds one argument per placeholder`() {
        SeriesSortOrder.entries.forEach { sortOrder ->
            listOf(null, "90").forEach { category ->
                listOf(null, "de").forEach { language ->
                    listOf(false, true).forEach { favorites ->
                        listOf(false, true).forEach { inProgress ->
                            listOf(null, "tatort").forEach { search ->
                                val query = SeriesQueryFactory.build(
                                    ACCOUNT,
                                    SeriesFilter(
                                        categoryId = category,
                                        languageTag = language,
                                        favoritesOnly = favorites,
                                        inProgressOnly = inProgress,
                                        searchQuery = search,
                                        sortOrder = sortOrder,
                                    ),
                                )
                                assertThat(query.sql.count { it == '?' })
                                    .isEqualTo(query.arguments.size)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `episodes reuse the shared watch progress table under their own content type`() {
        // A parallel table would have needed its own migration and its own progress rules.
        assertThat(SeriesQueryFactory.EPISODE_CONTENT_TYPE).isEqualTo("episode")
        assertThat(SeriesQueryFactory.EPISODE_CONTENT_TYPE)
            .isNotEqualTo(MovieQueryFactory.MOVIE_CONTENT_TYPE)
    }

    private companion object {
        const val ACCOUNT = "account-under-test"
    }
}
