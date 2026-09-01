package dev.killua.iptv.desktop

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArtworkStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** Driven by hand, so "least recently read" is a statement rather than a race. */
    private var clock = 1_000L
    private val store by lazy { ArtworkStore(File(folder.root, "artwork")) { clock } }

    @Test
    fun `what was written is what comes back`() {
        val bytes = byteArrayOf(1, 2, 3, 4)

        store.write("https://images.example/poster.jpg", bytes)

        assertThat(store.read("https://images.example/poster.jpg")).isEqualTo(bytes)
    }

    @Test
    fun `a url that was never written reads as nothing`() {
        assertThat(store.read("https://images.example/missing.jpg")).isNull()
    }

    @Test
    fun `an empty image is neither written nor returned`() {
        store.write("https://images.example/empty.jpg", ByteArray(0))

        // A zero-byte answer is a failed download, not a poster. Storing it would cache the failure.
        assertThat(store.read("https://images.example/empty.jpg")).isNull()
    }

    @Test
    fun `the file name gives away neither the host nor the path`() {
        store.write("http://provider.example/images/movies/501.jpg", byteArrayOf(9))

        val names = File(folder.root, "artwork").list().orEmpty().joinToString(" ")

        // Provider artwork is sometimes served from the provider's own host. A directory listing
        // full of readable URLs would put that host on disk in plain text for no reason at all.
        assertThat(names).doesNotContain("provider.example")
        assertThat(names).doesNotContain("501")
        assertThat(names).doesNotContain("images")
    }

    @Test
    fun `two urls do not collide`() {
        store.write("https://images.example/a.jpg", byteArrayOf(1))
        store.write("https://images.example/b.jpg", byteArrayOf(2))

        assertThat(store.read("https://images.example/a.jpg")).isEqualTo(byteArrayOf(1))
        assertThat(store.read("https://images.example/b.jpg")).isEqualTo(byteArrayOf(2))
    }

    @Test
    fun `writing the same url twice replaces it rather than failing`() {
        store.write("https://images.example/a.jpg", byteArrayOf(1))
        store.write("https://images.example/a.jpg", byteArrayOf(2, 2))

        // Windows refuses a rename onto an existing file; without the fallback the second write
        // would be dropped and a stale poster would outlive its replacement.
        assertThat(store.read("https://images.example/a.jpg")).isEqualTo(byteArrayOf(2, 2))
    }

    @Test
    fun `pruning drops the least recently read first and stops at the cap`() {
        repeat(5) { index ->
            clock += 1_000
            store.write("https://images.example/$index.jpg", ByteArray(1_000))
        }

        // Reading the oldest one puts it at the front of the queue: the cache is about what is
        // being looked at, not about what happened to be downloaded first.
        clock += 1_000
        store.read("https://images.example/0.jpg")

        val remaining = store.prune(maxBytes = 2_000L)

        assertThat(remaining).isAtMost(2_000L)
        assertThat(store.read("https://images.example/0.jpg")).isNotNull()
        assertThat(store.read("https://images.example/4.jpg")).isNotNull()
        assertThat(store.read("https://images.example/1.jpg")).isNull()
        assertThat(store.read("https://images.example/2.jpg")).isNull()
    }

    @Test
    fun `pruning a directory that fits leaves everything alone`() {
        store.write("https://images.example/a.jpg", ByteArray(100))

        val remaining = store.prune(maxBytes = 10_000L)

        assertThat(remaining).isEqualTo(100L)
        assertThat(store.read("https://images.example/a.jpg")).isNotNull()
    }

    @Test
    fun `a store with no directory yet answers rather than throwing`() {
        val fresh = ArtworkStore(File(folder.root, "never-created"))

        assertThat(fresh.size()).isEqualTo(0L)
        assertThat(fresh.prune()).isEqualTo(0L)
        assertThat(fresh.read("https://images.example/a.jpg")).isNull()
        fresh.clear()
    }

    @Test
    fun `clearing leaves nothing behind`() {
        repeat(3) { store.write("https://images.example/$it.jpg", ByteArray(500)) }

        store.clear()

        assertThat(store.size()).isEqualTo(0L)
    }
}
