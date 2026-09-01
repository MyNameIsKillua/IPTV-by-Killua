package dev.killua.iptv.desktop

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test
import java.io.IOException

class CancellationTest {
    @Test
    fun `an ordinary failure is still a failure to report`() {
        val result = catchingExceptCancellation { throw IOException("no route to host") }
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(IOException::class.java)
    }

    @Test
    fun `a value comes back as a value`() {
        assertThat(catchingExceptCancellation { 7 }.getOrNull()).isEqualTo(7)
    }

    @Test
    fun `a cancellation is not caught`() {
        try {
            catchingExceptCancellation { throw CancellationException("called off") }
            error("the cancellation should have been rethrown")
        } catch (cancellation: CancellationException) {
            assertThat(cancellation).hasMessageThat().isEqualTo("called off")
        }
    }

    /**
     * The case this exists for, in the shape it actually takes: a request abandoned because the
     * viewer moved on. With `runCatching` the block below would carry on past the cancellation and
     * report a failure about a request nobody was waiting for any more.
     */
    @Test
    fun `an abandoned request reports nothing and does not carry on`() = runTest {
        val started = CompletableDeferred<Unit>()
        var reportedFailure = false
        var ranPastTheCancellation = false

        val job = launch {
            catchingExceptCancellation {
                started.complete(Unit)
                // Suspends until cancelled, which is where a network call would be.
                CompletableDeferred<Unit>().await()
            }.onFailure { reportedFailure = true }
            ranPastTheCancellation = true
        }

        started.await()
        job.cancel()
        yield()

        assertThat(reportedFailure).isFalse()
        assertThat(ranPastTheCancellation).isFalse()
    }
}
