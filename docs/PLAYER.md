# Player

## Implemented playback architecture

Live playback uses Android Media3 and ExoPlayer. The player is owned by `PlaybackService` (`MediaSessionService`), not by a Compose screen. This lets the same player/controller survive recomposition, rotation, navigation changes, and Picture-in-Picture without rebuilding the media pipeline.

The relevant components are:

- `PlaybackService`: creates/releases ExoPlayer and MediaSession.
- `PlayerConnection`: asynchronously connects a `MediaController` to the service and exposes controller/snapshot flows.
- `PlaybackCoordinator`: obtains the active account's credentials, chooses a live format, builds the MediaItem, and starts/retries/stops playback.
- `PlayerPresentationState`: publishes only the Activity-level facts needed for PiP (visibility, readiness, playing state, aspect ratio).
- the Compose player UI: attaches the controller to a gesture-aware `PlayerView` surface and presents loading, standard/track controls, back, and safe retry failures.
- `MainActivity`: configures and enters native Picture-in-Picture.

## Playback request flow

Live, Movie, and episode playback share one screen, one service-owned player, and one route. `PlaybackRequest` says which of the three is being started; nothing else in the UI branches on content type.

1. The browser supplies an account and a stable provider ID. Navigation carries only that ID and the content kind.
2. The coordinator loads credentials from the vault and, for Live, selects `m3u8` or `ts`.
3. It builds the conventional authenticated Xtream live, movie, or episode URL. Episodes live under `series/` and are addressed by the provider's own episode ID, never by season and episode number, which providers repeat and renumber.
4. A MediaItem is created with a credential-free `PlaybackMediaId` — `live:{accountId}:{streamId}`, `movie:{accountId}:{movieId}`, or `episode:{accountId}:{episodeId}` — plus title/artwork metadata. Live declares an explicit MIME type; VOD deliberately does not, because the container is already in the URL and a wrong declared type would break a playable file.
5. For a Movie or episode, the resume position is handed to the controller **together with the item, before `prepare()`**, so playback opens at the resume point instead of seeking visibly after the first frame.
6. The connected controller replaces the current item, calls `prepare()`, then `play()`.
7. Player events update a credential-free snapshot used by Compose and PiP logic.
8. Live: after two seconds of confirmed playback the channel is marked recent. Movie and episode: watch position checkpoints begin.

The prefix is what keeps the two VOD types apart: providers number movies and episodes independently, so `movie:acc:501` and `episode:acc:501` routinely both exist and must never share a stored position.

The authenticated URL is intentionally not included in the media ID, the snapshot, navigation, or user-facing errors.

## Resume, restart, and watch progress

A Movie or episode starts where it was left, unless the viewer chose **Restart** or the title already counts as watched — resuming a finished one would drop the viewer three minutes before the credits. Completion is `WatchProgressPolicy`: 93% of the duration, or within three minutes of the end for anything at least ten minutes long.

Positions are captured through `PlaybackStateSource.capturePosition`, which reads the position, the duration, and the media ID they belong to in one go. Reading a position separately from its ID would let a checkpoint be attributed to a title the viewer already moved on from.

`WatchProgressWriter` performs the write for **every** resumable type rather than one writer per content type, because the rules below are the part that must not drift apart. Only the final store differs, chosen by an exhaustive `when` over `PlaybackMediaId.Resumable`, so a further resumable type cannot be added without deciding where its positions go. Live is not a `Resumable`, which is what rules a channel out at compile time instead of by a runtime check.

It refuses a write that:

- belongs to a different title or account than the one the screen is playing;
- carries no duration yet, because a resume point without one cannot be shown as progress;
- repeats the previous value, which a paused player would otherwise write on every lifecycle callback.

A finished title stores its full duration rather than the position the player reports, which is normally a little short; that makes the completion rule fire deterministically instead of depending on how precisely playback stopped.

Checkpoints are written:

- about every ten seconds while playing;
- immediately on pause, on the end of the title, when the screen is hidden — which covers backgrounding and the PiP transition — and on back, stop, or the ViewModel being cleared.

The write runs on an **application-owned scope**, not `viewModelScope`. The most valuable checkpoint of a session is the one taken as the screen goes away, and by then a ViewModel scope is already cancelled and would drop it silently. On the final path the position is captured *before* the media item is cleared; afterwards there is none to read.

After a force-stop or process death Android may deliver no final callback at all, so the expected loss is about one checkpoint interval.

Both the on-screen Back button and Android's system/back gesture stop and clear the current item before leaving the player route.

### Moving between episodes

