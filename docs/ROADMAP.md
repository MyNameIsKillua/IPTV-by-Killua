# Roadmap

This roadmap follows reliability-first delivery. A phase is complete only when its production path is implemented, builds/tests pass, and any real-provider-dependent behavior has a documented manual verification path.

Status key: **Implemented** means present in the current development code; **Device verification pending** means the production path exists but cannot be certified without an authorized real account; **Partially implemented** means a useful subset is present while later work remains; **Planned** means not implemented.

## Phase 1 — Project foundation: Implemented

- Native Android application module, Kotlin, Compose, Material 3, edge-to-edge activity.
- Android API 26 minimum, API 36 compile/target, Java 17 bytecode.
- Manual dependency composition with repository contracts and layered packages.
- Room 1 schema, Preferences DataStore, Retrofit/OkHttp, coroutines/Flow, Paging, Coil.
- Dark/Light/System Material theme; Dark default.
- Central failure taxonomy and safe user messages.
- Local unit coverage for URL normalization, credential-bearing Xtream URL parsing/login state, gesture zoning, Xtream JSON/request/retry behavior, live and Movie URL construction, VOD parsing, account-data coordination and logout races, credential-record encoding, and watch-completion boundaries, plus release shrinking configuration.

Alpha-3 gate: `testDebugUnitTest`, `assembleDebug`, and `lintDebug` pass; 90/90 JVM tests across 10 suites green with 0 failures, errors, or skips; the debug APK reports version code 3 / `0.1.0-alpha.3-debug`, uses the local debug certificate with APK Signature Scheme v2, and lint reports 0 errors and 13 non-blocking warnings. Runtime PiP code calls both recommended Android 12 transition APIs even though static analysis retains a PiP advisory.

Current workspace gate (Live search and sorting, 14 August 2026): 200/200 JVM tests across 20 suites green, `assembleDebug` successful, lint unchanged at 0 errors and the same 13 advisories, and all 4 instrumented migration cases green on an API 36 emulator.

## Phase 2 — Xtream authentication: Implemented

- Polished server/username/password form with password visibility toggle.
- Alternative sign-in from a provider-issued credential-bearing Xtream `get.php`/`player_api.php` URL; arbitrary credential-free M3U playlist import remains out of scope.
- Local validation and defensive URL normalization.
- Non-persisting connection test and saving Connect action.
- Account authentication/status/expiry/connection parsing.
- Android Keystore AES-GCM credential vault in no-backup storage.
- Cached startup session, background validation, reconnect, and logout flows.
- Safe network failure mapping and bounded API retries.
- HTTP compatibility with an explicit security warning; normal TLS validation for HTTPS.
- White login headline with a dark glow/shadow for reliable contrast, plus a restrained **Developed by MyNameIsKillua** signature on startup and Settings.

Real-world gate: verify successful/wrong-password/expired/offline flows against an authorized provider without capturing secrets.

## Phase 3 — Live TV: Implemented; base device verification passed

Implemented:

- `get_live_categories` and `get_live_streams` adapter calls.
- Defensive live JSON parsing, Room cache, generation-based transactional refresh.
- Paging for All, provider category, Uncategorized, and Recent, each combinable with a title search, a heuristic language filter, and a sort order through the shared query builder.
- Live category-chip/channel-list UI with debounced search, a language menu, a sort menu, manual refresh, loading skeletons, empty/error states that distinguish an empty cache from an over-narrow filter, logo placeholders, and Coil images.
- Home recently watched row plus cached-library warning after a temporary startup validation failure.
- Account-scoped recent-channel persistence after two seconds of confirmed playback.
- Live URL selection/building for HLS and TS.
- Service-owned ExoPlayer, MediaSession/controller bridge, standard `PlayerView` controls, bounded load retries, safe retry/back UI, and PiP Activity integration.
- Immersive full-screen playback that hides system bars, uses single tap for the normal controller, keeps the Back overlay tied to controller visibility, and prevents double-tap/hold gestures from opening the controller.
- Settings-based library refresh, PiP preference, player-gesture preferences, bounded artwork-cache clearing, reconnect, and logout.

