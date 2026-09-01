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

Current workspace gate (user-data export, 19 August 2026): 497/497 JVM tests across 43 suites green — 156 of them in `:shared` — with `assembleDebug`, `assembleRelease` and lint unchanged at 0 errors and the same 13 advisories. No instrumented migration run was required: nothing since alpha 32 touches the schema, and Room stays at version 9.

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
- **Implemented:** the permanent 4096-bit RSA key, generated locally on 14 August 2026, verified with `keytool -list -v`, and stored outside the repository and outside every synced folder. Its certificate SHA-256 is pinned in `docs/RELEASE.md`, which is kept with the development
repository rather than published.
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
- Stock audio/subtitle selection verified manually with a known authorized multi-track Movie, which is now also what would prove that the remembered language reaches the next title. Not reproducible against the synthetic provider, whose test clip has one audio track and no subtitles.

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
- **Implemented:** exporting user data to a file from Settings. Watch progress, both favourite lists,
  the saved list and recent channels for the active account, written through the system document
  picker so no storage permission is involved and the viewer chooses where it lands. The library
  itself is deliberately absent: categories, titles and episodes are re-downloadable, and an export
  is not a backup of a cache that is hundreds of megabytes and stale on arrival.

  **No credentials are in the file** - not the server, not the username, not the password. The
  account is identified by a SHA-256 fingerprint of host and username, so a later import can tell
  whether a file belongs to the account it is being merged into, while the file itself is worth
  nothing to whoever finds it. Records carry no `accountId` either: it is a UUID minted at login and
  means nothing on another device.

- **Implemented:** importing one of those files. The file is checked against the account's fingerprint
  **before** anything is read into a plan, and refused outright when it belongs elsewhere: merging one
  provider account's history into another cannot be undone. The viewer is then shown what would change
  and can decline; nothing moves until they agree.

  **Newest wins, and nothing is ever deleted.** One rule for every kind of row, because a second rule
  would need a second explanation, and an import that removed something would be a mistake nobody
  could undo. A row missing from the file simply stays, which also makes importing the same file
  twice free — the second run writes nothing.

  Unlike the export, the write goes through `AccountDataCoordinator.commitTransaction`, so it
  serializes with logout, account replacement and library refresh and lands all-or-nothing.

- Remaining: clearing individual user data. **The decided answer to
  continuing a series on another device**, asked by the owner on 18 August 2026 and settled the same
  day: an export/import file, not synchronisation.

  Xtream cannot help — its API is read-only for catalogue and guide, with no endpoint for playback
  state, so the provider can never be the meeting point. Automatic sync would mean a server this
  project operates, holding a record of what the owner watches, which the invariants rule out and
  which is a liability rather than a feature.

  The stored data is nearly ready for it regardless: `watch_progress` is keyed by
  `(accountId, contentType, contentId)` where `contentId` is the provider's own ID and therefore
  identical on every device, and `updatedAtEpochMillis` is already recorded, which is what a
  newest-wins merge needs. The one device-local part is `accountId`, a random UUID per login, so an
  export must carry an identity derived from the server and username instead and the import must map
  it onto the local account. Credentials never travel in it.

  Considered and deferred, in increasing cost: putting that same file in a cloud folder the owner
  already syncs; a sync endpoint the owner self-hosts; direct device-to-device transfer over the
  local network. None of them requires a project-operated backend, which is why they remain open.

Live and Movies each have their own content-local search and sorting already. This phase is the cross-content layer above them.

- Debounced global local search grouped by Live, Movies, and Series.
- Cross-content UI for the saved list is implemented; favorites stay per-library on purpose, and merging the two was considered and deliberately declined (it would mean migrating user data the provider cannot re-derive).
- Indexed filters/sorting and accessible empty states.
- History grouping and user-controlled clear/mark-watched actions.

## Playlist accounts: parser and policy done, no screen yet — 22 August 2026

The owner asked on 22 August why a plain `.m3u` address is refused, and the answer was that nothing
ever read one: `XtreamM3uUrlParser` accepts `get.php` and `player_api.php` and says so in its own
doc comment. The field labelled *Playlist link* is a second way to type Xtream credentials, not a
playlist reader.

**What was built** is the half that is testable without a screen: `M3uPlaylistParser` in `:shared`
turns an extended M3U into the same `LiveChannel` the Xtream listing produces, and `StreamUrlPolicy`
decides which addresses out of one may be opened at all. Nothing calls either yet — the same shape
as Gate 2, which landed the VOD protocol before any screen used it. 34 tests.

**A playlist account is Live only, and that is the format's doing rather than a decision.**
`group-title` becomes a category, `tvg-logo` a logo, `tvg-id` the guide id, and there it ends: an
M3U has no films, no series, no per-title metadata and no guide endpoint. Offering those rails for a
playlist would be offering what the file cannot answer.

**Measured against the real thing** rather than against a fixture — iptv-org's `index.m3u`,
2.76 MB and 26,422 lines, on 22 August 2026:

| | |
| --- | --- |
| Channels read | 12,785 in 1.3 s |
| Refused by the policy | 6 — three `rtmp:`, two `mmsh:`, one `srt:`; **no** private addresses |
| Malformed entries | 0 |
| Served over `http` | 2,273, or 18% |
| Groups | 178 |
| With a logo / with a guide id | 10,669 / 10,831 |

Two things in that file shaped the parser and are now tests. `#EXTVLCOPT:` lines sit **between** an
entry and its address, so an unknown directive must not discard the pending entry. And
`http-user-agent="… (KHTML, like Gecko) …"` puts commas inside a quoted value, so the comma that
begins the display name is the first one *outside* quotes — splitting on the first comma anywhere
cuts a name in half. A third finding became a fix: `…/georgia_play.php?id=…` is a live channel, and
reading `php` off the end of it put a script name in a field that means *what this media is*, so the
container is now only filled from a known list.

**What a playlist cannot do here, and should not be made to look as though it can.** Some entries
carry `http-user-agent`, and a few streams will not play without it; the parser drops the attribute
because `LiveChannel` has no field for it, so those channels will fail. That is a known gap, not a
mystery to debug later.

**Why it is worth having at all**, beyond the owner's reason: this is the first source this project
has ever had that serves media a test can actually decode. The remembered audio and subtitle
language, resume against real timestamps and the desktop's decoding path are all documented as
verifiable only on the owner's own provider. A public playlist changes that.

The security reasoning — what the policy refuses, what it cannot do, and which playlists are in
scope — is in [Security and privacy](SECURITY.md) under *Playlists, and addresses this program did
not build*.

Remaining for a usable feature: nothing. The desktop was finished on 22 August and **Android on
26 August**, in eight parts: Room 10, the account kind, `LiveListingSource`, `PlaylistLiveSource`,
`loginWithPlaylist`, the sign-in form's third way in, the rails and sync screen that drop what a
playlist has not got, and the headers reaching Media3.

**The two clients solved the same problem differently where their platforms differ**, and the
differences are worth knowing before changing either. The desktop abstracts the whole library
behind `LibraryReader`; Android abstracts only the *live* listing behind `LiveListingSource`,
because films and series have no playlist equivalent to abstract over. The desktop holds its library
in memory and shuts its disk cache for a playlist, since that cache strips `direct_source`; Android
keeps playlist channels in Room like any others, in three columns added for them in schema 10. And
the headers: libvlc took two strings on the media instance, while Media3 needs them in a `Bundle` on
the `MediaItem`, across a process boundary, rebuilt into a `DataSource` by a factory that sees each
item.

**What no test covers on either:** a playlist actually playing. The parser is measured against
12,785 real channels and every rule around it is tested, but decoding needs a device and a screen.

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
- A remembered audio and subtitle language, learned from the stock track menu and applied to everything watched afterwards, with subtitles-off as a state of its own and a Settings row that shows and clears it. Only a deliberate pick is learned; what the player selects on its own is never stored. Verified on the emulator through its rules and its bounded writes; **the effect itself cannot be verified locally**, because the synthetic provider serves a single audio track.
- Subtitle styling: a remembered size (a fraction of the picture height) and a remembered background (plain, drop shadow, outline, or a box). Both default to Android's own caption preferences, so an accessibility setting is never silently overridden, and a chosen style deliberately overrides the stream's embedded styling. Text colour and an in-Settings preview are deliberately not offered; see [Player behavior](PLAYER.md).

Remaining — the owner's stated priority for this phase is complete, and everything below it is
optional convenience:

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

The detailed continuation plan and acceptance matrix are in `docs/CLAUDE_HANDOFF.md`, the
maintainer's working notes, which are kept with the development repository rather than published.

## Television, first slice: the app is installable on one

**Done 20 August 2026.** The owner asked for a "Smart TV app" and left the choice of platform open.
What was built is **Android TV support in the existing app**, not a second app, because the phone
build already contains everything a television needs except a way onto its home screen:

- `android.software.leanback` and `android.hardware.touchscreen` are both declared
  **`required="false"`**, which is the whole of what makes one build serve both. A phone has no
  leanback launcher and a television has no touchscreen; a feature marked required would take the
  app off one listing or the other.
- The launcher activity gained `LEANBACK_LAUNCHER` beside `LAUNCHER` — the same activity, reached
  from a television's home screen.
- A **320x180 banner** at `drawable-xhdpi/tv_banner.png`, which is what a TV launcher draws instead
  of an icon. Composed from the existing app mark and the brand palette rather than drawn by hand.

Verified in the built APK with `aapt dump badging`: `leanback-launchable-activity` present,
`banner='res/drawable-xhdpi-v4/tv_banner.png'`, and both features listed as
`uses-feature-not-required`.

**Second slice, the same evening: you can now see where the remote is.** `Modifier.focusRing` in
`ui/components` is the phone's copy of the desktop's, down to the colour — **cyan means focus**,
violet stays for *chosen*, so a viewer using both clients in one evening reads the same signal. On a
phone it draws nothing anyone ever sees: a finger does not move focus, so nothing is ever focused
and the border stays transparent.

It went on the poster tile and the Live channel row, and getting the poster wrong once is worth
recording: the ring was first put on the artwork `Box` *inside* the tile, and never appeared.
`onFocusChanged` only reports the focus of its own node and its children, and the focusable node is
the `Column` that carries the `clickable` — the Box is its **child**, so it never heard about it.
The tile now watches focus on the Column and draws the ring on the artwork, which is also where it
belongs: the caption below is two lines of a length nobody controls, and a border that changes
height with the title is a list that jumps as the remote moves through it.

Verified the way the gap was found — `adb shell input keyevent` against the synthetic provider on an
emulator, then `uiautomator dump` to name the focused node and a screenshot to see it. Focus does
traverse the content (the earlier reading that it skipped to the bottom bar was wrong; it lands on
the Live TV card, then on the tiles), and a focused poster now carries a clear cyan ring.

