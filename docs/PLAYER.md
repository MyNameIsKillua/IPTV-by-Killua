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

Audio and subtitle availability is stream-dependent. The app uses Media3's stock track-selection UI, so a provider stream exposing multiple audio languages or subtitle tracks can be changed during playback. The app cannot create tracks that are absent from the source, and it does not yet offer custom subtitle styling. Current support remains enabled, but its meaningful real-device verification is deferred until a known multi-track source is available; a single-track Live channel cannot prove that selection works.

### Remembered audio and subtitle language

A provider hands the same series out with several audio tracks and no consistent order, so the track
the player picks on its own is whichever the file happens to list first. Choosing again on every
episode was the friction. The language picked in the stock track menu is now remembered and applied
to everything watched afterwards.

**Only a deliberate choice is learned.** The signal is a `TrackSelectionOverride` in the player's
selection parameters, which is what the stock menu writes and nothing else in this app does — the
overrides are cleared before every title, so one present afterwards can only have come from the
viewer. The automatic selection is deliberately never read: a film carrying nothing but French audio
would otherwise make French the preference for everything after it, without anyone having asked.

**Preferences are stored, overrides are not.** An override names a concrete track group of a
concrete stream; carrying one into the next title would either match nothing or match a group at the
same index holding a different language. What is stored is the language tag, applied as
`setPreferredAudioLanguage` / `setPreferredTextLanguage` after the item is loaded and **before**
`prepare`, so the first track selection pass already picks the right audio rather than the viewer
hearing the wrong language for a second.

**Subtitles off is a state of its own**, kept as `setTrackTypeDisabled(TRACK_TYPE_TEXT)` rather than
as an absent language, so a stream that offers subtitles cannot quietly turn them back on. Picking a
subtitle language turns them on again and clears the off state; turning them off clears the
remembered language, because the next explicit pick is what should define it.

**It is read, not awaited.** The choice is captured on the same rhythm as a watch-progress
checkpoint — every ten seconds while playing, and on pause, end, background, PiP, back, and
`onCleared` — rather than by listening for `onTrackSelectionParametersChanged`. That is the lesson
from the withdrawn playback-speed attempt below: a `MediaController` change callback that never
arrives is a control that silently does nothing. Live channels are captured too, even though they
store no position: a channel with two audio tracks is exactly the case worth remembering.

Writes are bounded twice. A capture carrying no hand-made choice is dropped without touching the
store at all — the common case, and the one that would otherwise cost a store read every ten seconds
for the length of a film — and a repeat of the selection already handled is dropped as well. The
write itself runs on the application scope, like the progress writer, because the most valuable
capture is the last one and a `viewModelScope` is already cancelled by then.

Undetermined tags (`und`, `zxx`) are never stored; remembering one would mean preferring whichever
track a muxer forgot to label, on every title from then on.

**Settings shows what is remembered and can clear it.** There is no picker there on purpose: which
languages exist is a property of the stream, and a list this app invented would offer languages the
provider does not carry. Clearing also resets the writer's memory of what it last handled, otherwise
re-picking the language just cleared would be swallowed as a duplicate.

The decisions live in `domain/model/TrackLanguages.kt` and are covered by `TrackLanguagesTest` and
`TrackLanguageWriterTest`. Only the translation to and from Media3 is in `TrackLanguageAdapter.kt`,
which cannot run on the JVM and has no test; reading a track group's language needs unstable Media3
API, opted into there and nowhere else.

### How subtitles look

Two settings, both remembered: **Subtitle size** and **Subtitle background**. They are separate
because they solve different problems — one is about being able to read the text at arm's length, the
other about the text disappearing into a bright scene.

**Both default to the system**, meaning Android's caption preferences under Accessibility. That is
the whole reason the enums carry a `System` member rather than starting at a value this app picked:
someone who has set large captions system-wide did so because they need them, and an app that
silently overrides that has made an accessibility setting useless. Media3's `SubtitleView` already
reads those preferences through `setUserDefaultStyle` and `setUserDefaultTextSize`, and the default
simply leaves it doing that.

