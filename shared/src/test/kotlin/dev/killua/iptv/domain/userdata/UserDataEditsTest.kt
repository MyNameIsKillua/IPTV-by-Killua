package dev.killua.iptv.domain.userdata

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** All fixtures are fictitious. */
class UserDataEditsTest {

    @Test
    fun `marking and unmarking a film favourite`() {
        val marked = EMPTY.toggleMovieFavourite("501", nowEpochMillis = 100L)

        assertThat(marked.isMovieFavourite("501")).isTrue()
        assertThat(marked.toggleMovieFavourite("501").isMovieFavourite("501")).isFalse()
    }

    /** The stamp records when it was first marked, which is what a recent-first ordering is about. */
    @Test
    fun `re-marking keeps the original timestamp`() {
        val marked = EMPTY.toggleMovieFavourite("501", nowEpochMillis = 100L)
        val unmarked = marked.toggleMovieFavourite("501", nowEpochMillis = 200L)
        val remarked = unmarked.toggleMovieFavourite("501", nowEpochMillis = 300L)

        assertThat(marked.movieFavorites.single().atEpochMillis).isEqualTo(100L)
        assertThat(remarked.movieFavorites.single().atEpochMillis).isEqualTo(300L)
    }

    @Test
    fun `films and series keep separate favourite lists`() {
        val marked = EMPTY.toggleMovieFavourite("7").toggleSeriesFavourite("7")

        assertThat(marked.isMovieFavourite("7")).isTrue()
        assertThat(marked.isSeriesFavourite("7")).isTrue()
        assertThat(marked.toggleMovieFavourite("7").isSeriesFavourite("7")).isTrue()
    }

    @Test
    fun `the saved list is keyed by type and id together`() {
        val saved = EMPTY.toggleSaved(MOVIE_CONTENT_TYPE, "7", nowEpochMillis = 100L)

        assertThat(saved.isSaved(MOVIE_CONTENT_TYPE, "7")).isTrue()
        assertThat(saved.isSaved(SERIES_CONTENT_TYPE, "7")).isFalse()
        assertThat(saved.watchlist).hasSize(1)
    }

    @Test
    fun `unsaving removes only the matching entry`() {
        val saved = EMPTY
            .toggleSaved(MOVIE_CONTENT_TYPE, "7")
            .toggleSaved(SERIES_CONTENT_TYPE, "7")
            .toggleSaved(MOVIE_CONTENT_TYPE, "7")

        assertThat(saved.isSaved(MOVIE_CONTENT_TYPE, "7")).isFalse()
        assertThat(saved.isSaved(SERIES_CONTENT_TYPE, "7")).isTrue()
    }

    @Test
    fun `continue watching is newest first`() {
        val export = EMPTY.copy(
            watchProgress = listOf(
                progress("a", at = 100L),
                progress("b", at = 300L),
                progress("c", at = 200L),
            ),
        )

        assertThat(export.continueWatching().map { it.contentId })
            .containsExactly("b", "c", "a").inOrder()
    }

    /** Offering something already watched to the end is the row nobody wants. */
    @Test
    fun `a finished title is not offered to continue`() {
        val export = EMPTY.copy(
            watchProgress = listOf(progress("done", at = 100L, completed = true)),
        )

        assertThat(export.continueWatching()).isEmpty()
    }

    @Test
    fun `a title at position zero is not offered either`() {
        val export = EMPTY.copy(watchProgress = listOf(progress("fresh", at = 100L, position = 0L)))

        assertThat(export.continueWatching()).isEmpty()
    }

    @Test
    fun `every edit stamps the export`() {
        assertThat(EMPTY.toggleMovieFavourite("1", nowEpochMillis = 42L).exportedAtEpochMillis)
            .isEqualTo(42L)
        assertThat(EMPTY.toggleSaved(MOVIE_CONTENT_TYPE, "1", nowEpochMillis = 43L).exportedAtEpochMillis)
            .isEqualTo(43L)
    }

    private fun progress(
        id: String,
        at: Long,
        completed: Boolean = false,
        position: Long = 60_000L,
    ) = ProgressRecord(
        contentType = MOVIE_CONTENT_TYPE,
        contentId = id,
        positionMs = position,
        durationMs = 600_000L,
        completed = completed,
        updatedAtEpochMillis = at,
    )

    @Test
    fun `marking one episode watched leaves every other episode exactly as it was`() {
        val half = 1_800_000L

        val export = EMPTY
            .markWatched(EPISODE_CONTENT_TYPE, "e1", half, nowEpochMillis = 100L)
            .markWatched(EPISODE_CONTENT_TYPE, "e2", half, nowEpochMillis = 200L)
            .markWatched(EPISODE_CONTENT_TYPE, "e3", half, nowEpochMillis = 300L)

        // The owner reported that marking the next episode appeared to clear the previous one.
        // Whatever they were seeing, it was not this: the rows are independent and all three stand.
        assertThat(export.watchProgress.map { it.contentId })
            .containsExactly("e1", "e2", "e3")
        assertThat(export.watchProgress.filter { it.completed }).hasSize(3)
    }