The player offers **Previous** and **Next episode** controls, and starts the next episode by itself
when one finishes unless *Autoplay next episode* is turned off in Settings. All of them go through
the same path.

Both directions are answered by one walk over the episode list the details screen shows, so the two
controls cannot disagree about what "next" and "previous" mean — including across a season boundary.
The first episode of a series offers no Previous, the last offers no Next.

They sit **in Media3's own bottom row**, centred between the clock on the left and the subtitle and
settings buttons on the right, as plain icons like everything else in that row. They are transport
controls of the same kind, and the labelled pair that used to float above the bar sat in the middle
of the picture. That row is measured at runtime rather than guessed at, so the app's controls line
up with Media3's whatever height it gives its bar.

### What survives an episode change

Moving between episodes **replaces** the player screen rather than stacking one, and Compose disposes
the outgoing screen *after* the incoming one is already running. Anything the screen tore down on
disposal therefore undid what its own successor had just set up, one episode in. Three things did:

| Owned by the screen | Symptom on every episode change |
| --- | --- |
| The window brightness override | Brightness snapped back to the device setting |
| Immersive system bars | The status and navigation bars came back over the video |
| `PlayerPresentationState` | Picture-in-Picture stopped working entirely |

The first two now belong to the **route**: `PlayerRouteWindow`, called from `IptvApp` beside the
NavHost, holds them for as long as the player route is on screen and hands them back when it is
left. Brightness is held across Picture-in-Picture as well, so returning from PiP does not land on a
different brightness than leaving it.

The third is settled by ownership instead: every call into `PlayerPresentationState` carries the
screen it came from, and a screen that is no longer the current one is ignored rather than being
allowed to overrule its successor. Logout uses a separate unconditional `clear()`, since it owns no
screen and must never be refused.

Volume needs none of this. It is the device's own music stream, so it is unaffected either way.

### The countdown before autoplay

An episode that ends does not pull the next one in immediately. A countdown appears over the
finished picture naming the episode it is about to start, with **Cancel** beside it, and only when
it runs out is the navigation performed. Eight seconds: long enough to notice and stop, short enough
not to feel like waiting, and a viewer who wants the next one sooner has the Next control right
there.

It is drawn whether or not the controls happen to be visible, unlike everything else in the player.
An ending that the viewer walked away from is exactly the moment the offer has to be on screen. It
stays out of Picture-in-Picture like every other overlay.

Cancelling stops the timer and nothing else. The finished episode keeps its checkpoint and stays
marked watched: the viewer declined the next episode, not the fact that they watched this one.
Leaving the player cancels it too.

`STATE_ENDED` can be reported more than once for the same ending, so a running countdown is left
alone rather than restarted — otherwise the timer would never reach zero.

Advancing is a **navigation, not a player command**. The player route is keyed by its content, so
starting the next episode gives it its own screen state rather than mutating the current one
underneath a running session. The navigation replaces the current player instead of stacking one,
so Back after five episodes returns to the series rather than walking backwards through all five.

Autoplay is expressed as a one-shot field in the UI state that the screen consumes rather than an
event stream: it has to survive a recomposition, but it must not fire twice. The checkpoint that
runs when playback ends has already stored the finished position, so the episode is marked watched
*before* the next one starts — otherwise the series screen would keep offering the one just
watched.

The Next control also appears on a playback error, following the same rule as the Back overlay: an
episode that will not play is exactly when skipping it is worth offering.

`nextEpisode` follows the order the details screen lists, so "next" always means the row below the
current one, including across a season boundary. It never leaves the series, and the last episode
has none.

### Marking something watched by hand

Completion was previously only ever derived from playback, which cannot express "I already saw this
somewhere else" — the ordinary case on a library of 180,000 films and 50,000 series. A Movie carries
the toggle beside the favourite heart in its top bar; a series carries one per episode row, because
that is the granularity the rest of the screen already works at, and a whole-series toggle would
write thousands of rows for one tap.

Marking watched sets `completed` outright rather than running a position through
`WatchProgressPolicy`: the viewer's statement is the input here, not a playback position. An
existing row keeps its duration and moves to the end, so the bar reads full. With no row to build
on the duration stays zero — the mark is the fact worth storing, and inventing a runtime would make
a later resume point a lie.

Marking unwatched **deletes** the row rather than clearing a flag. Anything else would leave a
resume point three minutes from the end of something the viewer just said they had not seen.

Because the screens observe stored progress rather than applying the change locally, a mark moves
the series' primary button on to the next episode exactly the way finishing one does.

### Which episode a series offers

