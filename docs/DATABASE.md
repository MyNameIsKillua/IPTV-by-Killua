# Database and local data

## Storage overview

On **Android**, Killua IPTV uses these local stores. The Windows/macOS client stores nothing in a
database at all; its four files are described under *The desktop client's files* below.

| Store | Contents | Secret? | Backup behavior |
| --- | --- | --- | --- |
| Room (`killuas-iptv.db`) | account metadata, live categories/channels, recent channel timestamps, Movie categories/listings/details, Movie favorites, Series categories/listings/details/episodes, watch progress | No password/server credential record | App backup disabled |
| Preferences DataStore in `noBackupFilesDir` | theme, PiP setting, encrypted credential envelope | Ciphertext + IV; key remains in Android Keystore | Excluded from backup location |
| Coil memory and `cacheDir/channel_artwork` caches | Channel logos/artwork returned by the provider | No credentials; provider artwork may reveal channel identity | Cache directory is not backed up |

The application manifest sets `android:allowBackup="false"`, and explicit backup/data-extraction rules exclude the application root from cloud backup and device-to-device transfer. There is no cloud database or sync service.

Room is not wrapped in an additional database-encryption layer. It intentionally contains no credential plaintext, but cached metadata and viewing recency still rely on Android's per-app sandbox and device encryption for confidentiality.

Artwork caching is explicitly bounded to 32 MiB of process memory and 128 MiB on disk. These are maximums rather than preallocated space, and normal cache eviction prevents unbounded growth; Android can also reclaim files in `cacheDir` under storage pressure. **Clear artwork cache** in Settings clears both artwork caches without removing the account, credentials, or Room library. The Media3 streaming pipeline has no configured download/disk cache, so live video is buffered transiently rather than accumulated as video files on the device.

## Room schema version 1

### `accounts`

Primary key: `accountId` (locally generated UUID).

Stores provider-derived non-secret state:

- status;
- expiry epoch seconds;
- active and maximum connections;
- server timezone;
- advertised output formats;
- last successful validation time;
- last successful live sync time.

Username and server URL are resolved from the encrypted credential record when mapping the current account to the domain model; they are not columns in this table.

### `live_categories`

Composite primary key: (`accountId`, `remoteCategoryId`).

Stores category name, provider order, and sync generation. The composite key prevents category ID collisions between future accounts. An index on account/order supports category browsing.

### `live_channels`

Composite primary key: (`accountId`, `remoteStreamId`).

Stores category ID, display name, validated logo URL, EPG channel ID, supported container extension, provider order, and sync generation. Indices support account/category filters, provider order, the normalized `sortName` from schema 3, and the heuristic `languageTag` from schema 4.

Unlike a movie title, `sortName` **keeps** a leading country tag, because `DE | RTL` is how the channel is labelled on screen. From schema 7 the bar itself folds to a space, so the key reads `de rtl` and both spellings find it.

The parsed provider `direct_source` field is not persisted in schema version 1.

### `recent_channels`

Composite primary key: (`accountId`, `remoteStreamId`).

Stores the last-watched timestamp after a channel has remained in confirmed playback for two seconds. Recent rows are joined to current live channels, so a missing/stale channel does not appear in results. An account/timestamp index supports reverse-chronological queries.

The schema currently uses logical composite IDs rather than foreign-key constraints. Repositories own deletion order and account scoping.

## Live refresh transaction

A normal refresh never clears user data before contacting the server:

1. Categories and channels are fetched concurrently.
2. A monotonically increasing sync generation is assigned.
3. Category and channel rows are upserted in chunks of 500 inside one Room transaction.
4. Rows for the account with older generations are deleted.
5. The account's last-live-sync timestamp is updated.
6. Room invalidates its flows and paging sources after the transaction commits.

If download, parsing, or a database write fails, the transaction does not expose a half-refreshed library. Existing cached metadata remains available after network failures.

Provider refresh does not deliberately clear recent-channel history. When a provider removes a channel, an orphan recent row may remain internally, but joins prevent it from displaying.