**Size is a fraction of the player's height** (0.04 to 0.09; `Normal` is Media3's own 0.0533, so it
means "what the player would have done"). Not a point size: the player is full-screen landscape, a
fixed size would be a different physical size on every device, and it would not follow the picture.

**Background covers the four ways text is separated from video** — plain, drop shadow, outline, or a
translucent box directly behind the glyphs. The subtitle *window* colour stays transparent in all of
them: a band across the whole subtitle region hides more of the picture than the text needs.

**Text colour is deliberately not offered.** White is the readable choice over video, and a colour
picker without a contrast rule is a way to make subtitles worse. It stays with the system option for
anyone who has set a colour there.

**A chosen style has to beat the stream's own.** `setApplyEmbeddedStyles(false)` is what makes that
true, and it is set as soon as a background is chosen — otherwise the setting would appear broken on
exactly the streams that carry styling. One consequence is worth knowing: Media3 ties embedded font
sizes to the same switch, so choosing a background also stops a stream's own sizes from being
honoured, whatever the size setting says.

**There is no preview in Settings**, and that is a decision rather than an omission. The size is a
fraction of the *player's* height, so a sample drawn in a settings list would be a different size
than the real thing and would mislead about the one property being set. The rows name the choice and
what it does instead.

The style is reapplied in the player's `update` block rather than at construction, because the view
outlives one episode and a change made in Settings has to reach a player that is already on screen.
Every call sets every property it owns, so no earlier choice survives as a leftover.

The rules are in `domain/model/SubtitleStyle.kt`, covered by `SubtitleStyleTest`. The translation to
`CaptionStyleCompat` is in `feature/player/SubtitleStyleView.kt`, is the second place opting into
unstable Media3 API, and has no test — `SubtitleView` is an Android view.

**These settings only style text subtitles, and on this provider that is a real limitation rather
than a footnote.** Probing the reference provider on 17 August 2026 (see *What the reference provider
actually serves* below) found `subrip` mixed with `hdmv_pgs_subtitle` and `dvd_subtitle` on the same
titles, and some titles carrying almost nothing but PGS. Bitmap subtitles are images: size and
background cannot apply to them, because there is no text to lay out or draw a box behind. A viewer
who picks a PGS track and sees no change has not found a bug.

Two things follow, and neither is done:

- the track menu does not say which tracks are text and which are bitmap, so the only way to find out
  is to try one;
- whether `dvd_subtitle` (VobSub) and `dvb_teletext` render **at all** is unconfirmed. Media3 decodes
  PGS, and DVB subtitles, but VobSub and teletext are not in its decoder set as far as this project
  knows. A track that appears in the menu and then shows nothing would look exactly like a broken
  app. This needs a device check before it is claimed either way.

### What the reference provider actually serves

Measured on 17 August 2026 with `ffprobe` from a Windows desktop against the owner's own account,
sampling one live category and one movie category. Recorded because it shapes every player decision
on every platform, and because guessing at it has already cost this project once.

- **The account is reachable from a desktop process.** No user-agent filtering, no IP binding: 21 of
  22 sampled streams read fine. The single failure was an HTTP read error consistent with the
  account's **one** simultaneous connection, not with a block.
- **Video is HEVC at 3840x2160 for most of it**, live channels included, with h264 1080p mixed in
  under channel names that advertise 4K. Movie masters are cropped to the film's aspect
  (3840x1608, 3840x2074, 3840x2080), which is what the picture-size control is for.
- **Audio is 6-channel AAC or E-AC3 on films, 2-channel AAC or MP2 on channels.**
- **Live channels are where the multiple audio tracks are, not films.** Every sampled film had one
  audio track; several sports channels carry two to four. That inverts the assumption this project
  has held since alpha 3, which was that Movies would eventually supply a multi-track source.
- **Films carry 12 to 35 subtitle tracks**, a mix of `subrip`, `hdmv_pgs_subtitle` and
  `dvd_subtitle`. Channels carry none, or `dvb_teletext`.
- Allowed output formats are `m3u8`, `ts` and `rtmp`; the server reports port 80 and HTTPS 443.

The probe lives outside the repository, reads credentials from a local file that is never committed,
and redacts host, username and password from everything it prints — including anything `ffprobe`
writes to stderr. Do not move it into the repository and do not record its input anywhere.

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