A series screen has one primary button rather than a play control per row, and it names the episode it would start underneath itself so it can never be ambiguous. The target is the earliest episode that was begun but not finished, otherwise the first unwatched one, and once the whole series is watched the first episode again. Tapping a row plays that row instead, which is what makes rewatching a specific episode possible.

Episode positions are read for the whole series in one query joined against `series_episodes`, so a screen showing twenty rows does not open twenty flows, and a position whose episode the provider later dropped stops being displayed without its row being deleted.

### What is playing

The overlay beside the Back button names the title: a channel name, a Movie name, or
`S1 E2 · Titel` for an episode — the same label the series list and the playback notification use,
so the three cannot disagree about what is on. It appears with the controls and on a playback error,
follows the same rule as everything else on that overlay, and is hidden in Picture-in-Picture.

The stock Media3 controller's **previous/next arrows are hidden**. The service holds a single
`MediaItem`, so they could never do anything, and the app offers its own **Next episode** control:
two next buttons where one is inert is worse than either alone.

### The programme guide overlay

For a live channel the player shows what is on now, a progress bar through it, and what follows —
beside the Back button, under the title, and only while the controls are visible, so the guide never
sits over the picture.

The guide is fetched **after** playback has been asked to start, never before: making the first
frame wait on a metadata request would trade the thing the viewer asked for against one they did
not. A provider that cannot answer yields an empty guide and no error, because a missing guide must
never turn into a playback failure.

Times are formatted from the provider's epoch timestamps in the device's own timezone and locale,
which is the clock the viewer compares against. The provider's formatted `start`/`end` strings
carry no offset, so an entry that has only those is dropped rather than placed at a guessed time.

`EpgSelection` picks now and next. It is a domain object rather than screen code because the
boundary cases are real: providers send overlapping entries, leave gaps between programmes, and
return listings that ran out hours ago.

The live channel list shows the same "now" line under each channel name, and this is where the
endpoint choice earns itself. A row asks for its programme only after it has stayed on screen for
a moment — scrolling past cancels the request before it is made — the view model refuses to ask
twice for the same channel, and no more than four requests run at once. Without all three,
scrolling a six-figure channel list would be a request per row.

## Media pipeline

Playback uses a dedicated OkHttp client through `OkHttpDataSource`:

- user agent: `KilluasIPTV/0.1`;
- connect timeout: 12 seconds;
- read timeout: 35 seconds;
- write timeout: 15 seconds;
- same-protocol redirects enabled;
- cross-protocol/SSL redirects disabled;
- OkHttp retry-on-connection-failure enabled.

Media3's load-error policy allows three automatic retry attempts. This is bounded; the app does not reconnect forever. The user can explicitly retry after a terminal error.

The current buffer configuration is tuned for responsive live startup:

| Setting | Value |
| --- | ---: |
| Minimum buffer | 8,000 ms |
| Maximum buffer | 35,000 ms |
| Buffer before initial playback | 1,200 ms |
| Buffer after rebuffer | 2,000 ms |

Hardware decoding is selected by Media3/Android's normal renderer logic. The app does not force an unsafe or device-specific decoder.

The current full-screen surface uses fit resizing, keeps the display awake, always shows buffering feedback, and uses Media3's standard controller with a four-second auto-hide timeout. The controller does not open automatically: a confirmed single tap on unobstructed video shows or hides it. While the player route is visible outside PiP, Android's status and navigation bars are hidden in immersive mode; an edge swipe can reveal them temporarily. The bars are restored when leaving the route or entering PiP and hidden again after returning from PiP.

### Remembered brightness

The player's brightness is stored and re-applied when the route opens, so a session starts where the
last one ended rather than at the device's setting.

Applied **once per visit**, not continuously: the viewer may drag it somewhere else while watching,
and re-applying the stored value would fight them. Stored when the finger lifts rather than on every
frame of a drag, which would be a few hundred writes for one decision. An unset preference is a real
state — null, not zero — so someone who has never touched the slider still gets their system
brightness rather than a value this app invented.

It remains a **window-local override**: nothing outside the player inherits it, and it is handed back
when the route is left. See *What survives an episode change* for who owns that lifetime.

### Not yet remembered: playback speed

Attempted and **withdrawn before release**. The plumbing worked — a speed set from Media3's settings
menu applies, and the rule separating it from the press-and-hold speed was written and tested — but
the settled speed never reached the store: `MediaController` did not deliver
`onPlaybackParametersChanged` for a change made through that menu, so nothing was ever written.

