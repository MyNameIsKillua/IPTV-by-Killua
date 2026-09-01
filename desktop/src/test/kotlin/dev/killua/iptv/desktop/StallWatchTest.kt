package dev.killua.iptv.desktop

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StallWatchTest {
    private fun StallWatch.run(times: Int, isPlaying: Boolean, positionMs: Long): Boolean {
        var stalled = false
        repeat(times) { stalled = observe(isPlaying, positionMs, POLL) }
        return stalled
    }

    @Test
    fun `a picture that keeps moving is never stalled`() {
        val watch = StallWatch(toleranceMillis = 15_000L)
        var position = 1_000L
        repeat(120) {
            position += POLL
            assertThat(watch.observe(isPlaying = true, positionMs = position, sinceLastMillis = POLL))
                .isFalse()
        }
    }

    @Test
    fun `a clock standing still while the player claims to play is a stall`() {
        val watch = StallWatch(toleranceMillis = 15_000L)
        // The first reading only records where we are.
        assertThat(watch.observe(true, 600_000L, POLL)).isFalse()
        // Fourteen seconds of nothing is still within a rebuffer.
        assertThat(watch.run(28, isPlaying = true, positionMs = 600_000L)).isFalse()
        // The thirtieth reading is fifteen seconds, which is the line.
        assertThat(watch.run(2, isPlaying = true, positionMs = 600_000L)).isTrue()
    }

    @Test
    fun `a paused film is never a stall`() {
        val watch = StallWatch(toleranceMillis = 15_000L)
        assertThat(watch.run(200, isPlaying = false, positionMs = 600_000L)).isFalse()
    }

    @Test
    fun `nothing started yet is never a stall`() {
        val watch = StallWatch(toleranceMillis = 15_000L)
        assertThat(watch.run(200, isPlaying = true, positionMs = 0L)).isFalse()
    }

    @Test
    fun `a picture that comes back clears the stall`() {
        val watch = StallWatch(toleranceMillis = 15_000L)
        assertThat(watch.run(40, isPlaying = true, positionMs = 600_000L)).isTrue()
        assertThat(watch.observe(true, 600_500L, POLL)).isFalse()
        // And the count starts again rather than carrying the old standstill forward.
        assertThat(watch.run(20, isPlaying = true, positionMs = 600_500L)).isFalse()
    }

    @Test
    fun `pausing forgets the standstill rather than banking it`() {
        val watch = StallWatch(toleranceMillis = 15_000L)
        assertThat(watch.run(28, isPlaying = true, positionMs = 600_000L)).isFalse()
        // A pause in the middle is not fourteen seconds of evidence towards a stall.
        assertThat(watch.observe(false, 600_000L, POLL)).isFalse()
        assertThat(watch.run(28, isPlaying = true, positionMs = 600_000L)).isFalse()
    }

    private companion object {
        const val POLL = 500L
    }
}
