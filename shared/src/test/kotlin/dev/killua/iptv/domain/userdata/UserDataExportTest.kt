package dev.killua.iptv.domain.userdata

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** All fixtures are fictitious. */
class UserDataExportTest {

    @Test
    fun `an export survives a round trip`() {
        val decoded = UserDataExportCodec.decode(UserDataExportCodec.encode(SAMPLE))

        assertThat(decoded).isInstanceOf(UserDataImportResult.Ok::class.java)
        assertThat((decoded as UserDataImportResult.Ok).export).isEqualTo(SAMPLE)
    }

    /**
     * The whole point of the format. A file that carried the account would be as dangerous as the
     * password itself, because a provider URL contains both.
     */
    @Test
    fun `no credential ever reaches the file`() {
        val export = SAMPLE.copy(
            accountFingerprint = UserDataExportCodec.fingerprint("provider.example", "hunter"),
        )

        val text = UserDataExportCodec.encode(export)

        assertThat(text).doesNotContain("hunter")
        assertThat(text).doesNotContain("provider.example")
        assertThat(text).doesNotContain("s3cret")
        assertThat(text).doesNotContain("http")
    }

    /** A device-local UUID means nothing anywhere else, so it must not travel. */
    @Test
    fun `no local account id reaches the file`() {
        val text = UserDataExportCodec.encode(SAMPLE)

        assertThat(text).doesNotContain("accountId")
        assertThat(text).doesNotContain("3f9a1c7e-0000-4000-8000-000000000000")
    }

    @Test
    fun `the same account fingerprints the same way whatever the url looked like`() {
        val one = UserDataExportCodec.fingerprint("Provider.Example", " killua ")
        val two = UserDataExportCodec.fingerprint("provider.example", "killua")

        assertThat(one).isEqualTo(two)
    }

    @Test
    fun `different accounts fingerprint differently`() {
        val one = UserDataExportCodec.fingerprint("provider.example", "killua")
        val two = UserDataExportCodec.fingerprint("provider.example", "someone-else")

        assertThat(one).isNotEqualTo(two)
    }

    /** Changing a password must not orphan an export that is already on another device. */
    @Test
    fun `the fingerprint does not depend on the password`() {
        val before = UserDataExportCodec.fingerprint("provider.example", "killua")

        assertThat(before).isEqualTo(UserDataExportCodec.fingerprint("provider.example", "killua"))
        assertThat(before).hasLength(64)
    }

    @Test
    fun `a file from a newer build is refused rather than half read`() {
        val text = UserDataExportCodec.encode(SAMPLE.copy(formatVersion = CURRENT_FORMAT_VERSION + 1))

        val result = UserDataExportCodec.decode(text)

        assertThat(result).isEqualTo(UserDataImportResult.UnsupportedVersion(CURRENT_FORMAT_VERSION + 1))
    }

    /** An older build has to survive a field it has never heard of, or the format cannot grow. */
    @Test
    fun `an unknown field does not stop a file being read`() {
        val text = UserDataExportCodec.encode(SAMPLE)
            .replaceFirst("{", """{"somethingAddedLater": "value",""")

        assertThat(UserDataExportCodec.decode(text)).isInstanceOf(UserDataImportResult.Ok::class.java)
    }

    @Test
    fun `anything that is not an export is rejected`() {
        assertThat(UserDataExportCodec.decode("")).isEqualTo(UserDataImportResult.NotAnExport)
        assertThat(UserDataExportCodec.decode("not json at all"))
            .isEqualTo(UserDataImportResult.NotAnExport)
        assertThat(UserDataExportCodec.decode("""{"formatVersion":1}"""))
            .isEqualTo(UserDataImportResult.NotAnExport)
        assertThat(UserDataExportCodec.decode("""{"exportedAtEpochMillis":1,"accountFingerprint":""}"""))
            .isEqualTo(UserDataImportResult.NotAnExport)
    }

    @Test
    fun `an empty export is valid and counts nothing`() {
        val empty = UserDataExport(exportedAtEpochMillis = 1L, accountFingerprint = "abc")

        assertThat(empty.recordCount).isEqualTo(0)
        assertThat(UserDataExportCodec.decode(UserDataExportCodec.encode(empty)))
            .isEqualTo(UserDataImportResult.Ok(empty))
    }

    @Test
    fun `the record count covers every list`() {
        assertThat(SAMPLE.recordCount).isEqualTo(5)
    }

    private companion object {
        val SAMPLE = UserDataExport(
            exportedAtEpochMillis = 1_760_000_000_000L,
            accountFingerprint = "0123456789abcdef",
            watchProgress = listOf(
                ProgressRecord("movie", "501", 61_000L, 600_000L, false, 1_759_000_000_000L),
                ProgressRecord("episode", "9001", 600_000L, 600_000L, true, 1_759_500_000_000L),
            ),
            movieFavorites = listOf(MarkRecord("501", 1_758_000_000_000L)),
            seriesFavorites = listOf(MarkRecord("77", 1_758_500_000_000L)),
            watchlist = listOf(WatchlistRecord("series", "77", 1_757_000_000_000L)),
        )
    }
}