## The desktop player

Everything above is the Android player. The Windows/macOS client has a second one, sharing the URL
construction, the format selection and the completion rules from `:shared` and nothing else — it is
libvlc through vlcj, not Media3, and the two have almost no vocabulary in common.

### How the picture reaches the screen

Compose Multiplatform has no video component, so the video is drawn as ordinary Compose content.
Two choices carry that, and both were settled by measurement rather than preference:

- **libvlc hands over its decoder's own I420 planes.** Asking it for BGRA instead costs about 28ms a
  frame at 4K on the CPU and caps the pipeline at 19fps; taking the planes and converting them in a
  Skia shader reaches the full 50, and drops the payload from 33MB a frame to 12.4MB.
- **Each frame becomes an immutable Skia image before Compose sees it.** Handing Compose a bitmap
  whose pixels libvlc then overwrites crashes the JVM natively — Skia keeps reading that memory while
  the decoder writes into it. The copy is what makes the race impossible rather than merely unlikely.

The measured result is 50 of 50 frames presented at 3840x2176 with Compose UI in the same scene,
using 4.4ms of a 20ms budget. The planes are handed to the shader as `ALPHA_8` rather than `GRAY_8`
deliberately: alpha is not colour-managed, so a value put in survives sampling unchanged, while a
grey image can pick up a colour-space transform on the way through and skew the conversion. The
shader itself is BT.709 limited range, which is what broadcast HD and UHD are.

The frame is **polled** by the surface every 2ms rather than pushed: the decode thread must never
touch Compose state, and a frame that arrives between two draws is simply the one that gets skipped.

Because the video is Compose content, every control drawn over it is Compose too. That is the
property the whole desktop client is built on, and the reason the player takes the entire content
area instead of a column beside the browsing list.

One provider-specific fact worth keeping: the reference provider serves **10-bit HEVC**, which
libvlc's `d3d11va` hardware path refuses, so those streams decode in software at 2.3–5.9x real time
on the development machine. That is comfortable, but it is CPU rather than GPU, and it is why the
per-frame budget above matters.

### What the controls do, and what they refuse

- **Seeking is refused on anything without a known length.** A live stream reports none, and libvlc
  answers a seek in one either by doing nothing or by dropping the connection — neither is a useful
  reply to a dragged slider. Live shows a **LIVE** marker where the timeline would be, and previous
  and next channel buttons in the space that frees up.
- **Resume is a media option, not a seek.** The position is handed to libvlc as `:start-time=` with
  the media, so the first frame the viewer sees is the right one. Seeking after playback begins means
  seeing the opening seconds and then a jump. The Android player hands its resume position over the
  same way, before prepare.
- **Position is polled twice a second** rather than driven by libvlc's events: those arrive on its own
  threads and vlcj is explicit that calling back into the player from them is unsafe. While a drag is
  in progress the slider follows the pointer rather than the stream.
- **Volume survives a media change.** libvlc drops the level with each new media and refuses to set
  it before playback is actually running, so there is no single moment to apply it at; the same poll
  that reads the position puts the chosen level back whenever it has drifted. Mute is left alone
  while muted, because mute and level are separate switches there and correcting one while the other
  is on invites the two to fight every half second.
- **Track menus ask libvlc when they open.** Descriptions exist only once the container has been
  parsed, so a cached list would be empty exactly when the viewer first looks. libvlc's own *Disable*
  entry is left in the subtitle list, because it carries id -1 and *is* how subtitles are turned off.

### Fitting the picture

Two modes, one button. **Fit** takes the smaller scale and leaves bars; **fill** takes the larger and
puts the overflow outside the canvas, which is the crop. Both keep the picture's own proportions —
nothing here ever stretches, because a stretched face is worse than either bars or a crop.

Fit is the default and fill resets with each new medium, for the same reason the rate does: it is a
decision about *this* picture's shape, taken because the bars were wider than the picture, and
carrying it to the next title would cut the sides off something nobody asked to have cut.

`C` is the key, which is VLC's own key for cropping — the client is played by people who have used
VLC, and a key they already know is one they do not have to learn.

### Playback speed

