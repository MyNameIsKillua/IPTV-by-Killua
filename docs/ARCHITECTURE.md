# Architecture

## Scope

The project is three Gradle modules. `:app` is the Android application, with layered packages.
`:shared` is a plain Kotlin library holding the platform-neutral domain layer and the Xtream
protocol; see *The `:shared` module* below. `:desktop` is the Windows/macOS client, built on
Compose Multiplatform and libvlc, and it depends on `:shared` and never on `:app`; see
*The desktop client* below. The whole is intentionally small enough to navigate without
framework-heavy indirection, while keeping Xtream behavior, local persistence, playback, and Compose
UI independent.

**Android is the reference implementation.** It is the only target with a device gate, a frozen
release identity and users. A change to `:shared` that would degrade it is not a change worth
making, whatever it buys the other client.

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

The desktop client is deliberately shallower, because it has less to hold:

```text
Main (window, keys, preferences)
        |
        v
BrowseScreen (all browsing state)
     /       |        \
    v        v         v
XtreamDesktopClient   stores   VlcVideoPlayer
    |                   |
    v                   v
:shared protocol    :shared export format
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
- `SessionRepository`, `LiveRepository`, `MovieRepository`, `SeriesRepository`, and `WatchlistRepository` contracts, all of which live in `:shared`;
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

The domain models, repository contracts, URL rules, JSON semantics, format selection, and completion policy are plain Kotlin or close to it. Android-specific concerns—Room, DataStore, Keystore, Compose, and Media3—remain behind those boundaries. Android quality takes priority over any other target.

### The `:shared` module

`:shared` is a plain Kotlin JVM library. `:app` depends on it; nothing depends on `:app`. It holds
rules with **no external dependency whatsoever**: the track-language rules, the subtitle-style rules,
watch-progress completion, and search-text normalisation, each with the tests that were already
covering them.

It is deliberately not a Kotlin Multiplatform module yet. What is in it has no dependencies, so a JVM
library already serves Android, Windows and macOS; multiplatform only starts paying for itself at
iOS, which additionally needs Ktor in place of Retrofit. The conversion is mechanical when that day
comes: change the plugin, and `src/main/kotlin` becomes `src/commonMain/kotlin`.

**The domain models moved too**, which needed the `@Immutable` problem solved rather than worked
around. That annotation is a recomposition hint, about twenty types carried it, and several of them
hold `List` fields, which Compose treats as unstable by default — so simply deleting it would have
made screens recompose where they previously did not, invisibly, because no test can observe a
recomposition.

`compose_stability.conf` in the repository root declares `dev.killua.iptv.domain.model.*` stable from
the outside, and `:app` points the Compose compiler at it. This is the supported mechanism for types
you do not want coupled to Compose, and it keeps `:shared` free of any dependency at all.

**Verify it after touching those models**, because nothing else will:

```
gradlew :app:compileDebugKotlin -PcomposeReports --rerun-tasks
```

Then read `app/build/compose-reports/app_debug-composables.txt`. Every bare domain-model parameter
must appear as `stable`. A `List<Model>` parameter reads `unstable` and always did: `kotlin.collections.List`
is unstable to Compose whatever its element type, and the annotation never changed that.

**The whole domain layer now lives in `:shared`** - models, repository contracts, the EPG selection
rules, watch-progress completion, the track and subtitle rules, and search normalisation. Two things
had to be settled to get the contracts across:

- They return `androidx.paging.PagingData`, so `:shared` depends on `paging-common`. That artifact is
  plain Kotlin, unlike `paging-runtime`, and it is exposed as `api` because it appears in the
  contracts' own signatures. It is the module's only dependency and any second one needs an argument.
- `SessionRepository` reached **upward** into `core/network` for `NormalizedServer`, which is the
  wrong direction with or without modules. `ServerUrlNormalizer` moved across whole, types and
  parser together. It keeps its original package, so no import anywhere had to change.

**The Xtream protocol moved too.** `XtreamJsonParser`, `XtreamStreamUrlFactory`,
`XtreamLanguageTagger`, `XtreamM3uUrlParser` and `ServerUrlNormalizer` are in `:shared` with their
tests — the defensive parsing, the streaming that survives a six-figure listing, the container
whitelist, and the safe construction of authenticated URLs. That is the most expensive code in this
project to have got right, and a desktop client now inherits it rather than reimplementing it.

**`OkHttp` is the one compromise.** Those files use `HttpUrl` as a URL builder and parser, never as
an HTTP client, and OkHttp is JVM-only. It was kept rather than rewritten against `java.net.URI`
because `XtreamStreamUrlFactory` relies on its percent-encoding, that encoding is what keeps
credentials safe inside authenticated paths, and the tests covering it would have had to be rewritten
at the same time. An iOS target has to replace it with Ktor's `Url`, on the same trip that replaces
Retrofit. `CLAUDE.md` carries this as an invariant.

**What is left in `:app`** is everything that genuinely needs Android or a JVM-only framework: Room
and the whole `core/database` layer, `XtreamApi` and `XtreamRemoteDataSource` (Retrofit), the
repository *implementations* in `data/repository`, DataStore, the Keystore vault, Media3, and the UI.
The desktop client carried none of it over: it uses plain OkHttp rather than Retrofit, and it avoided
the Room question entirely by not needing a cache. That question is still open for any client that
does.

One consequence worth knowing, because it was found by doing this rather than by reading: Kotlin does
not smart cast a public property declared in another module. Code that relied on
`if (x.field != null) use(x.field)` has to read the value into a local first. The compiler catches
every instance, so this is noisy rather than dangerous.

### The desktop client

`:desktop` reuses the expensive half of this project — the Xtream protocol, the URL construction, the
domain models and rules, the export format — and reimplements only what a different platform forces
it to. Its layering is shallower than Android's on purpose, and the absences are the design:

- **No repository interfaces and no DI container.** `AppContainer` exists on Android because a dozen
  screens share a cache, a vault, a coordinator and a player service. Here one screen holds the
  browsing state, is handed a client and a player, and constructs its own stores. Interfaces with one
  implementation and one caller are indirection without a reason.
- **No database, but the library is held in memory.** This one has moved. The client began by asking
  for one category at a time and never requesting the six-figure listing at all; that bought it its
  lack of a database and cost it the two things the owner asked for after using it — a library that
  is simply *there* the way the phone's is, and a search that can find a title without being told
  which shelf it is on. `player_api.php` has no search action, so neither is reachable one category
  at a time. `LibraryIndex` now holds all three listings, read **once per sign-in** through the same
  streaming parser the phone uses, and nothing about it is written down: no schema, no migration, no
  reconciliation. The next launch asks again. What the client *persists* is still only the user's own
  data in the **export format** from `:shared`, so its state file is interchangeable with the phone's
  by construction rather than by conversion.
- **Credential storage is opt-in, and it is DPAPI.** `CredentialVault` seals the sign-in with
  `CryptProtectData` against the logged-in Windows account, writes only when the viewer ticks *Stay
  signed in*, and deletes on unticking, on sign-out, and when the provider rejects what was stored.
  `SecretCipher` is the seam: one real implementation, one that refuses on every platform without
  DPAPI, and a reversing one so the file rules can be tested without calling into Windows.
- **The library cache is not a database.** `LibraryCache` keeps one JSON file per account so the next
  launch is not a wait, with its own DTOs rather than the `:shared` models — serializing those would
  turn a domain type into a stored contract nobody promised. A file that cannot be read, or that
  carries another version, is **deleted**; there is no migration path and there must not be one. It
  holds a listing and never an account: `direct_source` is not copied at all, and an artwork address
  containing the account's own user name or password is dropped.
- **No ViewModel.** Compose Multiplatform on the desktop has no lifecycle to survive; `remember` and
  a `rememberCoroutineScope` are the whole of it.

What it does own, and where the boundaries sit:

- `XtreamDesktopClient` is the network layer — plain OkHttp, because Retrofit is the one dependency
  `:shared` refuses, and a handful of requests built by hand are cheaper than a framework an iOS
  target would have to replace anyway. It parses through `:shared`'s `XtreamJsonParser` and builds
  every authenticated URL through `XtreamStreamUrlFactory`. It caches nothing. Its three
  `withAll…` calls are the whole-library requests: streamed an item at a time rather than read into a
  string, and given a second OkHttp client with no call deadline, because a listing that legitimately
  takes minutes is not a request that has hung.
- `LibraryIndex` is that listing once it has arrived, and `loadLibrary` is what fills it. Three
  independent steps — a provider that refuses one still gives the other two — reporting counts as
  they climb, capped at `MAX_ITEMS` so a listing larger than this client can hold stops rather than
  exhausting the heap, and handing over a fresh index **after every step** so Live becomes usable
  while the films are still arriving. The index folds every title through `SearchTextNormalizer`
  once, when the listing lands, which is what makes both the filter box and the search box a scan
  rather than a hundred thousand string builders per keystroke. `LibraryReader` is the seam its
  rules are tested through.
- **Four stores, all local, all keyed by account where the provider's ids are involved.**
  `DesktopUserData` holds the export at `%LOCALAPPDATA%\KilluaIPTV\user-data.json`; `TitleIndex`
  holds names for what has been marked; `PreferenceStore` holds the window furniture; `ArtworkStore`
  holds posters. The last three are disposable sidecars — deleting any of them costs a caption, a
  window size or a few seconds of re-fetching. Each carries the export format's one-way fingerprint
  where it holds anything numbered by the provider, because ids are per-account.
- `EpgCache` is memory-only and expires on two rules rather than a timer; see `docs/ROADMAP.md`.
- `VlcVideoPlayer` is the playback boundary. libvlc hands over I420 planes, a Skia shader converts
  them, and the frame becomes an immutable image before Compose sees it — handing Compose a bitmap
  that libvlc then overwrites crashes the JVM natively. Because the video is ordinary Compose
  content, every control over it is ordinary Compose.
- `ScreenKeys` is the one wire from the window down to the screen: keys are handled at the window so
  they work wherever the pointer is, but only the screen knows what is playable next, where the
  search box is, and what it holds that the disk does not. Its `flushToDisk` is what the closing
  window waits on — a suspending call rather than a launched one, because the process is exiting and
  the coroutine dispatchers run on daemon threads.
- `Modifier.focusRing` is where the keyboard is. `clickable` makes everything focusable whether
  anyone meant it or not, so tab and the arrows already reach every control; what was missing was
  any sign of it. A poster and a list row had their own treatment, and this is the same idea
  everywhere else, written once so it cannot be half-applied again.
- **Cyan means focus and violet means chosen**, and neither is used for the other. Violet was
  already the client's colour for the open section, the programme being read and a mark that is set;
  focus is not a choice but where the next key will land, and the two land in the same place often
  enough that sharing a hue makes both unreadable. Cyan was the scheme's `secondary` from the first
  day and had never been drawn, so giving it this meaning took none away from anything else.
- `catchingExceptCancellation` is `runCatching` minus the one thing it must not catch. `:app` has
  rethrown cancellation by hand at every boundary since it had them; the desktop used bare
  `runCatching` around its requests and never inherited the rule, so a category abandoned mid-flight
  woke up, reported a failure over whatever had replaced it, and carried on running. Only for blocks
  that can suspend — around a file read there is no suspension point for a cancellation to arrive at.
- `Shortcut` is **which** key means what, as data rather than as a `when` block. The window
  dispatches over it exhaustively, so a key offered to a viewer with no action behind it does not
  compile, and Settings and the `F1` overlay render the same entries rather than a description of
  them. It also carries the gate that keeps the window from swallowing what someone is typing.
- **The screens are one file each**, and `BrowseScreen.kt` holds the state they share. It grew to
  three thousand lines before the player, My list and a film's record were lifted out of it; that
  split was done a function at a time with a compile between each, because the compiler is the only
  reviewer this module has for a change nobody can run. What is left in it is browsing state and the
  things only browsing uses.

## Current boundaries

- Three modules — `:app`, `:shared` and `:desktop` — and one active account per client. No TV or iOS
  target exists yet. `:desktop` depends on `:shared` and never on `:app`; nothing depends on `:app`.
- Live TV, Movie, and Series browsing reach the user, all with search, sorting, and a language filter, and all three content types play through the same service-owned player.
- Global search over the three cached libraries, a per-channel now/next guide, one saved list spanning all three libraries, and a guide grid over the viewer's own channels all have production data paths. A grid over an arbitrary category or the whole library does not: the provider answers the programme one channel at a time, so that is a request-count problem rather than a layout one.
- No project-owned cloud sync, telemetry, remote configuration, or account service.
- No destructive Room migration fallback is configured. Schema changes must add explicit migrations before release upgrades.
