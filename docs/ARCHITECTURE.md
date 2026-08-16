# Architecture

## Scope

The current application is a single Android `app` module with layered packages. It is intentionally small enough to navigate without framework-heavy indirection, while keeping Xtream behavior, local persistence, playback, and Compose UI independent.

The architectural boundary is the configured provider: Killua IPTV has no project-owned backend. Network traffic is limited to the configured Xtream server and media/artwork URLs supplied by that server.

## Dependency direction

```text
Compose UI / ViewModels
        |
        v
Domain repository contracts and models
        |
        v
Data repository implementations
     /       \
    v         v
Xtream API   Room / credential vault

Compose player screen
        |
        v
PlaybackCoordinator -> PlayerConnection -> MediaSessionService -> ExoPlayer
```

UI code consumes repository interfaces and immutable domain objects. Data implementations know about Room and the Xtream adapter. Xtream-specific endpoint and response handling stays under `data/xtream`; it does not leak into Composables.

## Composition root

`IptvApplication` creates one `AppContainer`. The container owns:

- an application coroutine scope (`SupervisorJob + Dispatchers.Default`);
- a no-backup Preferences DataStore;
- the Android Keystore-backed credential vault;
- the Room database;
- separate OkHttp clients for API calls and playback;
- session, live, and Movie repository implementations;
- the Media3 controller connection, playback coordinator, PiP presentation state, and the application-scoped watch-progress writer.

The live repository additionally keeps a small in-memory guide cache, keyed by account and channel and cleared with the account. The guide is deliberately not a Room table: it is stale within the hour, only ever read for the channel on screen, and giving it a table would mean a schema change plus an expiry sweep for data whose whole value is being current.

Dependencies are assembled manually. Repository contracts make later migration to Hilt possible without changing feature logic, but Hilt is not currently used.

## Main layers

### UI and features

Feature packages hold Composables, UI-state models, and ViewModels. State is exposed through `StateFlow` and collected with lifecycle awareness. The root Navigation Compose graph currently contains Home, Live, Movies, Series, Search, Settings, and Player routes, plus details routes for a Movie and a Series. No route shows a placeholder any more. `MainActivity` is responsible for edge-to-edge setup, the root app Composable, and Picture-in-Picture integration; the player Composable applies and restores immersive system-bar state with the route lifecycle.

Live, Movies, and Series share a browsing shape: a committed filter object drives paging, and a search field debounces for 300 ms before it touches that filter, so typing never rebuilds the paging source per character. All three keep the raw field text in UI state so the field stays responsive while the list lags behind it deliberately. Movies and Series also share their poster tile, grid, skeletons, and Continue Watching row, so a provider that omits artwork degrades identically in both.

The Continue Watching row takes `ContinueWatchingEntry`, which carries the content kind and when the title was last watched. That is what lets Home hold one row containing both libraries: two separately ordered lists cannot be interleaved honestly, and trimming before sorting would drop the newest entry.

The authenticated app supports Dark, Light, and System modes, with Dark as the DataStore default. Startup and sign-in deliberately use the complete dark Material palette so a previously saved Light/System preference cannot produce dark controls on the cinematic dark login surface.

### Domain

The domain layer defines:

- account, live category, live channel, session, and failure models;
- provider-neutral `MovieCategory`, `MovieSummary`, and `MovieDetails` models, cached in Room, shown by the Movies screens, and playable;
- provider-neutral `SeriesCategory`, `SeriesSummary`, `SeriesDetails`, and `SeriesEpisode` models, cached in Room, browsable, and playable;
- `SessionRepository`, `LiveRepository`, and `MovieRepository` contracts;
- category selections (`All`, `Recent`, `Uncategorized`, provider category), wrapped together with a search term and sort order in `LiveFilter` and `MovieFilter`;
- `WatchProgressPolicy`, which defines the VOD completion rule and is applied by `DefaultMovieRepository.saveProgress` on every checkpoint the player writes.

