package dev.killua.iptv.core.database

import com.google.common.truth.Truth.assertThat
import dev.killua.iptv.domain.model.MovieFilter
import dev.killua.iptv.domain.model.MovieSortOrder
import org.junit.Test

class MovieQueryFactoryTest {
    @Test
    fun `the default browse is account scoped and ends with a unique tiebreaker`() {
        val query = MovieQueryFactory.build(ACCOUNT, MovieFilter())

        assertThat(query.sql).contains("m.accountId = ?")
        assertThat(query.arguments).containsExactly(ACCOUNT)
        assertThat(query.sql).contains("ORDER BY m.providerOrder ASC")
        assertThat(query.sql).endsWith("m.remoteStreamId ASC")
    }

    @Test
    fun `every sort order keeps a deterministic tiebreaker`() {
        MovieSortOrder.entries.forEach { sortOrder ->
            val query = MovieQueryFactory.build(ACCOUNT, MovieFilter(sortOrder = sortOrder))
            assertThat(query.sql).endsWith("m.remoteStreamId ASC")
        }
    }

    @Test
    fun `missing sort values are ordered last instead of counting as zero`() {
        val rating = MovieQueryFactory
            .build(ACCOUNT, MovieFilter(sortOrder = MovieSortOrder.RatingDescending)).sql
        val year = MovieQueryFactory
            .build(ACCOUNT, MovieFilter(sortOrder = MovieSortOrder.ReleaseYearDescending)).sql
        val added = MovieQueryFactory
            .build(ACCOUNT, MovieFilter(sortOrder = MovieSortOrder.RecentlyAdded)).sql

        assertThat(rating).contains("m.rating IS NULL, m.rating DESC")
        assertThat(year).contains("m.releaseYear IS NULL, m.releaseYear DESC")
        assertThat(added).contains("m.addedAtEpochSeconds IS NULL, m.addedAtEpochSeconds DESC")
    }

    @Test
    fun `category and language filters are bound as arguments in order`() {
        val query = MovieQueryFactory.build(
            ACCOUNT,
            MovieFilter(categoryId = "20", languageTag = "de"),
        )

        assertThat(query.sql).contains("m.remoteCategoryId = ?")
        assertThat(query.sql).contains("m.languageTag = ?")
        assertThat(query.arguments).containsExactly(ACCOUNT, "20", "de").inOrder()
    }

    @Test
    fun `favorites and in-progress filters join instead of widening the result`() {
        val favorites = MovieQueryFactory.build(ACCOUNT, MovieFilter(favoritesOnly = true))
        val inProgress = MovieQueryFactory.build(ACCOUNT, MovieFilter(inProgressOnly = true))

        assertThat(favorites.sql).contains("INNER JOIN movie_favorites")
        assertThat(inProgress.sql).contains("INNER JOIN watch_progress")
        assertThat(inProgress.sql).contains("wp.completed = 0")
        assertThat(inProgress.sql).contains("wp.positionMs > 0")
    }

    /**
     * SQLite binds `?` positionally, and a join placeholder always precedes the WHERE clause it
     * was added after. Listing the account before the join argument used to hand the content type
     * to `m.accountId`, which silently reduced the in-progress filter to an empty result.
     */
    @Test
    fun `a join argument is bound before the conditions that follow it in the statement`() {
        val query = MovieQueryFactory.build(ACCOUNT, MovieFilter(inProgressOnly = true))

        assertThat(query.sql.indexOf("wp.contentType = ?"))
            .isLessThan(query.sql.indexOf("m.accountId = ?"))
        assertThat(query.arguments).containsExactly("movie", ACCOUNT).inOrder()
    }

    @Test
    fun `every filter combination binds exactly one argument per placeholder`() {
        val searches = listOf(null, "matrix")
        val categories = listOf(null, "7")
        val languages = listOf(null, "de")
        MovieSortOrder.entries.forEach { sortOrder ->
            listOf(false, true).forEach { favorites ->
                listOf(false, true).forEach { inProgress ->
                    searches.forEach { search ->
                        categories.forEach { category ->
                            languages.forEach { language ->
                                val query = MovieQueryFactory.build(
                                    ACCOUNT,
                                    MovieFilter(
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
    fun `search text is bound lowercased and never concatenated`() {
        val query = MovieQueryFactory.build(ACCOUNT, MovieFilter(searchQuery = "  Avatar  "))

        assertThat(query.sql).contains("m.sortName LIKE ?")
        assertThat(query.sql).doesNotContain("Avatar")
        assertThat(query.arguments).containsExactly(ACCOUNT, "%avatar%").inOrder()
    }

    @Test
    fun `a blank search is ignored rather than matching everything`() {
        listOf(null, "", "   ").forEach { search ->
            val query = MovieQueryFactory.build(ACCOUNT, MovieFilter(searchQuery = search))
            assertThat(query.sql).doesNotContain("LIKE")
            assertThat(query.arguments).containsExactly(ACCOUNT)
        }
    }

    @Test
    fun `LIKE wildcards in a search term cannot survive normalization`() {
        val query = MovieQueryFactory.build(ACCOUNT, MovieFilter(searchQuery = "100%_x"))

        // Both wildcards fold to a space before escaping ever sees them, and the ESCAPE clause
        // stays on the statement as the second line of defence.
        assertThat(query.sql).contains("ESCAPE")
        assertThat(query.arguments.last()).isEqualTo("%100 x%")
    }

    @Test
    fun `a quote in a search term stays an argument and cannot alter the statement`() {
        val query = MovieQueryFactory.build(
            ACCOUNT,
            MovieFilter(searchQuery = "'; DROP TABLE movies; --"),
        )

        assertThat(query.sql).doesNotContain("DROP")
        assertThat(query.arguments.last()).isEqualTo("%drop table movies%")
    }

    @Test
    fun `a term of nothing but punctuation is dropped rather than matching everything`() {
        val query = MovieQueryFactory.build(ACCOUNT, MovieFilter(searchQuery = "..."))

        assertThat(query.sql).doesNotContain("LIKE")
        assertThat(query.arguments).containsExactly(ACCOUNT)
    }

    @Test
    fun `filters combine into one statement`() {
        val query = MovieQueryFactory.build(
            ACCOUNT,
            MovieFilter(
                categoryId = "7",
                languageTag = "en",
                favoritesOnly = true,
                inProgressOnly = true,
                searchQuery = "matrix",
                sortOrder = MovieSortOrder.RatingDescending,
            ),
        )

        assertThat(query.sql).contains("INNER JOIN movie_favorites")
        assertThat(query.sql).contains("INNER JOIN watch_progress")
        assertThat(query.arguments)
            .containsExactly("movie", ACCOUNT, "7", "en", "%matrix%")
            .inOrder()
    }

    private companion object {
        const val ACCOUNT = "account-under-test"
    }
}
