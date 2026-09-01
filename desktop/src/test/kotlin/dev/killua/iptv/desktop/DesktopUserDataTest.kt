package dev.killua.iptv.desktop

import com.google.common.truth.Truth.assertThat
import dev.killua.iptv.domain.userdata.MOVIE_CONTENT_TYPE
import dev.killua.iptv.domain.userdata.UserDataExport
import dev.killua.iptv.domain.userdata.UserDataExportCodec
import dev.killua.iptv.domain.userdata.toggleMovieFavourite
import dev.killua.iptv.domain.userdata.resumePositionOf
import dev.killua.iptv.domain.userdata.withProgress
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The desktop client has no database, so this file *is* its storage.
 *
 * Which makes these the tests that matter most in the module: everything a viewer has watched and
 * marked lives in one file that is rewritten in full on every save, and the rules about when it is
 * read, refused or replaced are the difference between remembering their evening and losing it.
 */
class DesktopUserDataTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val fingerprint = DesktopUserData.fingerprintOf("https://provider.example/", "alice")

    @Test
    fun `what was saved is what comes back`() = runBlocking {
        val store = DesktopUserData(folder.root)
        val saved = UserDataExport(
            exportedAtEpochMillis = 1_000L,
            accountFingerprint = fingerprint,
        ).withProgress(MOVIE_CONTENT_TYPE, "501", positionMs = 60_000L, durationMs = 7_200_000L)

        store.save(saved)

        assertThat(store.load(fingerprint).watchProgress).isEqualTo(saved.watchProgress)
    }

    @Test
    fun `a file belonging to another account is not shown and not merged`() = runBlocking {
        val store = DesktopUserData(folder.root)
        store.save(
            UserDataExport(exportedAtEpochMillis = 1_000L, accountFingerprint = fingerprint)
                .withProgress(MOVIE_CONTENT_TYPE, "501", 60_000L, 7_200_000L),
        )

        val other = DesktopUserData.fingerprintOf("https://provider.example/", "bob")
        val loaded = store.load(other)

        // Empty rather than an error, and carrying the *new* account's fingerprint: signing in as
        // someone else must neither reveal the previous account's history nor adopt it.
        assertThat(loaded.watchProgress).isEmpty()
        assertThat(loaded.accountFingerprint).isEqualTo(other)
    }

    @Test
    fun `a damaged file reads as empty rather than throwing`() = runBlocking {
        File(folder.root, "user-data.json").writeText("{ this is not json")

        val loaded = DesktopUserData(folder.root).load(fingerprint)

        assertThat(loaded.watchProgress).isEmpty()
        assertThat(loaded.accountFingerprint).isEqualTo(fingerprint)
    }

    @Test
    fun `saving twice replaces the file rather than failing on the rename`() = runBlocking {
        val store = DesktopUserData(folder.root)
        val base = UserDataExport(exportedAtEpochMillis = 1L, accountFingerprint = fingerprint)

        store.save(base.withProgress(MOVIE_CONTENT_TYPE, "501", 10_000L, 7_200_000L))
        store.save(base.withProgress(MOVIE_CONTENT_TYPE, "501", 90_000L, 7_200_000L))

        // Windows refuses a rename onto an existing file; without the delete-and-retry fallback the
        // second save would be silently dropped and the position would stop advancing.
        assertThat(store.load(fingerprint).watchProgress.single().positionMs).isEqualTo(90_000L)
    }

    @Test
    fun `the directory is created on the first save`() = runBlocking {
        val nested = File(folder.root, "KilluaIPTV")
        val store = DesktopUserData(nested)

        store.save(UserDataExport(exportedAtEpochMillis = 1L, accountFingerprint = fingerprint))

        assertThat(File(nested, "user-data.json").isFile).isTrue()
    }

    @Test
    fun `the stored file is an export the shared codec can read`() = runBlocking {
        val store = DesktopUserData(folder.root)
        store.save(
            UserDataExport(exportedAtEpochMillis = 1L, accountFingerprint = fingerprint)
                .withProgress(MOVIE_CONTENT_TYPE, "501", 60_000L, 7_200_000L),
        )

        // The whole point of storing the export format: this file can be handed to the phone's
        // import without conversion. If that ever stops being true, it stops here.
        val decoded = UserDataExportCodec.decode(File(folder.root, "user-data.json").readText())

        assertThat(decoded).isInstanceOf(
            dev.killua.iptv.domain.userdata.UserDataImportResult.Ok::class.java,
        )
    }

    @Test
    fun `the fingerprint ignores the protocol and the trailing slash but not the user`() {
        val plain = DesktopUserData.fingerprintOf("http://provider.example", "alice")
        val secure = DesktopUserData.fingerprintOf("https://provider.example/", "alice")
        val other = DesktopUserData.fingerprintOf("https://provider.example/", "bob")

        // The same account reached differently is the same account; a different username is not.
        assertThat(plain).isEqualTo(secure)
        assertThat(other).isNotEqualTo(secure)
    }

    @Test
    fun `no part of the fingerprint gives the account back`() {
        val print = DesktopUserData.fingerprintOf("https://provider.example/", "alice")

        assertThat(print).doesNotContain("provider.example")
        assertThat(print).doesNotContain("alice")
    }

    @Test
    fun `a finished title stores its full duration and stops offering to resume`() {
        val duration = 7_200_000L
        val export = UserDataExport(exportedAtEpochMillis = 1L, accountFingerprint = fingerprint)
            .withProgress(MOVIE_CONTENT_TYPE, "501", positionMs = duration - 1_000L, duration)

        val record = export.watchProgress.single()
        assertThat(record.completed).isTrue()
        assertThat(record.positionMs).isEqualTo(duration)
        // Resuming a finished film would drop the viewer into the credits, so it starts over.
        assertThat(export.resumePositionOf(MOVIE_CONTENT_TYPE, "501")).isNull()
    }

    @Test
    fun `a position without a duration is not recorded at all`() {
        val export = UserDataExport(exportedAtEpochMillis = 1L, accountFingerprint = fingerprint)
            .withProgress(MOVIE_CONTENT_TYPE, "501", positionMs = 60_000L, durationMs = 0L)

        // A live stream reports no length. Storing "sixty seconds into nothing" would put a channel
        // in continue-watching and offer to resume it tomorrow.
        assertThat(export.watchProgress).isEmpty()
    }

    @Test
    fun `watching the same title again replaces its row instead of adding one`() {
        val export = UserDataExport(exportedAtEpochMillis = 1L, accountFingerprint = fingerprint)
            .withProgress(MOVIE_CONTENT_TYPE, "501", 10_000L, 7_200_000L)
            .withProgress(MOVIE_CONTENT_TYPE, "501", 20_000L, 7_200_000L)

        assertThat(export.watchProgress).hasSize(1)
        assertThat(export.resumePositionOf(MOVIE_CONTENT_TYPE, "501")).isEqualTo(20_000L)
    }

    @Test
    fun `a save folds in what somebody else wrote`() = runBlocking<Unit> {
        val store = DesktopUserData(folder.root)
        store.save(UserDataExport(exportedAtEpochMillis = 1L, accountFingerprint = fingerprint))
        val loaded = store.load(fingerprint)

        // Another copy of the application, or a file dropped in by hand, writes while this one holds
        // its own picture of the state.
        val theirs = UserDataExport(exportedAtEpochMillis = 2_000L, accountFingerprint = fingerprint)
            .toggleMovieFavourite("999", nowEpochMillis = 2_000L)
        File(folder.root, "user-data.json").writeText(UserDataExportCodec.encode(theirs))
        File(folder.root, "user-data.json").setLastModified(System.currentTimeMillis() + 10_000)

        store.save(loaded.toggleMovieFavourite("501", nowEpochMillis = 3_000L))

        // Both survive: a whole-file write must not take somebody else's evening with it.
        val merged = store.load(fingerprint)
        assertThat(merged.movieFavorites.map { it.contentId }).containsExactly("501", "999")
    }

    @Test
    fun `a file that appeared for another account is not folded in`() = runBlocking<Unit> {
        val store = DesktopUserData(folder.root)
        store.save(UserDataExport(exportedAtEpochMillis = 1L, accountFingerprint = fingerprint))
        val loaded = store.load(fingerprint)

        val other = DesktopUserData.fingerprintOf("https://provider.example/", "bob")
        File(folder.root, "user-data.json").writeText(
            UserDataExportCodec.encode(
                UserDataExport(exportedAtEpochMillis = 2_000L, accountFingerprint = other)
                    .toggleMovieFavourite("999", nowEpochMillis = 2_000L),
            ),
        )
        File(folder.root, "user-data.json").setLastModified(System.currentTimeMillis() + 10_000)

        store.save(loaded.toggleMovieFavourite("501", nowEpochMillis = 3_000L))

        // Somebody else's account is not ours to merge, exactly as on import.
        assertThat(store.load(fingerprint).movieFavorites.map { it.contentId }).containsExactly("501")
    }

    @Test
    fun `an untouched file is written without a re-read`() = runBlocking<Unit> {
        val store = DesktopUserData(folder.root)
        val base = UserDataExport(exportedAtEpochMillis = 1L, accountFingerprint = fingerprint)

        store.save(base.toggleMovieFavourite("501", nowEpochMillis = 1_000L))
        store.save(base.toggleMovieFavourite("502", nowEpochMillis = 2_000L))

        // The second save saw its own write, so it replaces rather than merges — otherwise removing
        // a mark would be impossible, since the merge rule never deletes.
        assertThat(store.load(fingerprint).movieFavorites.map { it.contentId }).containsExactly("502")
    }

    @Test
    fun `a state file that cannot be read is moved aside rather than replaced`() = runBlocking {
        val store = DesktopUserData(folder.root)
        val file = File(folder.root, "user-data.json")
        file.writeText("{ this is not json")

        val loaded = store.load(fingerprint)

        // The launch goes ahead on an empty document...
        assertThat(loaded.recordCount).isEqualTo(0)
        assertThat(loaded.accountFingerprint).isEqualTo(fingerprint)
        // ...but the damaged file is out of the way, whole, and named so it can be found again.
        assertThat(file.exists()).isFalse()
        val kept = folder.root.listFiles().orEmpty().single { it.name.startsWith("user-data.unreadable-") }
        assertThat(kept.readText()).isEqualTo("{ this is not json")
        assertThat(store.setAside).isEqualTo(kept)
    }

    @Test
    fun `the next save lands on a clean file rather than on top of the damaged one`() = runBlocking {
        val store = DesktopUserData(folder.root)
        File(folder.root, "user-data.json").writeText("{ this is not json")
        val loaded = store.load(fingerprint)

        store.save(loaded.toggleMovieFavourite("501"))

        // This is the whole point of moving it: without it the empty document merged with nothing,
        // was written over the damaged file, and took everything that had been in it.
        val reloaded = DesktopUserData(folder.root).load(fingerprint)
        assertThat(reloaded.movieFavorites.map { it.contentId }).containsExactly("501")
        assertThat(folder.root.listFiles().orEmpty().count { it.name.startsWith("user-data.unreadable-") })
            .isEqualTo(1)
    }

    @Test
    fun `a file from a newer build is kept rather than quietly emptied`() = runBlocking {
        val store = DesktopUserData(folder.root)
        val fromTheFuture = UserDataExportCodec
            .encode(UserDataExport(exportedAtEpochMillis = 1L, accountFingerprint = fingerprint))
            .replace("\"formatVersion\": 1", "\"formatVersion\": 99")
        File(folder.root, "user-data.json").writeText(fromTheFuture)

        store.load(fingerprint)

        assertThat(store.setAside).isNotNull()
        assertThat(store.setAside!!.readText()).isEqualTo(fromTheFuture)
    }

    @Test
    fun `kept files are listed, oldest first, and nothing else is`() = runBlocking {
        val store = DesktopUserData(folder.root)
        File(folder.root, "user-data.json").writeText("{ broken")
        store.load(fingerprint)
        // A second damaged file, later in time, to prove the order and that neither is removed.
        File(folder.root, "user-data.json").writeText("{ broken again")
        Thread.sleep(2)
        store.load(fingerprint)

        val kept = store.keptFiles()
        assertThat(kept).hasSize(2)
        assertThat(kept.map { it.name }).isInOrder()
        assertThat(kept.first().readText()).isEqualTo("{ broken")
        assertThat(kept.last().readText()).isEqualTo("{ broken again")
        // The state file, the preferences and the titles sidecar are none of its business.
        File(folder.root, "titles.json").writeText("{}")
        assertThat(store.keptFiles()).hasSize(2)
    }

    @Test
    fun `nothing is kept when nothing went wrong`() = runBlocking {
        val store = DesktopUserData(folder.root)
        store.save(UserDataExport(exportedAtEpochMillis = 1L, accountFingerprint = fingerprint))
        store.load(fingerprint)

        assertThat(store.keptFiles()).isEmpty()
    }

    @Test
    fun `an ordinary load moves nothing aside`() = runBlocking {
        val store = DesktopUserData(folder.root)
        store.save(
            UserDataExport(exportedAtEpochMillis = 1L, accountFingerprint = fingerprint)
                .toggleMovieFavourite("77"),
        )

        val loaded = DesktopUserData(folder.root)
        loaded.load(fingerprint)

        assertThat(loaded.setAside).isNull()
    }

    @Test
    fun `another account's file is left where it is, not moved aside`() = runBlocking {
        // Readable, simply not ours. Moving it would be as wrong as reading it.
        val theirs = DesktopUserData.fingerprintOf("https://provider.example/", "bob")
        DesktopUserData(folder.root).save(
            UserDataExport(exportedAtEpochMillis = 1L, accountFingerprint = theirs),
        )

        val store = DesktopUserData(folder.root)
        store.load(fingerprint)

        assertThat(store.setAside).isNull()
        assertThat(File(folder.root, "user-data.json").exists()).isTrue()
    }
}