Models are provider-neutral where practical. Xtream credentials are an exception at the data boundary and redact their `toString()` representation.

### Data and Xtream adapter

`DefaultSessionRepository` coordinates URL normalization, remote authentication, encrypted credential storage, cached account metadata, startup restoration, reconnect, and logout. Login UI may collect server/username/password separately or parse them locally from a recognized credential-bearing Xtream `get.php`/`player_api.php` URL; both paths converge on the same repository methods and vault.

`DefaultLiveRepository` coordinates account-scoped Room queries and live-library refresh. Remote categories and channels are fetched concurrently, then committed in one Room transaction. A sync-generation marker removes provider records that disappeared without clearing recent-channel data during a normal refresh.

### Account-data coordination

`AccountDataCoordinator` owns the single lock that serializes every account-scoped mutation in the application, and it is the one place that deletes local account data. Session, Live, and future Movie code all pass through it rather than holding private locks.

- Content areas register an `AccountDataCleaner` instead of exposing cleanup on their own repository contract. Both the live and Movie repositories implement it, without Session code knowing either exists. Cleaners run inside the coordinator's lock and transaction, so they must never take the lock or open a transaction themselves.
- `commit`/`commitTransaction` recheck that the calling account still owns the credential-vault record before running any write, and reject a stale write with `AuthenticationFailed`.
- Ownership is read straight from `CredentialVault`, not through `SessionRepository`. Lock ordering is therefore always session mutex → coordinator lock, and nothing reached under the coordinator lock takes the session mutex, so the two cannot deadlock.
- Category downloads run outside the lock. The **listing** download does not: a provider with six-figure title counts cannot be held in memory, so it is streamed from the network directly into the database in batches, which requires the transaction — and therefore the lock — to be open for the duration. A logout issued during a refresh waits for it. That is the deliberate direction: not crashing beats a faster logout. Ownership is still rechecked before the first write, so a refresh whose account disappeared commits nothing.
- `TransactionRunner` abstracts the Room transaction boundary so these ordering and rejection rules are covered by deterministic JVM tests rather than only by instrumented ones.

Why this replaced the previous arrangement: the lock used to be a private field of `DefaultLiveRepository`, and `DefaultSessionRepository` reached it only by delegating cleanup through `LiveRepository`. A second content area with its own lock would have been able to commit a late refresh or progress write after logout had already cleared the account.

`XtreamRemoteDataSource`, `XtreamApi`, `XtreamJsonParser`, and `XtreamStreamUrlFactory` isolate protocol behavior. See [XTREAM_API.md](XTREAM_API.md).

### Persistence

Room stores non-secret account metadata, live categories, live channels, recent live-channel timestamps, Movie categories, Movie listings and lazily fetched details, Movie favorites, Series categories, Series listings with lazily fetched details and episodes, and generic watch progress. Preferences DataStore stores appearance, PiP, and player-gesture settings plus the encrypted credential envelope. The AES key itself lives in Android Keystore. A separate bounded Coil cache holds channel and poster artwork; Media3 streaming video is not written to that disk cache.

### Browsing query assembly

Global search is the exception to the paging shape: it runs three bounded suspend queries concurrently rather than three pagers, because a result screen showing twenty hits per library does not need a paging source per library, and one slow table would otherwise gate the other two. Each query reads one row beyond its limit so the section can offer **Show more** without a second counting scan over a six-figure table.

Every filter and sort combination would otherwise need its own declared Room `@Query`. Instead `PagedQueryBuilder` assembles the statement and `MovieDao`/`LiveDao`/`SeriesDao` execute it through `@RawQuery`. `MovieQueryFactory`, `LiveQueryFactory`, and `SeriesQueryFactory` supply only the fixed fragments their domain needs; every caller value — account, category, language, search term — is bound as an argument.

The builder keeps join arguments in a separate list from condition arguments and concatenates them in statement order. SQLite binds `?` positionally and each join placeholder precedes the `WHERE` clause, so a single call-ordered list silently pairs the wrong value with the wrong placeholder. It also checks that the finished statement has exactly one argument per placeholder.

