# IPTV by Killua

A vibe coded, open source IPTV player for Android.

Bring your own Xtream-compatible account and it plays it — no ads, no tracking, no account with
anyone but your provider, and credentials that never leave your phone. It holds its own against
players people pay for.

Android for now. Other platforms are planned, and so is an attempt at the Play Store.

> **What this is not.** There is no content here and no provider list. This is a client for an
> account you already have and are legally entitled to use. Nothing is bundled, discovered, or
> unlocked.

---

## What it does

**Three libraries.** Live TV, Movies and Series, each with their own categories, filters and
sorting, and one search across all three. Search runs against what is already on your device, so it
answers instantly even on a six-figure library and works while your provider is slow.

**A player built for a phone.** Drag the slider at the left edge for brightness and the right edge
for volume. Double-tap left or right to skip, the middle to pause. Press and hold for faster
playback until you let go. Picture size cycles between fit, zoom and stretch, and it remembers what
you chose. It turns sideways by itself and gives your orientation back when you leave.

**It remembers where you were.** Films and episodes resume where you stopped, with checkpoints on a
timer and on every way out of the screen — including force-stop, where the loss is bounded to about
ten seconds. Series move on to the next unwatched episode by themselves, with a countdown you can
cancel.

**One saved list.** A bookmark on a film, a series or a channel puts it on *My list* on the home
screen — all three kinds together. Bookmarked channels also become the rows of your guide.

**A guide over your channels.** A four-hour grid across the channels you keep, on one shared time
axis with a marker for now. It covers your channels rather than all of them, because Xtream sends
the programme one channel at a time and a grid over sixty thousand channels would be sixty thousand
requests.

**Picture-in-Picture**, a background playback service, and a media notification, so leaving the app
does not stop the film.

## Why not just use a paid player

| | |
| --- | --- |
| **No ads, ever** | Not "no ads in the paid tier". There is no tier. |
| **No tracking** | No analytics SDK, no crash reporter, no telemetry of any kind. |
| **No account** | You never sign up with *us*. There is no *us* to sign up with. |
| **Credentials stay put** | Encrypted with the Android Keystore, on your device. No cloud sync, no backup, no server. |
| **No provider discovery** | The app will not find you an account, and does not want to. |
| **You can read the code** | All of it. Including the parts that handle your password. |

## Built with care in the places that matter

Not every part of an IPTV client is equally interesting, but a few of them decide whether it works
at all on a real account:

- **Large libraries stream, they do not buffer.** A 69 MB listing of 153,000 titles is decoded one
  element at a time and written to the database in batches. Reading it whole exhausted Android's
  heap and crashed the process; that is why it does not.
- **The cache is the source of truth.** A failed refresh keeps the last complete library and every
  favourite, bookmark and stored position. Temporary provider trouble never costs you data.
- **Every schema change migrates.** Nine versions, all with explicit non-destructive migrations,
  retained exported schemas and instrumented migration tests. Destructive fallback is off.
- **Account data is serialised.** One coordinator makes sure a slow refresh finishing after a logout
  cannot resurrect deleted data.
- **456 unit tests and 21 instrumented tests** run before every release, and no test ever touches a
  real account.

## Install

Download the APK from [Releases](../../releases) and install it. Android will ask you to allow
installation from your browser or file manager.

Every release ships a `.sha256` next to the APK. To check it before installing:

```bash
sha256sum -c Killua-IPTV-<version>.apk.sha256
```

All builds from `v0.1.0-alpha.4` onward are signed with the same permanent key, so Android will
update them in place. Builds before that were debug-signed and cannot be upgraded — uninstall first.

## Set it up

You need three things from your provider, usually sent by email when you sign up: a **server
address**, a **user name** and a **password**. If you were given one long link instead, switch to
*M3U URL* on the sign-in screen and paste it whole — the three values are read out of it.

**Test** checks the details without saving anything. **Connect** stores them encrypted on your
device and downloads your library once. On a large account that first download takes a few minutes;
after it, everything is local and instant.

An optional **playlist name** is what the home screen greets you with. You can change it later in
Settings.

## Build it yourself

```bash
git clone https://github.com/MyNameIsKillua/IPTV-by-Killua.git
cd IPTV-by-Killua
./gradlew assembleDebug
```

JDK 17, Android SDK 36, minimum Android 8.0. The debug build installs alongside a release build
under its own application ID, so you can keep both.

`./gradlew assembleRelease` deliberately fails without a local signing configuration rather than
producing an unsigned or differently signed APK. That guard is part of the build; see
[`docs/SECURITY.md`](docs/SECURITY.md).

## How it is built

Kotlin, Compose and Material 3, Room, DataStore, Retrofit and OkHttp, Paging 3, Coil, and Media3 for
playback. One module, no dependency-injection framework — everything is assembled by hand in
`AppContainer`, which keeps the wiring readable.

The documentation is written for someone who has to change this code:

| | |
| --- | --- |
| [Architecture](docs/ARCHITECTURE.md) | Boundaries, layering, and what may depend on what |
| [Database](docs/DATABASE.md) | Every schema version, every migration, and why each one exists |
| [Player](docs/PLAYER.md) | Playback, gestures, resume, Picture-in-Picture, and the manual test steps |
| [Xtream API](docs/XTREAM_API.md) | The provider surface, and how careless responses are survived |
| [Security](docs/SECURITY.md) | Credential handling, the threat model, and the release checklist |
| [Roadmap](docs/ROADMAP.md) | What is done, what is not, and what was deliberately left out |

## Status

Alpha, and honestly so. It is used daily against a real account with roughly 60,000 channels,
180,000 films and 50,000 series, and it holds up. What is not done is written down in the
[roadmap](docs/ROADMAP.md) rather than glossed over — remembered audio and subtitle language, a
guide across a whole category, and several device cases that have never been verified on hardware.

Version numbers stay honest: it says alpha because it is alpha.

## Vibe coded

This was built by talking to an AI, in the open, decision by decision. That is not an apology — the
architecture, the migrations, the test suite and the documentation are all real, and the reasoning
behind the awkward choices is written down where the choice was made rather than lost in a chat log.

If you find something questionable, the code is right there.

## Licence

[GNU General Public License v3.0](LICENSE). You may use, study, change and share this. If you
distribute a modified version, it has to stay open under the same licence.
