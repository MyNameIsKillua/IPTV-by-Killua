package dev.killua.iptv.desktop

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class LocalFileTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `it creates the directory and writes the file`() {
        val directory = File(folder.root, "KilluaIPTV")

        val written = writeAtomically(directory, "state.json") { it.writeText("hello") }

        assertThat(written).isNotNull()
        assertThat(File(directory, "state.json").readText()).isEqualTo("hello")
    }

    @Test
    fun `writing twice replaces rather than failing`() {
        writeAtomically(folder.root, "state.json") { it.writeText("first") }
        writeAtomically(folder.root, "state.json") { it.writeText("second") }

        // Windows refuses a rename onto an existing file; without the fallback every save after the
        // first is dropped, which is the quietest possible way to lose someone's evening.
        assertThat(File(folder.root, "state.json").readText()).isEqualTo("second")
    }

    @Test
    fun `nothing temporary is left behind`() {
        writeAtomically(folder.root, "state.json") { it.writeText("hello") }

        assertThat(folder.root.list().orEmpty().toList()).containsExactly("state.json")
    }

    @Test
    fun `a failed write leaves the previous file intact`() {
        writeAtomically(folder.root, "state.json") { it.writeText("the good one") }

        val written = writeAtomically(folder.root, "state.json") {
            it.writeText("half")
            throw IOException("the disk went away")
        }

        // The whole point of the temporary: a failure damages that and nothing else.
        assertThat(written).isNull()
        assertThat(File(folder.root, "state.json").readText()).isEqualTo("the good one")
        assertThat(folder.root.list().orEmpty().toList()).containsExactly("state.json")
    }

    @Test
    fun `a directory that cannot be made is reported rather than thrown`() {
        // A file where the directory should be: mkdirs fails, and so must the write — quietly,
        // because every caller here is a save that must not take the application down with it.
        val blocked = File(folder.root, "blocked")
        blocked.writeText("I am a file")

        assertThat(writeAtomically(blocked, "state.json") { it.writeText("hello") }).isNull()
    }
}