Refresh, history writes, and every cleanup path pass through the application-wide `AccountDataCoordinator` described in [ARCHITECTURE.md](ARCHITECTURE.md). It holds the single account-data lock, rechecks that the writing account still owns the credential-vault record, and performs all account deletion itself. Logout therefore waits for any in-flight account-data transaction, clears all recent/channel/category/account rows in one transaction, and then clears the credential envelope. A refresh or history write that was already downloading is rejected at its commit point instead of repopulating Room afterward.

Because downloads happen outside that lock, a slow provider cannot delay logout. Each new content area registers an `AccountDataCleaner` with the coordinator rather than holding a private lock, so Movie tables will be covered by the same guarantee when they are added.

## Queries and large-library behavior

The UI does not load every cached channel into a Compose collection. `LiveRepository.channels()` creates a Paging 3 flow with:

- page size: 60;
- initial load size: 90;
- prefetch distance: 20;
- placeholders disabled.

`MovieRepository.movies()` uses the same configuration. Both take a filter object rather than a bare selection, and the statement behind it is assembled by `LiveQueryFactory` or `MovieQueryFactory` on the shared `PagedQueryBuilder` and executed through `@RawQuery`. One paging source therefore covers all channels, a provider category, uncategorized channels, and recent channels, in any of the offered sort orders, with or without a search term. Categories and a small recent-channel row remain ordinary Room flows.

Paging repeats its ordered statement for every page, so an unindexed `ORDER BY` would re-sort the whole filtered set on each scroll step. That is why both listing tables carry an indexed `sortName` instead of ordering on the raw display name: an index on the raw name cannot serve `COLLATE NOCASE` ordering, and ordering on it without `NOCASE` would sort every capital letter before every lowercase one.

A title search is a `LIKE '%term%'` contains match, which scans the account's rows rather than seeking the index. That is deliberate: providers prefix channels with country and quality tags, so a prefix match would fail to find `DE | RTL HD` from `RTL`. LIKE wildcards in the term are escaped so `100%` cannot match everything.