**Third slice: the search field no longer traps the remote.** A Compose text field consumes the up
and down arrows for moving a caret — right in a paragraph, wrong in a one-line search box, and on a
television a dead end: `DPAD_DOWN` pressed four times in the Live field left `uiautomator` reporting
the same `EditText` focused each time, so the channel list underneath could not be reached at all.

`Modifier.releasesFocusVertically` in `ui/components` is the fix, on all four search fields at once —
Live, Movies, Series and global Search, which each had their own copy of the same control. It has to
be a **preview** key handler rather than a focus property: `focusProperties { down = … }` only steers
a focus *search*, and no search ever happens while the field is eating the key. `moveFocus` returns
whether it found anywhere to go and that answer is passed straight back, so a press this cannot use
is left for whatever would have had it.

**Verified end to end with a remote**, on the emulator against the synthetic provider: `DPAD_DOWN`
now leaves the field (`uiautomator` names a channel row as focused), the row carries the cyan ring,
and `DPAD_CENTER` opens the player on that channel — which then reports *Stream could not be played*
with a *Retry*, because the synthetic provider serves no media. That is the whole path a viewer
takes with a remote, and it works.

**What is still unproven** is everything a television does that an emulated phone does not: overscan,
a 10-foot layout, and whether the text is readable across a room. The app draws a phone UI on a
television — usable, not designed for it. A leanback-shaped browse screen is a separate slice.

### It runs on a real one — 24 August 2026

The paragraph above used to end by saying the honest next step was finding out whether the owner had
an Android-based television at all. They have a **Fire TV Stick, 3rd generation** — Fire OS 7.7.1.5,
API 28, `armeabi-v7a` only, and 922MB of RAM. `v0.2.0-alpha.38` was sideloaded onto it over ADB and
signed in against the owner's real provider.

**It works.** The `LEANBACK_LAUNCHER` entry resolves, `MainActivity` resumes, the sign-in screen
draws at 1920x1080, and the full library sync completed against the six-figure account.

**The memory question is answered, and the answer is not the one that was feared.** Sampled every
ten seconds:

| Time | PSS |
| --- | --- |
| 2 min | 46MB, idle on the sign-in screen |
| **5.2 min** | **148.7MB — the peak, mid-sync** |
| 8-10 min | 61-85MB |
| 12 min onward | 22-38MB, steady |

The curve rises, falls and stays down: the sync finished and the memory came back. Note that PSS
counts native, graphics and code as well, so the Java heap that the 128MB
`dalvik.vm.heapgrowthlimit` actually caps was well under it — an app that exceeded it would have
died, and this one did not.

That matters because the fear was specific and reasonable: this device gives an app **128MB** where
the Samsung that once died of `OutOfMemoryError` had 192MB. The conclusion is that alpha 2 and 3 fixed
the problem properly rather than merely moving it out of a phone's reach. `dalvik.vm.heapsize` is
256MB here, so `android:largeHeap="true"` remains available as a lever — **and is not needed**, which
is the better outcome, since largeHeap costs every device to help one.

**Two cautions about how this was measured**, both worth carrying into the next device session:

- **A sampler must tell "process gone" apart from "device gone".** The first run recorded twenty
  minutes of readings and then a long run of what it logged as the process having died. It had not:
  the Stick had gone to sleep on its default 20-minute timer and taken ADB-over-WiFi with it, and
  `pidof` returning nothing through a dead connection looks exactly like a process that exited. Turn
  the Stick's sleep timer off for a long measurement, and have the script record device reachability
  as its own column.
- **Never read the memory question out of logcat.** The rule in `docs/SECURITY.md` holds here: a
  Fire TV logcat carries the same credentials a phone's does. The events buffer
  (`logcat -b events`) carries process lifecycle records and no account data, which is where a
  crash-or-not question should be settled.

**What this does not establish:** playback on the Stick, remote-control navigation through a real
session, the 10-foot layout, or overscan. The sign-in screen is visibly a phone form stretched to
1080p — full-width fields and text sized for a hand, not for a sofa.

**A caveat worth stating plainly.** Android TV covers Google TV, Fire TV and every Android box.
It does **not** cover a Samsung television, which runs Tizen, or an LG, which runs webOS — those
would need a web application, which is a different project rather than a flag in this manifest.

## Windows client: feasibility proven by measurement, not yet started

This section used to say Windows was out of scope, and that a shared Kotlin module would need "later
evidence to justify it". The owner asked for Windows on 17 August 2026, and that evidence was then
gathered rather than assumed. What follows is measured on the owner's own account and machine.

### How portable the existing code already is

Counted, not estimated, across 97 Kotlin files:

- `domain/` touches `androidx` in **four** files, and only for Paging in the repository contracts;
- `data/` touches only `androidx.sqlite.db` (the paged-statement builder) and `androidx.paging.map`;
- the Android-specific weight is concentrated in `core/`: Media3 (26 imports), DataStore (11),
  Keystore (2 files), Room and SQLite.

So Xtream parsing, the models, URL construction, the filter and sort statements, progress and
completion rules, the language heuristic, search normalisation, and the track and subtitle rules are
already platform-neutral. **Room 2.8.4, DataStore 1.2.1, Paging 3.4.2 and kotlinx-serialization all
have Kotlin Multiplatform artifacts at the versions already in use**, so the persistence stack needs
no upgrade to be shared. Retrofit/OkHttp is JVM-only, which is fine for Windows and macOS desktop and
would need Ktor for iOS.

Genuinely per-platform: the player, the credential vault, and the Android-only parts of the UI
(Picture-in-Picture, the touch gestures, immersive mode).

### What the desktop playback spike established

The decisive risk was never parsing or persistence, it was the player: Media3 is Android-only, and
Compose Desktop has no video component, so video with UI drawn over it is the known weak spot. A
throwaway spike answered it with numbers, against a 4K 50fps live channel from the owner's provider:

| Configuration | Delivered | Presented | CPU per frame |
| --- | --- | --- | --- |
| VLC converting to BGRA itself (RV32) | ~35 fps | ~19 fps | 17.9 ms |
| VLC handing over its I420 planes, colour converted in a Skia shader | **50 fps** | **50 fps** | **4.4 ms** |

A 50fps frame allows 20 ms, so the working version uses 22% of the budget. The video is ordinary
Compose content and the overlay UI lives in the same Compose scene, which is exactly the shape the
Android player already has.

Four findings worth keeping, because each one cost a measurement to learn:

