package dev.killua.iptv.core.player

import androidx.media3.common.MediaItem
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.killua.iptv.domain.model.StreamHeaders
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented rather than a plain unit test, and the reason is the thing under test.
 *
 * These headers exist to survive a `Bundle` crossing to the player's process. This module's unit
 * tests run with `unitTests.isReturnDefaultValues = true`, where `Bundle.putString` does nothing
 * and `getString` answers null - so a round trip there would "pass" by agreeing that nothing came
 * back, which is exactly the failure it is supposed to catch.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackRequestHeadersTest {

    @Test
    fun bothHeadersSurviveTheRoundTrip() {
        val headers = StreamHeaders(
            userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
            referrer = "https://portal.example/",
        )

        val read = PlaybackRequestHeaders.fromItem(itemCarrying(headers))

        assertThat(read?.userAgent).isEqualTo(headers.userAgent)
        assertThat(read?.referrer).isEqualTo(headers.referrer)
    }

    @Test
    fun oneHeaderTravelsWithoutInventingTheOther() {
        val read = PlaybackRequestHeaders.fromItem(
            itemCarrying(StreamHeaders(userAgent = "VLC/3.0.20")),
        )

        assertThat(read?.userAgent).isEqualTo("VLC/3.0.20")
        assertThat(read?.referrer).isNull()
    }

    @Test
    fun anXtreamItemCarriesNothingAndReadsAsNothing() {
        // The whole Xtream path depends on this: its items must take the single shared factory,
        // not a per-item one built from empty headers.
        assertThat(PlaybackRequestHeaders.toExtras(null)).isNull()
        assertThat(PlaybackRequestHeaders.toExtras(StreamHeaders())).isNull()

        val plain = MediaItem.Builder().setUri("https://provider.example/live/1.ts").build()
        assertThat(PlaybackRequestHeaders.fromItem(plain)).isNull()
    }

    private fun itemCarrying(headers: StreamHeaders): MediaItem {
        val extras = requireNotNull(PlaybackRequestHeaders.toExtras(headers))
        return MediaItem.Builder()
            .setUri("https://stream.example/a.m3u8")
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setExtras(extras).build())
            .build()
    }
}