All three listing tables carry a pre-normalized `sortName`, so alphabetical ordering and search are index-backed rather than dependent on collation at query time. Movies and Series strip a recognized leading language tag from it; channels deliberately keep theirs, because the tag is part of how the channel is labelled on screen.

The normalization itself is `SearchTextNormalizer` in `core/text`, and it deliberately sits outside both `core/database` and `data/xtream`: the stored keys are written by the Xtream mapping layer while the typed terms are folded by the query layer, and the two must agree exactly or a search finds nothing. It lowercases, drops apostrophes, turns every other non-alphanumeric character into a space, and collapses whitespace. See [DATABASE.md](DATABASE.md) for what that costs and why each half of the rule is the way it is.

Both also carry an indexed `languageTag`, written at refresh time by `XtreamLanguageTagger`: the provider category decides, and a tag on the item's own name is only a fallback. The Xtream API has no language field at all, so this is a documented heuristic over provider naming conventions and never authoritative.

See [DATABASE.md](DATABASE.md) and [SECURITY.md](SECURITY.md).

### Playback

ExoPlayer is owned by `PlaybackService`, a `MediaSessionService`, rather than by a Composable. `PlayerConnection` exposes controller state as flows and applies guarded seeks or temporary speed changes. `PlaybackCoordinator` retrieves credentials just in time, builds a live `MediaItem`, and tells the service to prepare/play it. The player screen attaches a gesture-aware video surface to the controller while retaining Media3's stock controls and provider-exposed audio/subtitle track selection.

`GestureAwarePlayerView` owns every gesture that starts on the video surface, including the vertical brightness/volume drag, so there is one touch owner rather than competing detectors. Screen brightness and volume are applied through `PlayerLevelControls`, which touches only the Activity window and `AudioManager` and never a system setting; the pure arithmetic behind the drag lives in `PlayerLevelGesture.kt` where JVM tests can reach it.

This split prevents normal Compose recomposition, navigation, or configuration changes from creating a second ExoPlayer. Live, Movies, and episodes share it through a typed `PlaybackRequest`; the ViewModel talks to `PlaybackCommands` and `PlaybackStateSource` rather than the Media3 classes, which is what makes resume and checkpoint behaviour testable on the JVM. Positions are written by one `WatchProgressWriter` for every resumable type, selected by an exhaustive `when` over `PlaybackMediaId.Resumable`, so the ownership and duplicate rules exist once; a live channel is not a `Resumable` and is therefore excluded at compile time. See [PLAYER.md](PLAYER.md).

## Important flows

### Startup and session restoration

```text
Application starts
  -> SessionRepository.start()
  -> decrypt saved credential envelope
  -> remove orphaned database rows that do not match the one saved account
  -> publish cached Room account immediately, if present
  -> validate against player_api.php
       -> success: update Room and authenticated state
       -> temporary network/server failure: keep cached account with warning
       -> auth/expiry/incompatible response: dedicated saved-account recovery screen
```

Missing or undecryptable credentials clear orphaned account/library/history rows and return to signed-out state. An unusable credential envelope is not silently replaced with plaintext storage. A transition from authenticated state to any non-authenticated state centrally stops and clears playback before showing login/recovery UI.

### Connection test and login

Connection testing uses temporary in-memory credentials and performs authentication without saving them. The M3U URL mode parses a recognized `get.php` or `player_api.php` link into an ephemeral login attempt; it neither populates the separate credential fields nor downloads/imports a playlist. The masked original link remains available for test/retry and is cleared after successful login. Connecting performs a new authentication, creates an account ID, writes non-secret account metadata to Room, then encrypts and stores the normalized server and parsed credentials. If secure storage fails, the newly inserted account row is rolled back. Saving the new vault record is the login commit point; older-account cleanup is attempted afterward and is repeated on startup/logout if that recoverable cleanup fails.