Shipping a control that silently does nothing is worse than not shipping it, so the code was removed
rather than left in. Whoever picks this up should start by confirming whether the controller reports
that event at all, and if not, read the speed back from the controller at a moment the app chooses
instead of waiting to be told.

The rule itself is worth keeping in mind: press-and-hold changes the speed too, and remembering that
would mean one hold left the player permanently at double speed.

### The player turns sideways

Opening the player locks the Activity to `SENSOR_LANDSCAPE`, and leaving it restores whatever
orientation was in force before. Video is wide; a portrait player spends most of the screen on black.
`SENSOR_LANDSCAPE` rather than plain `LANDSCAPE` so the phone can still be held either way round.

Not applied in Picture-in-Picture, where the window shape is not this app's to decide. Like immersive
mode and the brightness override it belongs to the **route**, so an episode change does not release
and re-take it.

### Picture size

A provider's stream rarely matches the screen, and until now there was no way to say what to do
about it. A control in the top-right corner cycles three modes and **remembers** the choice across
sessions:

| Mode | What it does | What it costs |
| --- | --- | --- |
| **Fit** | The whole picture, letterboxed | Black bars |
| **Zoom** | Fills the screen, keeps the shape | The edges are cropped away |
| **Stretch** | Fills the screen, keeps everything | The picture is distorted |

Which trade is acceptable depends on the stream and on the viewer, so the app offers all three
rather than choosing. Fit is the default because it is the honest one.

The control states the current mode in words. `Zoom` and `Stretch` produce similar-looking results
on a stream whose real shape the viewer cannot know, so an icon alone would not say which is on.

The mode is stored **by name**, not by ordinal, so reordering the enum cannot silently change what a
saved preference means; an unrecognised value falls back to Fit. Which Media3 constant expresses each
mode is kept in the player, not on the domain model — the rest of the app has no business knowing
that "fill and crop" is `RESIZE_MODE_ZOOM`.

### The app's chrome hides with Media3's, not after it

Everything this screen draws alongside the controls — the title, the transport icons, the resting
sliders — follows one fade that starts at the same instant Media3 starts its own.

Getting that right needed the auto-hide timeout to move out of Media3 and into
`GestureAwarePlayerView`. Media3's `ControllerVisibilityListener` reports a hide only once its fade
has **finished**, so overlays driven by that callback stayed at full opacity over a picture that had
already cleared and then popped away — about a third of a second late, which is very visible.

Owning the timeout was necessary but not sufficient. Media3 answers `hideController()` by reporting
`VISIBLE` **once more**, a millisecond later: its fade has not finished, so as far as it is concerned
the controls are still up. That callback silently undid the hide the view had just announced, and the
lag survived. Measured on an emulator, the report/undo/real-report sequence was 1 ms and 313 ms after
the request. The view therefore ignores `VISIBLE` while a hide it asked for is in flight, and treats
a requested hide as hidden — so a tap during the fade brings the controls back rather than asking to
hide them twice.

Media3's own timeout is set to zero (indefinite) so the two clocks cannot race. The view reproduces
Media3's rule that the controls stay up while nothing is playing, and restarts the timer whenever a
control is touched.

The stock subtitle button and playback-settings menu expose subtitle/audio choices when Media3 discovers selectable tracks in the stream. Player controls are disabled while the Activity is in PiP. The in-app Back overlay follows the Media3 controller's visibility and also remains available over a terminal error; Android's system Back action always works even when every overlay is hidden.

## Full-screen gestures and track selection

Gestures are divided across the unobstructed video surface:

- a confirmed single tap shows or hides the normal Media3 controller;
- double-tap the left third to seek backward by the configured interval;
- double-tap the right third to seek forward by the configured interval;
- double-tap the center third to toggle play/pause;
- press and hold to apply the configured temporary playback speed, then restore the exact previous speed on release or cancellation;
- drag vertically on the slider band at the left edge to change screen brightness, or the one at the right edge to change volume.

### Vertical drag for brightness and volume

The split is half and half rather than the thirds the double-tap seek uses: a drag has no center
meaning, and a dead strip down the middle would only make the gesture feel unreliable.

A drag is claimed only once it has clearly gone vertical, and once claimed it stays claimed for the
rest of the touch. The gesture detector stops delivering taps and long presses as soon as it reports
a scroll, which is what keeps a level drag from also seeking, triggering hold speed, or opening the
controller; a hold that had already started is cancelled explicitly when the drag is recognized.

**Only a band around each slider responds**, not the whole half of the picture. The band is about
108dp in from each edge and a little taller than the track, wide enough to find without looking but
narrow enough that an ordinary swipe over the middle of the video does nothing. Owning each whole
half made the gesture far too easy to trigger by accident, and gave the drawn slider no honest
relationship to where it actually worked.