A pill in the control row, offered **only where there is a timeline**: a rate on a live stream drifts
away from the broadcast and there is nothing to drift back to. It carries the current rate rather
than an icon, because the one thing worth knowing at a glance is whether anything is off normal —
a menu that hides that is how a film ends up watched at 1.25 for an hour by accident.

Deliberately **not remembered**, which is the same judgement the Android player made about its
press-and-hold speed. A rate is chosen for one title — a lecture at 1.25, a slow scene at 0.75 — and
carrying it into the next one, still less into live television, would be a setting nobody asked for
wearing the costume of a preference. Every new medium starts at one, which is also what libvlc does
on its own; the client only keeps the two in step, from the same poll that puts the volume back.

### Fullscreen is a second window

This is worth reading before anyone "simplifies" it back.

Fullscreen used `WindowPlacement.Fullscreen`, which is the obvious thing and is broken on Windows.
Compose hands that placement to Skiko, which puts the window into **exclusive** full-screen mode
through the graphics device, and exclusive mode has one documented behaviour that ruins it here: the
window minimizes itself the moment it loses focus. Alt-tabbing to a browser, or clicking anything on
a second monitor, made the picture vanish into the taskbar — and coming back out left the placement
stuck, so fullscreen could not be left either. Both were measured on the owner's machine before the
replacement was written:

```
AFTER-ENTER     exclusiveFullscreen=true   iconified=false
WHILE-UNFOCUSED exclusiveFullscreen=true   iconified=true    <- Windows minimized it
AFTER-LEAVE     placement=Maximized        iconified=true    <- and it stayed there
```

The replacement is an **undecorated second window** the size of the screen the main window is on,
always-on-top while it exists so it covers the taskbar. It is an ordinary window, so it behaves like
one: nothing minimizes it, and closing it is closing a window. This is what every video player on
Windows actually does.

It is only possible because of how the picture is drawn. libvlc renders into a buffer that Compose
paints as an ordinary image, so the video is not an AWT component nailed to one window — moving it
between windows costs nothing and does not interrupt playback. The browsing window carries on
existing behind it, which is what keeps every piece of screen state alive across a toggle;
recreating the main window instead, which is the only way to undecorate a live one, would throw all
of it away. (`ComposeWindow.dispose()` was tried: the container is disposed permanently and the
window cannot be shown again.)

Both windows share **one** key handler, because a client where space pauses in one window and types
a space in the other would be worse than one with no keys at all. `F1` draws its panel over whichever
window is on top.

### Keys

Space, left, right, up, down, `M`, `F`, `C`, escape, `F1`, and `PageUp`/`PageDown` — pause, skip back
and forward, volume, mute, fullscreen, fill or fit the picture, close what is open, the key list, and
step through what else is playable. `Ctrl+F` puts the caret in the search box.

**How far the two skip keys go is a setting**, in Settings under Playback, and the same numbers drive
the two round buttons under the picture. Ten and thirty are right for a film and wrong for a match;
the list of keys reads the chosen values back, so it cannot end up promising ten seconds to someone
who asked for forty-five.

**Every key except escape can be changed.** Settings makes each row a button: click it, press the key
you want. Only the changed ones are stored, so a better default in a later release still reaches
anyone who has not overridden that particular key. Taking a key that another shortcut had leaves that
one **unset** rather than silently sharing it — two shortcuts on one key means one of them stops
working and which one depends on declaration order, a rule no viewer can see. Escape is excluded on
purpose: it is what cancels a capture and what closes the panel over a film, and rebinding it there
is how someone locks themselves in. While a capture is waiting the window stops acting on keys
entirely, or pressing space to bind it would pause the film instead.

They are handled at the **window**, so they work wherever the pointer last was. The window sees every
key before the focused control does, which is why the playing keys are gated — and the gate is now
**whether the player is on screen**, not whether it holds media. Those are different questions: media
outlives the screen that shows it, so the old gate let a space bar pressed in a search box pause a
film the viewer had walked away from. Escape, `Ctrl+F` and `F1` are the deliberate exceptions —
fullscreen has no title bar and escape is the key everyone reaches for, and the other two are about
finding things rather than about playback.