Physical-device result: the base Phase 3 flow and alpha-2 gesture/cache checks passed on a Samsung Galaxy S23 Ultra running Android 16 / One UI 8.5 with an authorized real provider on 13 August 2026. On 14 August, the user reported that alpha 3 also works well, covering the requested login contrast, Xtream M3U account-link login, immersive/controller gesture interaction, and developer signature at a high level.

Remaining verification gate:

- provider-exposed audio/subtitle switching is implemented but its meaningful device verification is deferred until Movies supplies a known multi-track source;
- repeat coverage on an emulator/reference device and other provider variants where available;
- repository-level regression tests for transactional live cache refresh (stream selection/construction is already covered).

Live search and sorting passed on the Samsung Galaxy S23 Ultra with the user's real provider on 14 August 2026, installed as an update over the previous production build. The Room 2→3 migration therefore has a real-world result on a roughly 60,000-channel library: account, cache, and history survived. The language filter the user asked for during that pass is implemented in `v0.2.0-alpha.7`, verified on an emulator against a multilingual synthetic provider but not yet on the real account.

## Release-identity preflight: Implemented; device verification passed

- **Implemented:** a local-only release signing configuration read from an ignored `keystore.properties`, a tracked placeholder example, and a `verifyReleaseSigning` task that fails any release build before compilation while signing is unconfigured. No debug-key fallback exists and no secret is tracked or printed.
- **Implemented:** the permanent 4096-bit RSA key, generated locally on 14 August 2026, verified with `keytool -list -v`, and stored outside the repository and outside every synced folder. Its certificate SHA-256 is pinned, and every published build is verified against it before release.
- **Implemented:** the signed `v0.1.0-alpha.4` baseline (version code 4), verified as package `dev.killua.iptv` with one signer, v2/v3 signature schemes, and a certificate digest matching the pinned SHA-256 exactly. Published as a private GitHub pre-release whose uploaded asset digest was re-verified after download. The production identity is now frozen.
- **Device verification passed:** the user reported on 14 August 2026 that alpha 4 installs beside the alpha 3 debug app on a Samsung Galaxy S23 Ultra (Android 16 / One UI 8.5) and that everything tested works, including a fresh sign-in, live library, playback, gestures, PiP, restart, and logout. This is the first minified build to run on hardware, so it also validates the current R8 configuration.
- **Pending:** a second encrypted keystore backup. Because the production app is now installed and in use, losing the key would cost real user data.
- Be explicit that published alpha 3 is `dev.killua.iptv.debug`; the first production `dev.killua.iptv` build installs separately and requires one new login.
- Recommended: test a permanently signed `v0.1.0-alpha.4` baseline (version code 4) beside the debug app before Movies.
- Use the exact same production package/certificate for the Movies build with a higher version code; verify it updates the signed baseline without losing local state.
- Add ordinary secret-free CI first. Add CI release signing only after explicit approval and GitHub Secrets setup.

## Phase 4 — Movies: Partially implemented

Implemented:

- Shared account-data mutation/cleanup coordination through `AccountDataCoordinator`, so Live and Movie refresh, progress, favorites, logout, and account replacement cannot race. Downloads run outside the lock and stale commits are rejected by a credential-ownership recheck.
- Defensive `get_vod_categories`, `get_vod_streams`, and `get_vod_info` support plus safe authenticated Movie URL construction with a container-extension whitelist, and provider-neutral Movie models.
- Room schema 2 with an explicit non-destructive 1→2 migration, account-scoped Movie metadata, details, favorites, and generic watch progress, plus transactional cached-first refresh.
- Local Paging with filtering and sorting: category (which is the genre filter in practice), heuristic language, favorites, in-progress, and title search; sorted by provider order, name, rating, release year, or recently added.
- The paged Movie category/poster grid, debounced Movie-local search, filter bar, favorites, Continue Watching, and a missing-field-safe details screen. Confirmed working on the user's real provider.

- Movie playback through the existing service-owned Media3 architecture, with resume/restart and bounded progress checkpoints, which also brings the in-progress filter and Continue Watching row to life.

