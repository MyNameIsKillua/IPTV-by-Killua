package dev.killua.iptv.core.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The media id is what a progress write proves ownership with, so decoding has to be exact.
 * All identifiers here are fictitious.
 */
class PlaybackMediaIdTest {
    @Test
    fun `a live id survives a round trip`() {
        val id = PlaybackMediaId.Live("account-1", "41")

        assertThat(id.encode()).isEqualTo("live:account-1:41")
        assertThat(PlaybackMediaId.decode(id.encode())).isEqualTo(id)
    }

    @Test
    fun `a movie id survives a round trip and is distinct from a live id`() {
        val movie = PlaybackMediaId.Movie("account-1", "501")

        assertThat(movie.encode()).isEqualTo("movie:account-1:501")
        assertThat(PlaybackMediaId.decode(movie.encode())).isEqualTo(movie)
        assertThat(movie.encode()).isNotEqualTo(PlaybackMediaId.Live("account-1", "501").encode())
    }

    @Test
    fun `an episode id survives a round trip and never collides with a movie`() {
        val episode = PlaybackMediaId.Episode("account-1", "9012")

        assertThat(episode.encode()).isEqualTo("episode:account-1:9012")
        assertThat(PlaybackMediaId.decode(episode.encode())).isEqualTo(episode)
        // Providers number movies and episodes independently, so the same id does occur in both.
        assertThat(PlaybackMediaId.Episode("account-1", "501"))
            .isNotEqualTo(PlaybackMediaId.Movie("account-1", "501"))
    }

    @Test
    fun `only the resumable types are marked resumable`() {
        assertThat(PlaybackMediaId.Movie("account-1", "501"))
            .isInstanceOf(PlaybackMediaId.Resumable::class.java)
        assertThat(PlaybackMediaId.Episode("account-1", "9012"))
            .isInstanceOf(PlaybackMediaId.Resumable::class.java)
        // A channel has no position worth storing, and the type is what enforces that.
        assertThat(PlaybackMediaId.Live("account-1", "41"))
            .isNotInstanceOf(PlaybackMediaId.Resumable::class.java)
    }

    @Test
    fun `a content id containing a separator is preserved`() {
        val id = PlaybackMediaId.Movie("account-1", "5:0:1")

        assertThat(PlaybackMediaId.decode(id.encode())).isEqualTo(id)
    }

    @Test
    fun `anything unrecognized decodes to null rather than a guess`() {
        listOf(
            null,
            "",
            "live",
            "live:account-1",
            "series:account-1:7",
            "live::41",
            "live:account-1:",
            "https://provider.example/live/user/password/41.ts",
        ).forEach { value ->
            assertThat(PlaybackMediaId.decode(value)).isNull()
        }
    }

    @Test
    fun `an encoded id carries no credentials`() {
        val encoded = PlaybackMediaId.Movie("account-1", "501").encode()

        assertThat(encoded).doesNotContain("http")
        assertThat(encoded).doesNotContain("/")
    }
}