**The list is one value, not three.** It used to exist three times over — the `when` block that
actually runs, a paragraph in Settings, and this page — with nothing checking any of them against the
others. A list of keys that lies is worse than no list: someone presses the key, nothing happens, and
they stop trusting the rest of it. So `Shortcut` in `:desktop` is the list, the window's handler
dispatches over it with a `when` the compiler requires to be exhaustive, and the screens that show
the keys read the same entries. Adding a key without giving it an action does not compile, and no
screen can offer one that does nothing. This page is now the only copy that can still drift, and it
is the one a viewer never sees.

Matching is on the modifier as well as the key rather than merely tolerating it, which is what keeps
`Ctrl+F` and `F` apart without depending on their order. It also means `Ctrl+M` no longer mutes: a
modifier the client does not claim is left for the control that might want it.

The rule is tested in `KeyboardShortcutsTest` — a playing key is not claimed while the player is not
on screen, escape and `F1` do not wait for a stream, no two entries answer the same press, a stored
binding for a shortcut this version does not have is ignored, and escape cannot be rebound even by a
file that says it was. That is the bug class this once shipped: the space bar paused the player
instead of reaching the filter field. The rules take a key code and its modifiers rather than a
Compose event, which is what lets all of it be tested without a window or an event queue.

### Where the keys are written down

Settings has a **Keyboard** card listing them, and `F1` puts the same list over whatever is on
screen. Both are needed: Settings is where someone looks to find out what a program can do, and
fullscreen playback has no Settings to walk to — which is exactly where not knowing the keys hurts.
The overlay closes on the button, on `F1` again, on escape, and on a click anywhere, because a panel
over fullscreen video that cannot be dismissed is a panic.

### Remembered audio and subtitle language

The same idea as the phone's, over a different track list. A provider hands the same series out with
four audio tracks in no consistent order, so the track libvlc picks on its own is whichever the file
lists first, and choosing again every episode is the friction this removes.

The rules are `:shared`'s and already tested; what the desktop had to add is the **language of a
libvlc track**, which is not in the list the track menu is built from. The selectable descriptions
and the parsed track information are two different libvlc lists, joined here by id — a track the two
do not agree on simply has no language, which degrades to "let the player decide" rather than to a
wrong choice.

Matching is not string equality. ISO 639 has **two** three-letter codes for a dozen major languages —
German is `ger` bibliographically and `deu` terminologically — and providers use whichever their muxer
wrote. Someone who chose `ger` on one film and is handed `deu` on the next has, as far as anyone but
a computer is concerned, already chosen. Two-letter tags are expanded to three, region is ignored,
and `und`/`zxx` are dropped rather than remembered. Nothing matching is not a failure to retry: a
title that does not carry the preferred language plays in what it has.

The menu names the **language** rather than repeating the container. libvlc describes a track the way
the muxer did — `Track 1 - [Deutsch]`, `Audio - [eng]` — and where the language is known that is what
the viewer is choosing, so that is what it says, in one vocabulary instead of the provider's several.
Except where two tracks would then read alike: a film with German stereo and German 5.1 must not
offer *German* twice, so a name that is not unique carries the container's description after it.
Disambiguating only where it is needed keeps the common case short, and a track with no language at
all keeps what libvlc said, because there is nothing better to call it.

Only a **hand-picked** track is learned, which is the rule that matters: what libvlc selected on its
own must never reach the preferences, or a film carrying nothing but French audio would make French
the preference for everything afterwards without anyone having asked.

The preference lives in the desktop's own `preferences.json` rather than in the export, because the
phone keeps its own — learned from its own player, over its own track lists — and the two are about
different things.

Settings shows both of them, in words rather than tags, and offers to forget them. A preference that
learns itself and cannot be seen or undone is one a viewer has to guess at; *Whatever the stream
offers* is shown for no preference, which is a different state from having chosen, and **Off** for
subtitles deliberately turned off, which is a decision rather than an absent language.

### When nothing arrives

A refused stream used to be a black rectangle with working controls — a dead channel, an account at
its connection limit and a title the provider no longer has all looked like a picture that had not
arrived yet. Two silences are distinguished now:

