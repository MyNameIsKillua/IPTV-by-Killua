package dev.killua.iptv.desktop

import com.google.common.truth.Truth.assertThat
import dev.killua.iptv.domain.model.LiveChannel
import dev.killua.iptv.domain.model.MovieSummary
import dev.killua.iptv.domain.model.SeriesSummary
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The library kept between launches.
 *
 * Two things are being held down here. That it is **not a database** — an unreadable or outdated
 * file is deleted rather than repaired, because reading the library again is always available and a
 * migration path is the thing this must never grow. And that it is **a listing, not an account**:
 * the two fields through which a credential could reach the disk are closed on the way out.
 */
class LibraryCacheTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val account = "fingerprint-abc"
    private val secrets = SecretsToStrip(username = "viewer", password = "hunter2")

    @Test
    fun `what was kept comes back`() = runBlocking<Unit> {
        val cache = LibraryCache(folder.root)

        cache.save(account, index(), secrets)

        val kept = cache.load(account, maxAgeMillis = HOUR)
        assertThat(kept).isNotNull()
        val index = kept!!.asIndex()
        assertThat(index.countOf(LibraryKind.Channels)).isEqualTo(1)
        assertThat(index.movies.single().name).isEqualTo("Der Astronaut")
        assertThat(index.series.single().id).isEqualTo("300")
        assertThat(index.loaded).containsExactlyElementsIn(LibraryKind.entries)
    }

    @Test
    fun `a channel's direct source never reaches the disk`() = runBlocking<Unit> {
        // The one field a provider fills with a full signed-in URL. Nothing in this client plays
        // from it, so the safe thing is not to copy it at all.
        val cache = LibraryCache(folder.root)

        cache.save(account, index(), secrets)

        val written = File(folder.root, "library-$account.json").readText()
        assertThat(written).doesNotContain("hunter2")
        assertThat(written).doesNotContain("direct")
        assertThat(cache.load(account, HOUR)!!.asIndex().channels.single().directSource).isNull()
    }

    @Test
    fun `artwork carrying the account is dropped rather than kept`() = runBlocking<Unit> {
        val cache = LibraryCache(folder.root)
        val withAccountInUrl = LibraryIndex(
            movies = listOf(
                movie("1", "Kept", poster = "https://images.provider.example/poster-1.jpg"),
                movie("2", "Dropped", poster = "https://provider.example/viewer/hunter2/2.jpg"),
            ),
            loaded = setOf(LibraryKind.Movies),
        )

        cache.save(account, withAccountInUrl, secrets)

        val movies = cache.load(account, HOUR)!!.asIndex().movies
        assertThat(movies.first { it.id == "1" }.posterUrl).isNotNull()
        assertThat(movies.first { it.id == "2" }.posterUrl).isNull()
        assertThat(File(folder.root, "library-$account.json").readText()).doesNotContain("hunter2")
    }

    @Test
    fun `a copy older than the viewer allows is not offered`() = runBlocking<Unit> {
        val cache = LibraryCache(folder.root)
        cache.save(account, index(), secrets)
        ageTheFile(byMillis = 3 * HOUR)

        assertThat(cache.load(account, maxAgeMillis = HOUR)).isNull()
        // Not deleted: it is stale, not broken, and the next successful read overwrites it anyway.
        assertThat(File(folder.root, "library-$account.json").exists()).isTrue()
    }

    @Test
    fun `zero means until I ask`() = runBlocking<Unit> {
        val cache = LibraryCache(folder.root)
        cache.save(account, index(), secrets)
        ageTheFile(byMillis = 400 * HOUR)

        assertThat(cache.load(account, maxAgeMillis = 0L)).isNotNull()
    }

    @Test
    fun `a file this version cannot read is deleted rather than repaired`() = runBlocking<Unit> {
        val cache = LibraryCache(folder.root)
        File(folder.root, "library-$account.json").writeText("{ half a file")

        assertThat(cache.load(account, HOUR)).isNull()

        assertThat(File(folder.root, "library-$account.json").exists()).isFalse()
    }

    @Test
    fun `a file from another version is deleted rather than migrated`() = runBlocking<Unit> {
        val cache = LibraryCache(folder.root)
        File(folder.root, "library-$account.json").writeText(
            """{"version":99,"savedAtEpochMillis":${System.currentTimeMillis()}}""",
        )

        assertThat(cache.load(account, HOUR)).isNull()
        assertThat(File(folder.root, "library-$account.json").exists()).isFalse()
    }

    @Test
    fun `two accounts on one machine cannot see each other`() = runBlocking<Unit> {
        val cache = LibraryCache(folder.root)
        cache.save("one", index(), secrets)

        assertThat(cache.load("two", HOUR)).isNull()
        assertThat(cache.load("one", HOUR)).isNotNull()

        cache.forgetAll()
        assertThat(cache.load("one", HOUR)).isNull()
        assertThat(cache.bytesOnDisk()).isEqualTo(0L)
    }

    private fun ageTheFile(byMillis: Long) {
        val file = File(folder.root, "library-$account.json")
        val document = file.readText()
        val now = System.currentTimeMillis()
        file.writeText(
            document.replace(
                Regex("\"savedAtEpochMillis\":\\s*\\d+"),
                "\"savedAtEpochMillis\":${now - byMillis}",
            ),
        )
    }

    private fun index() = LibraryIndex(
        channels = listOf(
            LiveChannel(
                id = "100",
                categoryId = "1",
                name = "DE | Testkanal",
                logoUrl = "https://images.provider.example/logo.png",
                epgChannelId = "kanal.de",
                containerExtension = "ts",
                // What must never be written down.
                directSource = "https://provider.example/live/viewer/hunter2/100.ts",
                providerOrder = 1,
            ),
        ),
        movies = listOf(movie("200", "Der Astronaut")),
        series = listOf(
            SeriesSummary(
                id = "300",
                categoryId = "3",
                name = "Avatar",
                posterUrl = null,
                rating = 8.7,
                releaseYear = 2005,
                lastModifiedEpochSeconds = 42L,
                providerOrder = 3,
            ),
        ),
        loaded = LibraryKind.entries.toSet(),
    )

    private fun movie(id: String, name: String, poster: String? = null) = MovieSummary(
        id = id,
        categoryId = "2",
        name = name,
        posterUrl = poster,
        containerExtension = "mkv",
        rating = 7.4,
        releaseYear = 2026,
        addedAtEpochSeconds = 1_700_000_000L,
        providerOrder = 2,
    )

    private companion object {
        const val HOUR = 60L * 60L * 1000L
    }
}