- A manual mark-as-watched action on the details screen, which also clears the stored position.
- Progress persistence on interval and on every available playback/lifecycle transition, without excessive writes. Navigation and ordinary lifecycle exits checkpoint immediately; after force-stop/process death the loss is bounded to roughly one checkpoint interval, because Android may provide no final callback.

Remaining:
- Stock audio/subtitle selection verified manually with a known authorized multi-track Movie. Not reproducible against the synthetic provider, whose test clip has one audio track and no subtitles.

Recommended first Movies release: `v0.2.0-alpha.1` using the permanent identity and a version code higher than the signed baseline. Exact versions are set only when the user authorizes the release.

## Phase 5 — Series: Implemented

- Defensive `get_series_categories`, `get_series`, and `get_series_info` support, with the Series listing streamed from the start rather than after it first exhausts the heap.
- Provider-neutral `SeriesCategory`, `SeriesSummary`, `SeriesDetails`, and `SeriesEpisode` models, with episode identity taken from the provider ID and never from season or episode numbers.
- Safe authenticated episode URL construction under `series/`, sharing the Movie container whitelist.

- Room schema 5 with an explicit non-destructive 4→5 migration, account-scoped Series metadata, lazily cached details, and episodes keyed by the provider's own episode ID. Verified on an emulator; episodes reuse `watch_progress` through its `contentType`, so no progress migration was needed.
- Local Paging with filtering and sorting: category, heuristic language, and title search; sorted by provider order, name, rating, release year, or the provider's last-modified stamp.

- A Series poster grid with category chips, debounced search, a language filter, and sorting, plus a details screen with plot, genre, cast, director, and episodes grouped by season.
- Episode playback through the same service-owned player as Live and Movies, with resume, restart, checkpointed positions in the shared `watch_progress` table, per-episode watched and remaining-time marks, and a primary button that advances to the next unwatched episode.
- Series favorites in Room v6 with an additive 5→6 migration, plus Favorites and Continue filter chips and a Continue Watching row, matching Movies.

Remaining:

- Episode navigation in the player: a Next episode control, and autoplay at the end of an episode behind a setting that defaults to on.

Remaining:

- A countdown before autoplay starts, and a previous-episode control.
- A manual completed/unwatched control; completion is still only derived from playback.

## Phase 6 — Personalized Home aggregation: Partially implemented

- **Implemented:** a Continue Watching row on Home, above the recently watched channels, holding unfinished Movies **and** Series merged and ordered by when each was last watched. Each tile opens the details screen of its own library.
- Recently watched sections by content type.
- Favorite and recent live channels.
- **Implemented:** a **My list** row holding Movies, Series, and channels together, newest first, hidden while empty. **Implemented:** a **Recently added** row over both VOD libraries — films by the provider's `added` timestamp, series by `last_modified`, which is when a returning show gains an episode. A library whose timestamps are all identical is left out of the row rather than ordered by a field that distinguishes nothing; if neither library says anything the row hides itself.
- Cached-first startup with background refresh indicators.

## Phase 7 — Cross-content search and user-data management: Partially implemented

- **Implemented:** global search over the three cached libraries from one debounced field. Results are grouped per library, each section expands on request, and a hit opens its own screen. The search is local-only — Xtream has no search endpoint, and re-downloading a six-figure listing per keystroke is not a search.
- **Implemented:** one saved list across all three libraries, written from a bookmark on Movie details, Series details, and every channel row.
- Remaining: exporting or clearing individual user data.

Live and Movies each have their own content-local search and sorting already. This phase is the cross-content layer above them.

- Debounced global local search grouped by Live, Movies, and Series.
- Cross-content UI for the saved list is implemented; favorites stay per-library on purpose, and merging the two was considered and deliberately declined (it would mean migrating user data the provider cannot re-derive).
- Indexed filters/sorting and accessible empty states.
- History grouping and user-controlled clear/mark-watched actions.

## Phase 8 — Advanced player: Partially implemented

Implemented through alpha 3:

- Stock Media3 audio/subtitle selection when the stream exposes alternate tracks.
- Double-tap left/right seeking on seekable/DVR media, configurable from 5 to 60 seconds (10 seconds by default).
- Double-tap center play/pause.
- Press-and-hold temporary playback speed, configurable from 1.25x to 2x (2x by default), with restoration on release/cancel.