- **libvlc says it gave up.** Reported at once, from its own event thread, which only ever assigns a
  flag — vlcj is explicit that calling back into the player from an event handler can deadlock.

  It says only *that* it gave up. The event carries no reason — `error(MediaPlayer)` has no other
  argument — and `libvlc_errmsg` is thread-local to the last call, which is not something to read
  from an event thread. So the client does not invent one. The single exception is an account it
  already knows has **expired**: that is a fact it holds rather than a guess about a failure it
  cannot see into, and it is named.
- **Nothing is said and no frame comes.** After twenty seconds the client admits it does not know
  which of the two it is, in those words. Twenty is long enough for a slow provider opening a 4K
  stream and short enough that nobody sits there wondering.
- **A picture that was moving has stopped.** Both of the above are about *opening* a stream, and
  neither could ever fire again afterwards: the test for "all is well" is that the player is playing
  or has a position at all, and once a stream has produced one frame that stays true for the rest of
  the film. So a provider dropping the connection forty minutes in had no answer at all — a frozen
  frame, working controls, and no explanation, which is the same complaint the two messages above
  were written to fix, arriving at a different moment.

  `StallWatch` is the narrow test for it: the player says it is playing while its clock stands still.
  A **paused** player is not playing and can never be called stalled, nor can one that has not
  started; what is left is a stream that has died or one rebuffering, and fifteen seconds separates
  those — far longer than any rebuffer, far shorter than a viewer's patience with a stopped picture.

None of the three stops the player, so a picture that arrives — or comes back — clears the message by
itself. *Try again* is offered where asking again can change the answer, and not where it cannot — a
missing libvlc gets the message and no button.

### Writing down where you got to

Five things write a position, and only one of them is on a timer. Going back, switching title,
closing the window and signing out each write immediately and unconditionally — those are the last
chance the position gets, and a write skipped there because it looked like the one before it is a
write that never happens.

Two of the five **wait** for the write rather than launching it: closing the window and signing out.
Both end the composition in the same breath, and a coroutine launched into a scope being torn down
finishes nowhere — so a film left mid-way and then signed out of lost up to ten seconds, or, if it
had only just started, had no position at all. The back button and a change of section launch and
carry on, because the screen is still there afterwards.

The closing window waits for **more than the position**. Every mark — a heart, a bookmark, a title
crossed off, an imported file — updates the state in memory and launches its write without waiting,
which is right while the client is running and wrong at the end of it: the coroutine dispatchers run
on daemon threads, so a mark set a moment before the window closes is a write the exiting process has
no reason to finish. One call covers both, folding the position into the document before it goes
down, and it is available whenever the browsing screen is rather than only while something plays —
which is what the position-shaped version of it got wrong.

The timer is the fourth, every ten seconds, and it exists for the ways an evening ends that nothing
else covers: a crash, a power cut, a laptop lid. It now asks first whether there is anything to
write. Without that the answer was always yes: a film left paused rewrote the whole user-data file
every ten seconds to record that nothing had happened, and recomposed the screen each time for the
same nothing.

**The Android app has refused a repeated checkpoint from the beginning** — `WatchProgressWriter`
drops one equal to the last — and the desktop simply never inherited the rule when it got its own
loop. `WatchProgressPolicy.isWorthWriting` is that rule in `:shared`, where both can reach it, and it
is a little stronger than exact equality because the desktop reads a live libvlc clock that inches
forward while a stream stalls: a second of movement is the threshold, measured in **both**
directions, since seeking backwards moves the position as truly as playing forwards does. The
duration is part of the question too — it is what a progress bar divides by, and libvlc does not
always know it in the first seconds, so a rule watching only the position could hold a zero in place
and read as a bar stuck empty.

### One episode leads to the next

Thirty seconds from the end of an episode a card offers what follows, and at the end it starts by
itself. Episodes only: a category is not a playlist. This needed a rule of its own in `:shared`,
because `isCompleted` answers "is this still worth resuming" and says yes three minutes before the
credits — handing the next episode over at that moment would cut the last three minutes off every
one. `hasReachedEnd` asks the narrower question with a two-second tolerance, since a provider's
container routinely ends a second short of the duration it advertises.

### The buffer is not the picture