    @Test
    fun `unmarking one episode leaves the others alone`() {
        val half = 1_800_000L
        val marked = EMPTY
            .markWatched(EPISODE_CONTENT_TYPE, "e1", half)
            .markWatched(EPISODE_CONTENT_TYPE, "e2", half)

        val export = marked.clearProgress(EPISODE_CONTENT_TYPE, "e1")

        assertThat(export.watchProgress.map { it.contentId }).containsExactly("e2")
    }

    @Test
    fun `two libraries can share an id without marking one touching the other`() {
        val hour = 3_600_000L

        val export = EMPTY
            .markWatched(MOVIE_CONTENT_TYPE, "501", hour)
            .markWatched(EPISODE_CONTENT_TYPE, "501", hour)

        assertThat(export.watchProgress).hasSize(2)
        assertThat(export.clearProgress(MOVIE_CONTENT_TYPE, "501").watchProgress.single().contentType)
            .isEqualTo(EPISODE_CONTENT_TYPE)
    }

    @Test
    fun `an episode marked by hand carries its series, exactly as playing one does`() {
        val export = EMPTY.markWatched(
            contentType = EPISODE_CONTENT_TYPE,
            contentId = "e1",
            durationMs = 1_800_000L,
            seriesId = "s9",
        )

        // Without this the other device is handed a number it cannot name, which is what put a
        // resumed series on nobody's start screen. A film passes nothing and keeps null.
        assertThat(export.watchProgress.single().seriesId).isEqualTo("s9")
        assertThat(
            EMPTY.markWatched(MOVIE_CONTENT_TYPE, "501", 1L).watchProgress.single().seriesId,
        ).isNull()
    }

    @Test
    fun `marking something watched leaves the row a real viewing would`() {
        val hour = 3_600_000L

        val export = EMPTY.markWatched(MOVIE_CONTENT_TYPE, "501", hour, nowEpochMillis = 900L)

        val record = export.watchProgress.single()
        assertThat(record.completed).isTrue()
        assertThat(record.positionMs).isEqualTo(hour)
        assertThat(record.durationMs).isEqualTo(hour)
        assertThat(record.updatedAtEpochMillis).isEqualTo(900L)
        // Nothing downstream should be able to tell the difference, which is the point: completion
        // is a stored fact rather than a second kind of mark.
        assertThat(export.continueWatching()).isEmpty()
        assertThat(export.resumePositionOf(MOVIE_CONTENT_TYPE, "501")).isNull()
    }

    @Test
    fun `a title finished three minutes early still stores its whole duration`() {
        val twentyMinutes = 20 * 60 * 1_000L

        // Seventeen and a half minutes in. The completion rule calls that finished — it is within
        // three minutes of the end — but it is only 87 percent of the way through.
        val export = EMPTY.withProgress(
            MOVIE_CONTENT_TYPE,
            "501",
            positionMs = 17 * 60 * 1_000L + 30_000L,
            durationMs = twentyMinutes,
            nowEpochMillis = 1L,
        )

        val record = export.watchProgress.single()
        assertThat(record.completed).isTrue()
        // Stored as the whole duration rather than where playback actually reached, which is what
        // lets anything reading a *fraction* agree with the flag. The desktop's watched tick does
        // exactly that, so this is the line holding the two clients to the same answer.
        assertThat(record.positionMs).isEqualTo(twentyMinutes)
    }

    @Test
    fun `marking replaces a position rather than adding a second row`() {
        val export = EMPTY
            .markWatched(MOVIE_CONTENT_TYPE, "501", 3_600_000L, nowEpochMillis = 100L)
            .markWatched(MOVIE_CONTENT_TYPE, "501", 3_600_000L, nowEpochMillis = 900L)

        assertThat(export.watchProgress).hasSize(1)
        assertThat(export.watchProgress.single().updatedAtEpochMillis).isEqualTo(900L)
    }

    @Test
    fun `a duration nobody knows is refused rather than invented`() {
        val export = EMPTY.markWatched(MOVIE_CONTENT_TYPE, "501", durationMs = 0L)

        // A fabricated duration would travel to the other device and be believed there.
        assertThat(export.watchProgress).isEmpty()
        assertThat(export).isEqualTo(EMPTY)
    }

    @Test
    fun `clearing removes the row rather than zeroing it`() {
        val export = EMPTY
            .markWatched(MOVIE_CONTENT_TYPE, "501", 3_600_000L, nowEpochMillis = 100L)
            .clearProgress(MOVIE_CONTENT_TYPE, "501", nowEpochMillis = 900L)

        // A position of zero is a title someone started and stopped at once, which is a different
        // claim from never having watched it.
        assertThat(export.watchProgress).isEmpty()
    }

    @Test
    fun `clearing one title leaves the others alone`() {
        val export = EMPTY
            .markWatched(MOVIE_CONTENT_TYPE, "501", 3_600_000L)
            .markWatched(EPISODE_CONTENT_TYPE, "501", 1_800_000L)
            .clearProgress(MOVIE_CONTENT_TYPE, "501")

        // Same id, different library: only the type and the id together identify anything here.
        assertThat(export.watchProgress.single().contentType).isEqualTo(EPISODE_CONTENT_TYPE)
    }