**The sliders are visible**, showing the level, the percentage, and which of the two it is. While
the controls are up — and on a playback error, the same rule the Back overlay follows — both rest
at 36% opacity so the viewer can see where the gesture lives before reaching for it; the one being
dragged goes fully opaque.

The track *is* the drag distance: dragging its length covers the whole scale, so the gesture and the
thing it draws describe the same movement. An earlier version mapped the whole screen height and
showed only a percentage, which meant the indicator could not honestly show how far there was left
to go. The track is **32% of the picture's height**, clamped to 76–132dp, rather than a fixed 180dp;
on a landscape phone the fixed value made a secondary control the tallest thing on screen. Because
the track is also the drag distance, the gesture shrinks with it.

The slider is drawn in Compose rather than inside the video view, which is what puts it above every
other overlay. As a text cue inside the view it disappeared behind the playback-error card. It fades
out shortly after the finger lifts.

It is deliberately **not** a Material `Surface`. `Surface` installs a no-op pointer input to block
touches behind it, so the slider swallowed the very drag it advertises for as long as it was on
screen — which, once the sliders became visible with the controls, meant the gesture did not work at
all while the player UI was up. A `Column` with a background is inert, and the touch reaches the
video view underneath where the gesture lives.

The level continues from the value the device is already at rather than jumping to wherever the
finger landed. Dragging past either end clamps.

Brightness is a **window-local override** and never the system setting. Writing `Settings.System`
would need a special permission and would dim the phone for every other app; the override is handed
back when the player route goes away, so no later screen inherits it. It is clamped just above zero,
because a screen the viewer cannot see is one they cannot drag back up. The device's own brightness
is read once, to place the start of the first drag; adaptive brightness can make that an
approximation, and every value applied afterwards is the true 0..1 override.

Volume is the ordinary `STREAM_MUSIC` volume, set without the system's own slider because the player
draws its own cue. Nothing is cached between gestures, so the hardware keys stay authoritative: each
drag reads the current volume first. Android exposes volume as a handful of steps, so the level is
rounded to the nearest one — truncating would make the last step unreachable by dragging.

Like every other overlay, the cue is off in Picture-in-Picture.

The arithmetic — which half a touch is in, the drag-to-level mapping against the visible track, the step rounding — lives in
`PlayerLevelGesture.kt` rather than in the view, because `GestureAwarePlayerView` is an Android
`View` and cannot run on the JVM. `PlayerLevelGestureTest` covers it. What is left in the view is
only the part that genuinely needs a touch stream, and that part is still unverified by any test.

Video-surface taps are held until Android confirms whether they are a single tap, double tap, or hold. A double tap or hold explicitly keeps the controller hidden, so the first tap cannot flash player chrome or the in-app Back button over the video. Touches that begin on visible Media3 controls—including the seek bar, settings, and subtitle button—remain normal control interactions. The visible center play/pause button is deferred just long enough to distinguish a single press from a center double-tap; a confirmed single press still activates the stock button.

The double-tap seek interval can be 5, 10, 15, 20, 30, 45, or 60 seconds; its default is 10 seconds. The hold speed can be 1.25x, 1.5x, 1.75x, or 2x; its default is 2x. Both preferences are stored locally and can be changed in Settings.

Relative seeking is guarded by Media3's current-window state. It runs only when an active item is present and `isCurrentMediaItemSeekable` is true, and the target is clamped to the available duration when known. A true linear live channel normally cannot seek; a compatible DVR/time-shift window can. The on-screen cue says **Seeking unavailable** instead of pretending a live seek succeeded.

Audio and subtitle availability is stream-dependent. The app uses Media3's stock track-selection UI, so a provider stream exposing multiple audio languages or subtitle tracks can be changed during playback. The app cannot create tracks that are absent from the source, and it does not yet remember a preferred language or offer custom subtitle styling. Current support remains enabled, but its meaningful real-device verification is deferred until Movies/Series provide known multi-track sources; a single-track Live channel cannot prove that selection works.

## Playback and artwork caching

The playback pipeline buffers transient media data for playback but does not configure a Media3 download/disk cache, so streaming video is not stored as a growing video library on the device.

Coil caches channel artwork separately with hard maxima of 32 MiB in memory and 128 MiB in `cacheDir/channel_artwork`. These are upper limits, not reserved space; normal eviction keeps the caches bounded and Android may remove cache-directory files under storage pressure. **Settings > Clear artwork cache** clears both artwork caches without deleting credentials, account metadata, or the cached live channel list.