**Found on 21 August 2026, the first time this client was ever made to decode anything locally.**
`tools/fake-provider.ps1 -MediaFile` will serve a real file, so a throwaway probe drove the real
player through the real URL factory and reported what came back. Most of it was right — first frame
in 306ms, correct length, seekable, seek lands, pause holds the clock, resume advances it, two audio
tracks — and one number was not.

The frame sink reported **1280x738 for a 1280x720 file**. libvlc calls the buffer-format callback
**three times** for one medium — 1280x720, 1280x720, then 1280x738 — and it writes into the last
one. The client took that as the picture's size, so every video was drawn eighteen rows too tall.
Encoding the luma plane and comparing row means showed what those rows are: each one identical,
spread 0.0, against a spread of 13.7 across the picture. Filler.

The same +18 appeared at another size (360 → 386 through 368), so it is a constant, not alignment.

**The fix is two sizes instead of one.** The buffer is allocated exactly as libvlc asked, because it
writes there and anything smaller would be a write outside memory this client owns. The *image* is
built from the source's own dimensions — `videoDimension()`, which the player knows independently
and which stays right when a stream changes resolution mid-play — at the picture's height and the
**buffer's stride**, which is what makes one a window onto the other rather than a copy of it. Where
the source size is not known yet, the asked-for size is the fallback: a band at the bottom is better
than a picture that is not drawn at all.

Verified by comparing per-row means against the same frame taken out of the file by ffmpeg: the flat
rows and the varying ones line up, which a wrong stride could not produce — it would shear the
picture and even out every row. `FrameSinkTest` holds the rule.

**Why nobody had seen it**: the band sat behind the control bar, which used to be permanent. Making
the chrome go away is what would have revealed it.

### The chrome goes away

Controls that never leave are the whole of what a viewer notices about a client in fullscreen: the
picture is the thing, and a violet strip across the bottom of it is not. Three still seconds and the
title, the back arrow and the control bar fade out; the first movement of the pointer brings them
back, and the pointer itself goes with them — a cursor parked on a still frame is the same complaint
as a bar parked on one.

Four states refuse to hide, and they have one thing in common: in each of them the controls are what
is being used rather than what is in the way. A **paused** film is one somebody stopped on purpose; a
**failure** is a message and a *Try again*; an **open switch panel** is a list being read; and a
title that has **not produced a frame** yet is a spinner, not a picture. `chromeMayHide` is that rule
with a test each, because chrome that hides while someone is reaching for it is worse than chrome
that stays.

**A click on the picture pauses**, which is what a click on a picture means everywhere else. It sits
on the video surface rather than on the box around it, so a click landing on the control bar — even
on the empty space between its buttons — is not also a pause. It doubles as the way back if a
pointer move somehow does not arrive: a click always brings the chrome back, so the hidden state can
never be a trap.

There is deliberately **no double-click for fullscreen**, tempting as the pairing is. Compose would
have to hold every single click back for the double-tap timeout to see whether a second one
followed, and a pause that arrives a third of a second late is a worse trade than a keyboard
shortcut for something `F` and a button already do.

### Starting is said out loud

Between asking for a stream and the first frame there was nothing at all: a black rectangle with
working controls, which reads as a client that has done nothing. On a 4K film from a busy provider
that stretch is several seconds. A spinner and *Starting…* now sit over the picture until a frame has
actually arrived — `FrameSink.latest`, read by the same poll that reads the position, rather than
libvlc's own "playing" flag, which is true while it is still opening the stream. The watchdog behind
it is unchanged and still speaks up when the wait turns into a failure; this only covers the ordinary
case where it is about to work.

The rail is also gone while something is playing. It used to sit beside a playing film taking
ninety-six pixels of it — a strip of the picture spent on the list of things nobody is watching — and
it made the difference between playing and playing full-screen look like nothing at all.

### What the desktop player does not have

Not omissions to be fixed silently, but things the Android player has and this one does not:

- no Picture-in-Picture, no background audio, no notification or media-session integration;
- no subtitle styling — libvlc has its own rendering and its own defaults, and neither has been
  touched;
- no gestures;
- no brightness control, which is a phone idea rather than a desktop one.