When saved credentials cannot currently establish an authenticated session for a non-temporary reason, a dedicated recovery screen displays the safe failure reason and cached account identity when available. **Reconnect** retries the saved credentials; **Use another account** logs out and clears local account data before returning to the blank login form.

The current vault holds one active credential record, even though Room keys library records by account ID.

### Live-library refresh

```text
authenticated account
  -> fetch get_live_categories + get_live_streams concurrently
  -> lenient parsing and de-duplication          (outside the account lock)
  -> AccountDataCoordinator: acquire lock, recheck vault ownership
  -> one Room transaction
       -> upsert rows in batches of 500
       -> delete rows from older sync generations
       -> update last-sync timestamp
  -> Room Flow/PagingSource invalidation updates the UI
```

Cached rows are not cleared before network success. A failed refresh therefore leaves the last complete library intact. Logout/account replacement cannot race a late refresh commit: both pass through `AccountDataCoordinator`, and the commit is rejected unless the account still owns the vault record.

### Live playback

```text
channel selection
  -> choose m3u8 or ts
  -> build authenticated /live/{user}/{password}/{id}.{format} URL
  -> MediaController sets item on service-owned ExoPlayer
  -> player snapshot drives loading/error/PiP presentation
  -> after 2 seconds of confirmed playback, write account-scoped recent channel
```

Authenticated stream URLs exist transiently in process memory because Xtream requires credentials in the path. They must never be logged or included in diagnostics.

## Failure model

Network, authentication, parsing, account, storage, and playback failures are reduced to `FailureKind` plus a `retryable` flag. UI code receives a safe message from `AppFailure.userMessage()` rather than a raw exception or response body.

API retries are bounded and apply only to temporary categories. Player load retries are also bounded. Cancellation is always rethrown so obsolete ViewModel or lifecycle work can stop normally.

## Performance strategy

- Room is the source of truth for browsing after refresh.
- Channel and Movie lists use Paging 3 with 60-row pages, a 20-row prefetch distance, and a 90-row initial load.
- Room indices cover account/category, provider order, normalized sort name, language, and recent timestamps for channels, plus rating, release year, and added timestamp for movies. Every offered sort is index-backed, which matters because Paging repeats the ordered query for each page.
- Response-body reading and JSON parsing run behind an explicit `Dispatchers.IO` boundary; provider rows are written in 500-row batches inside one Room transaction.
- Compose lazy lists/rows and Coil handle viewport-bound UI/image work.

The Xtream listing endpoints return the whole library in one response, so the full-array shape is unavoidable. Both large listings are streamed from the network straight into the database in batches, so neither the response nor the parsed collection is ever held whole; see [XTREAM_API.md](XTREAM_API.md). Peak memory is now bounded by the batch size rather than the library size.

A refresh of that size still takes a long time — roughly a minute and a half for 153,000 titles in a local test. `InitialSyncScreen` runs the first sync behind a progress screen that reports the running written count after each batch; the provider never sends a total, so a percentage would be a lie. It covers all three libraries in turn and skips any that is already cached, so adding a library costs the user only that library's download rather than a full re-sync.

## Portability

The domain models, repository contracts, URL rules, JSON semantics, format selection, and completion policy are plain Kotlin or close to it. Android-specific concerns—Room, DataStore, Keystore, Compose, and Media3—remain behind those boundaries. A future Windows client can reuse concepts and fixtures, but this is not currently a Kotlin Multiplatform project and Android quality takes priority.

## Current boundaries

- One application module and one active account.
- Live TV, Movie, and Series browsing reach the user, all with search, sorting, and a language filter, and all three content types play through the same service-owned player.
- Global search over the three cached libraries, a per-channel now/next guide, one saved list spanning all three libraries, and a guide grid over the viewer's own channels all have production data paths. A grid over an arbitrary category or the whole library does not: the provider answers the programme one channel at a time, so that is a request-count problem rather than a layout one.
- No project-owned cloud sync, telemetry, remote configuration, or account service.
- No destructive Room migration fallback is configured. Schema changes must add explicit migrations before release upgrades.