## Session and background behavior

The MediaSession provides Android media-system integration while playback is active. Audio focus is handled by ExoPlayer; playback pauses appropriately for noisy audio-route changes such as unplugged headphones.

When the Activity stops while the player route is visible, playback pauses unless the Activity is changing configuration or already in PiP. This prevents accidental hidden background audio when PiP is disabled. A dedicated user-controlled background-audio mode is not implemented.

Removing the app task stops playback, clears media items, and stops the service. The app does not use a keep-alive hack. The service and controller are released when destroyed.

## Picture-in-Picture

PiP is available because `MainActivity` declares `supportsPictureInPicture` and tracks the player presentation independently of Composables.

Auto-entry is allowed only when:

- PiP is enabled in settings (default `true`);
- the player screen is visible;
- video is ready;
- playback is currently active.

On Android 12 and later, Activity auto-enter is configured. On Android 8–11, `onUserLeaveHint()` requests PiP under the same conditions. Video dimensions update the PiP aspect ratio, clamped to Android's supported range, and the visible Activity bounds are supplied as a source-rectangle transition hint. Returning to the Activity restores the full player surface without creating a second player.

No custom PiP `RemoteAction` buttons are defined yet. System-provided MediaSession play/pause behavior depends on the Android version and launcher/system UI.

## Error behavior

Media3 errors are reduced to safe application failures:

- decoder errors -> device could not decode stream;
- parsing errors -> unsupported stream format;
- bad HTTP status/file not found -> stream unavailable;
- I/O/timeout -> server unavailable;
- unknown player errors -> stream unavailable.

Retryable presentation does not include the source URL or raw exception text. For a media-load failure, Retry prepares/plays the existing controller. If controller construction itself failed, Retry cancels the failed future, rebuilds the controller connection, then continues the channel start when a controller is available. Technical debug tooling must preserve URL redaction because Xtream URLs contain the username and password.

Whenever application session state leaves `Authenticated`—including a cached-first startup that later discovers invalid/expired credentials—the root UI centrally stops and clears playback before displaying recovery or login UI.

## Device results

On 15 August 2026 the owner reported that everything appears to work after installing the alpha-19..21 line, which is a successful high-level pass over the player title, the brightness/volume slider, punctuation-insensitive search, the manual watched mark, and the previous/countdown controls. It also means the schema 7 migration completed on their roughly 290,000-row library. No per-case matrix was reported; steps 17-19 and 25 below stay open.

On 14 August 2026 the user reported that Movies play on the Samsung Galaxy S23 Ultra with their own provider and that resuming works. That covers the core of the playback slice on real media, which the local synthetic provider cannot exercise. The finer checks in the list below — force-stop recovery, Picture-in-Picture, rotation, and the watched state at the end of a film — were not reported on and remain open.

## Manual playback verification

Use only a legally authorized test account:

Live, Movie, and episode playback were all reported working on a Samsung Galaxy S23 Ultra running Android 16 / One UI 8.5 through `v0.2.0-alpha.13` on 14 August 2026, on the owner's real provider. That is a high-level pass: steps 17-19 and 25 — force-stop recovery, Picture-in-Picture, rotation, and the watched state at the end of a title — were never reported case by case and must not be written up as verified.

The Phase 3 base playback flow and alpha-2 gesture/cache checks were successfully exercised on the same device on 13 August 2026. On 14 August, the user reported that alpha 3 works well, including the requested immersive system-bar/controller gesture refinements at a high level. Audio/subtitle selection remains enabled, but its meaningful verification is deferred until Movies provides a known multi-track source. Other provider variants and a reference/emulator pass remain useful release-hardening checks.

