package dev.killua.iptv.domain.browse

import com.google.common.truth.Truth.assertThat
import dev.killua.iptv.domain.model.SeriesEpisode
import dev.killua.iptv.domain.userdata.EPISODE_CONTENT_TYPE
import dev.killua.iptv.domain.userdata.MOVIE_CONTENT_TYPE
import dev.killua.iptv.domain.userdata.ProgressRecord
import dev.killua.iptv.domain.userdata.UserDataExport
import org.junit.Test

class SeasonSelectionTest {

    private val series = listOf(
        episode("101", season = 1),
        episode("102", season = 1),
        episode("201", season = 2),
        episode("202", season = 2),
        episode("301", season = 3),
    )

    @Test
    fun `a series nobody has started opens on its first season`() {
        assertThat(series.seasonToOpen(data())).isEqualTo(1)
        assertThat(series.seasonToOpen(null)).isEqualTo(1)
    }

    @Test
    fun `a series in progress opens where the viewer left off`() {
        val data = data(progress("101", at = 100L), progress("201", at = 900L))

        assertThat(series.seasonToOpen(data)).isEqualTo(2)
    }

    @Test
    fun `finishing a season keeps it open rather than jumping back`() {
        val data = data(progress("202", at = 900L, completed = true))

        // The next thing to watch is one row further down, and on the last season staying put beats
        // being sent back to the pilot.
        assertThat(series.seasonToOpen(data)).isEqualTo(2)
    }

    @Test
    fun `progress for other content is not mistaken for this series`() {
        val data = data(
            ProgressRecord(MOVIE_CONTENT_TYPE, "201", 1_000L, 2_000L, false, 900L),
            progress("101", at = 100L),
        )

        // A film numbered 201 has nothing to do with an episode numbered 201; only the type and the
        // id together identify anything in this format.
        assertThat(series.seasonToOpen(data)).isEqualTo(1)
    }

    @Test
    fun `progress for a different series is ignored`() {
        val data = data(progress("9999", at = 900L))

        assertThat(series.seasonToOpen(data)).isEqualTo(1)
    }

    @Test
    fun `a series with no episodes has no season to open`() {
        assertThat(emptyList<SeriesEpisode>().seasonToOpen(data())).isNull()
    }

    @Test
    fun `a provider that numbers from zero is taken at its word`() {
        val specials = listOf(episode("001", season = 0), episode("101", season = 1))

        // Season 0 is where providers put specials. It is a real season to them, so it is one here.
        assertThat(specials.seasonToOpen(data())).isEqualTo(0)
    }

    @Test
    fun `the big button starts the episode that was begun and not finished`() {
        val data = data(
            progress("101", at = 10L, completed = true),
            progress("102", at = 20L),
        )

        assertThat(series.nextEpisodeToWatch(data)?.id).isEqualTo("102")
    }

    @Test
    fun `a part-watched episode wins over an earlier unwatched one`() {
        // 101 was never opened; 201 was left half way. The half-watched one is what someone means
        // by "carry on", even though it is further down the list.
        val data = data(progress("201", at = 30L))

        assertThat(series.nextEpisodeToWatch(data)?.id).isEqualTo("201")
    }

    @Test
    fun `with everything finished the button starts the series again`() {
        val data = data(*series.map { progress(it.id, at = 10L, completed = true) }.toTypedArray())

        assertThat(series.nextEpisodeToWatch(data)?.id).isEqualTo("101")
    }

    @Test
    fun `a series nobody has started begins at the beginning`() {
        assertThat(series.nextEpisodeToWatch(data())?.id).isEqualTo("101")
        assertThat(series.nextEpisodeToWatch(null)?.id).isEqualTo("101")
    }

    @Test
    fun `progress belonging to a film cannot choose an episode`() {
        val film = ProgressRecord(
            contentType = MOVIE_CONTENT_TYPE,
            // The provider numbers each library separately, so a film really can share an id
            // with an episode. Reading it here would start the wrong thing.
            contentId = "201",
            positionMs = 60_000L,
            durationMs = 1_800_000L,
            completed = false,
            updatedAtEpochMillis = 40L,
        )

        assertThat(series.nextEpisodeToWatch(data(film))?.id).isEqualTo("101")
    }

    @Test
    fun `a series with no episodes has nothing to start`() {
        assertThat(emptyList<SeriesEpisode>().nextEpisodeToWatch(data())).isNull()
    }

    private fun data(vararg records: ProgressRecord) = UserDataExport(
        exportedAtEpochMillis = 1_000L,
        accountFingerprint = "fingerprint",
        watchProgress = records.toList(),
    )

    private fun progress(id: String, at: Long, completed: Boolean = false) = ProgressRecord(
        contentType = EPISODE_CONTENT_TYPE,
        contentId = id,
        positionMs = if (completed) 1_800_000L else 60_000L,
        durationMs = 1_800_000L,
        completed = completed,
        updatedAtEpochMillis = at,
    )

    private fun episode(id: String, season: Int) = SeriesEpisode(
        id = id,
        seriesId = "1",
        seasonNumber = season,
        episodeNumber = 1,
        title = "Episode",
        containerExtension = "mkv",
        durationSeconds = 1_800,
        plot = null,
        stillUrl = null,
    )
}
