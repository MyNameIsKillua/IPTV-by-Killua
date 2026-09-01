package dev.killua.iptv.data.repository

import com.google.common.truth.Truth.assertThat
import dev.killua.iptv.core.database.MovieQueryFactory
import dev.killua.iptv.core.database.SeriesQueryFactory
import dev.killua.iptv.domain.userdata.CHANNEL_CONTENT_TYPE
import dev.killua.iptv.domain.userdata.EPISODE_CONTENT_TYPE
import dev.killua.iptv.domain.userdata.MOVIE_CONTENT_TYPE
import dev.killua.iptv.domain.userdata.SERIES_CONTENT_TYPE
import org.junit.Test

/**
 * The words the two clients have to agree on.
 *
 * The export format is the only thing joining the phone and the desktop, and it joins them by
 * *string*: a saved row says `movie`, a progress row says `episode`. Both clients write those words
 * from their own constants, and nothing at compile time makes the two sets equal — a rename on one
 * side is a bookmark that silently stops crossing between devices, with no failure anywhere to
 * notice it.
 *
 * This is that failure, brought forward to the build. It exists because the mistake was already made
 * once: the desktop wrote `live` for a saved channel while the phone had always written `channel`,
 * so a bookmark set on one arrived at the other and was dropped on the floor.
 */
class ContentTypeVocabularyTest {

    @Test
    fun `the phone's watch-progress discriminators are the format's own`() {
        assertThat(MovieQueryFactory.MOVIE_CONTENT_TYPE).isEqualTo(MOVIE_CONTENT_TYPE)
        assertThat(SeriesQueryFactory.EPISODE_CONTENT_TYPE).isEqualTo(EPISODE_CONTENT_TYPE)
    }

    @Test
    fun `the words themselves are pinned, not merely equal to each other`() {
        // Comparing the constants alone would let both sides be renamed together, which changes
        // what is written into every file already on a viewer's disk.
        assertThat(MOVIE_CONTENT_TYPE).isEqualTo("movie")
        assertThat(SERIES_CONTENT_TYPE).isEqualTo("series")
        assertThat(EPISODE_CONTENT_TYPE).isEqualTo("episode")
        assertThat(CHANNEL_CONTENT_TYPE).isEqualTo("channel")
    }

    @Test
    fun `a saved channel is called what the phone has always called it`() {
        // The phone drops a watchlist row whose type it does not recognise — the safe reading for a
        // stored value it cannot open, and the reason a mismatch here is silent rather than loud.
        assertThat(CHANNEL_CONTENT_TYPE).isNotEqualTo("live")
    }
}
