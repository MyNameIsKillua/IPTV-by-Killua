package dev.killua.iptv.domain.progress

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WatchProgressPolicyTest {
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
}