    @Test
    fun `a channel watched by accident can be forgotten`() {
        val export = EMPTY
            .withRecentChannel("41", nowEpochMillis = 100L)
            .withRecentChannel("42", nowEpochMillis = 200L)
            .withoutRecentChannel("41", nowEpochMillis = 300L)

        assertThat(export.ownChannels()).containsExactly("42")
    }

    @Test
    fun `forgetting a visit leaves the bookmark alone`() {
        val export = EMPTY
            .toggleSaved(CHANNEL_CONTENT_TYPE, "41", nowEpochMillis = 100L)
            .withRecentChannel("41", nowEpochMillis = 200L)
            .withoutRecentChannel("41", nowEpochMillis = 300L)

        // A bookmark is a decision and has its own switch; this undoes the visit, not the intention.
        assertThat(export.isSaved(CHANNEL_CONTENT_TYPE, "41")).isTrue()
        assertThat(export.ownChannels()).containsExactly("41")
    }

    @Test
    fun `forgetting a channel that was never watched changes nothing`() {
        val export = EMPTY.withRecentChannel("41", nowEpochMillis = 100L)

        assertThat(export.withoutRecentChannel("99").recentChannels)
            .isEqualTo(export.recentChannels)
    }

    private companion object {
        val EMPTY = UserDataExport(exportedAtEpochMillis = 0L, accountFingerprint = "abc")
    }
}

/** Recently watched channels and what a guide should cover. All fixtures are fictitious. */
class OwnChannelsTest {

    /** The opposite rule to a favourite: re-watching is exactly what should move it to the front. */
    @Test
    fun `re-watching a channel refreshes its place`() {
        val once = EMPTY.withRecentChannel("41", nowEpochMillis = 100L)
        val twice = once.withRecentChannel("41", nowEpochMillis = 500L)

        assertThat(twice.recentChannels).hasSize(1)
        assertThat(twice.recentChannels.single().atEpochMillis).isEqualTo(500L)
    }

    @Test
    fun `saved channels come before recently watched ones`() {
        val export = EMPTY
            .withRecentChannel("recent", nowEpochMillis = 900L)
            .toggleSaved(CHANNEL_CONTENT_TYPE, "saved", nowEpochMillis = 100L)

        assertThat(export.ownChannels()).containsExactly("saved", "recent").inOrder()
    }

    @Test
    fun `a channel that is both saved and recent appears once`() {
        val export = EMPTY
            .withRecentChannel("41", nowEpochMillis = 900L)
            .toggleSaved(CHANNEL_CONTENT_TYPE, "41", nowEpochMillis = 100L)

        assertThat(export.ownChannels()).containsExactly("41")
    }

    /** A guide is a request per channel, so the count has to be bounded rather than trusted. */
    @Test
    fun `the list is capped`() {
        var export = EMPTY
        repeat(60) { export = export.withRecentChannel("channel-$it", nowEpochMillis = it.toLong()) }

        assertThat(export.ownChannels(limit = 40)).hasSize(40)
    }

    @Test
    fun `newest watched comes first`() {
        val export = EMPTY
            .withRecentChannel("old", nowEpochMillis = 100L)
            .withRecentChannel("new", nowEpochMillis = 200L)

        assertThat(export.ownChannels()).containsExactly("new", "old").inOrder()
    }

    private companion object {
        val EMPTY = UserDataExport(exportedAtEpochMillis = 0L, accountFingerprint = "abc")
    }

    @Test
    fun `an episode's progress remembers which series it belongs to`() {
        // Without this the other device receives a number it cannot name: no Xtream listing indexes
        // episodes, so the only way to resolve one is to ask about every series in the library.
        val stored = EMPTY.withProgress(
            contentType = EPISODE_CONTENT_TYPE,
            contentId = "9001",
            positionMs = 60_000L,
            durationMs = 1_800_000L,
            seriesId = "400",
        )

        assertThat(stored.watchProgress.single().seriesId).isEqualTo("400")
    }

    @Test
    fun `a film carries no series and says so`() {
        val stored = EMPTY.withProgress(
            contentType = MOVIE_CONTENT_TYPE,
            contentId = "501",
            positionMs = 60_000L,
            durationMs = 1_800_000L,
        )

        assertThat(stored.watchProgress.single().seriesId).isNull()
    }

    @Test
    fun `a file written before the field existed still reads`() {
        // Both codecs ignore unknown keys, and the field defaults to null, so neither direction of
        // the round trip needs a format version.
        val old = """{"formatVersion":1,"exportedAtEpochMillis":1,"accountFingerprint":"abc",
            "watchProgress":[{"contentType":"episode","contentId":"9001","positionMs":10,
            "durationMs":100,"completed":false,"updatedAtEpochMillis":5}]}"""

        val decoded = UserDataExportCodec.decode(old)

        assertThat(decoded).isInstanceOf(UserDataImportResult.Ok::class.java)
        val record = (decoded as UserDataImportResult.Ok).export.watchProgress.single()
        assertThat(record.seriesId).isNull()
        assertThat(record.contentId).isEqualTo("9001")
    }
}