Both the stored key and the typed term are folded through `SearchTextNormalizer`, so punctuation the viewer did or did not type makes no difference; see [Search terms](#search-terms).

The provider API returns the full library in one response and offers no server-side pagination, so refresh streams it straight into the database in batches; see [ARCHITECTURE.md](ARCHITECTURE.md). Database paging improves browsing but cannot make the remote payload paginated.

## Credentials and preferences

Credential plaintext is serialized only as an in-memory binary record immediately before AES-GCM encryption. DataStore contains:

- credential-record version;
- account ID used both as metadata and authenticated additional data (AAD);
- random GCM IV;
- Base64-encoded ciphertext.

The AES-256 key is generated under alias `killuas_iptv_credentials_v1` in Android Keystore and is not stored in Room or DataStore. If the envelope is malformed, has a wrong version/account binding, or cannot be decrypted, it is cleared and the app requires sign-in again.

Non-secret preferences currently include theme mode (Dark by default), Picture-in-Picture enabled state (`true` by default), double-tap seek interval (10 seconds by default), and temporary hold speed (2x by default).

See [SECURITY.md](SECURITY.md) for limitations and network exposure.

## Account model

Database keys are already account-scoped, preserving a schema path to multiple accounts. The current vault/session retain exactly one active account: startup and successful login delete rows for every other account, and login creates a new local account ID. Full multi-account switching will need:

- one encrypted credential envelope per account;
- an active-account preference;
- account-management UI;
- explicit retention/removal semantics per account.

## Room schema version 2

Version 2 adds the account-scoped Movie tables and a generic watch-progress table. It is purely additive: `MIGRATION_1_2` creates tables and touches no existing row, so an installed production app keeps its account, live library, and recent channels.

### `movie_categories`

Composite primary key: (`accountId`, `remoteCategoryId`). Stores name, provider order, sync generation, and a heuristic `languageTag`. Because Xtream exposes a genre only through per-title `get_vod_info`, provider categories are what the UI filters on as genres.

### `movies`

Composite primary key: (`accountId`, `remoteStreamId`). Stores category, name, poster, container extension, rating, release year, added timestamp, provider order, sync generation, a heuristic `languageTag`, and a pre-normalized `sortName`.

`sortName` exists so alphabetical paging is index-backed and stable rather than depending on collation at query time. It removes a recognized leading language tag, so `DE | Avatar` sorts under A, and then normalizes what is left: lowercase, punctuation folded, whitespace collapsed. Indices cover category, provider order, sort name, rating, release year, added timestamp, and language, which is what makes every offered sort and filter combination cheap.

### `movie_details`

Composite primary key: (`accountId`, `remoteStreamId`). Lazily fetched plot, genre, cast, director, backdrop, duration, and fetch time. Deliberately has **no** sync generation, so a listing refresh can never replace rich metadata with nulls.

### `movie_favorites`

Composite primary key: (`accountId`, `remoteStreamId`). Also has no sync generation: a favorite survives a provider temporarily dropping the title.

### `series_favorites`

Added in schema 6 and identical in shape to `movie_favorites`, down to the missing sync generation and for the same reason.

### `watch_progress`

Composite primary key: (`accountId`, `contentType`, `contentId`). Stores position, duration, completion, and update time. `contentType` is `movie` or `episode`; both were served without a further migration, which is what the column was there for. Identity never derives from display text.

The two content types must stay separated by `contentType` in every query: providers number movies and episodes independently, so the same `contentId` routinely exists as both. Continue Watching joins against `movies` and the per-series episode query joins against `series_episodes`, so a removed title disappears from its row without its progress being deleted.

A row can also be written without playback: **mark as watched** sets `completed` directly, keeping an existing row's duration and moving the position to the end, or storing zeroes when there is nothing to build on. **Mark as unwatched** deletes the row outright — clearing only the flag would leave a resume point behind, and a title the viewer says they have not seen must start from the beginning. See [PLAYER.md](PLAYER.md).

Both `MovieDao` and `SeriesDao` declare queries against this table. A DAO is a query holder, not an owner; `DefaultMovieRepository`'s cleaner clears the table for **every** content type on logout and account replacement, which is why `DefaultSeriesRepository` does not clear it again. The player writes these rows through one application-scoped writer that rechecks the title and account first; see [PLAYER.md](PLAYER.md).

## Movie refresh transaction

Identical in shape to the live refresh: categories and movies are fetched concurrently outside the shared account lock, assigned one sync generation, upserted in chunks of 500 inside a single transaction, and stale provider rows are then removed. Details, favorites, and progress are never touched by that pass. A download, parse, or database failure leaves the previous cache intact.

## Search terms

`LikeSearchTerm` folds and escapes every user-typed term exactly once, for both the paged browsing statements and the global search queries. An unescaped `%` would turn a search into "match every row", which on a six-figure table means a full scan returning the whole cache.

The folding itself is `SearchTextNormalizer`, and it is the same rule that wrote every `sortName`. Both sides have to agree exactly, which is what the shared object guarantees: a stored key of `mr. robot` cannot be reached by `LIKE '%mr robot%'`, and nobody typing a title reproduces the provider's punctuation.

Two rules, and the difference between them is deliberate:

- an apostrophe is **removed**, because it never stands between two words. `Marvel's` and `Marvels` are one key;
- every other non-alphanumeric character becomes a **space**, because something readable usually stands on each side of it. Folding `Spider-Man` to `spiderman` would stop `spider man` from finding it, trading one miss for another.

The acknowledged cost is acronyms: `S.W.A.T.` normalizes to `s w a t`, so typing `swat` still finds nothing. That was already true before the rule existed, so nothing regressed. Ordering shifts slightly as well — `The A-Team` files under `the a team`, and a title beginning with a bracket now sorts under its first letter instead of after Z.

A term that normalizes to nothing is refused rather than bound as `%%`. Length checks therefore run on the normalized term, not the raw input: `...` is three keystrokes and nothing to match on.

Global search additionally refuses a term below two characters. Browsing inside one library deliberately does not: there the term narrows a list already on screen, and one character is a reasonable thing to type.

The search statements are covered by `SearchQueryTest` in `androidTest`, against real SQLite. That is not optional thoroughness: the DAO fakes match in memory and never parse SQL, so a malformed `ESCAPE` clause once compiled, passed every unit test, and failed only on a device.

## Room schema version 3

Version 3 adds one column: `live_channels.sortName`, a pre-normalized copy of the channel name that alphabetical paging and title search run against. It mirrors `movies.sortName` with one deliberate difference — a leading language or country tag is **kept**, because `DE | RTL` is how the channel is labelled on screen and an A-Z list should read the way the list looks. Movies strip theirs so `DE | Avatar` sorts under A.

`MIGRATION_2_3` adds the column, backfills existing rows with SQLite's `lower(name)`, drops the unused index on the raw name, and creates one on (`accountId`, `sortName`). The table therefore keeps the same number of indices.

The backfill uses the SQLite built-in, which folds ASCII only, while new rows use Kotlin's `lowercase()`. A cached channel whose name starts with an uppercase non-ASCII letter keeps sorting by its raw form until the next refresh rewrites the column. This is a cosmetic ordering difference in an upgraded cache, chosen over leaving the column empty until the user downloads tens of megabytes again.

## Room schema version 4

Version 4 adds one column: `live_channels.languageTag`, the heuristic language a channel is filtered by, with an index on (`accountId`, `languageTag`). Refresh writes it the same way Movies do — the provider category decides, and a tag on the channel's own name is only the fallback.

`live_categories` deliberately gets **no** matching column. `movie_categories.languageTag` exists but is never read: the refresh recomputes the language from the category name it just downloaded, so a second stored copy would only be another thing to keep consistent.

`MIGRATION_3_4` adds the column and index, then backfills. The backfill runs the app's own Kotlin heuristic inside the migration, but only over `live_categories` — one `UPDATE` per category carrying a recognized tag, rather than a pass over six figures of channel rows. A channel whose category says nothing while its own name carries a tag therefore stays unlabelled until the next refresh applies the full rule. That is a deliberate trade: a migration that runs in bounded time on a large library beats one that is exhaustive on the first launch after an update.

## Room schema version 5

Version 5 adds the account-scoped Series tables. It is purely additive: new tables only, no existing row touched, so an installed app keeps its account, both libraries, history, favorites, and watch positions.

### `series_categories`

Composite primary key: (`accountId`, `remoteCategoryId`). Name, provider order, and sync generation, like its Movie counterpart. It carries no language column, for the same reason `movie_categories.languageTag` turned out to be dead weight: the refresh recomputes the language from the category name it just downloaded.

### `series`

Composite primary key: (`accountId`, `remoteSeriesId`). Category, name, `sortName`, poster, rating, release year, provider last-modified stamp, heuristic `languageTag`, provider order, and sync generation. Indexed the same way `movies` is, so every offered sort and filter combination is cheap. `sortName` strips a recognized leading language tag as a Movie title does, unlike a channel name.

The provider last-modified stamp is what "recently updated" sorts on; the listing carries no episode counts, so it is the closest thing to "a new episode arrived".

### `series_details`

Composite primary key: (`accountId`, `remoteSeriesId`). Lazily fetched plot, genre, cast, director, backdrop, and fetch time. No sync generation, so a listing refresh can never replace it with nulls.

### `series_episodes`

Composite primary key: (`accountId`, `remoteEpisodeId`) - the provider's own episode ID, which is also what `watch_progress` keys on. Season and episode numbers are stored for display and ordering only; identity never derives from them, because providers repeat and renumber them.

Episodes arrive with the details fetch and carry no sync generation either. A details fetch **replaces a series' episodes as a set** inside one transaction, which is what removes episodes the provider dropped without a generation marker.

Episodes deliberately reuse `watch_progress` through its `contentType` column rather than getting a table of their own, which is why schema 5 changes nothing about progress.

## Room schema version 6

Version 6 adds `series_favorites` and nothing else. One new table, shaped exactly like `movie_favorites` down to the missing sync generation, so a favorite survives the provider temporarily dropping the series.

## Room schema version 7

Version 7 changes no table, column, or index. It exists purely to carry a **data rewrite**: every cached `sortName` was written before `SearchTextNormalizer` existed and still holds the provider's punctuation, so a cached `mr. robot` could not be found by `LIKE '%mr robot%'`.

`MIGRATION_6_7` walks `live_channels`, `movies`, and `series` and rewrites the column in place. Changing only the write path would have left every already-cached row wrong until the next full refresh, which on the reference provider is minutes of download for something the device can fix locally.

It runs the app's own `SearchTextNormalizer` rather than SQL, so an upgraded cache ends up with exactly the keys a refresh would write — no approximation, unlike `MIGRATION_2_3`'s `lower()`. `MIGRATION_3_4` already set the precedent of running Kotlin inside a migration. Rows are read a page of 2,000 rowids at a time and each page's cursor is closed before its rows are updated, so nothing is materialized whole and no cursor walks an index that is being rewritten underneath it. Only rows whose key actually changes are written.

**It was first written as one nested `replace()` expression per table, and SQLite rejected it on a device with `parser stack overflow`.** The parser's stack is far smaller than the expression-depth limit — around forty nested calls is already past it — and no JVM test could see this, because the DAO fakes never parse SQL. Chunking the statements would have meant rewriting every row several times over; the Kotlin pass writes each row at most once.

This is still the most expensive migration in the project: roughly 290,000 rows read and most of them rewritten, each maintaining its `sortName` index. It is a one-off cost on the first launch after the update and far cheaper than the refresh it saves.

## Room schema version 8

Version 8 adds one additive table, `watchlist`, with the composite key `(accountId, contentType, contentId)` and an index on `(accountId, addedAtEpochMillis)`. `contentType` is `movie`, `series`, or `channel`, so the three libraries share one table rather than three parallel ones — the list is read as a single ordering across all of them, and three tables would mean merging and re-sorting in Kotlin to answer one question.

`MIGRATION_7_8` creates the table and its index and touches nothing else. It shipped in `v0.2.0-alpha.22`, one release ahead of the screens that use it: an empty additive table costs the first launch nothing, which keeps the schema step separate from the feature built on top of it.

Both reads are covered by `SearchQueryTest` on a device: the union's cross-library ordering, its account scoping and limit, and that `observeSavedIds` reports a saved id whose library row is missing.

Reads take two shapes. `observeSavedIds` returns the saved ids of one kind, for the paged channel list, which cannot afford a query per visible row. The list itself is a `UNION ALL` of three `INNER JOIN`s onto `movies`, `series`, and `live_channels`, so a title the provider has temporarily dropped stops appearing **without its saved row being deleted** — it returns when the provider does. That is deliberate: a saved row is user data that the provider cannot re-derive.

## Room schema version 9

Version 9 adds one nullable column, `accounts.displayName`: what the viewer calls this playlist,
entered at sign-in and shown on the home screen.

`MIGRATION_8_9` is a single `ALTER TABLE ... ADD COLUMN` and nothing else. Null is the honest value
for every account that already exists — the provider has no such field, so there is nothing to
backfill it from, and the sign-in user name stands in until the viewer says otherwise.

Reconnect deliberately carries the existing name forward rather than letting the provider's response
overwrite it, because the provider never sends one. The name can also be changed later from Settings,
which writes the column directly and re-publishes the cached account.

## Room schema version 10

Version 10 adds three nullable columns to `live_channels`, and they exist for a library Xtream did
not send: `directSource`, `streamUserAgent` and `streamReferrer`.

An M3U playlist has no stream ids to build an address from and no account to put in one — the
address *is* the entry. So a playlist channel keeps its own address in `directSource`, and the two
headers beside it are what its server insists on: roughly one channel in sixteen of a real public
playlist answers 403 without a user agent or a referrer.

**An Xtream channel leaves all three null, and that is what tells the two apart.** There the address
is built at playback time by `XtreamStreamUrlFactory` out of the credentials, and `XtreamJsonParser`
deliberately never reads `direct_source`; storing one would put the account in a second place.
Because of that, no separate "kind" column was added — the presence of `directSource` is the whole
distinction, and a column that merely restates it could disagree with it.

`MIGRATION_9_10` is three `ALTER TABLE ... ADD COLUMN` statements and nothing else. Nothing is
backfilled, because null is already the right answer for every row that exists, and nothing is
rewritten — which matters at the size this database actually reaches: ~156MB with 60,000 channels on
the reference account, now also on a television stick with 922MB of RAM in total.

The schema moved before anything on Android writes to it, the same order Gate 3 used. Adding a
column later would be a second migration over those same rows for nothing.

## Migrations

Room schema export is enabled and KSP writes schemas under `app/schemas` during builds. Version 1 is the initial schema, version 2 adds Movies, version 3 adds the live sort key, version 4 adds the live language, version 5 adds Series, version 6 adds Series favorites, version 7 rewrites the sort keys without touching the schema, version 8 adds the saved list, version 9 names the account, and version 10 gives a channel room for its own address. All ten exported schemas are retained in source control. There is no destructive-migration fallback.

`MIGRATION_1_2` through `MIGRATION_9_10` are registered in the database builder and covered by `app/src/androidTest/.../MigrationTest.kt`. It seeds a version 1 database with account, category, channel, and recent-channel rows and asserts they survive; seeds a version 2 database and asserts that `sortName` is backfilled while recent channels, favorites, and watch progress are untouched; seeds a version 3 database and asserts that a channel inherits its category's language while one under an untagged category stays null; seeds a version 4 database and asserts the Series tables appear empty and writable while live, favorite, and progress rows are untouched, including an episode progress row alongside a movie one; seeds a version 5 database and asserts `series_favorites` accepts a row while nothing existing moves; seeds a version 6 database and asserts each listing table's `sortName` is folded while the displayed `name` is left alone; seeds a version 7 database and asserts the `watchlist` table accepts a row while nothing existing moves; seeds a version 8 database and asserts the new name column starts null while everything the account already held survives; seeds a version 9 database and asserts an existing channel keeps its name, sort key, language and order with all three new columns null, then that a playlist channel with an address and headers can be written beside it; and runs chained 1→4, 1→5, 1→6, 1→7, 1→8, and 1→9 upgrades, which is the path an installation that skipped a release actually takes.

**Verification status:** executed and passing. Re-run on **24 August 2026** after version 10: all **22** instrumented cases ran on the headless API 36 x86_64 emulator (`killua-migration-test`) via `connectedDebugAndroidTest`, with 0 failures, errors, or skips — including `migrate9To10_addsThePlaylistColumnsAndLeavesEveryChannelAlone`. The previous run, on 15 August 2026, covered 15 cases up to version 9. `runMigrationsAndValidate` compares the migrated database against the exported schema, so a mismatched column, type, or index name would have failed the run.

That run is also what caught `MIGRATION_6_7`'s first implementation: 397 JVM tests passed while SQLite rejected the statement outright on the device. Anything about a *statement* has to be proven here.

Re-run it after every schema change:

```powershell
.\gradlew.bat connectedDebugAndroidTest --no-daemon --console=plain
```

An emulator is enough; no physical device is needed. Create one once with `sdkmanager "emulator" "system-images;android-36;default;x86_64"` and `avdmanager create avd -n killua-migration-test -k "system-images;android-36;default;x86_64"`, then boot it headless with `emulator -avd killua-migration-test -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect`.

What a faulty migration actually costs, stated precisely: because destructive fallback is disabled, Room refuses to open a database whose schema does not match the exported one and throws on startup. The app crashes visibly instead of silently deleting anything, and the rows stay on disk, so a corrected migration in a later build can still upgrade them. Data is only lost if the user clears app storage to make the app launch again.

The cost of skipping this check therefore rises over time rather than being constant. At the 1 to 2 upgrade the database holds only an account record and a re-downloadable live cache, both recoverable by signing in and refreshing. Once Movie favorites and watch positions accumulate, they are not re-derivable from the provider, and running the migration suite before release stops being optional. From schema 3 onward, a failed upgrade also costs the user a multi-minute re-download of a six-figure library.

For every schema change:

1. preserve the prior exported JSON schema in source control;
2. increment the database version;
3. add an explicit Room `Migration` that preserves account-scoped user data;
4. add migration tests using Room's migration-test tooling;
5. verify upgrades from every supported released schema;
6. never solve a production migration by enabling destructive fallback.

During development, uninstalling a debug build is acceptable when no released user data exists, but it is not a migration strategy.

## The desktop client's files

The desktop client has **no database**. Browsing asks the provider for one category at a time, so the
six-figure listing that forced Room, streaming and batching on Android is never requested, and the
question of caching a library is postponed rather than answered badly. What it does keep is four
files in `%LOCALAPPDATA%\KilluaIPTV` (a dot-directory under the home folder elsewhere):

| File | Contents | Scoped to an account? | Disposable? |
| --- | --- | --- | --- |
| `user-data.json` | watch progress, both favourite lists, the saved list, recently watched channels | Yes, by the export format's fingerprint | **No** — this is the viewer's own data |
| `titles.json` | the name, artwork URL and container of every title that has been marked or played | Yes, same fingerprint | Yes |
| `preferences.json` | destination, browsing order, volume, mute, window size, and the last category per section | Only the category map | Yes |
| `artwork/` | posters and channel logos, one file per URL | No — a URL belongs to whoever asks for it | Yes |

**`user-data.json` is an export**, in the format from `:shared` that the phone reads and writes. That
decision buys three things at once: no schema to design or migrate, reuse of a format already built
and tested, and interchange for nothing — this file *is* an export, so it can be handed straight to
the phone's Import, and a file exported from the phone can be dropped in here. The cost is that a
save rewrites the whole file; at a few thousand rows that is a couple of hundred kilobytes, written
at most every ten seconds, which is not worth a database to avoid.

**A save folds in anything it has not seen.** Nothing stops two copies of this application running
at once, and the state file is rewritten whole on every save — so the second window to save would
quietly take the first window's evening with it. Each save compares the file's timestamp against what
this client last saw; if somebody else has written since, it re-reads and merges with the same
newest-wins rule import uses, then writes that. A file belonging to a different account is not
merged, exactly as on import.

The sidecars get **no such protection, and need none**: disposability is the answer to the same
question. Two windows fighting over the title cache costs a caption until that title is browsed
again, and over the preferences costs the category one of them had open. Neither is a loss that
outlives using the client, and the artwork store writes one file per image rather than replacing a
whole document.

It is deliberately a **timestamp** rather than a lock: a lock left behind by a crash is worse than
the problem it solves, and the merge rule is idempotent, so the worst case of a missed conflict is
the behaviour that existed before.

**No credentials are in any of them.** The account is identified by the same one-way SHA-256
fingerprint the export uses, taken over the host and username and never the password, so changing a
password does not orphan a file. A file whose fingerprint does not match is treated as *absent*
rather than as an error: signing in as someone else must neither show the previous account's history
nor quietly adopt it.

**Everything is written through one function**, `writeAtomically`, rather than four copies of the
same four steps. Four copies of a rule is three chances for one to drift, and the one that drifts is
the one nobody notices: a save that silently does nothing looks exactly like an application that
forgot. Unifying them also fixed something all four had — a temporary file left behind when the write
itself failed.

**The rule it holds: written through a temporary file and renamed.** A half-written state file would be
refused on the next launch and take every stored position with it, and a rename is the closest thing
to atomic the filesystem offers. Windows refuses a rename onto an existing file, so each writer falls
back to deleting the target and renaming again — without that, every save after the first is silently
dropped, which is exactly the kind of failure that only shows up as "it forgot where I was".

**The two caches are bounded and account-aware where the provider's ids are involved.** Names are
scoped by fingerprint because providers number each library from one, so film 501 is a different film
on a different account. Artwork is not: a file is named by the SHA-256 of its URL — partly because a
URL is not a filename, and partly because provider artwork is sometimes served from the provider's
own host and a directory listing full of readable URLs would put that host on disk in plain text. The
directory is capped at 200MB and pruned least-recently-*read* first, with the clock injected so that
ordering is testable rather than a race.

### When the state file cannot be read

Writes go through a temporary file and a rename, so a crash cannot leave half a file behind. That
makes damage unlikely rather than impossible, and a half-synced copy or a file edited by hand arrives
by another road entirely.

An unreadable file used to be handled exactly like an absent one: the load returned an empty
document, and the first mark afterwards wrote it back over the damaged file. Everything stored went
with it, silently, and the evidence went in the same motion.

It is **moved aside** now — renamed to `user-data.unreadable-<millis>.json` — before the empty
document is returned, and a banner names where it went. A rename rather than a copy, because two
files claiming to be the state is its own confusion; if the rename itself fails, nothing is moved and
nothing is claimed.

Two neighbouring cases are deliberately different. A file written by a **newer build** counts as
unreadable, for the same reason import refuses it: it is data from the future, and replacing it with
an empty document is the worst available answer. A file belonging to a **different account** does
not: it is perfectly readable and simply not ours, so it is left exactly where it is.

**Nothing removes a kept file but the viewer.** Each one is the only remaining copy of a history this
client could not read, and an application that tidies away the thing it could not read has completed
the loss it was trying to prevent — so there is no cleanup, no age limit, and deliberately no delete
button beside them. They cannot pile up on their own either: one is written only when a load fails,
and the next save leaves a good file behind, so a second requires fresh damage.

What they *can* do is become unexplainable, which is the failure mode the atomic writer's own comment
warns about for stray `.tmp` files. A name said once in a banner that can be dismissed is a name lost.
So **Settings lists them** under *Your data*, with what to do: close the client, delete
`user-data.json`, rename the kept file back.

### Why the recent-channel list is not capped

It looks like an oversight, so it is written down. The list holds *distinct channels* rather than
viewings — re-watching one moves it rather than adding a row — so it grows only when somewhere new is
watched, and a row is some forty bytes. Thousands of them are a file measured in tens of kilobytes.

A cap would also fight the merge rule. Nothing is ever deleted in a merge, so trimming on write would
simply be undone by the next import from a device that kept more, and the two would take turns
rewriting each other. What is bounded instead is the **display** — forty, by `ownChannels` — and a
viewer can drop one on purpose from the guide. The phone works the same way: its table is unbounded
and the limit lives in its queries.

Because none of it is ever pruned, the desktop's settings screen breaks the stored count down by
kind. One number answers whether anything is stored; five answer which of them is growing.

### Why none of this needs migrations

The Room discipline above exists because a schema mismatch costs a viewer their data. Nothing here
can:

- `user-data.json` is the shared format, which is **versioned**: it ignores unknown keys so an older
  build can read a newer file, and it **refuses** a file from a newer format version outright rather
  than half-reading it. Its eleven tests are in `:shared`, and eleven more in `:desktop` cover this
  client's own reading and writing of it.
- The other three are disposable by construction. A damaged or unreadable one reads as empty, and the
  cost is a caption, a window size, or a few seconds of re-fetching. Twenty-two tests in `:desktop`
  pin that — twelve over the two sidecars and ten over the artwork store — including that a foreign
  account's file reads as empty, that saving twice replaces rather than fails, and that pruning drops
  the least recently read rather than the oldest downloaded.

The rule that replaces "add a migration" here is therefore: **anything that would need one belongs in
the export format**, where the versioning already exists, and everything else must be safe to delete.

## Planned entities

Seasons and EPG do not yet have production tables; `watchlist` does. Series and episodes do, and episodes reuse `watch_progress` through its `contentType` column rather than a parallel table. When further entities are added, their keys must include `accountId` and stable provider IDs, and identity must not depend on display text.

Refresh logic must never delete favorites, watchlist entries, or progress simply because provider metadata is temporarily unavailable.