1. Start a known-good HLS channel. Measure whether video begins, audio is present, and channel/title/logo metadata are correct.
2. Start a known-good TS channel if available. Confirm the selected format in a sanitized diagnostic—not by sharing its full URL.
3. Pause and resume, rotate between portrait and landscape, leave and return to the player, and switch channels repeatedly. There should be one continuing MediaSession, not overlapping audio. Confirm the status/navigation bars disappear during full-screen playback, can be revealed transiently with an edge swipe, and return after leaving the player.
4. Single-tap unobstructed video and confirm the Media3 controls plus in-app Back overlay show/hide together. Confirm the Android system Back gesture still exits when controls are hidden, and that the overlay remains available on an error.
5. On seekable/DVR media, verify left/right double-tap at every configured interval and verify boundary clamping. On a true non-seekable channel, confirm **Seeking unavailable** appears. Verify center double-tap toggles play/pause without making the Media3 controller or Back overlay appear.
6. Verify hold speed at 1.25x, 1.5x, 1.75x, and 2x without making the controller appear. Release, cancel, navigate away, and rotate while holding; playback must restore its previous speed and never become stuck fast.
7. With controls visible, operate play/pause, the seek bar, settings, and subtitle button. They must retain their stock behavior despite the custom surface gestures.
8. If the stream exposes alternate audio/subtitle tracks, switch them through the stock Media3 UI. Also test a single-track stream and confirm the UI remains harmless. If Live has no known multi-track source, defer this check until Movies/Series rather than treating absence as a failure.
9. Press Home while video is actively playing. Confirm PiP appears; pause/resume and return to full screen. Confirm system bars are restored for PiP and hidden again after returning. Repeat with PiP disabled and confirm the Activity does not auto-enter and playback pauses in the background.
10. Interrupt the network briefly. Confirm retries stop, a useful error appears, **Retry** works after recovery, and there is no infinite loop.
11. Test an unavailable stream, an unsupported codec if safely known, and a provider connection limit. Confirm the app remains stable and offers back/retry behavior.
12. Swipe the app away from recents. Confirm playback stops.
13. Reopen the live browser and verify channels that played for at least two seconds are ordered under **Recent** for the active account and appear on Home.
14. Clear the artwork cache in Settings, revisit the browser, and confirm images reload while account/channel metadata remains intact.
15. Open a Movie and press **Play**. Confirm video and audio start, then leave with Back after a minute or two. Reopen the same title: the button must read **Resume**, a progress bar must show roughly where you stopped, and **Restart** must appear beside it.
16. Press **Resume** and confirm playback opens at that position without first showing the beginning. Press **Restart** and confirm it opens at zero and that the stored position is replaced only once you watch past it.
17. Repeat the leave-and-return check for pause, Home, Picture-in-Picture, and rotation. Each should preserve the position within a few seconds.
18. Force-stop the app during Movie playback and reopen the title. The position may be up to about ten seconds behind; anything close to that is expected, a jump back to zero is not.
19. Watch a Movie to the end. Confirm it is marked watched, that reopening it offers **Play** from the beginning rather than a resume near the credits, and that it leaves the Continue Watching row.
20. Confirm a Movie appears in Continue Watching after partial playback and that the row and details screen agree.
21. Open a Series, press its primary button, and confirm the episode named beneath the button is the one that starts, with the right title in the player and its notification.
22. Leave after a minute or two. The button must read **Resume**, **Restart** must appear beside it, and that episode's row must show a partial bar with the remaining minutes.
23. Watch an episode to the end. Its row must show **Watched**, and the primary button must move on to the next unwatched episode of the series rather than offering the finished one again.
24. Tap an episode row directly and confirm it starts that episode, resuming if it was partly watched and starting from zero if it was finished.
25. Repeat the leave-and-return, force-stop, and Picture-in-Picture checks from steps 17 and 18 for an episode. Episodes use the same player and the same checkpoint interval, so the tolerances are identical.
26. Favorite a series from the heart in its top bar, then use the **Favorites** chip in the Series tab and confirm only favorited series remain. Unfavorite it and confirm it leaves.
27. With one episode partly watched, use the **Continue** chip and confirm its series appears exactly once, even when several of its episodes are unfinished.
28. Confirm the partly watched series shows in Continue Watching on both the Series tab and Home, and that on Home a recently watched episode sorts ahead of a film watched earlier.
29. Start a live channel and tap once to show the controls. Confirm the programme title, its times, a progress bar, and the next programme appear beside the Back button, that the times match your own clock, and that the titles are readable words rather than Base64. On a channel your provider has no guide for, confirm nothing appears and playback is unaffected.
30. In the live channel list, confirm each visible row fills in what is on now shortly after it settles, and that a channel with no guide keeps showing its stream format instead. Then flick quickly through several screens of channels and confirm the list stays smooth, nothing errors, and the programmes only appear once you stop.
31. Play an episode and confirm skip-forward and skip-back icons appear in the bottom control row, level with the clock and the subtitle button. Press the forward one and confirm the following episode starts, that Back then returns to the series rather than to the previous episode, and that the last episode of a series offers no forward icon.
32. Let an episode play to the end and confirm the next one starts on its own, that the finished one is marked watched, and that the series' primary button has moved on. Turn *Autoplay next episode* off in Settings and confirm the ending no longer advances while the button still works.
33. Tap once during a Movie and an episode and confirm the title appears beside the Back button — the film's name, and `S1 E2 · Titel` for an episode. On a live channel, confirm the title sits above the now/next guide. Confirm no previous/next arrows appear beside play/pause any more.
34. Tap once to show the controls and confirm both sliders appear faintly at the edges, showing the current brightness and volume, and that each takes up clearly less than half the height of the picture. Drag up and down on the left one and confirm it goes fully opaque, the screen brightens and dims with it, dragging the length of the visible track covers the whole range, and it starts from the current brightness rather than jumping. Leave the player and confirm the rest of the app is back at the device's own brightness, then check the same after backgrounding and returning.
35. Repeat on the right half for volume. Confirm the slider matches the system volume, that pressing a hardware volume key afterwards continues from where the drag left it, and that the system's own volume slider does not also appear.
36. Swipe up and down over the middle of the picture and confirm nothing changes — only the bands at the edges respond. Then confirm a vertical drag on a band never seeks, never triggers hold speed, and never opens the controller, and that double-tap seek, center double-tap, and press-and-hold still behave exactly as before. Enter Picture-in-Picture and confirm no title, guide, or level cue is drawn there.
37. Search `mr robot`, `mr. robot`, and `Mr Robot` in the global Search tab and in the Movies and Series fields, and confirm all three find the same title. Search a channel by name with and without its country bar, for example `de rtl` and `de | rtl`. Confirm typing only punctuation shows the "keep typing" hint rather than the whole library.
38. After updating from the previous build, confirm the first launch completes rather than hanging, and that search works on the **existing** cache without a refresh. That is the schema 7 backfill; on a six-figure library it is the longest migration this project has shipped.
39. Open a Movie you have never played and use the check mark in its top bar. Confirm it fills, that the title leaves Continue Watching, and that reopening it offers **Play** from the beginning rather than a resume. Tap it again and confirm the mark clears.
40. Mark a partly watched Movie as watched and confirm its progress bar fills rather than disappearing. Then mark it unwatched and confirm the stored position is gone, not just the mark.
42. In a series, tap the check mark on an episode row. Confirm it fills, that the row's progress bar disappears, and that the primary button above moves on to the next unwatched episode. Tap it again and confirm the button moves back.
43. **With the controls visible**, drag on each slider band and confirm both still change brightness and volume. This is the case that was broken: the sliders themselves swallowed the touch, so the gesture did nothing for as long as the player UI was on screen.
44. Show the controls and let them time out without touching anything. Confirm the title, the skip icons, and the resting sliders fade away **together with** Media3's play/pause, seek bar, and clock rather than lingering after them. Tap during that fade and confirm the controls come straight back. Then pause playback and confirm the controls stay up instead of timing out.
45. Set brightness to something obvious, then use the skip-forward icon. Confirm the next episode keeps that brightness, that the status and navigation bars stay hidden, and that volume is untouched. Repeat with skip-back, and once more by letting an episode run out into autoplay.
46. After an episode change, press Home and confirm Picture-in-Picture still appears — this was the case that stopped working. Return to the app, leave the player, and confirm the system bars come back on the screen behind it. The PiP round trip is the part that matters here: without it the bars returned anyway.
47. Play something whose shape does not match your screen and use the picture-size control in the top-right corner. Confirm it walks Fit, Zoom, Stretch and back; that Zoom fills the screen by cropping the edges while Stretch fills it by distorting; and that leaving the player and returning reopens in the mode you left it in.
48. Set the brightness slider somewhere obvious, leave the player, and force-stop the app. Reopen it and start something: the player must come up at the brightness you left, not the device's. Then drag it somewhere else mid-episode and confirm it stays where you put it rather than snapping back to the stored value.

Test on at least one Samsung device and one emulator/reference device when preparing a release; vendor decoder and PiP behavior can differ.

## Not implemented yet

- Dedicated VOD seek buttons.
- Remembered audio/subtitle language preferences and custom subtitle styling.
- Persistent playback-speed selection and aspect-ratio modes. Press-and-hold temporary speed is implemented.
- A remembered playback speed. The press-and-hold speed is a separate, deliberately temporary setting. A first attempt is described under *Not yet remembered* below.
- Previous/next live channel actions and in-player channel/EPG drawer.
- Internal browse-over-video mini-player.
- Optional audio-only background mode.
- Custom notification/PiP actions beyond MediaSession defaults.
- Additional gesture controls, decoder diagnostics, or user-configurable buffers.

These are sequenced after reliable Live TV playback; see [ROADMAP.md](ROADMAP.md).
