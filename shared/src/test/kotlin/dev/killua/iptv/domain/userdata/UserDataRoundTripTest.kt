package dev.killua.iptv.domain.userdata

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * One evening on the desktop, carried to the phone and back.
 *
 * The individual pieces are tested elsewhere — the codec, the merge rule, each edit. What is not
 * tested elsewhere is the *journey*: that the marks a viewer actually makes survive being written,
 * read, merged into another device's state, and merged back again, with the same words on the wire
 * at every step.
 */
class UserDataRoundTripTest {

    private val account = "fingerprint"

    private fun anEveningOfMarks(): UserDataExport = UserDataExport(
        exportedAtEpochMillis = 1_000L,
        accountFingerprint = account,
    )
        .toggleMovieFavourite("501", nowEpochMillis = 1_100L)
        .toggleSaved(SERIES_CONTENT_TYPE, "88", nowEpochMillis = 1_200L)
        .toggleSaved(CHANNEL_CONTENT_TYPE, "7", nowEpochMillis = 1_300L)
        .withRecentChannel("7", nowEpochMillis = 1_400L)

    @Test
    fun `everything marked survives being written and read`() {
        val evening = anEveningOfMarks()

        val decoded = UserDataExportCodec.decode(UserDataExportCodec.encode(evening))

        assertThat(decoded).isInstanceOf(UserDataImportResult.Ok::class.java)
        assertThat((decoded as UserDataImportResult.Ok).export).isEqualTo(evening)
    }

    @Test
    fun `the words on the wire are the ones both clients read`() {
        val encoded = UserDataExportCodec.encode(anEveningOfMarks())

        // Not a formatting assertion: these strings are the whole of the agreement between the two
        // clients, and a rename that only touched one side would leave this file readable but
        // meaningless to the other.
        assertThat(encoded).contains("\"$SERIES_CONTENT_TYPE\"")
        assertThat(encoded).contains("\"$CHANNEL_CONTENT_TYPE\"")
    }

    @Test
    fun `a file carries its marks into a device that has none`() {
        val fresh = UserDataExport(exportedAtEpochMillis = 2_000L, accountFingerprint = account)

        val merged = fresh.mergedWith(anEveningOfMarks())

        assertThat(merged.movieFavorites.map { it.contentId }).containsExactly("501")
        assertThat(merged.watchlist.map { it.contentType to it.contentId })
            .containsExactly(SERIES_CONTENT_TYPE to "88", CHANNEL_CONTENT_TYPE to "7")
        assertThat(merged.recentChannels.map { it.contentId }).containsExactly("7")
    }

    @Test
    fun `carrying the same file again changes nothing`() {
        val evening = anEveningOfMarks()
        val phone = UserDataExport(exportedAtEpochMillis = 2_000L, accountFingerprint = account)
            .mergedWith(evening)

        val again = phone.mergedWith(evening)

        // The rule that makes a repeated import free, and the reason import never has to ask
        // whether it has already been done.
        assertThat(again.watchlist).isEqualTo(phone.watchlist)
        assertThat(again.movieFavorites).isEqualTo(phone.movieFavorites)
        assertThat(again.recentChannels).isEqualTo(phone.recentChannels)
        assertThat(again.watchProgress).isEqualTo(phone.watchProgress)
    }

    @Test
    fun `a plan says what a file would change before anything is written`() {
        val phone = UserDataExport(exportedAtEpochMillis = 2_000L, accountFingerprint = account)
        val document = UserDataExportCodec.encode(anEveningOfMarks())

        val plan = phone.planImportOf(document)

        assertThat(plan).isInstanceOf(UserDataImportPlan.Ready::class.java)
        // One favourite, two saved rows and one recent channel: four things the phone does not have.
        assertThat((plan as UserDataImportPlan.Ready).changeCount).isEqualTo(4)
    }

    @Test
    fun `a file that adds nothing says so rather than being applied`() {
        val evening = anEveningOfMarks()
        val phone = UserDataExport(exportedAtEpochMillis = 2_000L, accountFingerprint = account)
            .mergedWith(evening)

        val plan = phone.planImportOf(UserDataExportCodec.encode(evening))

        // The difference between "nothing new" and "wrong account" is exactly what a viewer needs
        // to hear, and merging first would make the two indistinguishable.
        assertThat((plan as UserDataImportPlan.Ready).changeCount).isEqualTo(0)
    }

    @Test
    fun `another account's file is refused rather than planned`() {
        val theirs = UserDataExport(exportedAtEpochMillis = 1_000L, accountFingerprint = "someone else")
            .toggleMovieFavourite("501")
        val mine = UserDataExport(exportedAtEpochMillis = 2_000L, accountFingerprint = account)

        val plan = mine.planImportOf(UserDataExportCodec.encode(theirs))

        // The merge rule never deletes, so a wrong import is permanent. This is the check that
        // stands between a viewer and someone else's history in their own list forever.
        assertThat(plan).isEqualTo(UserDataImportPlan.WrongAccount)
    }

    @Test
    fun `something that is not an export at all is unreadable rather than empty`() {
        val mine = UserDataExport(exportedAtEpochMillis = 2_000L, accountFingerprint = account)

        assertThat(mine.planImportOf("{ not really json")).isInstanceOf(
            UserDataImportPlan.Unreadable::class.java,
        )
    }

    @Test
    fun `the return journey does not drag a position backwards`() {
        val onTheDesktop = UserDataExport(exportedAtEpochMillis = 1_000L, accountFingerprint = account)
            .withProgress(EPISODE_CONTENT_TYPE, "9001", 60_000L, 1_800_000L, nowEpochMillis = 1_000L)

        val onThePhone = onTheDesktop
            .withProgress(EPISODE_CONTENT_TYPE, "9001", 900_000L, 1_800_000L, nowEpochMillis = 5_000L)

        val backAgain = onThePhone.mergedWith(onTheDesktop)

        // Carrying the older file back is the mistake anyone makes eventually; newest-wins is what
        // stops it costing fifteen minutes of an episode.
        assertThat(backAgain.watchProgress.single().positionMs).isEqualTo(900_000L)
    }
}
