package dev.killua.iptv.domain.progress

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WatchProgressPolicyTest {
    @Test
    fun `the end is the end, not the point where resuming stops making sense`() {
        val hour = 60 * 60 * 1_000L
        // Completed three minutes early, so that resuming does not land in the credits...
        assertThat(WatchProgressPolicy.isCompleted(hour - 2 * 60 * 1_000L, hour)).isTrue()
        // ...but nowhere near the end, which is what handing over the next episode depends on.
        assertThat(WatchProgressPolicy.hasReachedEnd(hour - 2 * 60 * 1_000L, hour)).isFalse()
        assertThat(WatchProgressPolicy.hasReachedEnd(hour - 3_000L, hour)).isFalse()
        assertThat(WatchProgressPolicy.hasReachedEnd(hour - 2_000L, hour)).isTrue()
        assertThat(WatchProgressPolicy.hasReachedEnd(hour, hour)).isTrue()
    }

    @Test
    fun `nothing has reached the end without a duration or a position`() {
        assertThat(WatchProgressPolicy.hasReachedEnd(1_000, 0)).isFalse()
        assertThat(WatchProgressPolicy.hasReachedEnd(1_000, -1)).isFalse()
        // Zero is where a stopped player reports itself, and a stopped player has ended nothing.
        assertThat(WatchProgressPolicy.hasReachedEnd(0, 1_000)).isFalse()
    }

    @Test
    fun `fraction is zero for non-positive duration`() {
        assertThat(WatchProgressPolicy.fraction(100, 0)).isEqualTo(0.0)
        assertThat(WatchProgressPolicy.fraction(100, -1)).isEqualTo(0.0)
    }

    @Test
    fun `fraction clamps positions to the valid range`() {
        assertThat(WatchProgressPolicy.fraction(-1, 1_000)).isEqualTo(0.0)
        assertThat(WatchProgressPolicy.fraction(0, 1_000)).isEqualTo(0.0)
        assertThat(WatchProgressPolicy.fraction(250, 1_000)).isEqualTo(0.25)
        assertThat(WatchProgressPolicy.fraction(1_000, 1_000)).isEqualTo(1.0)
        assertThat(WatchProgressPolicy.fraction(2_000, 1_000)).isEqualTo(1.0)
    }

    @Test
    fun `completion percentage changes exactly at ninety three percent`() {
        assertThat(WatchProgressPolicy.isCompleted(929, 1_000)).isFalse()
        assertThat(WatchProgressPolicy.isCompleted(930, 1_000)).isTrue()
    }

    @Test
    fun `remaining time rule only applies to content at least ten minutes long`() {
        val justUnderTenMinutes = 10 * 60 * 1_000L - 1
        val tenMinutes = 10 * 60 * 1_000L

        assertThat(
            WatchProgressPolicy.isCompleted(
                justUnderTenMinutes - WatchProgressPolicy.REMAINING_THRESHOLD_MS,
                justUnderTenMinutes,
            ),
        ).isFalse()
        assertThat(
            WatchProgressPolicy.isCompleted(
                tenMinutes - WatchProgressPolicy.REMAINING_THRESHOLD_MS,
                tenMinutes,
            ),
        ).isTrue()
    }

    @Test
    fun `remaining time boundary is inclusive`() {
        // At 30 minutes, both positions remain below the 93% percentage rule,
        // so this isolates the independent remaining-time boundary.
        val duration = 30 * 60 * 1_000L

        assertThat(
            WatchProgressPolicy.isCompleted(
                duration - WatchProgressPolicy.REMAINING_THRESHOLD_MS - 1,
                duration,
            ),
        ).isFalse()
        assertThat(
            WatchProgressPolicy.isCompleted(
                duration - WatchProgressPolicy.REMAINING_THRESHOLD_MS,
                duration,
            ),
        ).isTrue()
    }

    @Test
    fun `non-positive durations are never complete`() {
        assertThat(WatchProgressPolicy.isCompleted(Long.MAX_VALUE, 0)).isFalse()
        assertThat(WatchProgressPolicy.isCompleted(Long.MAX_VALUE, -1)).isFalse()
    }

    @Test
    fun `negative positions remain incomplete without overflowing remaining time`() {
        assertThat(WatchProgressPolicy.isCompleted(-1, Long.MAX_VALUE)).isFalse()
        assertThat(WatchProgressPolicy.isCompleted(Long.MIN_VALUE, Long.MAX_VALUE)).isFalse()
    }

    @Test
    fun `positions at or beyond the duration are complete`() {
        assertThat(WatchProgressPolicy.isCompleted(1_000, 1_000)).isTrue()
        assertThat(WatchProgressPolicy.isCompleted(Long.MAX_VALUE, 1_000)).isTrue()
    }

    @Test
    fun `the first checkpoint of a title is always worth writing`() {
        assertThat(WatchProgressPolicy.isWorthWriting(null, null, 0, 0)).isTrue()
        assertThat(WatchProgressPolicy.isWorthWriting(null, null, 12_000, 3_600_000)).isTrue()
    }

    @Test
    fun `a paused film is not written down again`() {
        val hour = 60 * 60 * 1_000L
        // Ten seconds later, in exactly the same place: this is the case the whole rule exists for.
        assertThat(WatchProgressPolicy.isWorthWriting(90_000, hour, 90_000, hour)).isFalse()
        // A stream inching forward while it stalls is the same nothing.
        assertThat(WatchProgressPolicy.isWorthWriting(90_000, hour, 90_400, hour)).isFalse()
    }

    @Test
    fun `a second of playback is enough to be worth writing`() {
        val hour = 60 * 60 * 1_000L
        assertThat(WatchProgressPolicy.isWorthWriting(90_000, hour, 91_000, hour)).isTrue()
        assertThat(WatchProgressPolicy.isWorthWriting(90_000, hour, 100_000, hour)).isTrue()
    }

    @Test
    fun `seeking backwards counts as having moved`() {
        val hour = 60 * 60 * 1_000L
        assertThat(WatchProgressPolicy.isWorthWriting(600_000, hour, 480_000, hour)).isTrue()
    }

    @Test
    fun `a duration that arrives late is written even from a standstill`() {
        // libvlc does not always know the length in the first seconds. A rule that watched only the
        // position could hold a zero in place, which reads as a progress bar stuck empty.
        assertThat(WatchProgressPolicy.isWorthWriting(3_000, 0, 3_000, 5_400_000)).isTrue()
    }
}
