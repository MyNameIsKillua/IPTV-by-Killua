package dev.killua.iptv.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.killua.iptv.core.text.SearchTextNormalizer
import dev.killua.iptv.data.xtream.XtreamLanguageTagger

/**
 * Schema 1 -> 2: adds the account-scoped Movie tables and a generic watch-progress table.
 *
 * The migration is purely additive. It creates new tables and touches no existing row, so the
 * live library, recent channels, and account metadata of an installed production app survive the
 * upgrade untouched. Destructive fallback is never enabled; see docs/DATABASE.md.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `movie_categories` (
                `accountId` TEXT NOT NULL,
                `remoteCategoryId` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `languageTag` TEXT,
                `sortOrder` INTEGER NOT NULL,
                `syncGeneration` INTEGER NOT NULL,
                PRIMARY KEY(`accountId`, `remoteCategoryId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_movie_categories_accountId_sortOrder` " +
                "ON `movie_categories` (`accountId`, `sortOrder`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_movie_categories_accountId_languageTag` " +
                "ON `movie_categories` (`accountId`, `languageTag`)",
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `movies` (
                `accountId` TEXT NOT NULL,
                `remoteStreamId` TEXT NOT NULL,
                `remoteCategoryId` TEXT,
                `name` TEXT NOT NULL,
                `sortName` TEXT NOT NULL,
                `posterUrl` TEXT,
                `containerExtension` TEXT,
                `rating` REAL,
                `releaseYear` INTEGER,
                `addedAtEpochSeconds` INTEGER,
                `languageTag` TEXT,
                `providerOrder` INTEGER NOT NULL,
                `syncGeneration` INTEGER NOT NULL,
                PRIMARY KEY(`accountId`, `remoteStreamId`)
            )
            """.trimIndent(),
        )
        listOf(
            "index_movies_accountId_remoteCategoryId" to "`accountId`, `remoteCategoryId`",
            "index_movies_accountId_providerOrder" to "`accountId`, `providerOrder`",
            "index_movies_accountId_sortName" to "`accountId`, `sortName`",
            "index_movies_accountId_rating" to "`accountId`, `rating`",
            "index_movies_accountId_releaseYear" to "`accountId`, `releaseYear`",
            "index_movies_accountId_addedAtEpochSeconds" to "`accountId`, `addedAtEpochSeconds`",
            "index_movies_accountId_languageTag" to "`accountId`, `languageTag`",
        ).forEach { (name, columns) ->
            db.execSQL("CREATE INDEX IF NOT EXISTS `$name` ON `movies` ($columns)")
        }

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `movie_details` (
                `accountId` TEXT NOT NULL,
                `remoteStreamId` TEXT NOT NULL,
                `plot` TEXT,
                `genre` TEXT,
                `cast` TEXT,
                `director` TEXT,
                `backdropUrl` TEXT,
                `durationSeconds` INTEGER,
                `fetchedAtEpochMillis` INTEGER NOT NULL,
                PRIMARY KEY(`accountId`, `remoteStreamId`)
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `movie_favorites` (
                `accountId` TEXT NOT NULL,
                `remoteStreamId` TEXT NOT NULL,
                `favoritedAtEpochMillis` INTEGER NOT NULL,
                PRIMARY KEY(`accountId`, `remoteStreamId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_movie_favorites_accountId_favoritedAtEpochMillis` " +
                "ON `movie_favorites` (`accountId`, `favoritedAtEpochMillis`)",
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `watch_progress` (
                `accountId` TEXT NOT NULL,
                `contentType` TEXT NOT NULL,
                `contentId` TEXT NOT NULL,
                `positionMs` INTEGER NOT NULL,
                `durationMs` INTEGER NOT NULL,
                `completed` INTEGER NOT NULL,
                `updatedAtEpochMillis` INTEGER NOT NULL,
                PRIMARY KEY(`accountId`, `contentType`, `contentId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_watch_progress_accountId_contentType_updatedAtEpochMillis` " +
                "ON `watch_progress` (`accountId`, `contentType`, `updatedAtEpochMillis`)",
        )
    }
}

/**
 * Schema 2 -> 3: adds the indexed `sortName` column that live title search and alphabetical
 * sorting run against.
 *
 * No row is deleted and no existing column is touched. Cached channels are backfilled in place
 * with SQLite's `lower()` so search and A-Z sorting work immediately after the upgrade instead of
 * waiting for a refresh that downloads tens of megabytes. That built-in folds ASCII only, so a
 * channel whose name starts with an uppercase non-ASCII letter keeps sorting by its raw form until
 * the next refresh rewrites the column with the same normalization the app applies to new rows.
 *
 * The unused index on the raw name is replaced rather than supplemented, so the table keeps the
 * same number of indices: ordering by the raw name sorts every capital letter before every
 * lowercase one, and `COLLATE NOCASE` ordering could not use that index at all.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE `live_channels` ADD COLUMN `sortName` TEXT NOT NULL DEFAULT ''",
        )
        db.execSQL("UPDATE `live_channels` SET `sortName` = lower(`name`)")
        db.execSQL("DROP INDEX IF EXISTS `index_live_channels_accountId_name`")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_live_channels_accountId_sortName` " +
                "ON `live_channels` (`accountId`, `sortName`)",
        )
    }
}

/**
 * Schema 3 -> 4: adds the heuristic language of a live channel, so Live can offer the same
 * language filter Movies already has.
 *
 * The column is nullable and no existing value is touched. Cached channels are backfilled here
 * rather than left blank until the next refresh, because a refresh of this provider's library
 * takes minutes and the filter would otherwise offer nothing at all after the upgrade.
 *
 * The backfill runs the same Kotlin heuristic the app uses, but only over the **categories**: one
 * `UPDATE` per category with a recognized tag, rather than a pass over six figures of channel
 * rows. A channel whose category says nothing while its own name carries a tag therefore stays
 * unlabelled until the next refresh, which writes the full rule including that fallback. That is
 * the deliberate trade: a migration that runs in bounded time on a large library beats one that
 * is exhaustive on the first launch after an update.
 *
 * The movie tables get no equivalent column here: `movie_categories.languageTag` already exists
 * and `movies.languageTag` is written on every refresh.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `live_channels` ADD COLUMN `languageTag` TEXT")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_live_channels_accountId_languageTag` " +
                "ON `live_channels` (`accountId`, `languageTag`)",
        )

        val tagged = mutableListOf<Triple<String, String, String>>()
        db.query("SELECT `accountId`, `remoteCategoryId`, `name` FROM `live_categories`").use {
            while (it.moveToNext()) {
                val language = XtreamLanguageTagger.languageOfCategory(it.getString(2)) ?: continue
                tagged += Triple(it.getString(0), it.getString(1), language)
            }
        }
        tagged.forEach { (accountId, categoryId, language) ->
            db.execSQL(
                "UPDATE `live_channels` SET `languageTag` = ? " +
                    "WHERE `accountId` = ? AND `remoteCategoryId` = ?",
                arrayOf(language, accountId, categoryId),
            )
        }
    }
}

/**
 * Schema 4 -> 5: adds the account-scoped Series tables.
 *
 * Purely additive, like the Movie tables before it: new tables only, no existing row touched, so
 * an installed app keeps its account, both libraries, history, favorites, and watch positions.
 *
 * Episodes deliberately reuse `watch_progress` through its `contentType` column rather than
 * getting a table of their own, which is why nothing about progress changes here.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `series_categories` (
                `accountId` TEXT NOT NULL,
                `remoteCategoryId` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `sortOrder` INTEGER NOT NULL,
                `syncGeneration` INTEGER NOT NULL,
                PRIMARY KEY(`accountId`, `remoteCategoryId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_series_categories_accountId_sortOrder` " +
                "ON `series_categories` (`accountId`, `sortOrder`)",
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `series` (
                `accountId` TEXT NOT NULL,
                `remoteSeriesId` TEXT NOT NULL,
                `remoteCategoryId` TEXT,
                `name` TEXT NOT NULL,
                `sortName` TEXT NOT NULL,
                `posterUrl` TEXT,
                `rating` REAL,
                `releaseYear` INTEGER,
                `lastModifiedEpochSeconds` INTEGER,
                `languageTag` TEXT,
                `providerOrder` INTEGER NOT NULL,
                `syncGeneration` INTEGER NOT NULL,
                PRIMARY KEY(`accountId`, `remoteSeriesId`)
            )
            """.trimIndent(),
        )
        listOf(
            "index_series_accountId_remoteCategoryId" to "`accountId`, `remoteCategoryId`",
            "index_series_accountId_providerOrder" to "`accountId`, `providerOrder`",
            "index_series_accountId_sortName" to "`accountId`, `sortName`",
            "index_series_accountId_rating" to "`accountId`, `rating`",
            "index_series_accountId_releaseYear" to "`accountId`, `releaseYear`",
            "index_series_accountId_lastModifiedEpochSeconds" to
                "`accountId`, `lastModifiedEpochSeconds`",
            "index_series_accountId_languageTag" to "`accountId`, `languageTag`",
        ).forEach { (name, columns) ->
            db.execSQL("CREATE INDEX IF NOT EXISTS `$name` ON `series` ($columns)")
        }

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `series_details` (
                `accountId` TEXT NOT NULL,
                `remoteSeriesId` TEXT NOT NULL,
                `plot` TEXT,
                `genre` TEXT,
                `cast` TEXT,
                `director` TEXT,
                `backdropUrl` TEXT,
                `fetchedAtEpochMillis` INTEGER NOT NULL,
                PRIMARY KEY(`accountId`, `remoteSeriesId`)
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `series_episodes` (
                `accountId` TEXT NOT NULL,
                `remoteEpisodeId` TEXT NOT NULL,
                `remoteSeriesId` TEXT NOT NULL,
                `seasonNumber` INTEGER NOT NULL,
                `episodeNumber` INTEGER,
                `title` TEXT NOT NULL,
                `containerExtension` TEXT,
                `durationSeconds` INTEGER,
                `plot` TEXT,
                `stillUrl` TEXT,
                PRIMARY KEY(`accountId`, `remoteEpisodeId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_series_episodes_accountId_remoteSeriesId_seasonNumber_episodeNumber` " +
                "ON `series_episodes` " +
                "(`accountId`, `remoteSeriesId`, `seasonNumber`, `episodeNumber`)",
        )
    }
}

/**
 * Schema 6 adds `series_favorites`, so a series can be marked the way a Movie already could.
 *
 * One new table, nothing existing touched. Like `movie_favorites` it carries no sync generation:
 * a favorite must survive a provider temporarily dropping the series from its listing.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `series_favorites` (
                `accountId` TEXT NOT NULL,
                `remoteSeriesId` TEXT NOT NULL,
                `favoritedAtEpochMillis` INTEGER NOT NULL,
                PRIMARY KEY(`accountId`, `remoteSeriesId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_series_favorites_accountId_favoritedAtEpochMillis` " +
                "ON `series_favorites` (`accountId`, `favoritedAtEpochMillis`)",
        )
    }
}

/**
 * Schema 6 -> 7: folds punctuation out of every cached `sortName`, so search matches the way people
 * type.
 *
 * No table, column, or index changes here; the version exists purely to carry a data rewrite. Every
 * `sortName` was written before `SearchTextNormalizer` existed and still contains the provider's
 * punctuation, so a cached `mr. robot` cannot be found by `LIKE '%mr robot%'`. Changing only the
 * write path would leave every already-cached row wrong until the next full refresh, which on the
 * reference provider is minutes of download for something the device can fix locally.
 *
 * The rewrite runs the app's own `SearchTextNormalizer` rather than SQL, so an upgraded cache ends
 * up with exactly the keys a refresh would write — no approximation, unlike `MIGRATION_2_3`'s
 * `lower()`. `MIGRATION_3_4` already set the precedent of running Kotlin inside a migration.
 *
 * It was first written as one nested `replace()` expression per table, and SQLite rejected it on a
 * device with "parser stack overflow": the parser's stack is far smaller than its expression-depth
 * limit, and around forty nested calls is already past it. Splitting that into chunked statements
 * would have meant rewriting every row several times over; this way each row is written **once**,
 * and only if its key actually changes.
 *
 * Ordering shifts slightly, and that is accepted: `The A-Team` files under `the a team`, and a title
 * that used to sort after Z because it began with a bracket now sorts under its first letter.
 *
 * It is still the most expensive migration in the project: roughly 290,000 rows are read and most of
 * them rewritten, each maintaining its `sortName` index. That is a one-off cost on the first launch
 * after the update, and far cheaper than the full refresh it saves.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        listOf("live_channels", "movies", "series").forEach { table ->
            db.foldSortNames(table)
        }
    }
}

/**
 * Schema 7 -> 8: adds `watchlist`, the one saved list that spans all three libraries.
 *
 * Purely additive: one new table, no existing row touched, so an installed app keeps its account,
 * every library, favorites, and watch positions. Like the favorites tables it carries no sync
 * generation, because a saved title has to survive the provider dropping it from a listing.
 *
 * One table rather than a flag per library. The list exists to be read as a whole, and three
 * separate marks would have to be merged on every read anyway.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `watchlist` (
                `accountId` TEXT NOT NULL,
                `contentType` TEXT NOT NULL,
                `contentId` TEXT NOT NULL,
                `addedAtEpochMillis` INTEGER NOT NULL,
                PRIMARY KEY(`accountId`, `contentType`, `contentId`)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_watchlist_accountId_addedAtEpochMillis` " +
                "ON `watchlist` (`accountId`, `addedAtEpochMillis`)",
        )
    }
}

/**
 * How many rows are held in memory at once while folding.
 *
 * The same rule as everywhere else in this project: a six-figure library must never be materialized
 * whole. Each page is read, closed, and written before the next one is asked for.
 */
