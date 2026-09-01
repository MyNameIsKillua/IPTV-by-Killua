package dev.killua.iptv.domain.userdata

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** All fixtures are fictitious. */
class UserDataMergeTest {

    @Test
    fun `a position this device has never seen is written`() {
        val result = UserDataMerge.progress(emptyList(), listOf(progress("501", at = 100L)))

        assertThat(result).hasSize(1)
    }

    @Test
    fun `a newer position replaces an older one`() {
        val result = UserDataMerge.progress(
            local = listOf(progress("501", at = 100L)),
            imported = listOf(progress("501", at = 200L)),
        )

        assertThat(result.single().updatedAtEpochMillis).isEqualTo(200L)
    }

    /** The device you watched on most recently knows best, whichever direction the file travels. */
    @Test
    fun `an older position in the file is ignored`() {
        val result = UserDataMerge.progress(
            local = listOf(progress("501", at = 200L)),
            imported = listOf(progress("501", at = 100L)),
        )

        assertThat(result).isEmpty()
    }

    @Test
    fun `an equal timestamp writes nothing, so importing twice is free`() {
        val local = listOf(progress("501", at = 100L))

        assertThat(UserDataMerge.progress(local, local)).isEmpty()
    }

    /** A film and an episode can share an id; only the pair identifies a row. */
    @Test
    fun `content type is part of the identity`() {
        val result = UserDataMerge.progress(
            local = listOf(progress("7", type = "movie", at = 200L)),
            imported = listOf(progress("7", type = "episode", at = 100L)),
        )

        assertThat(result).hasSize(1)
        assertThat(result.single().contentType).isEqualTo("episode")
    }

    /** Nothing is ever removed: a row absent from the file has to survive it. */
    @Test
    fun `a local row missing from the file is untouched`() {
        val result = UserDataMerge.progress(
            local = listOf(progress("501", at = 100L), progress("502", at = 100L)),
            imported = listOf(progress("501", at = 200L)),
        )

        assertThat(result.map { it.contentId }).containsExactly("501")
    }

    @Test
    fun `marks follow the same rule`() {
        val local = listOf(MarkRecord("501", 200L))

        assertThat(UserDataMerge.marks(local, listOf(MarkRecord("501", 100L)))).isEmpty()
        assertThat(UserDataMerge.marks(local, listOf(MarkRecord("501", 300L)))).hasSize(1)
        assertThat(UserDataMerge.marks(local, listOf(MarkRecord("999", 1L)))).hasSize(1)
    }

    @Test
    fun `the saved list is keyed by type and id together`() {
        val local = listOf(WatchlistRecord("movie", "7", 200L))

        assertThat(UserDataMerge.watchlist(local, listOf(WatchlistRecord("movie", "7", 100L))))
            .isEmpty()
        assertThat(UserDataMerge.watchlist(local, listOf(WatchlistRecord("series", "7", 100L))))
            .hasSize(1)
    }

    @Test
    fun `a plan counts every kind of row it would change`() {
        val plan = UserDataImportPlan.Ready(
            export = UserDataExport(exportedAtEpochMillis = 1L, accountFingerprint = "a"),
            progress = listOf(progress("501", at = 1L)),
            movieFavorites = listOf(MarkRecord("1", 1L)),
            seriesFavorites = listOf(MarkRecord("2", 1L)),
            watchlist = listOf(WatchlistRecord("movie", "3", 1L)),
            recentChannels = listOf(MarkRecord("4", 1L)),
        )

        assertThat(plan.changeCount).isEqualTo(5)
    }

    private fun progress(id: String, type: String = "movie", at: Long) = ProgressRecord(
        contentType = type,
        contentId = id,
        positionMs = 1_000L,
        durationMs = 10_000L,
        completed = false,
        updatedAtEpochMillis = at,
    )
}

/** Whole-file merging, which is what the desktop client's Import does. All fixtures fictitious. */
class UserDataMergedWithTest {

    @Test
    fun `a newer incoming position replaces the local one exactly once`() {
        val local = base(progress = listOf(record("501", at = 100L, position = 1_000L)))
        val incoming = base(progress = listOf(record("501", at = 200L, position = 5_000L)))

        val merged = local.mergedWith(incoming, nowEpochMillis = 999L)

        assertThat(merged.watchProgress).hasSize(1)
        assertThat(merged.watchProgress.single().positionMs).isEqualTo(5_000L)
    }

    @Test
    fun `an older incoming position leaves the local one alone`() {
        val local = base(progress = listOf(record("501", at = 200L, position = 5_000L)))
        val incoming = base(progress = listOf(record("501", at = 100L, position = 1_000L)))

        val merged = local.mergedWith(incoming)

        assertThat(merged.watchProgress.single().positionMs).isEqualTo(5_000L)
    }

    @Test
    fun `rows only one side has survive from both`() {
        val local = base(progress = listOf(record("local", at = 100L)))
        val incoming = base(progress = listOf(record("incoming", at = 100L)))

        val merged = local.mergedWith(incoming)

        assertThat(merged.watchProgress.map { it.contentId })
            .containsExactly("local", "incoming")
    }

    @Test
    fun `marks and the saved list merge too`() {
        val local = base(favourites = listOf(MarkRecord("a", 100L)))
        val incoming = base(
            favourites = listOf(MarkRecord("b", 100L)),
            saved = listOf(WatchlistRecord("movie", "c", 100L)),
        )

        val merged = local.mergedWith(incoming)

        assertThat(merged.movieFavorites.map { it.contentId }).containsExactly("a", "b")
        assertThat(merged.watchlist.map { it.contentId }).containsExactly("c")
    }

    /** A merge decides what the data is, not whose it is. */
    @Test
    fun `the receiving fingerprint is kept`() {
        val local = base().copy(accountFingerprint = "mine")
        val incoming = base().copy(accountFingerprint = "theirs")

        assertThat(local.mergedWith(incoming).accountFingerprint).isEqualTo("mine")
    }

    @Test
    fun `merging the same file twice changes nothing the second time`() {
        val local = base(progress = listOf(record("501", at = 100L)))
        val incoming = base(progress = listOf(record("501", at = 200L, position = 9_000L)))

        val once = local.mergedWith(incoming)
        val twice = once.mergedWith(incoming)

        assertThat(twice.watchProgress).isEqualTo(once.watchProgress)
    }

    private fun base(
        progress: List<ProgressRecord> = emptyList(),
        favourites: List<MarkRecord> = emptyList(),
        saved: List<WatchlistRecord> = emptyList(),
    ) = UserDataExport(
        exportedAtEpochMillis = 1L,
        accountFingerprint = "abc",
        watchProgress = progress,
        movieFavorites = favourites,
        watchlist = saved,
    )

    private fun record(id: String, at: Long, position: Long = 1_000L) = ProgressRecord(
        contentType = "movie",
        contentId = id,
        positionMs = position,
        durationMs = 600_000L,
        completed = false,
        updatedAtEpochMillis = at,
    )
}
