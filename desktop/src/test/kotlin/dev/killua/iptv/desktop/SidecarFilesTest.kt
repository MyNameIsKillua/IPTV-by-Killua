package dev.killua.iptv.desktop

import com.google.common.truth.Truth.assertThat
import dev.killua.iptv.domain.userdata.MOVIE_CONTENT_TYPE
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The two files beside the state file.
 *
 * Both are declared disposable, which is a promise about behaviour rather than an excuse: deleting
 * or corrupting either has to cost a caption and a window size, never a crash and never the state
 * file next to them.
 */
class SidecarFilesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val account = DesktopUserData.fingerprintOf("https://provider.example/", "alice")
    private val otherAccount = DesktopUserData.fingerprintOf("https://provider.example/", "bob")

    @Test
    fun `titles survive a round trip`() = runBlocking {
        val index = TitleIndex(folder.root)
        val entries = mapOf(
            TitleIndex.keyOf(MOVIE_CONTENT_TYPE, "501") to IndexedTitle(
                label = "A Film",
                artworkUrl = "https://images.example/501.jpg",
                containerExtension = "mkv",
            ),
        )

        index.save(account, entries)

        assertThat(index.load(account)).isEqualTo(entries)
    }

    @Test
    fun `names cached for one account are not shown for another`() = runBlocking {
        val index = TitleIndex(folder.root)
        index.save(
            account,
            mapOf(TitleIndex.keyOf(MOVIE_CONTENT_TYPE, "501") to IndexedTitle("A Film")),
        )

        // The provider numbers each library from one, so 501 is a different film on a different
        // account. An unscoped cache would caption one account's saved list with another's titles.
        assertThat(index.load(otherAccount)).isEmpty()
    }

    @Test
    fun `a missing or damaged title cache is simply empty`() = runBlocking {
        assertThat(TitleIndex(folder.root).load(account)).isEmpty()

        File(folder.root, "titles.json").writeText("not json at all")

        assertThat(TitleIndex(folder.root).load(account)).isEmpty()
    }

    @Test
    fun `a title key is scoped by content type`() {
        // A film and a series can both be numbered 501 by the provider, and both can be marked.
        assertThat(TitleIndex.keyOf(MOVIE_CONTENT_TYPE, "501"))
            .isNotEqualTo(TitleIndex.keyOf("series", "501"))
    }

    @Test
    fun `preferences survive a round trip`() = runBlocking {
        val store = PreferenceStore(folder.root)
        val preferences = DesktopPreferences(
            section = "Movies",
            categories = mapOf("Movies" to "42", "Live" to "7"),
            volume = 35,
            muted = true,
            windowWidth = 1600,
            windowHeight = 900,
        )

        store.save(preferences)

        assertThat(store.load()).isEqualTo(preferences)
    }

    @Test
    fun `a playlist name survives a round trip, keyed by fingerprint rather than by account`() =
        runBlocking {
            val mine = DesktopUserData.fingerprintOf("https://provider.example/", "alice")
            val theirs = DesktopUserData.fingerprintOf("https://other.example/", "alice")
            val store = PreferenceStore(folder.root)

            store.save(
                DesktopPreferences(playlistNames = mapOf(mine to "Wohnzimmer", theirs to "Zweitanbieter")),
            )

            val loaded = store.load()
            assertThat(loaded.playlistNames[mine]).isEqualTo("Wohnzimmer")
            assertThat(loaded.playlistNames[theirs]).isEqualTo("Zweitanbieter")
            // The key is the one-way fingerprint, so nothing in the file is the account itself.
            assertThat(File(folder.root, "preferences.json").readText())
                .doesNotContain("provider.example")
        }

    @Test
    fun `the window starts filling the screen unless it was left otherwise`() = runBlocking {
        assertThat(DesktopPreferences().windowMaximized).isTrue()

        val store = PreferenceStore(folder.root)
        store.save(DesktopPreferences(windowMaximized = false, windowWidth = 1200, windowHeight = 800))

        val loaded = store.load()
        assertThat(loaded.windowMaximized).isFalse()
        assertThat(loaded.safeWidth).isEqualTo(1200)
    }

    @Test
    fun `a missing or damaged preferences file falls back to the defaults`() = runBlocking {
        assertThat(PreferenceStore(folder.root).load()).isEqualTo(DesktopPreferences())

        File(folder.root, "preferences.json").writeText("{ half a file")

        assertThat(PreferenceStore(folder.root).load()).isEqualTo(DesktopPreferences())
    }

    @Test
    fun `a preferences file from a newer version keeps what it recognises`() = runBlocking {
        File(folder.root, "preferences.json").writeText(
            """{ "section": "Series", "volume": 20, "somethingLater": true }""",
        )

        val loaded = PreferenceStore(folder.root).load()

        // Ignoring unknown keys is what lets an older build read a newer file instead of throwing
        // the whole thing away and resetting the window.
        assertThat(loaded.section).isEqualTo("Series")
        assertThat(loaded.volume).isEqualTo(20)
    }

    @Test
    fun `a category is only reopened for the account it was opened on`() {
        val preferences = DesktopPreferences().withCategory(account, "Movies", "42")

        assertThat(preferences.categoriesFor(account)).containsExactly("Movies", "42")
        // Category ids are small integers handed out per account, so "42" means one thing here and
        // another there. Reopening it for the wrong account is opening the wrong category.
        assertThat(preferences.categoriesFor(otherAccount)).isEmpty()
    }

    @Test
    fun `opening a category on a second account replaces the first account's`() {
        val preferences = DesktopPreferences()
            .withCategory(account, "Movies", "42")
            .withCategory(otherAccount, "Live", "7")

        assertThat(preferences.categoriesFor(otherAccount)).containsExactly("Live", "7")
        assertThat(preferences.categoriesFor(account)).isEmpty()
    }

    @Test
    fun `everything else in the preferences outlives a change of account`() {
        val preferences = DesktopPreferences(
            section = "Movies",
            sorts = mapOf("Movies" to "NameAscending"),
            volume = 30,
            windowWidth = 1600,
        ).withCategory(otherAccount, "Live", "7")

        // The destination, the order, the volume and the window are about the client rather than
        // the library; only the category ids belong to a provider.
        assertThat(preferences.section).isEqualTo("Movies")
        assertThat(preferences.sorts).containsExactly("Movies", "NameAscending")
        assertThat(preferences.volume).isEqualTo(30)
        assertThat(preferences.windowWidth).isEqualTo(1600)
    }

    @Test
    fun `a window size cannot be restored to something unreachable`() {
        val tiny = DesktopPreferences(windowWidth = 1, windowHeight = 1)
        assertThat(tiny.safeWidth).isEqualTo(DesktopPreferences.MIN_WIDTH)
        assertThat(tiny.safeHeight).isEqualTo(DesktopPreferences.MIN_HEIGHT)

        val absurd = DesktopPreferences(windowWidth = 99_999, windowHeight = 99_999)
        assertThat(absurd.safeWidth).isEqualTo(DesktopPreferences.MAX_DIMENSION)
        assertThat(absurd.safeHeight).isEqualTo(DesktopPreferences.MAX_DIMENSION)

        val ordinary = DesktopPreferences(windowWidth = 1600, windowHeight = 900)
        assertThat(ordinary.safeWidth).isEqualTo(1600)
        assertThat(ordinary.safeHeight).isEqualTo(900)
    }

    @Test
    fun `no credential reaches a sidecar`() = runBlocking {
        PreferenceStore(folder.root).save(
            DesktopPreferences(section = "Live").withCategory(account, "Live", "7"),
        )
        TitleIndex(folder.root).save(
            account,
            mapOf(
                TitleIndex.keyOf(MOVIE_CONTENT_TYPE, "501") to IndexedTitle(
                    label = "A Film",
                    artworkUrl = "https://images.example/501.jpg",
                ),
            ),
        )

        val written = folder.root.listFiles().orEmpty().joinToString(" ") { it.readText() }

        // What these files may hold: provider ids, title names, artwork URLs and window furniture,
        // plus the one-way fingerprint that says whose they are. What they may never hold is a
        // username or a password, and no code path puts one there.
        //
        // Deliberately *not* asserted: that no host appears. An artwork URL is stored as the
        // provider gave it, and some providers serve posters from their own host, so this file can
        // name it. That is accepted because it never leaves the machine — unlike the export, which
        // carries no host at all. The artwork files themselves are named by hash, where keeping the
        // URL readable would have bought nothing.
        listOf("alice", "bob", "password", "secret").forEach {
            assertThat(written).doesNotContain(it)
        }
        assertThat(written).contains(account)
    }
}