private const val SORT_NAME_PAGE = 2_000

/**
 * Rewrites one table's sort keys, a page of rowids at a time.
 *
 * Paging by `rowid` rather than iterating one cursor over the whole table keeps each cursor closed
 * before its rows are updated. Updating a table under an open cursor is not something to rely on,
 * and `sortName` sits in an index the scan would otherwise be walking.
 *
 * The table names are code constants; every value is bound.
 */
private fun SupportSQLiteDatabase.foldSortNames(table: String) {
    var lastRowId = 0L
    while (true) {
        var read = 0
        val changed = ArrayList<Pair<Long, String>>()
        query(
            "SELECT `rowid`, `sortName` FROM `$table` WHERE `rowid` > ? " +
                "ORDER BY `rowid` LIMIT $SORT_NAME_PAGE",
            arrayOf<Any>(lastRowId),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                read++
                lastRowId = cursor.getLong(0)
                val current = cursor.getString(1) ?: continue
                val folded = SearchTextNormalizer.normalize(current)
                // Most keys already read this way; writing them again would only cost index churn.
                if (folded != current) changed += lastRowId to folded
            }
        }
        changed.forEach { (rowId, folded) ->
            execSQL(
                "UPDATE `$table` SET `sortName` = ? WHERE `rowid` = ?",
                arrayOf<Any>(folded, rowId),
            )
        }
        if (read < SORT_NAME_PAGE) return
    }
}

/**
 * Adds the account's own name for the playlist.
 *
 * One nullable column and nothing else. Null is the honest value for every account that already
 * exists: the provider has no such field, so there is nothing to backfill it from, and the sign-in
 * name stands in until the viewer says otherwise.
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `accounts` ADD COLUMN `displayName` TEXT")
    }
}