Added since:

- Vertical drag for brightness and volume, confined to a visible slider at each edge.
- Previous **and** next episode, plus a cancellable countdown before autoplay.
- Aspect-ratio modes (Fit, Zoom, Stretch), remembered across sessions.
- A remembered player brightness.

Remaining:

- Remembered preferred audio/subtitle language and custom subtitle styling. **The owner's stated priority for this phase.** Neither is verifiable against the synthetic provider.
- Persistent playback-speed selection. Attempted in `v0.2.0-alpha.27` and withdrawn before release: `MediaController` did not report the change, so nothing was ever stored. See `docs/PLAYER.md`.
- Previous/next **live channel** actions; episodes have both, channels have neither.
- Custom MediaSession notification and PiP actions where supported.
- Optional in-app mini-player and carefully scoped background-audio behavior.
- **Implemented:** reliable progress writes on interval, pause, background, PiP, stop, and stream change, shared by Movies and episodes through one writer.

Native PiP and the MediaSession service foundation already exist; this phase expands controls and VOD behavior.

## Phase 9 — EPG: Partially implemented

- **Implemented:** now/next for the channel being watched, from the per-channel `get_short_epg` endpoint, cached in memory for five minutes and shown beside the Back button with a progress bar. Entries are placed from the provider's epoch timestamps; a formatted time without an offset is dropped rather than guessed at.
- **Implemented:** what is on now under each name in the live channel list, fetched only for rows the viewer settles on and capped to four requests at a time.
- **Implemented:** a guide grid over **the viewer's own channels** — the ones bookmarked onto the saved list plus the ones recently watched, deduped and capped at 40 rows. Four hours from the previous half hour, one shared horizontal axis, a now marker, and a tap that starts the channel.
- Remaining: a grid over an arbitrary category or the whole library, and a reminder or recording hook if the provider supports one.

**Why the rows are the viewer's own channels.** Xtream answers the programme one channel at a time; `get_short_epg` takes a single stream id and there is no bulk call. A grid over 60,000 channels is therefore not a layout problem but a request problem — it would be 60,000 requests. Bounding the rows to what the viewer actually keeps turns that into tens. Extending it to a whole category means fetching only the rows on screen, the same discipline the channel list already uses, and is a separate slice.

**What it cannot do.** `get_short_epg` returns upcoming programmes, so the guide only looks forward; there is no yesterday. The window is fixed when the screen opens so the axis does not drift while it is being read, and refresh moves it.

- Current/next program data and cache with timezone handling.
- Channel-list program progress.
- Program details and a performant time-grid TV guide.
- Refresh/expiry policy that tolerates incomplete provider data.

## Phase 10 — Polish and hardening: Planned

- Skeletons, transitions, refined error/empty states, and accessibility audit.
- Artwork-cache limits and manual clearing are implemented; cache-size display and large-library memory/startup profiling remain.
- Hidden categories and optional local PIN.
- Sanitized network/player diagnostics.
- Export/import for non-secret local data; credentials excluded or separately encrypted.
- Release signing is pulled forward as the pre-Movies gate; explicit Room migrations, restore/upgrade testing, and repeated security review continue here.

## Cross-cutting quality gates

Every phase should maintain:

- no real credentials, authenticated URLs, response captures, or provider data in source control/tests;
- a clean `testDebugUnitTest` and `assembleDebug` run;
- safe cancellation and bounded retries;
- cache preservation on temporary failures;
- account scoping for all user state;
- readable TalkBack labels, contrast, and touch targets;
- real-device checks on a supported Samsung phone before a release APK is trusted.

The detailed continuation plan and acceptance matrix are kept in the private development repository.

## Future Windows client

Windows remains intentionally out of scope until the Android streaming-library experience is reliable. The reusable concepts are repository contracts, IDs/models, parsing fixtures, sorting/filtering rules, and progress/continuation policies. Playback, secure storage, persistence, and UI should remain platform-specific unless later evidence justifies a shared Kotlin module.