None of the desktop player has been used against the real provider yet beyond signing in and
browsing; the checks that would establish it are listed under *The Windows client* in
`docs/CLAUDE_HANDOFF.md` — the maintainer's working notes, kept with the development repository
rather than published — and nothing here should be recorded as working until they come back.

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
49. On a title with more than one audio language, open the stock track menu and pick a language. Leave the player, start something else that carries the same language, and confirm it comes up in that language without touching the menu. This is the whole point of the feature and it cannot be checked against the synthetic provider, which serves one audio track. **Known sources on the reference provider, found by probing on 17 August 2026:** the Nordic 4K sports channel carrying four audio tracks (Swedish, Norwegian, Danish, Finnish) is the best one, and two Polish 4K sports channels carry two tracks each. Search Live for `sport` and look in the 4K category. No sampled *film* had more than one audio track, so do not spend time looking there.
50. Turn subtitles off through the track menu's **None** entry. Start something else that carries subtitles and confirm they stay off. Then pick a subtitle language and confirm the next title comes up with that language, and that Settings shows both the audio and the subtitle language under **Audio and subtitle language**.
51. Play something that does **not** carry the remembered language and confirm it plays in whatever the stream offers, that nothing is silent or missing, and that the remembered language is still shown in Settings afterwards — a title without it must not clear it.
52. Use **Clear** on that Settings row, confirm the row goes back to *Not set*, then pick the same language again in the player and confirm Settings shows it again. Re-picking what was just cleared is the case that the writer's duplicate rule could otherwise swallow.
53. Pick an audio language on a live channel, leave the player, and confirm it applies to the next channel that carries it. Live stores no watch position, so this is the one place where the two captures do not travel together.
54. With subtitles on, walk **Settings → Subtitle size** through every step and confirm the text in the player changes accordingly, that **Normal** looks like the size before this build, and that leaving and reopening the player keeps the choice. **Pick a `subrip` track for this.** On the reference provider most films carry both text and bitmap subtitle tracks, and a bitmap one cannot be styled at all — see the note under *How subtitles look*. Films are plentiful here: every sampled title in the first movie category carried between 12 and 35 subtitle tracks.
55. Walk **Subtitle background** through all five. Confirm *Plain text* has nothing behind it, that *Drop shadow* and *Outlined* differ visibly from it and from each other, that *Behind a box* draws a dark box behind the text only and not a band across the screen, and that each survives leaving the player.
56. Change either setting **while the player is open** — leave to Settings and come back without stopping playback — and confirm the change is already applied rather than needing a restart.
57. Set Android's own caption size and style under Settings → Accessibility → Caption preferences to something obvious, then set both app rows back to **System default** and confirm the subtitles follow the platform. This is the accessibility case and it is the one that must not regress.
58. On a stream whose subtitles carry their own styling, confirm that choosing a background overrides it, and that switching back to **System default** hands it back.
59. Pick a **bitmap** subtitle track — one of the PGS tracks on a 4K film — and confirm it renders at all, and that the size and background settings visibly do nothing to it. Both halves of that are the expected result, not a defect; the open question is only whether it renders. Then try a `dvd_subtitle` track and a channel's `dvb_teletext` track and record whether anything appears. If a track shows nothing, that is worth knowing before a viewer finds it.

Test on at least one Samsung device and one emulator/reference device when preparing a release; vendor decoder and PiP behavior can differ.

## Not implemented yet

These are the Android player's gaps; the desktop player's are listed in its own section above.

- Dedicated VOD seek buttons.
- A subtitle **text colour** choice, and a preview of the chosen style. Size and background are implemented; both omissions are deliberate and the reasons are under *How subtitles look*.
- A remembered playback speed. The press-and-hold speed is a separate, deliberately temporary setting. A first attempt is described under *Not yet remembered: playback speed* above. Aspect-ratio modes and the remembered brightness, once listed here, are implemented.
- Previous/next live channel actions and in-player channel/EPG drawer.
- Internal browse-over-video mini-player.
- Optional audio-only background mode.
- Custom notification/PiP actions beyond MediaSession defaults.
- Additional gesture controls, decoder diagnostics, or user-configurable buffers.

These are sequenced after reliable Live TV playback; see [ROADMAP.md](ROADMAP.md).
