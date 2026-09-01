package dev.killua.iptv.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies each upgrade against the exported schemas.
 *
 * The production app is installed and in use, so this migration runs on a database that already
 * holds a real account, live library, and watch history. Losing any of it would be a data-loss
 * bug the user cannot recover from, which is why the assertions check surviving rows rather than
 * only that the migration completes.
 *
 * All fixtures below are fictitious.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        IptvDatabase::class.java,
    )

    @Test
    fun migrate1To2_preservesAccountLibraryAndHistory() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO accounts (
                    accountId, status, expiresAtEpochSeconds, activeConnections,
                    maximumConnections, serverTimezone, allowedOutputFormats,
                    lastValidatedAtEpochMillis, lastLiveSyncAtEpochMillis
                ) VALUES ('account-1', 'Active', 4102444800, 1, 2, 'Europe/Berlin',
                          'm3u8,ts', 1000, 2000)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO live_categories (
                    accountId, remoteCategoryId, name, sortOrder, syncGeneration
                ) VALUES ('account-1', '7', 'Nachrichten', 0, 42)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO live_channels (
                    accountId, remoteStreamId, remoteCategoryId, name, logoUrl,
                    epgChannelId, containerExtension, providerOrder, syncGeneration
                ) VALUES ('account-1', '41', '7', 'Kanal Ü', NULL, NULL, 'ts', 1, 42)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO recent_channels (accountId, remoteStreamId, lastWatchedAtEpochMillis)
                VALUES ('account-1', '41', 5000)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2)

        db.query("SELECT accountId, lastLiveSyncAtEpochMillis FROM accounts").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("account-1")
            assertThat(cursor.getLong(1)).isEqualTo(2000)
            assertThat(cursor.count).isEqualTo(1)
        }
        db.query("SELECT name FROM live_channels WHERE remoteStreamId = '41'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("Kanal Ü")
        }
        db.query("SELECT lastWatchedAtEpochMillis FROM recent_channels").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(5000)
        }
        db.query("SELECT name FROM live_categories WHERE remoteCategoryId = '7'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("Nachrichten")
        }
    }

    @Test
    fun migrate1To2_createsUsableEmptyMovieTables() {
        helper.createDatabase(TEST_DB, 1).close()

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2)

        listOf(
            "movie_categories",
            "movies",
            "movie_details",
            "movie_favorites",
            "watch_progress",
        ).forEach { table ->
            db.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getInt(0)).isEqualTo(0)
            }
        }

        // The new tables must accept writes immediately after the upgrade.
        db.execSQL(
            """
            INSERT INTO movies (
                accountId, remoteStreamId, remoteCategoryId, name, sortName, posterUrl,
                containerExtension, rating, releaseYear, addedAtEpochSeconds, languageTag,
                providerOrder, syncGeneration
            ) VALUES ('account-1', '501', '20', 'DE | Beispielfilm', 'beispielfilm', NULL,
                      'mkv', 7.4, 2019, 1690000000, 'de', 3, 99)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO watch_progress (
                accountId, contentType, contentId, positionMs, durationMs, completed,
                updatedAtEpochMillis
            ) VALUES ('account-1', 'movie', '501', 1234, 5678, 0, 7000)
            """.trimIndent(),
        )
        db.query("SELECT sortName, languageTag FROM movies WHERE remoteStreamId = '501'").use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.getString(0)).isEqualTo("beispielfilm")
            assertThat(it.getString(1)).isEqualTo("de")
        }
        db.query("SELECT positionMs FROM watch_progress WHERE contentId = '501'").use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.getLong(0)).isEqualTo(1234)
        }
    }

    @Test
    fun migrate2To3_backfillsSortNameAndKeepsEveryRow() {
        helper.createDatabase(TEST_DB, 2).use { db ->
            db.execSQL(
                """
                INSERT INTO accounts (
                    accountId, status, expiresAtEpochSeconds, activeConnections,
                    maximumConnections, serverTimezone, allowedOutputFormats,
                    lastValidatedAtEpochMillis, lastLiveSyncAtEpochMillis
                ) VALUES ('account-1', 'Active', 4102444800, 1, 2, 'Europe/Berlin',
                          'm3u8,ts', 1000, 2000)
                """.trimIndent(),
            )
            listOf(
                "'41', '7', 'DE | Beispiel HD'",
                "'42', '7', 'ZWEITER Kanal'",
            ).forEach { values ->
                db.execSQL(
                    """
                    INSERT INTO live_channels (
                        accountId, remoteStreamId, remoteCategoryId, name, logoUrl,
                        epgChannelId, containerExtension, providerOrder, syncGeneration
                    ) VALUES ('account-1', $values, NULL, NULL, 'ts', 1, 42)
                    """.trimIndent(),
                )
            }
            db.execSQL(
                """
                INSERT INTO recent_channels (accountId, remoteStreamId, lastWatchedAtEpochMillis)
                VALUES ('account-1', '41', 5000)
                """.trimIndent(),
            )
            // Movie state must survive an upgrade that only touches the live table.
            db.execSQL(
                """
                INSERT INTO movie_favorites (accountId, remoteStreamId, favoritedAtEpochMillis)
                VALUES ('account-1', '501', 6000)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO watch_progress (
                    accountId, contentType, contentId, positionMs, durationMs, completed,
                    updatedAtEpochMillis
                ) VALUES ('account-1', 'movie', '501', 1234, 5678, 0, 7000)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3)

        // Cached channels are searchable and sortable immediately, without a fresh download.
        db.query(
            "SELECT remoteStreamId, name, sortName FROM live_channels ORDER BY sortName ASC",
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("41")
            assertThat(cursor.getString(1)).isEqualTo("DE | Beispiel HD")
            assertThat(cursor.getString(2)).isEqualTo("de | beispiel hd")
            assertThat(cursor.moveToNext()).isTrue()
            assertThat(cursor.getString(2)).isEqualTo("zweiter kanal")
            assertThat(cursor.count).isEqualTo(2)
        }
        db.query("SELECT lastWatchedAtEpochMillis FROM recent_channels").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(5000)
        }
        db.query("SELECT favoritedAtEpochMillis FROM movie_favorites").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(6000)
        }
        db.query("SELECT positionMs FROM watch_progress WHERE contentId = '501'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(1234)
        }
    }

    @Test
    fun migrate3To4_labelsChannelsFromTheirCategoryLanguage() {
        helper.createDatabase(TEST_DB, 3).use { db ->
            listOf(
                "'7', 'DE | Nachrichten'",
                "'8', 'Sport'",
            ).forEach { values ->
                db.execSQL(
                    """
                    INSERT INTO live_categories (
                        accountId, remoteCategoryId, name, sortOrder, syncGeneration
                    ) VALUES ('account-1', $values, 0, 42)
                    """.trimIndent(),
                )
            }
            listOf(
                "'41', '7', 'DE | Erster Kanal', 'de | erster kanal'",
                "'42', '8', 'FR | Zweiter Kanal', 'fr | zweiter kanal'",
                "'43', NULL, 'Dritter Kanal', 'dritter kanal'",
            ).forEach { values ->
                db.execSQL(
                    """
                    INSERT INTO live_channels (
                        accountId, remoteStreamId, remoteCategoryId, name, sortName, logoUrl,
                        epgChannelId, containerExtension, providerOrder, syncGeneration
                    ) VALUES ('account-1', $values, NULL, NULL, 'ts', 1, 42)
                    """.trimIndent(),
                )
            }
            db.execSQL(
                """
                INSERT INTO recent_channels (accountId, remoteStreamId, lastWatchedAtEpochMillis)
                VALUES ('account-1', '41', 5000)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4)

        val languages = mutableMapOf<String, String?>()
        db.query("SELECT remoteStreamId, languageTag FROM live_channels").use { cursor ->
            while (cursor.moveToNext()) {
                languages[cursor.getString(0)] =
                    if (cursor.isNull(1)) null else cursor.getString(1)
            }
        }
        // The category carries the tag, so its channels inherit it.
        assertThat(languages["41"]).isEqualTo("de")
        // An untagged category leaves the channel unlabelled even though its own name has a tag:
        // the migration resolves categories only, and the next refresh fills that fallback in.
        assertThat(languages["42"]).isNull()
        assertThat(languages["43"]).isNull()

        db.query("SELECT lastWatchedAtEpochMillis FROM recent_channels").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(5000)
        }
    }

    @Test
    fun migrate4To5_addsSeriesTablesWithoutTouchingExistingData() {
        helper.createDatabase(TEST_DB, 4).use { db ->
            db.execSQL(
                """
                INSERT INTO live_channels (
                    accountId, remoteStreamId, remoteCategoryId, name, sortName, logoUrl,
                    epgChannelId, containerExtension, languageTag, providerOrder, syncGeneration
                ) VALUES ('account-1', '41', '7', 'DE | Erster', 'de | erster', NULL, NULL,
                          'ts', 'de', 1, 42)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO movie_favorites (accountId, remoteStreamId, favoritedAtEpochMillis)
                VALUES ('account-1', '501', 6000)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO watch_progress (
                    accountId, contentType, contentId, positionMs, durationMs, completed,
                    updatedAtEpochMillis
                ) VALUES ('account-1', 'movie', '501', 1234, 5678, 0, 7000)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 5, true, MIGRATION_4_5)

        listOf("series_categories", "series", "series_details", "series_episodes").forEach { table ->
            db.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getInt(0)).isEqualTo(0)
            }
        }
        db.query("SELECT languageTag FROM live_channels WHERE remoteStreamId = '41'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("de")
        }
        db.query("SELECT favoritedAtEpochMillis FROM movie_favorites").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(6000)
        }
        db.query("SELECT positionMs FROM watch_progress WHERE contentId = '501'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(1234)
        }

        // The new tables must accept writes immediately, and episodes must share watch_progress
        // through its content type rather than getting a table of their own.
        db.execSQL(
            """
            INSERT INTO series (
                accountId, remoteSeriesId, remoteCategoryId, name, sortName, posterUrl, rating,
                releaseYear, lastModifiedEpochSeconds, languageTag, providerOrder, syncGeneration
            ) VALUES ('account-1', '7', '90', 'DE | Beispielserie', 'beispielserie', NULL, 8.1,
                      2019, 1690000000, 'de', 1, 99)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO series_episodes (
                accountId, remoteEpisodeId, remoteSeriesId, seasonNumber, episodeNumber, title,
                containerExtension, durationSeconds, plot, stillUrl
            ) VALUES ('account-1', '101', '7', 1, 1, 'Der Anfang', 'mkv', 2700, NULL, NULL)
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO watch_progress (
                accountId, contentType, contentId, positionMs, durationMs, completed,
                updatedAtEpochMillis
            ) VALUES ('account-1', 'episode', '101', 4321, 2700000, 0, 8000)
            """.trimIndent(),
        )
        db.query("SELECT sortName FROM series WHERE remoteSeriesId = '7'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("beispielserie")
        }
        db.query(
            "SELECT positionMs FROM watch_progress WHERE contentType = 'episode'",
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(4321)
            assertThat(cursor.count).isEqualTo(1)
        }
    }

    @Test
    fun migrate5To6_addsSeriesFavoritesWithoutTouchingExistingData() {
        helper.createDatabase(TEST_DB, 5).use { db ->
            db.execSQL(
                """
                INSERT INTO series (
                    accountId, remoteSeriesId, remoteCategoryId, name, sortName, posterUrl,
                    rating, releaseYear, lastModifiedEpochSeconds, languageTag, providerOrder,
                    syncGeneration
                ) VALUES ('account-1', '7', '90', 'DE | Beispielserie', 'beispielserie', NULL,
                          8.1, 2019, 1600000000, 'de', 1, 42)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO watch_progress (
                    accountId, contentType, contentId, positionMs, durationMs, completed,
                    updatedAtEpochMillis
                ) VALUES ('account-1', 'episode', '800001', 1234, 5678, 0, 7000)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 6, true, MIGRATION_5_6)

        // The new table exists and accepts a row.
        db.execSQL(
            """
            INSERT INTO series_favorites (accountId, remoteSeriesId, favoritedAtEpochMillis)
            VALUES ('account-1', '7', 9000)
            """.trimIndent(),
        )
        db.query("SELECT favoritedAtEpochMillis FROM series_favorites").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(9000)
        }
        // Nothing that existed before was disturbed.
        db.query("SELECT name FROM series WHERE remoteSeriesId = '7'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("DE | Beispielserie")
        }
        db.query("SELECT positionMs FROM watch_progress WHERE contentId = '800001'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(1234)
        }
    }

    @Test
    fun migrate6To7_foldsPunctuationOutOfEveryCachedSortName() {
        helper.createDatabase(TEST_DB, 6).use { db ->
            db.execSQL(
                """
                INSERT INTO movies (
                    accountId, remoteStreamId, remoteCategoryId, name, sortName, posterUrl,
                    containerExtension, rating, releaseYear, addedAtEpochSeconds, languageTag,
                    providerOrder, syncGeneration
                ) VALUES ('account-1', '504', '21', 'Mr. Robot', 'mr. robot', NULL, 'mkv',
                          NULL, NULL, NULL, NULL, 1, 42)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO movies (
                    accountId, remoteStreamId, remoteCategoryId, name, sortName, posterUrl,
                    containerExtension, rating, releaseYear, addedAtEpochSeconds, languageTag,
                    providerOrder, syncGeneration
                ) VALUES ('account-1', '505', '21', 'Marvel''s Spider-Man',
                          'marvel''s spider-man', NULL, 'mkv', NULL, NULL, NULL, NULL, 2, 42)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO live_channels (
                    accountId, remoteStreamId, remoteCategoryId, name, sortName, logoUrl,
                    epgChannelId, containerExtension, languageTag, providerOrder, syncGeneration
                ) VALUES ('account-1', '41', '7', 'DE | RTL HD', 'de | rtl hd', NULL, NULL,
                          'ts', 'de', 1, 42)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO series (
                    accountId, remoteSeriesId, remoteCategoryId, name, sortName, posterUrl,
                    rating, releaseYear, lastModifiedEpochSeconds, languageTag, providerOrder,
                    syncGeneration
                ) VALUES ('account-1', '7', '90', 'Akte X: Die Wahrheit',
                          'akte x: die wahrheit', NULL, NULL, NULL, NULL, NULL, 1, 42)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 7, true, MIGRATION_6_7)

        // The reported bug: this key could not be reached by a `LIKE '%mr robot%'`.
        db.query("SELECT sortName FROM movies WHERE remoteStreamId = '504'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("mr robot")
        }
        // An apostrophe closes up, a hyphen opens out, and neither leaves a double space behind.
        db.query("SELECT sortName FROM movies WHERE remoteStreamId = '505'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("marvels spider man")
        }
        db.query("SELECT sortName, name FROM live_channels WHERE remoteStreamId = '41'")
            .use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo("de rtl hd")
                // The displayed name is never rewritten, only the key.
                assertThat(cursor.getString(1)).isEqualTo("DE | RTL HD")
            }
        db.query("SELECT sortName FROM series WHERE remoteSeriesId = '7'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("akte x die wahrheit")
        }
    }

    @Test
    fun migrate7To8_addsTheWatchlistWithoutTouchingExistingData() {
        helper.createDatabase(TEST_DB, 7).use { db ->
            db.execSQL(
                """
                INSERT INTO movies (
                    accountId, remoteStreamId, remoteCategoryId, name, sortName, posterUrl,
                    containerExtension, rating, releaseYear, addedAtEpochSeconds, languageTag,
                    providerOrder, syncGeneration
                ) VALUES ('account-1', '501', '20', 'Beispielfilm', 'beispielfilm', NULL, 'mkv',
                          NULL, NULL, NULL, NULL, 1, 42)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO movie_favorites (accountId, remoteStreamId, favoritedAtEpochMillis)
                VALUES ('account-1', '501', 4321)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 8, true, MIGRATION_7_8)

        db.execSQL(
            """
            INSERT INTO watchlist (accountId, contentType, contentId, addedAtEpochMillis)
            VALUES ('account-1', 'movie', '501', 9000)
            """.trimIndent(),
        )
        db.query("SELECT contentType, addedAtEpochMillis FROM watchlist").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("movie")
            assertThat(cursor.getLong(1)).isEqualTo(9000)
        }
        // The per-library favorite is a separate mark and must not be disturbed by the new list.
        db.query("SELECT favoritedAtEpochMillis FROM movie_favorites").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getLong(0)).isEqualTo(4321)
        }
        db.query("SELECT sortName FROM movies WHERE remoteStreamId = '501'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("beispielfilm")
        }
    }

    @Test
    fun migrate9To10_addsThePlaylistColumnsAndLeavesEveryChannelAlone() {
        helper.createDatabase(TEST_DB, 9).use { db ->
            db.execSQL(
                """
                INSERT INTO live_channels (
                    accountId, remoteStreamId, remoteCategoryId, name, sortName, logoUrl,
                    epgChannelId, containerExtension, languageTag, providerOrder, syncGeneration
                ) VALUES ('account-1', '77', '5', 'DE | Beispiel HD', 'de | beispiel hd', NULL,
                          'beispiel.de', 'ts', 'de', 3, 42)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 10, true, MIGRATION_9_10)

        // The row that was already there is untouched, and its three new columns are empty rather
        // than filled with a guess: every channel from before this schema is an Xtream channel,
        // whose address is built at playback time and never stored.
        db.query(
            """
            SELECT name, sortName, languageTag, providerOrder,
                   directSource, streamUserAgent, streamReferrer
            FROM live_channels WHERE remoteStreamId = '77'
            """.trimIndent(),
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("DE | Beispiel HD")
            assertThat(cursor.getString(1)).isEqualTo("de | beispiel hd")
            assertThat(cursor.getString(2)).isEqualTo("de")
            assertThat(cursor.getInt(3)).isEqualTo(3)
            assertThat(cursor.isNull(4)).isTrue()
            assertThat(cursor.isNull(5)).isTrue()
            assertThat(cursor.isNull(6)).isTrue()
        }

        // And a playlist channel can be written into the same table beside it.
        db.execSQL(
            """
            INSERT INTO live_channels (
                accountId, remoteStreamId, remoteCategoryId, name, sortName, logoUrl,
                epgChannelId, containerExtension, languageTag, providerOrder, syncGeneration,
                directSource, streamUserAgent, streamReferrer
            ) VALUES ('account-1', 'abc123', 'News', 'Beispiel News', 'beispiel news', NULL,
                      NULL, 'm3u8', NULL, 4, 42,
                      'https://stream.example/a.m3u8', 'Mozilla/5.0', 'https://portal.example/')
            """.trimIndent(),
        )
        db.query(
            "SELECT directSource, streamUserAgent, streamReferrer FROM live_channels " +
                "WHERE remoteStreamId = 'abc123'",
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("https://stream.example/a.m3u8")
            assertThat(cursor.getString(1)).isEqualTo("Mozilla/5.0")
            assertThat(cursor.getString(2)).isEqualTo("https://portal.example/")
        }
    }

    @Test
    fun migrate1To8_runsEveryStepInSequence() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO live_channels (
                    accountId, remoteStreamId, remoteCategoryId, name, logoUrl,
                    epgChannelId, containerExtension, providerOrder, syncGeneration
                ) VALUES ('account-1', '41', '7', 'DE | RTL  HD', NULL, NULL, 'ts', 1, 42)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            9,
            true,
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
        )

        db.query("SELECT sortName FROM live_channels WHERE remoteStreamId = '41'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("de rtl hd")
        }
        db.query("SELECT COUNT(*) FROM watchlist").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(0)
        }
    }

    @Test
    fun migrate8To9_namesTheAccountWithoutLosingAnythingItAlreadyHad() {
        helper.createDatabase(TEST_DB, 8).use { db ->
            db.execSQL(
                """
                INSERT INTO accounts (
                    accountId, status, expiresAtEpochSeconds, activeConnections,
                    maximumConnections, serverTimezone, allowedOutputFormats,
                    lastValidatedAtEpochMillis, lastLiveSyncAtEpochMillis
                ) VALUES ('account-1', 'Active', 4102444800, 1, 2, 'Europe/Berlin', 'm3u8,ts',
                          1234, 5678)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true, MIGRATION_8_9)

        // The provider has no such field, so an existing account has no name to be given one from.
        db.query(
            "SELECT displayName, serverTimezone, lastLiveSyncAtEpochMillis FROM accounts",
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.isNull(0)).isTrue()
            assertThat(cursor.getString(1)).isEqualTo("Europe/Berlin")
            assertThat(cursor.getLong(2)).isEqualTo(5678)
        }
        db.execSQL("UPDATE accounts SET displayName = 'Wohnzimmer' WHERE accountId = 'account-1'")
        db.query("SELECT displayName FROM accounts").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("Wohnzimmer")
        }
    }

    @Test
    fun migrate1To7_runsEveryStepInSequence() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO live_channels (
                    accountId, remoteStreamId, remoteCategoryId, name, logoUrl,
                    epgChannelId, containerExtension, providerOrder, syncGeneration
                ) VALUES ('account-1', '41', '7', 'DE | RTL  HD', NULL, NULL, 'ts', 1, 42)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            7,
            true,
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
        )

        // 2->3 lowercased it, 6->7 folded it; the double space has to survive neither.
        db.query("SELECT sortName FROM live_channels WHERE remoteStreamId = '41'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("de rtl hd")
        }
        listOf("movies", "series", "series_favorites").forEach { table ->
            db.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getInt(0)).isEqualTo(0)
            }
        }
    }

    @Test
    fun migrate1To6_runsEveryStepInSequence() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO live_channels (
                    accountId, remoteStreamId, remoteCategoryId, name, logoUrl,
                    epgChannelId, containerExtension, providerOrder, syncGeneration
                ) VALUES ('account-1', '41', '7', 'Kanal Eins', NULL, NULL, 'ts', 1, 42)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            6,
            true,
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
        )

        db.query("SELECT sortName FROM live_channels WHERE remoteStreamId = '41'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("kanal eins")
        }
        listOf("movies", "series", "series_favorites").forEach { table ->
            db.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getInt(0)).isEqualTo(0)
            }
        }
    }

    @Test
    fun migrate1To5_runsEveryStepInSequence() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO live_categories (
                    accountId, remoteCategoryId, name, sortOrder, syncGeneration
                ) VALUES ('account-1', '7', 'DE | Nachrichten', 0, 42)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO live_channels (
                    accountId, remoteStreamId, remoteCategoryId, name, logoUrl,
                    epgChannelId, containerExtension, providerOrder, syncGeneration
                ) VALUES ('account-1', '41', '7', 'Kanal Eins', NULL, NULL, 'ts', 1, 42)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            5,
            true,
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
        )

        db.query(
            "SELECT sortName, languageTag FROM live_channels WHERE remoteStreamId = '41'",
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("kanal eins")
            assertThat(cursor.getString(1)).isEqualTo("de")
        }
        listOf("movies", "series").forEach { table ->
            db.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getInt(0)).isEqualTo(0)
            }
        }
    }

    @Test
    fun migrate1To4_runsEveryStepInSequence() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO live_categories (
                    accountId, remoteCategoryId, name, sortOrder, syncGeneration
                ) VALUES ('account-1', '7', 'DE | Nachrichten', 0, 42)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO live_channels (
                    accountId, remoteStreamId, remoteCategoryId, name, logoUrl,
                    epgChannelId, containerExtension, providerOrder, syncGeneration
                ) VALUES ('account-1', '41', '7', 'Kanal Eins', NULL, NULL, 'ts', 1, 42)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB,
            4,
            true,
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
        )

        db.query(
            "SELECT sortName, languageTag FROM live_channels WHERE remoteStreamId = '41'",
        ).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getString(0)).isEqualTo("kanal eins")
            assertThat(cursor.getString(1)).isEqualTo("de")
        }
        db.query("SELECT COUNT(*) FROM movies").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getInt(0)).isEqualTo(0)
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