- **The provider's streams are 10-bit HEVC**, and libvlc's `d3d11va` refuses them ("Unsupported
  bitdepth 10 for HEVC Main profile"). Hardware decoding is therefore not available for this content
  through libvlc, and software decoding is what actually runs. This machine manages it with room to
  spare; a weaker one may not.
- **Asking VLC for BGRA costs about 28 ms per frame** of CPU colour conversion at 4K. Asking for the
  decoder's own I420 planes removes it completely and drops the payload from 33 MB to 12.4 MB per
  frame. This single choice is the difference between 19 fps and 50 fps.
- **Compose was never the bottleneck.** In every run, presented equalled delivered exactly. The
  overlay concern that argued against Compose Multiplatform did not survive contact with a
  measurement.
- **Handing Compose a Skia `Bitmap` whose pixels are then overwritten crashes the JVM natively.**
  Skia keeps reading that memory while the next frame is written into it. Each frame must become an
  immutable `Image` first; that copy is what makes the race impossible rather than merely unlikely.

Because every candidate stack uses the same libvlc, none of these numbers would improve by choosing
.NET, WinUI 3 or Avalonia instead. That removed the main argument against sharing Kotlin code.

**Still unverified:** how it looks. Colour accuracy and perceived smoothness have not been judged by
anyone, and audio/video synchronisation was not tested — the earlier runs had audio disabled to
isolate video cost. A single machine and a single stream were measured.

### First slice built, on 19 August 2026

`:desktop` exists and runs: it signs in against a real provider and browses **all three libraries** —
Live, Movies and Series — by category, playing a channel, a film, or an episode. Series drill into
their episodes and back out again. `:shared` supplies the models, the whole Xtream parser, the URL
normalizer and the stream-URL factory, so nothing about the provider protocol was rewritten.

Two absences are deliberate and load-bearing:

- **No database.** Browsing by category asks for one category at a time — 913 categories, tens of
  channels each — so the six-figure listing that forced streaming and batching on Android is never
  requested. That postpones the persistence question rather than answering it badly, and it is what
  let this slice exist without touching Room or its frozen schema.

  *(Half of this was reversed on 20 August 2026; see **The library, held in memory** below. There is
  still no database — the listing is now requested once per sign-in and kept in memory, and nothing
  about it is written down.)*
- **No credential storage.** Signing in again each launch is honest; Windows has no Keystore
  equivalent, and doing it properly means DPAPI. No credential is written to disk by this module —
  what it does write is the user's own data in the export format, plus two disposable sidecars for
  names and window state, none of which contains an account detail.

Networking is plain OkHttp rather than Retrofit, because Retrofit is the one dependency `:shared`
refuses. That is four requests built by hand against a framework in the shared module that an iOS
target would have to replace anyway.

The player has transport controls: pause and resume, skip back ten and forward thirty seconds, a
seek bar with elapsed and total time, **volume with mute**, and **fullscreen** — with the keyboard
covering all of them (space, left, right, up, down, `M`, `F`, `C`, escape, `F1`, and
`PageUp`/`PageDown` to step through what else is playable), handled at the window so they work
wherever the pointer is. Seeking is refused on anything without a known
length — a live stream reports none, and libvlc answers a seek in one by doing nothing or dropping
the connection, neither of which is a useful reply to a dragged slider. Live shows a **LIVE** marker
where the timeline would be, because there is nothing honest to draw there.

Position is polled twice a second rather than driven by libvlc's events: those arrive on its own
threads and vlcj is explicit that calling back into the player from them is unsafe. While a drag is
in progress the slider follows the pointer instead of the stream, or the poll fights the drag.

**The volume needed two things that are not obvious.** libvlc drops the level with each new media and
refuses to set it before playback is actually running, so there is no single moment to apply it at;
instead the same poll that reads the position puts the chosen level back whenever it has drifted —
one comparison every half second, and a channel change no longer resets the volume. And the level
lives as Compose state on the player object rather than in the screen, because two things change it,
the slider and the keyboard, and a private copy in either would drift the moment the other was used.

**Handling keys at the window had a bug worth recording.** The window sees every key *before* the
focused control does, so with only "signed in" as the guard, the space bar paused the player instead
of typing a space into the filter field — and adding `F` and `M` would have made two more letters
unavailable to type. The playback keys are now gated on something actually being loaded, which is a
flag the player owns. Escape is deliberately the exception: fullscreen has no title bar, and escape
is the key everyone reaches for. Fullscreen also ends when playback does, because a poster grid with
no title bar and no obvious way back is a trap rather than a feature.

**Switching without leaving the picture.** Going back to the grid, finding the category again and
picking the next channel is four actions for something that should be one, and the stream stops in
between. So the control row opens a panel over the video listing what else was on screen when this
started — the category, My list, the guide, or the episodes of a series — and a click switches to it.
The panel deliberately stays open afterwards: zapping is rarely one channel, so the highlight moves
and the list stays walkable at one click each. Each row also says what is on that channel **where the
guide has already been read** — zapping blind is what a channel list without a programme is, and this
is that knowledge appearing where the decision is made rather than only on a page nobody is on. Only
from the cache: fetching here would be a request per row, over the connection the video wants, for a
panel that is open for seconds. Live playback also gets previous and next buttons
where the timeline would be — there is no timeline on a live stream, and stepping through the list is
what that space is for — and `PageUp`/`PageDown` do the same from the keyboard, wrapping at the ends
because a channel list is circular on every television ever made.

That last one needed a wire. Keys are handled at the window so they work wherever the pointer is, but
"the next channel" is a question only the browsing screen can answer — it holds the list. Rather than
lifting the whole queue into the window, the screen leaves a handler in one small shared object and
the window calls it; the handler is cleared when the screen goes away, so it can never answer for a
list that no longer exists.

The list is *carried in* rather than derived, because the same title can be started from a category,
from My list or from the guide, and "what else is there" has a different honest answer each time.
Position is written down before the switch, so a film switched away from is still resumable — a
switch that forgot it would be the worse of the two ways to leave, because nothing visibly stopped
and so nothing looks like it should have been saved.

**One episode leads to the next.** Thirty seconds from the end of an episode a card appears over the
picture with what follows, and at the end it starts by itself. Episodes only: a category is not a
playlist, and rolling from one film into whatever the provider happened to list after it is nobody's
idea of an evening. A series' episode list *is* a playlist, which is the whole distinction.

That needed a rule `:shared` did not have. `isCompleted` answers "is this still worth resuming" and
says yes three minutes before the credits, so resuming does not drop the viewer into them — handing
the next episode over at that moment would cut the last three minutes off every one. So there is now
`hasReachedEnd`, which asks the narrower question with a two-second tolerance, because a provider's
container routinely ends a second short of the duration it advertises and an episode that never quite
ends would never quite hand over. Both are tested against each other.

The same slice closed a gap: a film played from My list was tracked by nothing, because progress was
keyed on how the item was reached rather than on what it is. It resumes now like any other.

Audio and subtitle tracks are selectable from the control row, asked of libvlc when a menu opens
rather than held in state — descriptions exist only once the container has been parsed, so a cached
list would be empty exactly when the viewer first looks. libvlc's own *Disable* entry is left in the
subtitle list, because it carries id -1 and *is* how subtitles are turned off; inventing a separate
"Off" row would describe the same thing twice.

Each browsing column has a filter that uses the shared `SearchTextNormalizer`, so `Mr. Robot` and
`mr robot` match here exactly as they do on the phone. It filters what is already in memory; asking
the provider per keystroke would be absurd, and the filter clears itself when the column's subject
changes rather than hiding a new list behind an old query.

**Watch progress and resume are in**, and the persistence question they forced was answered by not
inventing anything: the client stores the **export format** from `:shared`, in
`%LOCALAPPDATA%\KilluaIPTV\user-data.json`. No schema to design or migrate, a format already built
and tested, and interoperability for nothing — this client's state file *is* an export, so it can be
handed to the phone's Import and a phone export can be dropped in here. The cost is rewriting the
whole file per checkpoint, which at a few thousand rows is a couple of hundred kilobytes every ten
seconds and not worth a database to avoid. Writes go through a temporary file and a rename, because a
half-written state file would be refused on the next launch and take every stored position with it.

The resume position is handed to libvlc as `:start-time` rather than seeked to after playback begins,
so the first frame the viewer sees is the right one instead of the opening seconds and then a jump.
Completion uses the shared `WatchProgressPolicy`, so a title counts as finished at exactly the point
it does on the phone, and a finished one starts over rather than resuming three minutes before the
credits.

**It packages.** `gradlew :desktop:createDistributable` produces a self-contained app image at
`desktop/build/compose/binaries/main/app/Killua IPTV/Killua IPTV.exe`, with its own Java runtime, so
it starts by double-click with no JDK, no `JAVA_HOME` and no Gradle. Verified by launching it with
`JAVA_HOME` explicitly removed from the environment.

**172MB as measured on 20 August 2026**: 95MB of application jars — Skiko and Compose are most of
that — and 77MB of linked runtime. An earlier note here said 136MB, which was either a different
measurement or a smaller dependency set; the figure is now one that was taken rather than
remembered.

An **app image rather than an installer**, deliberately: jpackage builds one from the JDK alone,
while an MSI additionally needs the WiX toolset installed. That is a dependency worth imposing only
once somebody other than the author installs this. The bundled runtime is limited to the modules the
app actually uses, which is what keeps the runtime half at 77MB instead of several times
that.

**VLC is still required and is not bundled.** The client plays through libvlc; a missing one is
reported on screen rather than crashed on.

**Now and next** appear over the picture for a live channel, with a progress bar and the following
programme's start time. `get_short_epg` per channel, never the whole XMLTV file, and fetched *after*
playback has been asked to start — the guide is decoration around the picture, and making the first
frame wait on metadata trades the thing the viewer asked for against one they did not. A failure is
an empty guide rather than an error. Which entry is current comes from the shared `EpgSelection`,
which already knows what to do with the overlapping entries, gaps and stale listings providers send.

### The library, held in memory — 20 August 2026

The owner used the client and reported the consequence of the rule above: *"man muss eine Kategorie
aufmachen damit die filme usw laden aber aufm handy geht das so, da wird einfach alles angezeigt"*.
Two more of the same day's requests had the same root — there was no global search, and no start
screen worth having — and none of the three is reachable one category at a time. `player_api.php`
has no search action, so a search over a library nobody has downloaded is a search over nothing.

So the listing is now asked for **once per sign-in** and held in `LibraryIndex`. What that bought:

- **Opening Movies means all of the films**, as it does on the phone. A category is a way to narrow
  that rather than a toll to pay first, and picking one costs no request at all any more — it is a
  scan over a list already in hand.
- **One search box over all three libraries**, grouped by library rather than merged, with titles
  that *start* with the term ahead of the rest.
- **A start screen**: continue watching, recently added, the viewer's own channels, favourites.
  Recently added uses the shared rule the phone uses — a provider that stamps its whole import with
  one timestamp gets no row at all, because a row ordered by a column where every value is identical
  is an arbitrary slice of the library wearing a label.

What it deliberately did **not** buy: a cache. Nothing is written down, so there is no schema, no
migration and nothing to reconcile — the next launch asks again. A stale library that has to be
reconciled is a database, and that decision has not been made.

Five details are load-bearing:

- **Streamed, through the shared parser.** The same `withMovieSummaries`-style path the phone uses:
  a film listing arrives as tens of megabytes, and holding the JSON and the objects at once doubles
  the worst moment for nothing. The whole-library requests also get an OkHttp client with **no call
  deadline** — a listing that legitimately takes minutes is not a request that has hung — while
  keeping the read timeout, which is the one that catches a provider that has gone quiet.
- **Three independent steps.** A provider that refuses one listing still gives the other two, and
  what failed simply falls back to being browsed by category. A **partial** listing is thrown away
  rather than shown: half a film library looks exactly like a small one, and a viewer would go
  looking for a title that is merely missing.
- **Capped at 250,000 titles per listing.** Not a number any real provider reaches, but the
  difference between a client that says a listing is larger than it can hold and one that runs out
  of memory with no explanation.
- **The index is handed over after every step**, not at the end, which is what makes the progress
  panel skippable rather than something to wait through: Live is whole while the films are arriving.
- **Names are folded once**, when the listing lands. Both the filter box and the search box are then
  a scan over strings that already exist; re-normalizing a hundred thousand titles per keystroke is
  a string builder and a regex each, which is the difference between a filter box and a stutter.
  Typing is debounced by a fifth of a second on top of that, and clearing the box is immediate.

### What "show everything" costs the account's connection limit

The other thing the whole-library read changed is how long a connection is held. A category request
was a second; a listing is minutes. Most Xtream accounts allow **one or two connections at a time**,
so a viewer who presses play while the films are being read is asking their provider for one
connection more than they have — and what fails is either the film or the read, neither of which the
client gets to choose.

So **watching wins**: `loadLibrary` asks whether anything is playing before each listing and waits if
it is, saying so on the panel rather than looking stalled. The question is asked *between* steps and
not during one, because there is no way to pause a response that is already arriving — abandoning it
throws away minutes of reading, and holding it open keeps exactly the connection this is trying to
free.

A film left paused still counts as playing, and rightly: libvlc keeps the socket. The library
resumes when the picture stops, and Settings can always ask for it again.

### What "show everything" costs the provider's image server

**Fixed the same evening it was introduced.** A poster grid that used to hold one category of a few
hundred now holds the whole library, and that changes what *scrolling* costs. A lazy grid only
composes what is on screen, so the number of tiles was never the danger — the danger is that every
tile which passes starts a fetch, and a synchronous HTTP call does not stop because the coroutine
around it was cancelled. A flick through ten thousand posters left the provider serving pictures
nobody would ever see.

Three things now stand between a flick and that:

- **A tile has to sit still for 180ms** before its picture is asked for — the same rule the guide
  uses for its programme requests. Scrolling past cancels the effect long before that elapses.
- **Eight fetches at a time**, and acquiring the permit is a suspension point, which is the half
  that matters: a tile that has already scrolled away is cancelled *while queueing* and never asks.
- **The call is cancelled when its coroutine ends**, so one that did start is abandoned rather than
  read to the end.

`ArtworkLoaderTest` holds all of it against a local server that counts what is in its hands at once:
forty posters at once never put more than eight there, a poster already fetched is not fetched
again, and one that is missing is not asked for twice — a 404 is extremely common on this kind of
provider, and retrying it on every scroll would be a storm over something nobody can fix.

The same tests are the reason `:desktop:test` now runs with `LOCALAPPDATA` pointed at a build
directory: a test that exercises the artwork store for real would otherwise write into
`%LOCALAPPDATA%\KilluaIPTV`, beside someone's actual watch history.

### The language filter, which the phone had and this did not

**Done 20 August 2026.** On a provider that files everything as `DE | …`, `EN | …` and `FR | …`, a
library without a language filter is three libraries in a heap. The phone has had one since alpha 7;
the desktop could not, because filtering by language means reading every title, and the desktop only
ever held one category at a time. With the listing in memory it is one pass.

The rule is `:shared`'s `XtreamLanguageTagger` and the **order is the phone's**: the category name
first, the title's own prefix second. That order is the whole value — a channel called `Sky Sport`
filed under `DE | SPORT` is German, and one called `FR | Sky Sport` on the same shelf is not.
Answering from either source alone misses one of those, which is what `LanguageTagTest` holds shut.

Three details worth keeping:

- **The category names come from the picker's own request.** A listing gives every title a category
  *id* and nothing else, so nothing else could have supplied them.
- **The menu appears only when the listing offers more than one language**, and never inside a
  series — every episode of one is in the language the series is.
- **Unlabelled titles are never matched by a filter**, rather than being guessed into one. A
  heuristic that hides what it was unsure about is worse than one that leaves it in.

Both the tagging and the filtering happen on the same background pass as the ordering, for the same
reason: it is a string scan over a six-figure list.

### What it costs, measured

Against `tools/fake-provider.ps1` at the reference provider's size — 180,000 films and 60,000
channels of realistic field lengths, about 96MB of JSON, which is the payload that once exhausted
the phone's heap — on the owner's machine:

| | |
| --- | --- |
| Reading all three listings, streamed and parsed | **4.6–5.1s** over loopback |
| Heap while reading | 276–403MB peak |
| Heap once settled | **187MB** |
| Opening a category of 4,500 films | **12ms**, and no request |
| Searching all three libraries | **16–24ms** |
| Filtering the whole 180,000-film library | **24–39ms** |
| Building the recently-added row | 16–31ms |
| Turning 180,000 films into rows | 19–26ms |
| Ordering 180,000 films by name | **732–768ms** |

Everything on that list is comfortably inside a frame except the last one, and that is why the
narrowing and the ordering happen **off the composition thread**. Measured with a tick on the UI
dispatcher, the same sort costs a **48ms** worst gap when it is done in the background and an
**883ms** one when it is not — three-quarters of a second of a window that does not redraw, at
exactly the moment a viewer is watching to see their sort order take effect. The previous list stays
on screen while the next is built.

The name sort is the expensive one because the shared rule folds a sort key per title rather than
comparing display names — which is deliberate, and the reason a category comes out in the same order
here as on the phone. Precomputing those keys is the obvious next optimisation and has not been
done: it would mean a second implementation of a rule that exists in one place on purpose.

The progress panel is the phone's post-login sync screen for the same reason the phone has one: a
provider with six figures of titles takes minutes, and a client that spends them on an empty screen
has not said whether it is working, broken or finished. Counts that climb are the cheapest possible
proof that something is happening, and **skipping is always offered**, because everything it fetches
is an improvement on browsing by category rather than a precondition for it.

### How the desktop client is laid out

The first version was three flat columns of text with the video squeezed into the last one. It was
reworked once there was enough of it to judge:

- **A navigation rail** down the left for the three libraries — with the guide, My list and
  Settings below them — and the account at its foot. The
  window is wide; vertical space is what the content needs, and three icons read faster down the side
  than as text along the top.
- **The stored state is arranged for the question the grid asks.** Every tile asks three things —
  is it hearted, is it saved, how far through it am I — once per item, on every recomposition.
  Answered against the stored lists directly, each was a scan: a category of two hundred against a
  few hundred watched rows is tens of thousands of comparisons for one frame, growing with how much
  someone has watched, which is the worst way for a slowdown to arrive. Three lookups are built when
  the state changes instead.
- **The category menu builds two hundred rows, not nine hundred.** A dropdown composes its whole
  content at once — it is a column with a scrollbar rather than a lazy list — so every category was
  being measured and laid out on the click that opened the menu, on the provider where that number is
  913. A lazy list cannot be put inside one either: the menu measures its content with intrinsic
  width, and subcomposition does not support that. So the menu is capped and says how many it did not
  show. Nothing becomes unreachable: the field at its top narrows, and typing outside it offers
  matching categories as chips.
- **The category is a dropdown, not a column.** This provider has over nine hundred live categories,
  and a column that long spent most of the window listing categories nobody was looking at. The menu
  carries its own filter, because finding one category among nine hundred is searching rather than
  browsing.
- **Search is a search for categories**, which is the only kind this provider can answer.
  `player_api.php` has no search action, and the library one would have to search is six figures of
  titles — the one request this client refuses to make. What it does have in hand is the category
  list, and on a provider organised as `DE | SPORT` and `UK | MOVIES 4K` that list is how anyone
  finds anything. So typing offers the matching categories as chips above the content, one click
  opens one, and the word stays to filter what is inside it. Two answers to one gesture.
- **Posters.** Movies and Series are a poster grid; the artwork URLs were in the models from the
  first day and simply unused. Channels and episodes stay as rows, where a logo or a still is a
  detail rather than the subject. Artwork is fetched by a small bounded loader — a few hundred images
  kept, the rest dropped — because an unbounded map would be a slow leak that only shows after an
  hour of browsing, and a failed URL is remembered so a broken poster is not re-fetched on every
  scroll.
- **The player takes the whole content area** when something is playing, with its controls drawn
  *on* the picture. That is what the rendering approach was chosen for: the video is ordinary Compose
  content, so an overlay is just Compose. A permanently squeezed third column gave 4K video less room
  than the list of things not being watched.

**Favourites, a saved list and continue-watching** live under a fourth rail destination, **My list**.
A heart and a bookmark sit on every poster and every row, always visible rather than revealed on
hover — a mark nobody can see is a mark set twice by accident.

The marks are written into the same stored export, so a film hearted on the desktop arrives on the
phone through Import. The editing rules are in `:shared` with tests, including one that matters for
ordering: re-marking something keeps its **original** timestamp rather than refreshing it, because
the stamp records when it was first marked and a "recently saved" list should not reshuffle itself.

**Closing the window writes where you were.** Positions are checkpointed every ten seconds while
something plays, which is the right answer for a crash and the wrong one for the ordinary way an
evening ends: someone closes the window, and the last thing they watched is up to ten seconds behind
— or, for a film started a minute ago, has no position at all. The close now writes it, and waits,
because a coroutine launched into a scope that is being torn down finishes nowhere. It is the same
argument the preferences already made, so the two are written together in one blocking moment.

The window title says what is playing while it plays, which is what someone alt-tabbing back to it
is looking for.

**Import says what it did.** It used to merge in silence, and every other outcome was silent too: a
file that was not an export, a file belonging to another account, a file that added nothing. Picking
a file and seeing nothing happen is indistinguishable from the client being broken — and the one
message that matters most, *that file belongs to a different account*, was the one nobody ever saw.

The three outcomes are now planned **before** anything is written and named afterwards: *Imported N
entries*, *Nothing new in that file*, *That file belongs to a different account*, *That file is not
an export*. Merging first and reporting after would make the second and third indistinguishable,
which is the whole reason the phone plans before it applies. That planning is a pure function on two
exports, so unlike the phone's — which assembles it from a database — it lives in `:shared` with
tests. Export reports too: a silent write failure is a viewer who thinks their evening is backed up.

The desktop also stops reading a chosen file without a ceiling. Thirty-two megabytes, the same limit
the phone uses: an export of a large library is a few hundred kilobytes, and anything past that is a
file somebody picked by mistake.

**What was marked on the phone is looked up rather than left out.** The stored state carries
provider ids and nothing else — deliberately, because it is the export format — so a film hearted on
the phone arrived here as the number 501 and could not be shown at all. The honest answer used to be
to leave it out until it happened to be browsed. The better honest answer is to ask: opening My list
fetches the records for what is marked but unnamed, four at a time, bounded at forty, into the same
title cache that browsing fills. A film also arrives with its container, so it is playable here
without first being found in its category.

Two things are still invisible until browsed, and the reason is the format rather than laziness. An
**episode** is identified in the export by its own id, and no endpoint turns an episode id into
anything without knowing its series first. A **channel** would need the whole live listing, which is
the one request this client refuses to make. That is the price of a format that carries ids and no
names, and it buys the interchange the whole scheme rests on.

**Something can be marked watched by hand**, which is the only way to tell the client about an
evening it was not present for — a title seen on another device, in another client, on television
years ago. Without it the list keeps offering something already finished, and no amount of watching
it here fixes that. The reverse is there too: forgetting that something was watched removes the row
rather than zeroing it, because a position of zero is a title someone started and stopped at once,
which is a different claim.

The mark is offered **only where a duration is known** — an episode carries one in the listing, a
film once its record has been fetched, which is why it sits on the film's panel rather than on its
poster. Nothing is invented where it is unknown: the format refuses a progress row without a
duration, and a made-up one would travel to the phone and be believed there.

The two rules that were already doing this work for real viewings, `withProgress` and
`resumePositionOf`, moved from `:desktop` into `:shared` on the same trip. They were always pure
rules about the shared format, and the round-trip test had been duplicating one of them by hand,
which is the usual sign that something is living in the wrong module.

**A list says what has been watched.** The stored state knew all along; the lists simply were not
asking. An episode row now carries a bar where the viewer stopped, and a dimmed title where they
effectively finished — two different questions ("where was I", "which of these have I seen") that
want two different answers. Poster grids carry the same bar, so a category shows which films are
already started.

The threshold for "seen" is deliberately *not* the completion policy. That one answers whether to
resume and says yes at ninety-three percent so a resume does not land in the credits; this one
answers what to draw, and a progress bar that is a sliver from the end tells a viewer less than a
dimmed title does.

The player also names the series now. An episode's own label is `S2 E7 · Title`, which says nothing
about the show once the list behind it is gone — and autoplay means it is gone for hours at a time.

**My list says why each title is in it.** It used to be one heap — continue-watching, hearts and
bookmarks concatenated and de-duplicated — so nothing on the screen answered the question a saved
list exists to answer. Three rows with three headings now: one is unfinished, one is a decision, one
is an intention. Rows rather than a grid, because this page is read by glancing down it and a grid
would push the second and third off the screen behind the first.

A tile in the unfinished row can also be **dismissed**. A film abandoned twenty minutes in otherwise
sits there for good, and both ways out were lies: finishing something you did not want, or marking as
watched something you did not watch. The cross forgets the position and nothing else — the rule for
that has been in `:shared` since marking by hand arrived, and it removes the row rather than zeroing
it, because a position of zero is a title someone started and stopped at once.

That also gave the unfinished row somewhere to put how far it got: a progress bar under the poster,
which a flat list had no place for. A section with nothing in it is left out entirely rather than
drawn empty — an empty heading is a reproach, and the client has no business telling anyone they have
not marked enough. Typing in the search box collapses all three back into one grid of results, since
a search is asking about titles rather than about why they were kept.

**A series is browsed by season now.** `get_series_info` answers with every episode of every season
at once, so opening a long-running show meant two hundred undifferentiated rows. There is a season
picker when there is more than one season — a picker over a single choice is furniture — and the
client opens on the season the viewer was last in rather than on the pilot. That rule is in
`:shared` with tests: a completed episode still counts, because finishing a season means the next
thing to watch is one row further down, and on the last season staying put beats being sent back to
the beginning.

Two details that are easy to get wrong and were: **searching inside a series looks across all
seasons**, since someone typing a title is not thinking about which season it is in; and **the
playback queue is still every episode**, not the season on screen, so the last episode of a season
leads into the next one. The provider already sent them all — the season picker is a view, not a
fetch.

**A stream that would not open was a black rectangle with working controls.** A dead channel, an
account at its connection limit and a title the provider no longer has all looked exactly like a
picture that had not arrived yet. There are two silences now, and the client distinguishes them:
libvlc says so itself when it gives up, which is reported at once, and when it says nothing and no
frame comes either, the wait gets twenty seconds — long enough for a slow provider opening a 4K
stream, short enough that nobody sits there wondering — before the client admits it does not know
which of the two it is, in those words.

Neither case stops the player. It keeps trying underneath, so a picture that arrives late clears the
message by itself. *Try again* is offered where asking again can change the answer, and deliberately
not for a missing libvlc, where it cannot.

The flag is set from libvlc's own event thread, and only ever assigned there: vlcj is explicit that
calling back into the player from an event handler can deadlock, so the handler touches nothing but a
boolean and the polling side decides what it means.

**A bookmark set on the desktop was being dropped on the phone**, and nothing anywhere failed when
it happened. The export joins the two clients by *string* — a saved row says `movie`, a progress row
says `episode` — and the desktop wrote `live` for a saved channel while the phone had written
`channel` since long before this format existed. The phone drops a watchlist row whose type it does
not recognise, which is the safe reading for a stored value it cannot open and exactly why the loss
was silent. It went the other way too: a channel saved on the phone was invisible on the desktop.

The word is corrected rather than translated on read, because no desktop build has been distributed
and no file in that shape exists outside this repository. The phone now takes all three words from
`:shared` instead of repeating them, and a test in `:app` pins both halves — that the two
vocabularies are equal, and that they are equal to the literal strings already sitting in every
viewer's database, since renaming both sides together would be just as wrong and just as quiet.

A round-trip test in `:shared` now covers the journey rather than the pieces: an evening of marks
written, read, merged into a device that has none, merged again to no effect, and carried back
without dragging a position backwards.

**Keyboard browsing was half there and invisible.** Compose's `clickable` already makes a tile
focusable and already accepts enter and space once it has focus, so arrow keys traversed the poster
grid from the day it was written — nothing showed *which* tile they were on, which makes a working
feature indistinguishable from a broken one. Focus is drawn now: a violet ring on a tile, a violet
wash on a row. And `Ctrl+F` puts the caret in the search box, deliberately as a modifier combination
rather than a bare `/`, because the window sees every key before the focused control and a bare slash
would be a search box that cannot contain a slash.

That handler is registered while the browsing column is on show and taken back when it goes. A focus
requester whose field has left the composition throws when it is asked for focus, so leaving it
behind would have turned `Ctrl+F` on the settings screen into a crash rather than a no-op. The wire
it travels on is the same one the channel keys use, now named for what it is: `ScreenKeys`.

**A record is fetched once.** Opening a film's panel asked the provider for `get_vod_info` every
time, including for a film whose panel had just been closed, and opening a series re-downloaded every
episode of it. Both are one small answer per title that does not change between one opening and the
next, so they are held in memory for half an hour — bounded, because a session that walks a large
category would otherwise keep everything it passed, and expiring, because a provider that corrects a
title should not need a restart to be believed.

That is not a retreat from "no cache": the thing this client refuses to cache is the **listing**, six
figures of it, which is what a database would have been for. One record per title someone actually
looked at is the opposite kind of object.

**The guide stopped asking twice.** Every visit to it made all forty requests again, and every
channel played made one more for the strip over the picture — for listings that cover hours and had
just been fetched. They are held in memory now, and validity is two rules rather than a timer: a
listing expires after an hour, so a provider correcting its schedule shows up without a restart, and
it expires the moment its last programme has ended, because at that point it has stopped describing
anything current no matter how recently it arrived. Whichever comes first wins.

Returning to the guide now paints from what is still current before anything is asked for, and only
the gaps are fetched. Empty answers are cached too — a channel with no guide at all is common here,
and re-asking for nothing forty times a visit is the behaviour this exists to prevent — which is why
*Refresh* clears the cache outright rather than merely redrawing the same thing.

**The client belongs to one account at a time**, and signing out now says so properly. Two things
cached by provider id were not scoped to an account: the title cache, and the category the client
reopens. Providers number each library from one, so film 501 is a different film on a different
account — an unscoped title cache captions one account's saved list with another's names, and a
remembered category id opens whatever happens to be numbered 42 over there. Both carry the export
format's own one-way fingerprint now, and both read as empty for anyone else. Everything else in the
preferences — destination, order, volume, window — is about the client rather than the library and
stays. Signing out also drops the artwork held in memory, since one account's posters have no
business on the next one's screen; the files stay, because they are keyed by URL and a URL belongs to
whoever asks for it.

One test was quietly claiming more than it checked, and now says what it means: the sidecars may hold
provider ids, title names, artwork URLs and window furniture, and may never hold a username or a
password. It deliberately does **not** claim that no host appears — an artwork URL is stored as the
provider gave it, and some providers serve posters from their own host. That is accepted because this
file never leaves the machine, unlike the export, which carries no host at all.

**Posters survive a restart.** The memory cache holds a few hundred images and dies with the
process, so every launch re-fetched the same grid over the same connection the video wants. There is
a disk half now: a third disposable sidecar, in the same directory and under the same rule — deleting
it costs a few seconds of re-fetching and nothing else. Settings shows what it occupies and offers to
clear it, which is what "disposable" ought to mean in practice rather than only in a comment.

Two details are deliberate. **A file is named by the hash of its URL**, because provider artwork is
sometimes served from the provider's own host and a directory listing full of readable URLs would put
that host on disk in plain text for no reason; a test asserts the host does not appear. And **the
clock is injected**, because eviction is by least recently *read* and file timestamps have neither
the resolution nor the guarantees to tell two reads microseconds apart from each other — with the
clock handed in, "least recently read" becomes something a test can state rather than race against.

**Browsing order** is a menu beside the search box, and the options differ per library because the
provider's listing does: a channel carries no rating, no year and no added date, so offering to sort
by them would be offering to sort by nothing. The rules live in `:shared` — Android orders in SQL
because it pages over a cached library, the desktop holds one category in memory and orders it there,
and what has to agree is the rule rather than the implementation. Two of them run through every
option: **missing values sort last** rather than as zero, because a film with no rating is not a film
rated 0.0; and **ties break on the provider's own order**, or two equally rated titles would swap
places between visits and the list would read as one that will not sit still. Eight tests, including
one that no ordering loses or invents an entry.

Episodes are never reordered — a series lists them in the order they were made — and neither is My
list, which is already in the order it was assembled. The choice is remembered per library by enum
*name* rather than position, so reordering the enum later cannot silently turn "top rated" into
"recently added" on someone's next launch.

**A film is read about before it is watched.** A poster used to start playing on the first click,
which is right for a channel and wrong for two hours. Clicking one now opens its record — poster,
year, rating, runtime, genre, plot, cast and director — with *Play*, or *Resume from 41:12* and a
*Start over* beside it, and the heart and bookmark where the decision is actually being made.
Channels and episodes still play on the first click: one is zapped to, and the other was chosen when
the series was.

Everything except the plot comes from the listing already in hand, so the panel is complete the
instant it opens and the fetched record only fills in the paragraph. If that request fails the panel
still plays. Metadata is what someone reads before deciding, not a precondition for watching, and a
client that refused to play a film because its plot did not arrive would have the priorities
backwards. Opening a series now shows its plot above the episodes too, which costs nothing: the
episode request already carried it and the client was throwing it away.

**The persistence is tested now**, which it was not while it was being built. Nineteen tests in
`:desktop`, and they are about the promises rather than the plumbing: a file belonging to another
account reads as empty and carries the *new* fingerprint, so signing in as someone else neither
reveals the previous account's history nor adopts it; a damaged file reads as empty rather than
throwing; saving twice replaces the file, which is the Windows rename fallback and without it every
position after the first would be silently dropped; a position with no duration is not stored at all,
because a live stream reports none and "sixty seconds into nothing" would offer to resume a channel
tomorrow; and what the client writes is still an export the shared codec can read, which is the whole
reason it is stored in that format.

One of them checks that neither sidecar contains a server address or a username. It is not a proof,
but it pins the shape of two files that nothing else guards.

`:desktop:test` is part of the gate from here on. It also compiles the desktop sources, which
nothing else in the gate did.

**A refusal puts the caret where the mistake is.** A provider saying no is about the credentials, an
unreachable host is about the address, and the client knows which it got — so the next keystroke goes
to the password field or the server field rather than wherever the pointer happened to leave it. The
password text is left alone: the mistake may have been the username, and clearing something nobody
has said is wrong makes the retry longer than the error was. The screen also says what a server
address looks like now, which is the one thing a first run cannot guess.

**The sign-in form can be typed into.** The caret starts in the first field, which matters more here
than in most applications: this client signs in every launch by design, so a window that opens with
the caret nowhere is a form to be aimed at, every time. The password field can also be revealed —
off by default and never remembered, because a shown password is a decision for one moment rather
than a setting, and because a mistyped one otherwise costs a round trip to the provider to discover.

**Signing in tells the truth now.** The screen had gone untouched since the first day and had three
faults. The cleartext warning was set and the screen left in the same breath, so the one message on
it that is actually about the viewer's credentials was never once read — it is a step now, with what
HTTP means spelled out and a *Continue anyway* to press, which is the same rule Android follows.
Enter did nothing, in a form of three fields and one button. And the footer said "Nothing is written
to disk", which stopped being true the moment marks were stored and is a careless thing for a client
to claim about someone's data; it now says which part is never written and which part is.

**A refusal is not a fault** — and the sign-in screen had the same confusion one screen earlier. A
provider that answers 401 was reported as *the server could not be reached*, of a server that had
just answered; a wrong password sent the viewer to look at their network. Sign-in now catches the
refusal **before** the input-failure branch, which is the whole distinction: a refusal is an
`IOException` too, so the order of the catch blocks is what separates "it said no" from "it said
nothing". Most providers actually answer `200` with `auth: 0`, which was already handled; this is
the other half.

**Browsing had it too.** An account that has expired, been disabled, or run out of connections
answers 401 or 403, and every one of those was being reported as "that library could not be loaded"
— which sends someone to look at their network for a problem that is on their bill. The client now
separates the two, and says what happened.

It deliberately does **not** guess which refusal it is. Expiry, a disabled account and too many
connections all arrive the same way; naming one of them would send the viewer to the wrong place
three times out of four. So the message names the account and lists what it might be, which is the
most that can honestly be said from a status code.

**A failure now has a way out of it.** Three things were wrong at once. A library that failed to
load put the viewer on the player's error screen, whose back button did not clear the error, so the
only escape was to change destination. A category that failed said "This category is empty" — a
different claim from "the request failed", and the wrong one to make about someone's provider. And
nothing anywhere offered to try again.

Loading failures are a banner above the content now, with the retry carried alongside the message,
because only the thing that failed knows how to ask again: a library, one category and one series are
three different requests, and a button saying "try again" has to mean the one that just did not work.
The rail keeps working, what is already loaded stays, and the empty hint underneath goes quiet rather
than contradicting the banner above it.

**The client comes back where it was left.** Which destination was open, which category was open in
each of them, the volume and whether it was muted, and the window's size. Not in `user-data.json`:
that file is the export format, byte-for-byte interchangeable with the phone's, and "which tab was
open on the Windows machine" is not something the phone should be handed or asked to preserve. It is
a second sidecar in the same spirit as the title cache — local, disposable, no credentials, and the
category remembered by its provider id rather than its name, which is read back out of the listing
like any other.

Writing it is debounced by most of a second, because dragging a volume slider or a window edge
produces a value every frame and none of the ones in between is worth a file. Closing the window
writes it outright, since at that moment the debounce is the only thing between a change and being
forgotten.

**The one thing that needed inventing** was a place to keep names. The stored state holds provider
ids only, deliberately, because it is the export format and it has to stay interchangeable with the
phone. So a title's name and poster go in a small cache *beside* it, `titles.json`, holding only what
has been marked or played — indexing everything browsed would grow without limit across a six-figure
library. That file can be deleted at any time; the cost is a few captions missing until those titles
are seen again. A film or series marked on the phone and never opened here is looked up when My list
is opened — see *What was marked on the phone is looked up* below — and an episode or channel stays
out until it is browsed, which is what a format carrying ids and no names costs.

**An expiring account says so where browsing happens.** The expiry is the one piece of account state
with a deadline attached — everything else a provider reports is a fact about now, while this becomes
a problem on a date — and it was only ever on the settings screen, which is read once when the
account is new and the date is far away. Inside a week, a quiet line appears above the content, and
it can be put away for the session.

Deliberately quiet, and deliberately without a button: nothing in this client can renew anything, so
it is a sentence rather than an action, and someone told twice a day about something they cannot act
on has been given a decoration instead of a warning. The rule — how long is left, and whether that is
worth the space — is in `:shared` with tests, including that days round **down**, so "in 1 day" never
means "in a few hours". The settings row now carries the same reading beside the date, because a date
on its own does not say whether it is next week.

**Settings** are a fifth rail destination: which account is signed in and its status, how many
entries are stored and where, whether libvlc was found, and sign out. The server address and username
are deliberately **not** shown — they are the account, they are secrets, and a settings screen that
prints them is one that ends up in a screenshot.

**Export and import are there too**, through the platform's own file dialog, which is why this client
needs no permission to write anywhere and never decides where a viewer's file goes. Import refuses a
file whose fingerprint belongs to another account, exactly as the phone refuses it, and merges with
the same newest-wins rule. `UserDataExport.mergedWith` in `:shared` holds that rule for whole files
and is tested, including that merging the same file twice changes nothing the second time.

With that, the loop is closed in both directions: mark or watch something on either device, carry one
file, and the other agrees.

**The guide** is a sixth destination, and the interesting thing about it is what it is *not*. Xtream
answers the programme one channel at a time — `get_short_epg` takes a single stream id — so a grid
over the whole library is not a layout problem but a request problem: this provider carries six
figures of channels, and asking for all of them is six figures of requests nobody would wait for. So
the guide covers the channels this viewer actually keeps: saved first, then recently watched, capped
at forty. That rule is `ownChannels` in `:shared`, with tests, because it is about the format rather
than about this window.

**Live opens on the channels you actually watch.** A provider with nine hundred categories and no
starting point is a wall, and the client was showing one: an empty area and an instruction. The first
time Live is entered after a launch it now lists the same channels the guide covers — saved first,
then recently watched — which costs no request at all, since the ids are in the stored state and the
names in the title cache. Each row says what is on where the guide has been read.

Coming back to Live later in the same session still restores the category that was open, because then
it is where the viewer just was. The rule is that what someone returns to on a television is a
channel, and what they return to in a film library is a shelf — so Movies and Series restore theirs
on launch as before. Somebody who has watched nothing yet gets the picker and a sentence, which is
the honest state of a client that has not been used.

**The client has its own icon**, in the window, the taskbar and the alt-tab strip, and in the
packaged application: a violet tile with a play mark cut out of it, in the same palette `:shared`
hands the Android app. Without it the client wore the Java coffee cup, which says nothing about what
it is and everything about how it was built.

It is drawn by a script in `desktop/tools/` rather than pasted in as a binary nobody can change: no
image library is available here and none is needed, since a PNG is a handful of chunks and zlib is in
the standard library. Everything is rendered at four times the size and averaged down, which is what
gives the corners and the triangle clean edges at sixteen pixels, and the `.ico` carries five sizes
so Windows picks one rather than scaling a single bitmap into mush.

**The guide is a timeline now**, not a pair of columns. Each channel's few hours are laid out in
proportion to time, so a programme's width is its length: an hour looks like an hour, and a glance
shows that one channel is in the middle of a film while another has three things in the same span.
One shared window across every row, three hours wide, starting at the last half hour, with the clock
drawn straight down the page — without that line the blocks are only relatively placed and a viewer
has to work out where "now" is from whichever one happens to be tinted. The current programme fills
up rather than carrying a separate bar, so it is one thing on screen instead of two.

The window follows the clock rather than being draggable, and that is a data decision rather than a
missing feature: `get_short_epg` returns eight entries, a handful of hours, and a timeline that can
be dragged past the end of its data is a timeline that mostly shows nothing.

A row **opens** as well as plays. Clicking a block in the timeline reads that programme — its
description, its times, and the rest of the channel's listing with it highlighted — while the row
around the strip still plays the channel, and the chevron opens on whatever is on now. A block is a
programme to read about; the row around it is a channel to watch, and an inner click target takes the
press so the two never both fire.

That trade needed closing, because a viewer clicking the timeline might well have meant *play*: the
opened detail carries a **Watch this channel** button, so watching is one labelled click from
wherever reading left them. Nothing there offers to start a programme that has not begun — the
channel is what there is to watch, and pretending otherwise would be a button that cannot work. That costs no extra request — `get_short_epg` is asked for eight
entries and the strip uses two, so the other six were already in hand and simply unread. One row is
open at a time, because forty rows unfolded is a wall of text rather than a guide, and the
description is left out entirely when the provider did not supply one: a heading with nothing under
it says less than no heading at all.

**A row can be dropped.** The guide is built from what was watched, and two seconds on the wrong
channel is enough to join it — something assembled from occurrences needs a way to say "not that
one". The cross at the end of a row removes it, and removes whatever put it there: the visit is
forgotten, and a bookmark is taken back with it, because a row that reappears immediately is a button
that lied. The rule that forgets a visit is in `:shared` and deliberately leaves the saved list alone
on its own; it is the guide that decides to undo both, since that is what its cross is for.

Two consequences worth naming. A channel counts as watched only after **two seconds of real
playback**, so clicking through a category does not fill the list with channels nobody stayed on —
and unlike a favourite, re-watching one *does* refresh its timestamp, because this list is ordered by
last use rather than by when a decision was made. And the rows are fetched four at a time, merged in
as each batch lands, so the page fills from the top instead of staying blank until the last request
returns.

The same slice fixed a real defect: a channel bookmarked into My list was played by id alone, and the
URL builder had no branch for that, so it built a *series* URL for a live stream. Live now has its
branch, and `:shared` gained the one-line rule it needs — with no channel listing loaded, the
account's advertised formats alone decide between `m3u8` and `ts`, which is exactly the answer the
channel-aware function already gave in that case. A test pins the two to each other.

**The keys became one list rather than three.** They existed in the `when` block that runs, a
paragraph in Settings, and `docs/PLAYER.md`, with nothing checking any of them against the others —
and the copy a viewer actually reads was the one furthest from the code. A list of keys that lies is
worse than no list: the key does nothing, and now the rest of the list is suspect too.

So `Shortcut` is the list, as data. The window dispatches over it with a `when` the compiler requires
to be exhaustive, which turns "added a key and forgot to make it do anything" into a build failure.
Settings renders the same entries in a **Keyboard** card, and `F1` puts them over whatever is on
screen — both are needed, because Settings is where someone looks to find out what a program can do
and fullscreen playback has no Settings to walk to.

Two things fell out of doing it. Matching now compares the modifier rather than tolerating it, so
`Ctrl+F` and `F` are kept apart by their definition instead of by their order in a `when`, and
`Ctrl+M` no longer mutes — a modifier the client does not claim belongs to the control that might.
And the rule is finally testable away from a window: `KeyboardShortcutsTest` pins that a playing key
is not claimed while nothing plays, which is the exact bug this client once shipped, when the space
bar paused the player instead of reaching the filter field.

The list also gained `C`, for the fill/fit toggle added the round before. It is VLC's crop key, which
is where anyone reaching for this already learned it.

**The keyboard can now be seen as well as used.** `Modifier.clickable` makes a control focusable
whether anyone intended it or not, so tab and the arrow keys already reached every control in this
client — the rail, the menus, the chips, the marks, the buttons, the guide's rows. What none of them
did was show it. That had been found once already, at the poster grid, whose comment says exactly
what was wrong: the half a viewer can see was missing. It was fixed there, and at the list row, and
nowhere else.

`Modifier.focusRing` is that fix written once, and applied to everything that can be tabbed to. The
border draws inside the bounds and never changes a layout, so nothing moved.

One place could not take it at first: a programme block inside the guide's timeline already draws a
violet border for the programme being read, and a second violet ring a pixel thicker would have said
the same thing twice while meaning something else. That was named rather than half-solved, and the
next slice solved it properly — **by giving focus a colour of its own**.

Violet already had a job in this client: it is *chosen*, the open section, the mark that is set, the
programme being read. Focus is not a choice, it is where the next key will land, and the guide's
timeline is where the two provably collide. Cyan had been the scheme's `secondary` since the first
day and had never once been drawn, so it was the only colour left that could be given a meaning
without taking one away — and nothing in the client reads `secondary`, so the rule *cyan means the
keyboard, violet means chosen* holds everywhere rather than mostly. The poster border and the list
row's wash moved to it too, so focus does not mean two colours depending on where you are.

The **order** the keyboard walks in was checked afterwards and needed nothing: Compose traverses in
composition order, the rail is composed before the content and drawn to its left, and the header
composes its menus and search box in the order they appear across it. Layout order and tab order are
already the same thing here. Recorded because "checked and correct" is worth as much as a fix, and
because the next person to wonder should not have to look again.

What that check *did* turn up is in the key list's own overlay. Its scrim took clicks through
`clickable`, which made a rectangle the size of the window a tab stop — invisible, and the first one
anything would land on. It takes them through `pointerInput` now, which is not focusable, and
**Close** claims the focus as the panel opens, so enter closes the panel without tabbing anywhere
first. Tab from there still reaches the controls behind the scrim: Compose can hold focus inside a
subtree, but every way of doing it changes how key events reach the window, where escape and `F1` are
handled, and that is not a thing to change while it cannot be run. One control and four ways out
makes the leak untidy rather than a trap, so it is written down instead of guessed at.

The same pass caught two dismiss buttons on the banners that the first sweep had missed.

There is no test with any of these slices, and there is nothing honest to test: a modifier, a colour
and a focus request have no branch a unit test can see that a screenshot would not tell better. The
gate ran to prove nothing else broke.

**A paused film stopped rewriting the state file.** The desktop checkpoints a position every ten
seconds, which is right, and it did so whether or not anything had changed, which is not: a film left
paused rewrote the whole user-data file every ten seconds, and recomposed the screen each time, to
record that nothing had happened. Over a two-hour film left running that is several hundred writes of
a file whose contents did not move.

The interesting part is that this was not a new rule to invent. The Android app has refused a
repeated checkpoint since it had one — `WatchProgressWriter` drops a checkpoint equal to the last —
and the desktop never inherited it when it got its own loop. The rule now lives in `:shared` as
`WatchProgressPolicy.isWorthWriting`, where both clients can reach it, slightly stronger than the
phone's exact equality because libvlc's clock inches forward while a stream stalls: a second of
movement in either direction, since seeking backwards moves the position as truly as playing forwards
does, and a change of duration counts on its own because that is what a progress bar divides by.

The immediate writes — going back, switching title, closing the window — stay unconditional. They are
the last chance a position gets, and a write skipped there because it resembles the one before it is
a write that never happens at all.

**A stream that dies mid-film now says so.** The client distinguished two silences and both were
about opening a stream; the branch that means "all is well" tests whether the player is playing or
has a position at all, and once one frame has arrived that is true for the rest of the film. So every
later failure was invisible by construction. A provider dropping the connection forty minutes in gave
a frozen frame, working controls and no explanation — exactly the complaint the opening messages were
written to fix, arriving at a different moment.

`StallWatch` is the test, and it is deliberately narrow: the player claims to be playing while its
clock stands still. A paused player is not playing, so it can never be called stalled; nor can one
that has not started. What remains is a stream that has died or one rebuffering, and the tolerance is
what separates them — fifteen seconds, far longer than any rebuffer and far shorter than a viewer's
patience with a picture that has stopped. Six tests pin it, including the two that matter most: a
pause does not bank evidence towards a stall, and a picture coming back starts the count again rather
than carrying the old standstill forward.

The same check cleared the other two write paths it set out to look at. The preferences are already
`distinctUntilChanged` behind an 800ms debounce, and the title index refuses to ask twice for a title
it could not resolve and refuses to save when it found nothing. Neither needed anything.

**An abandoned request stopped reporting itself as a fault.** `runCatching` catches `Throwable`,
and a cancelled coroutine is delivered as one, so every request in the browsing screen treated "the
viewer moved on" as "this went wrong". Two consequences, neither small: the abandoned coroutine woke
up, wrote a failure banner and an empty list *over* whatever the new request had since put there —
cancellation arrives when the old coroutine is next scheduled, not before, so clicking through the
rail quickly could answer "that library could not be loaded" about a library that was loading
perfectly well — and the body carried on past the cancellation, which is the thing structured
concurrency exists to prevent.

The Android app has caught this by hand at every one of its twenty-odd boundaries since it had them,
and the project's own rules say to. The desktop simply never inherited it.
`catchingExceptCancellation` is that rule once, at the five places that can actually suspend; the
file reads keep `runCatching`, because there is no suspension point in them for a cancellation to
arrive at and `runCatching` there is saying something true.

**And the two sides now tell the same story about the same cause.** A 403 hedges by necessity — the
status code cannot tell an expired account from one with every connection in use — and the player
already made one exception to that, naming an expiry it could see in the account itself. The
browsing side had the same fact and did not use it, so the same provider refusing the same account
said one thing over a poster grid and another over a black frame. One function decides now, and
returns null for anything that is not a refusal, so each caller keeps its own words for a fault.

**A to Z now means the same thing on both clients.** Two rounds in a row had found the same shape of
defect — a rule the phone follows that the desktop never inherited — so the third went looking for
them on purpose, by listing what `:shared` exports, and what `:app` uses of it that `:desktop` does
not. Most of the answer is noise: repository interfaces the desktop has no database to implement,
subtitle styling it deliberately lacks. Two candidates looked real and were not — the desktop already
folds search text through `SearchTextNormalizer` on every path, and already normalises a typed server
address through `ServerUrlNormalizer`. One was.

Android does not sort by the display name. It sorts by a `sortName` column written when the listing
was cached, and *A to Z* is `ORDER BY sortName ASC`. `BrowseOrdering` compared the raw name, so the
same category came out in a different order on the two clients: `DE | Avatar` under D on the desktop
and under A on the phone, and `(2001) Amelie` after Z on one and under A on the other. That is exactly
the disagreement the file's own header argues against — "what has to agree is the rule, which is why
it lives here with tests rather than being written twice by eye".

The key is now built the same way rather than approximated, including the part where the two are
deliberately different: a **film or series** goes through `XtreamLanguageTagger.sortNameOf`, which
drops a recognised leading language tag, and a **channel** through `SearchTextNormalizer` alone,
keeping its `DE |` because for a channel that is which channel it is rather than noise in front of a
title. Five tests pin the difference, including the one that would have caught it.

Only `:desktop` calls `orderedBy`; Android orders in SQL and never touches it, so nothing on the
phone changed. The key is also computed once per item now instead of once per comparison, because
folding the same few thousand strings O(n log n) times to sort them is most of the work.

**Signing out mid-film kept the position.** The sweep through what `:shared` offers turned up no
further rule the desktop was missing — the stored `completed` flag and the fraction the tick reads
cannot disagree, because a completed record stores its whole duration rather than where playback
reached, and that invariant now has a test naming the client that depends on it. What the sweep did
turn up was next to it.

`stop()` launches the position write and carries on, which is right for the back button and a change
of section: the screen is still there afterwards. Sign-out called the same `stop()` and then took the
screen out of the composition in the same breath, and a coroutine launched into a scope being torn
down finishes nowhere. Up to ten seconds of a film, or a film started a moment ago with no position
at all.

This is the same defect the window's close handler was built to avoid, and its comment says so
outright — which is what made it findable. Sign-out now waits the same way, and `stop()` says in one
place which callers wait and why.

**The closing window now waits for marks as well as positions.** Looking for more of the same shape
— a launched write that is the last thing before a teardown — turned the question around: the hook
the window waits on existed *for positions*, and was only set while something was playing. Every mark
went out the same fire-and-forget way and had nothing holding the door. The coroutine dispatchers run
on daemon threads, so a heart set a second before the window closes is a write the exiting process
has no reason to finish.

The hook is `flushToDisk` now rather than `savePosition`, available whenever the browsing screen is,
and it writes once: the position is folded into the document before the document goes down. Saving a
document that has not changed costs one small file and settles the question — the alternative is
tracking which of half a dozen callers still has a job in flight, which is more machinery than the
problem is worth.

Two other launched writes were left alone on purpose. The title cache and the artwork store are both
declared disposable — losing a name means fetching it again, and losing a cache clear means the cache
stays — so blocking the close on either would be paying for something the client already says it does
not need.

**A damaged state file is no longer silently emptied.** The reads at launch were the thing to check,
and all three are properly defensive: the state file, the preferences and the title cache each fall
back to an empty value rather than costing the start. What none of them did was tell the difference
between *absent* and *unreadable*.

For the preferences and the title cache that is the right answer — both are derived, and a fresh one
costs a re-fetch. For the state file it was not. It is the only copy of everything a viewer has
watched and marked, and an unreadable one produced an empty document that the very next mark wrote
back over it. Everything went, silently, and the evidence went with it. The atomic write makes that
unlikely rather than impossible, and a half-synced copy or a hand-edited file arrives by another road
anyway.

An unreadable file is moved aside now, under a name carrying the moment it happened, and a banner
says where it went. A file from a **newer build** counts as unreadable for the same reason import
refuses it — data from the future, and an empty document is the worst available answer — while a
file belonging to a **different account** deliberately does not: it is readable and simply not ours,
and moving it would be as wrong as reading it. Five tests, including the one that names the old bug:
a mark after a failed load must not take the damaged file with it.

**Four things the owner asked for after the first real look at it.** A playlist link as a way in,
a name for the account, a disclaimer where it can be read, and a window that opens the size of the
screen.

The **link** was the one with a rule already waiting: `XtreamM3uUrlParser` has been in `:shared`
since the phone needed it, and the desktop had never called it — the same shape of gap as the sorting
key. Two tabs on the form now, one request behind them. The link is a credential and is handled as
one: masked by default, in memory only, and a parse failure says which half of the line is wrong
rather than showing the line.

The **name** is stored, because a name that has to be retyped every launch is not a name. It goes in
`preferences.json` under the account's one-way fingerprint — no address, no username, no password —
and an empty field means "keep what I called it last time", which is what makes it worth having.

The **disclaimer** is on the way in rather than in Settings, because it is the one thing about this
program someone should know before they use it rather than after. The byline sits under it.

The **window** opens maximized, and full-screen stays what it was: for the picture. Leaving
full-screen now puts the window back where it was rather than into a third state — a maximized window
that played one film used to come out of it floating at whatever size it had been before.

### The installer — 22 August 2026

This section used to end by naming what was missing to call it a client: an installer proper. That
is now built. `gradlew :desktop:packageMsi` produces `Killua IPTV-<version>.msi`, and the zip of the
app image stays beside it for anyone who would rather not install anything.

Three decisions in it, none of them large but each with an alternative that was rejected:

- **Per user, not per machine.** A per-machine MSI installs into `Program Files` and needs
  administrator rights. This bundle is unsigned, and an unsigned installer asking for administrator
  rights is the worst prompt Windows has — the one that names no publisher and offers only *Yes*.
  A per-user installation into `%LOCALAPPDATA%` asks for nothing, and it puts the program beside the
  `%LOCALAPPDATA%\KilluaIPTV` directory the client already writes.
- **Uninstall keeps the data.** The watch history, favourites and saved list survive an uninstall,
  because they are the one thing here that cannot be re-downloaded. The alternative — a clean
  uninstall — is the tidier answer to a question nobody asked and the wrong answer to the one they
  did.
- **The version is three numbers.** MSI compares `major.minor.build` and knows nothing about
  `-alpha.39`, so the third field is the alpha number. It is what makes installing a later build an
  upgrade rather than a second copy, and it has to move with the tag; `docs/RELEASE.md` says so
  beside the Android version code.

**What it costs to build**, and this is the part worth knowing before a fresh machine tries: jpackage
builds an app image from the JDK alone, but an MSI needs the **WiX toolset 3.x** on `PATH`. Not 4,
not 5 — a JDK 21 jpackage does not speak them. It is one `winget install WiXToolset.WiXToolset`,
once, with administrator rights. `createDistributable` is unaffected, so a machine without WiX can
still build and run the client; it just cannot package one.

The order after Windows is macOS (largely the same desktop target), then Android TV, then iOS. Android
TV is by far the cheapest of them — same repository, same player, same data layer, essentially a
D-pad focus UI — and is worth pulling forward. A hosted **web** player is the one genuinely blocked
target: browsers enforce CORS and block mixed content, `<video>` cannot play raw MPEG-TS, and Xtream
panels send no CORS headers, so it would require a relay proxy. Operating one would mean relaying
authenticated provider traffic, which the security rules rule out. Deferred by the owner on
17 August 2026 rather than designed around.

### The spike itself stays out of this repository

It lives in a local folder outside Proton Drive because it reads real credentials and writes a file
containing the account URL in plain text. It is throwaway: if the desktop client is built, it is built
properly as a module here, not by moving that code in.

## Supporting the project: done in both clients — 29 August 2026

A *Support* section in both settings screens, and a `Sponsor` button on the repository. Requested by
the owner on 29 August 2026, with one instruction that shaped all of it: **visible, but not pushy.**

It is a row like every other row on Android and a card like every other card on Windows — no banner,
no dialogue, no badge on the tab. The reason is not taste. This is a client for an account the viewer
already pays a provider for, so anything with push in it is asking the same person for money twice.

**What the wording has to do.** Both screens say the donation supports work on the app and unlocks
nothing. That sentence is not modesty, it is the difference between a donation and a sale: an IPTV
client that is vague about what a payment buys reads as selling access to content. This one has no
content to sell, and the text has to leave no room to think otherwise.

**Ko-fi.** `https://ko-fi.com/mynameiskillua`. Android opens it with `ACTION_VIEW` and a new task;
Windows through AWT `Desktop.browse`. Both assume the open can fail and neither treats that as an
error:

- a **Fire TV Stick frequently has no browser at all**, and there `ACTION_VIEW` throws rather than
  doing nothing. Android catches it and shows the address as a second row, which copies on click.
- on Windows, desktop integration can be absent, `BROWSE` can be unsupported, and the call can throw
  on a machine with nothing registered for `http`. All three answer `false`, and the card copies the
  link to the clipboard and says so instead.

**Crypto, added the same day the owner supplied the addresses.** Three entries: one EVM address, one
Solana address, one Bitcoin address. There is **one EVM address rather than one per chain**, because
that is what an EVM account is — the same address receives on Ethereum, Base, Polygon and the rest,
and receives tokens such as USDT alongside the native coin. Every entry carries the networks that
reach it, since sending on a chain the recipient cannot be reached on is the ordinary way crypto is
lost, and it is lost quietly.

**They are dedicated donation wallets, created fresh for this.** The first set supplied were
accounts already in use; they were replaced within the hour with new ones. That is the right
instinct: a published address hands anyone a permanent, complete, searchable record of everything
that account ever does — every transfer either way, the balance, the counterparties, the timing.

**Those first accounts stay abandoned.** Publishing an address cannot be undone: replacing it stops
it being offered and does nothing about the copies already made. Which is the whole argument for a
donation address never being one that also holds personal money.

**How far an address can be verified before trusting it**, which is not equally far for each:

- **Bitcoin** carries a bech32 checksum, so a single wrong character is provable. `bc1qmn…mv8e`
  passes; that address demonstrably has no typo.
- **Solana** has no checksum, but a valid address decodes to exactly 32 bytes — a truncated or
  padded paste does not. `2RfUQ…dGE7` decodes to 32.
- **EVM** has EIP-55, and it is a *mixed-case* checksum. An all-lowercase address, which is what
  wallets usually offer for copying, carries **no checksum at all**: length and alphabet are the
  only checks possible, and both pass on a wrong address just as happily. It is the weakest of the
  three, and the test pin is what actually protects it.

**One of the three addresses supplied was not a wallet, and this is the part worth remembering.**
The owner gave `0xc2132d05…b58e8f` as their "USDT on Polygon" address. That is the **USDT token
contract** on Polygon — the address an explorer or a wallet shows when you look up *the token*, not
*your account*. USDT sent there is absorbed by the contract and cannot be recovered by anyone. It
was caught by reading the address rather than by any rule, and it would have been in a public README
telling strangers to send money to it.

It also did not need to exist: the owner's own wallet screenshot said the EVM address covers
Polygon, so USDT on Polygon goes to the same `0x2ce5…1751` as ETH.

`CryptoAddress.KNOWN_TOKEN_CONTRACTS` now refuses that address and three other commonly-confused
stablecoin contracts, folding case because an EVM address's mixed case is a checksum rather than
part of the address. `Donations.coins` also refuses a placeholder, a blank, and anything carrying
whitespace — the usual damage from copying an address out of a wallet app, invisible once rendered.
None of it can tell a correct address from a wrong one; nothing outside the coin's own network can.
It refuses only the shapes that are certainly not a wallet.

**Why the strings live in `:shared`.** One wrong character in an address is money that goes somewhere
else permanently. Two copies of that string is two chances to get it wrong and no way to notice, so
there is one copy and both clients read it. `DonationsTest` pins the exact addresses — the single
deliberate duplication in the project, because noticing a drifted copy is precisely a test's job.

**Device pass, 29 August 2026, on `v1.0.1`.** The owner tested steps 31-34 in
`docs/CLAUDE_HANDOFF.md` — the Ko-fi row on the phone, the browser-less fallback on the Stick, the
Windows card, and a copied crypto address compared against the README — and reported that everything
works. A single high-level report, not a per-case matrix, so do not cite an individual step as
verified.

That closes this slice. The Bitcoin address arrived with the second set, so nothing is outstanding
except `.github/FUNDING.yml`, which is written but shows nothing while the repository is private —
GitHub only renders a Sponsor button on a public one.

## Updating from inside the app — 30 August 2026

The owner asked for this before the project's first public release, and gave the shape: an overlay
at launch rather than a bar along the top, showing `vX.Y.Z → vA.B.C`, with **Download and install**,
**Not yet**, and a line saying it can be switched off and why.

### Why it reads the public repository

The check is GitHub's `releases/latest` endpoint. A **private** repository's API needs a token, and
a token shipped inside an app is a token anyone can pull back out of it — so this points at
`IPTV-by-Killua` and there was never a second option.

That has a consequence with no way around it: **the updater only helps from the version after it
ships.** Anyone on 1.0.1 or older learns nothing and must update by hand once. Which is also why
1.0.1 was not published publicly on its own — the first public release should already contain this.

### What is in `:shared`, and why each piece is there

- **`AppVersion`** exists because the obvious comparison is wrong. As text, `1.0.10` sorts below
  `1.0.9`, and an updater that believes that hides every release from the tenth patch onward. It
  also reads this project's own past: semver puts a pre-release *below* the release it leads to, so
  an installation still on `0.2.0-alpha.39` is correctly told that `1.0.1` is newer, and
  `alpha.10` correctly outranks `alpha.9`.
- **`ReleaseFeed`** is a wall of refusals rather than a parser. A draft, a pre-release, an
  unparseable tag, an `html_url` that is not GitHub, an asset served from any host but this
  project's own release path, an asset of zero length — each is dropped. The alternative is an app
  that downloads and installs whatever a substituted response described.
- **`UpdateStatus.Unknown` is a third answer beside `UpToDate`**, and the distinction is the point:
  a client must never turn "I could not ask" into "you are current".

### The two clients differ, and the difference is real

**Android** downloads the package and hands it to the system installer through a `FileProvider`
scoped to one cache directory. `REQUEST_INSTALL_PACKAGES` is now in the manifest — it lets the app
*ask* the installer to install a file, not install anything itself, and from Android 8 the viewer
must first allow this app in system settings.

**Nothing verifies a checksum, deliberately.** The digest would come from the same response as the
file, so it would prove only that the response agrees with itself. The check that cannot be forged
is Android's own: it refuses any update whose certificate differs from the installed app's.

**Windows was going to open the release page.** The reasoning was that an MSI cannot overwrite a
running program — correct — and the conclusion was wrong, which the owner pointed out by naming an
app that does it anyway. The program closes first. So the client downloads the installer, flushes
the same two files the close button flushes, starts `msiexec /i … /qb` detached, and exits. Nothing
to download by hand, nothing to uninstall — `upgradeUuid` is pinned.

One **UAC prompt** remains, because the package registers under `HKLM`; that is the open per-user
question in `docs/RELEASE.md`, not something this code can fix. **SmartScreen does not appear**,
because that warning comes from the zone marker a browser attaches to a download, and a file
fetched in-process carries none.

**Windows has no signature gate**, because the installer is unsigned. TLS to `github.com` with a
redirect out of HTTPS refused, plus the strict download prefix, is what stands in its place. That
is weaker than the phone's position and stays weaker until the package is signed. `docs/SECURITY.md`
says so in those words.

### The desktop version now has one source

The build generates a resource from the same `appVersion` the installer uses. Two hand-kept copies
of a version number end in a client offering an update to the version it already is — the same
argument as the wallet addresses. The first attempt captured the build script itself and the
configuration cache refused to store it, which is how it was caught rather than shipped.

**Pending.** Everything about this is unverified on hardware, and it cannot be fully verified until
a public release exists to check against: an installed build has nothing newer to find. See steps
35-38 in `docs/CLAUDE_HANDOFF.md`.
