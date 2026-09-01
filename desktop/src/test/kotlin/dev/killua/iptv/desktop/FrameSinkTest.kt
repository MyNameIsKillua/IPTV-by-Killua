package dev.killua.iptv.desktop

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.ByteBuffer

/**
 * The difference between the buffer libvlc writes into and the picture inside it.
 *
 * Assuming they were the same put a grey band along the bottom of every video this client played,
 * and squashed everything above it by the same fraction. It was invisible for as long as the control
 * bar covered the bottom of the picture; making the bar go away is what would have shown it.
 *
 * Measured, not guessed: libvlc asks three times for one medium — for a 1280x720 file it asked for
 * 1280x720, 1280x720, then **1280x738** — and it is the last answer it writes into.
 */
class FrameSinkTest {

    @Test
    fun `the picture is the source size, not the padded buffer size`() {
        val sink = FrameSink()
        sink.resize(bufferWidth = 1280, bufferHeight = 738, pictureWidth = 1280, pictureHeight = 720)

        sink.accept(planesFor(width = 1280, height = 738))

        val frame = sink.latest
        assertThat(frame).isNotNull()
        assertThat(frame!!.height).isEqualTo(720)
        assertThat(frame.width).isEqualTo(1280)
        // The image is built at the picture's height and the buffer's stride, so the rows past the
        // picture are still in the array and simply never read.
        assertThat(frame.y.height).isEqualTo(720)
        assertThat(frame.y.width).isEqualTo(1280)
        assertThat(frame.u.height).isEqualTo(360)
    }

    @Test
    fun `a buffer with no padding is unchanged`() {
        val sink = FrameSink()
        sink.resize(bufferWidth = 640, bufferHeight = 360, pictureWidth = 640, pictureHeight = 360)

        sink.accept(planesFor(width = 640, height = 360))

        assertThat(sink.latest!!.height).isEqualTo(360)
        assertThat(sink.width).isEqualTo(640)
        assertThat(sink.height).isEqualTo(360)
    }

    @Test
    fun `a frame arriving before any size is known is dropped rather than guessed at`() {
        val sink = FrameSink()

        sink.accept(planesFor(width = 8, height = 8))

        assertThat(sink.latest).isNull()
    }

    /** Three I420 planes of the right sizes, filled with anything at all. */
    private fun planesFor(width: Int, height: Int): Array<ByteBuffer> = arrayOf(
        ByteBuffer.allocateDirect(width * height),
        ByteBuffer.allocateDirect(width / 2 * (height / 2)),
        ByteBuffer.allocateDirect(width / 2 * (height / 2)),
    )
}
