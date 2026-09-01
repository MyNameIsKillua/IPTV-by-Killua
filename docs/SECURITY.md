# Security and privacy

## Intended use and trust boundary

Killua IPTV is a private client for content and an Xtream-compatible account the user is legally authorized to access. It does not ship content, discover providers, redistribute streams, bypass DRM, evade connection limits, or operate a project-owned backend.

The configured IPTV provider is inside the functional trust boundary: it receives the account credentials and returns account metadata, library metadata, artwork URLs, and media locations. The app cannot make an untrusted provider safe. Prefer a provider you trust and an HTTPS endpoint with a valid certificate.

## Data-flow summary

On **Android**:

| Data | Where it originates | Where it goes |
| --- | --- | --- |
| Server URL, username, password | Separate login fields, parsed credential-bearing Xtream URL, or encrypted local vault | Only the configured Xtream API and its conventional stream URL |
| Account/library metadata | Configured provider | Room on the device and app UI |
| Artwork requests | Provider response | HTTP(S) artwork hosts returned by the provider, via Coil |
| Playback traffic | Generated provider live URL | Configured provider; same-protocol redirects may reach a provider-selected media host |
| Theme/PiP preference | User/app defaults | Local no-backup DataStore |
| Recent channel timestamp | Playback selection | Local Room database |

On the **desktop client**, which has no database:

| Data | Where it originates | Where it goes |
| --- | --- | --- |
| Server URL, username, password | The sign-in form | Only the configured Xtream API and its conventional stream URLs; held in memory for the life of the window, and written to disk **only** if the viewer ticks *Stay signed in*, sealed by Windows DPAPI against their own Windows account |
| Account/library metadata | Configured provider | Screen state, and — where the viewer leaves *Keep the library on this computer* on — one local JSON file per account holding names, ids and artwork addresses, with a channel's `direct_source` and any artwork address containing the account's own user name or password stripped before writing |
| Artwork requests | Provider response | HTTP(S) artwork hosts returned by the provider; the bytes are cached on disk under a hash of their URL |
| Playback traffic | Generated provider URL | Configured provider, via libvlc |
| Watch progress, favourites, saved list, recent channels | The viewer's own marking and watching | One local file in the shared export format, containing no credentials |
| Window state, chosen order, last category | The viewer's own use of the client | One local file; the category is stored by provider id and scoped to an account fingerprint |

There is no analytics SDK, advertising SDK, telemetry endpoint, cloud account, remote configuration service, or project-owned API in the current dependency graph of either client.

Two hosts appear in both clients that are not the viewer's provider, and they are different in kind:

- the Ko-fi page behind *Settings → Support*, which is a link and nothing more — opened by the system browser, only on a tap. See [The support link](#the-support-link).
- **GitHub, which the app contacts by itself.** Once a day at launch, to ask whether a newer release exists. It is the only request either client makes without being asked, it is on by default, and it can be switched off. See [The update check](#the-update-check).

## Implemented controls

### Credential storage

- A random AES-256 key is generated in `AndroidKeyStore` under `killuas_iptv_credentials_v1`.
- Credentials are encrypted with AES/GCM/NoPadding using a random IV and a 128-bit authentication tag.
- The account ID is authenticated as GCM additional authenticated data, binding the envelope to the record.
- DataStore contains only the record version, account ID, IV, and ciphertext.
- The credential DataStore lives under `noBackupFilesDir`; the manifest also disables app backup.
- Room contains no password column and no standalone username/server credential columns.
- An invalid, tampered, obsolete, or undecryptable record is cleared and requires sign-in again.
- `XtreamCredentials.toString()` is hard-coded to `XtreamCredentials(REDACTED)`.
- The credential-bearing URL parser returns a redacted object and accepts only recognized Xtream `get.php`/`player_api.php` links with one username and one password; it does not fetch or import arbitrary playlists.
- The password field is cleared after successful login and when its ViewModel is cleared.

The Keystore key does not require biometric/user authentication. This is a usability choice for automatic startup; an unlocked, compromised, or rooted device is outside the protection the app can guarantee.

### Network security

- HTTPS uses Android/OkHttp's normal trust store, certificate chain validation, and hostname verification.
- No permissive TrustManager, hostname-verifier override, or global TLS-disable code is present.
- Credential-bearing API redirects are disabled.
- Cross-protocol redirects are disabled for playback; only same-protocol redirects are followed.
- API and player retries are finite.
- Inputs allow only HTTP(S), reject URL user-info/fragments/control characters, and remove credentials accidentally pasted in recognized Xtream endpoint queries before saving a base URL.
- No HTTP body or URL logging interceptor is installed.

### Local privacy

- App backup is disabled, with explicit cloud-backup and device-transfer exclusion rules as defense in depth.
- Library/history data is account-scoped.
- Logout serializes against refresh/history writes through one application-wide account-data coordinator, clears every local account/library/recent row, and then clears the encrypted credential envelope. Any write still in flight is rejected at its commit point because the coordinator rechecks credential ownership, so a late response cannot restore logged-out rows. Startup also removes orphaned database rows when no usable credential record exists.
- Leaving authenticated session state stops and clears any active MediaSession item before recovery/login UI is shown.
- User-facing errors contain safe categories rather than raw exceptions, server bodies, or authenticated URLs.

### Diagnostics

*Settings → Diagnostics* shows what this installation can say about itself for a bug report. It is
**opt-in, local, and shown before it can be copied** — nothing is sent anywhere, by this feature or
by anything else in the app.

**Every field is a type rather than a message, and that is the control.** The failure is a
`FailureKind` enum, not an exception; the account is a kind and a status, not an address; the
library is three integers from `COUNT(*)`. There is no free-text field, so no path exists by which a
server URL, a user name or a raw response body arrives in a report — not because something strips
them afterwards, but because nothing can put them in.

What is deliberately absent, having been considered rather than overlooked:

- **The account.** No server, no user name, no password, no account URL — and not the one-way
  fingerprint either, which identifies nothing useful in a report and is still an identifier.
- **Exception messages.** They routinely carry the authenticated URL that failed. The enum says
  what kind of thing went wrong, which is what a reader actually needs.
- **Titles.** What is in someone's library is their business; the counts answer the questions that
  scale bugs raise.

A guard behind the design refuses any value containing `://` or `@`, replacing it with
`(withheld)`. It should never fire — the typed fields make a credential impossible to express — and
exists so that a field added carelessly later is caught here rather than in somebody's public
issue. It is tested with deliberately hostile values.

The report is shown on screen before *Copy* is offered, because a report the viewer has not read is
one they cannot decide about, and deciding is what makes this opt-in rather than merely local.

### The update check

This is the only thing either client does on the network without being asked, so it gets the most
exact description in this document.

**What it is.** Once every twenty-four hours, at launch, a `GET` of
`https://api.github.com/repos/MyNameIsKillua/IPTV-by-Killua/releases/latest` — a public JSON
document. If the tag it names is a higher version than the installed one, an overlay says so; if it
is the same or lower, nothing appears. Launching the app four times in an evening asks once, and a
failed request still counts as having asked, so an unreachable GitHub costs one attempt a day
rather than one per launch.

**What it sends.** No account, no credential, no identifier, no device model, no locale, nothing
about the library or what has been watched. The `User-Agent` is the fixed string `KilluaIPTV`,
which GitHub requires and which says nothing about the machine. **What it unavoidably reveals** is
an IP address and the fact that this app was launched — the same thing opening any web page
reveals, and the reason the switch exists.

**Where the switch is.** *Settings → Updates*, in both clients, with that reason printed beside it
rather than only here. Turned off, neither client contacts GitHub at all. It is **on by default**,
which is a deliberate exception to this project's rule of contacting nobody but the viewer's own
provider: a sideloaded app has no store behind it, so without this the only way to learn that a fix
exists is to go and look.

**Why the public repository.** The private one's API needs a token, and a token shipped inside an
app is a token anyone can extract from it. That has a consequence worth stating: the check only
begins helping from the version *after* it ships.

**What protects the download.** Both clients now refuse an installer that is not the maintainer's.
They reach that answer by different routes, and the routes are worth knowing apart.

- **Android** hands the package to the system installer, and Android refuses any update whose
  signing certificate differs from the installed app's. That check belongs to the operating system:
  it is automatic and nothing in an APK can talk it out of it. It is also why no checksum is
  verified here — a digest taken from the same response as the file would prove only that the
  response agrees with itself.
- **Windows has no such rule, so the client supplies it.** This is worth stating precisely, because
  the obvious fix is not one: **buying a code-signing certificate would not give this guarantee.**
  Authenticode proves a signature is *valid* — that it chains to a trusted authority — never that
  it belongs to the same publisher as the program being replaced. A validly signed installer from
  somebody else would install over this one just as happily. A certificate removes *"Unknown
  publisher"* from the elevation prompt and, with reputation, quiets SmartScreen; it does not
  answer the question that matters.

  So the desktop client carries the public half of an **Ed25519** key whose private half only the
  maintainer holds, and refuses any installer the private half did not sign. Each release publishes
  a detached `…​.msi.sig` beside the installer; the client fetches it, verifies the downloaded file
  against the pinned key, and starts `msiexec` **only** if that passes. A release without a
  signature is refused rather than installed unchecked — which is why `v1.0.1`, published before
  this existed, cannot be installed this way and has to be updated by hand once.

  Failing closed is the rule throughout: a build with no key configured refuses every installer, a
  malformed signature is a refusal rather than an exception, and a file that fails verification is
  deleted rather than left on disk where a later step could find it.

TLS to `github.com`, with a redirect out of HTTPS refused, and a download URL that must begin with
`https://github.com/MyNameIsKillua/IPTV-by-Killua/releases/download/` apply on both. Those narrow
who could serve the file; the signature decides whether the maintainer actually made it.

The signing key is generated and held by the maintainer under the same rules as the Android
keystore — outside the repository, outside every synced folder, encrypted with a passphrase kept in
a different service. `tools/ReleaseSigning.java` refuses to write it into a directory whose path
names Proton Drive, OneDrive or Dropbox, and refuses to overwrite an existing one, because
replacing this key makes every installed client reject every future update.

Both refuse a draft, a pre-release, an unparseable tag, an asset from any other host, and a file
whose length does not match what the feed declared. `REQUEST_INSTALL_PACKAGES` in the Android
manifest lets the app *ask* the system installer to install a file; it does not let the app install
anything on its own, and from Android 8 the viewer must first allow this app in system settings.

On Windows the client downloads the installer, writes its own state to disk, starts `msiexec`
detached and exits, because an installer cannot replace a program while it is running. Nothing has
to be uninstalled first — `upgradeUuid` is pinned. One UAC prompt appears, because the package
registers under `HKLM`; SmartScreen does not, because that warning comes from the zone marker a
browser attaches to a download and a file fetched in-process carries none.

### The support link

*Settings → Support* is the only place either client points at a host that is not the viewer's own
provider, so it is worth being exact about what it does.

It opens `https://ko-fi.com/mynameiskillua` in the system browser, and only when the viewer taps or
clicks it. Nothing about it runs on its own: there is no request at startup, no request when the
screen is opened, and no SDK behind it — both clients hold the address as a string and hand it to
the platform. Ko-fi therefore learns nothing until someone chooses to go there, and what it learns
then is what any web visit tells a site, from the browser rather than from this app. No account
detail, no identifier, and no fact about the library crosses over, because there is nothing in the
link to carry one.

The address lives once, in `Donations` in `:shared`, and is `https` — a support link is a link the
app tells someone to trust, and over cleartext anyone on the path could rewrite it into a different
page asking for money. A test asserts the scheme so it cannot be relaxed by accident.

Crypto addresses live in the same file and are shown beside it — one EVM address covering Ethereum,
Base and Polygon, one Solana address, and one Bitcoin address, each labelled with the networks that
reach it. No request is involved: they are strings the client renders and copies to the clipboard.

**These are dedicated donation wallets, and that is a security property rather than tidiness.** A
published address is a permanent, public, searchable record: anyone who has it can read every
transaction that account ever makes, in either direction, along with balances, counterparties and
timing — forever, and without asking anyone. Publishing an address that also holds personal funds
publishes that history too. The addresses shown here belong to accounts created for this and used
for nothing else.

An earlier set, replaced within the hour on 29 August 2026, were accounts already in use. **They
stay abandoned**, because publishing an address cannot be undone: replacing it stops it being
offered, and does nothing about the copies already made or the chain that records it for ever. That
is the rule for any address this project has ever shown, and the reason a donation address should
never be one that also holds personal money.

`Donations.coins` filters what may be drawn, and the filter is the security control here rather than
a tidiness one. Money sent to a wrong address is gone silently — no bounce, no error, no way to
return it — so an address is refused when it is:

- still `CryptoAddress.PLACEHOLDER`, or blank;
- carrying whitespace, which is the usual damage from copying one out of a wallet app or an email
  and is invisible once rendered;
- a **known token contract**. On 29 August 2026 the owner supplied the USDT contract on Polygon as
  their USDT address — which is what an explorer shows for *the token* rather than for *an account*.
  Anything sent to a token contract is absorbed permanently. It was caught by reading it, so the
  rule exists to catch the next one.

None of this can tell a correct address from a wrong one; nothing outside the coin's own network
can, and the contract list holds a handful of famous ones rather than all of them. It refuses only
the shapes that are certainly not a wallet. The addresses are additionally pinned in
`DonationsTest`, so a mistyped character fails the build rather than a donation.

### The desktop client

Its posture is deliberately different from the phone's, and the difference is one decision:

- **Credential storage is opt-in and is DPAPI, not a home-made secret store.** For most of this
  client's life there was none at all, on the grounds that signing in each launch was the honest
  alternative to inventing one. That is now done properly instead: `CryptProtectData` seals the
  sign-in against the **logged-in Windows account**, so no other user of the machine can read it and
  neither can the file carried to another machine. It is written only when the viewer ticks *Stay
  signed in*, deleted when they untick it, when they sign out, and when the provider rejects what was
  stored. On any platform without DPAPI the box is not offered, because writing a password in the
  clear would be worse than asking for it. The residual risk is stated plainly on the settings
  screen: it protects the file, not a session someone is already sitting in front of.
- **TLS is OkHttp's default trust store, chain validation and hostname verification.** No permissive
  trust manager, no hostname-verifier override, no protocol downgrade. Every authenticated URL is
  built by the same `:shared` factory the phone uses, including its percent-encoding.
- **No logging interceptor, and no URL is printed.** The client writes exactly two lines to the
  console, both at startup: that it is starting, and whether libvlc was found. Nothing else in it
  prints at all.
- **Nothing it writes contains a credential.** Four files: the user's own data in the export format,
  a title cache, window preferences, and cached artwork. The account is identified in the first three
  by the export's one-way fingerprint over host and username — never the password — and a file whose
  fingerprint does not match is treated as absent rather than merged.
- **Artwork files are named by the SHA-256 of their URL**, so a directory listing does not become a
  record of the provider's host and library.
- **The password can be revealed while typing it**, off by default and never stored — the state
  lives in one screen and dies with it. Masking exists so that a shoulder is not a leak; a deliberate
  press by the person typing is not the threat it defends against, and the alternative is discovering
  a typo by asking the provider.
- **The settings screen never shows the server address or the username.** They are the account, and a
  settings screen that prints them is one that ends up in a screenshot.
- **Import refuses a file from another account** before anything is written, because the merge rule
  never deletes and a wrong import is therefore permanent.

## Cleartext HTTP

The manifest currently permits cleartext traffic because some authorized Xtream providers expose only HTTP. The user must enter `http://` explicitly. **Connect** refuses an HTTP account until **Test** succeeds and leaves the cleartext warning visible; changing any credential field resets that acknowledgement.

The desktop client applies the same rule in its own shape: an account that signs in over HTTP does
not reach the library until the warning has been shown and *Continue anyway* pressed, and *Back*
leaves the form with nothing kept. Its warning names what travels in the clear — credentials and
viewing alike — and says the protocol is the provider's choice rather than something the client can
correct.

With HTTP, usernames and passwords appear in unencrypted API query parameters and stream URL paths. Anyone able to observe or alter the network path—including public Wi-Fi operators, a malicious VPN, or a compromised router—may capture the credentials and viewing traffic. The app cannot correct that protocol weakness.

Use HTTPS whenever available. If HTTP is unavoidable, use a trusted private network or a trusted VPN and understand that the provider endpoint itself still receives plaintext. A later hardening phase should replace the application-wide allowance with a narrower, user-mediated network security policy where Android permits it.

## Credential-bearing link and Xtream protocol exposure

Even over HTTPS, Xtream commonly puts credentials in API query parameters and live media URL paths. Encryption protects them in transit from intermediaries, but they may still appear in the IPTV provider's access logs. During playback the authenticated URL necessarily exists in application/Media3 process memory.

A provider-issued M3U-style `get.php?...` or `player_api.php?...` account link visibly contains the username and password. Treat the entire link as a password: do not show it in a screenshot, paste it into chat or an issue, leave it in shared clipboard history, or store it in documentation. The alternative login field is masked by default, uses password-class keyboard handling, is marked as sensitive content for supported screen-sharing environments, keeps the original link only for test/retry, and clears it immediately after a successful connection. Parsed credentials use the same encrypted vault; masking and local parsing do not make the original link safe to share.

Never:

- log a complete Retrofit request, MediaItem URI, or playback exception that embeds the URI;
- put real credentials or URLs into test fixtures, screenshots, issue titles, crash reports, analytics, or source control;
- share raw provider responses without redacting account identifiers and private endpoints;
- add a network body logger to a build used with real credentials.

## The desktop sign-in

Two ways in, one request. **Server details** takes an address, a username and a password; **playlist
link** takes the whole `get.php?username=…&password=…` line a provider hands out, and reads the three
out of it with `XtreamM3uUrlParser` — `:shared`'s parser, the phone's, which accepts only `get.php`
and `player_api.php` and refuses a blank or repeated credential.

The link is a credential in every sense and is treated as one: masked in the field by default, held
in memory for exactly as long as the password is, never written to disk, and never echoed in an
error. A parse failure reports **which half of the line is wrong**, never the line. `ParsedXtreamM3uUrl`
prints as `REDACTED` for the same reason.

It reads a *URL*, not an M3U *file*. Nothing is downloaded to sign in — a playlist body would be the
six-figure library this client has decided never to fetch.

The optional **playlist name** is not a credential and is the only part that is stored. It lives in
`preferences.json` keyed by the same one-way account fingerprint the state file uses, so that file
still contains no server address, no username and no password.

## Playlists, and addresses this program did not build

A playlist changes the trust boundary in one specific way, and it is worth stating exactly rather
than generally. With an Xtream account, every address the program opens is one it assembled itself
in `XtreamStreamUrlFactory` out of a host the viewer typed. With a playlist, **the file decides**,
and the person who wrote the file is not necessarily the viewer. That is the whole of the difference;
everything below follows from it.

`StreamUrlPolicy` in `:shared` is the check that stands there. It runs on every address read out of
a playlist, and it refuses three kinds:

- **Anything but `http` and `https`.** A real public playlist does contain `rtmp:`, `mmsh:` and
  `srt:` - six of them in the 12,791 entries measured on 22 August 2026 - and refusing those costs
  six channels. `file:` is the one that matters: without the rule, a playlist could name a path on
  the viewer's own disk.
- **Credentials inside the address.** `http://user:pass@host/` hides a login behind a UI that shows
  a host. `ServerUrlNormalizer` has always refused it and this now does too.
- **Loopback, private, link-local and carrier-NAT addresses.** This is the one the rule exists for.
  Without it, a playlist can point the program at `192.168.1.1`, at `127.0.0.1:8080`, or at the
  `169.254.169.254` that cloud metadata answers on, and use it to reach machines its author cannot
  reach directly.

**What the policy cannot do, said plainly rather than glossed over.** A name is not an address.
`evil.example` may resolve to `192.168.1.1` and nothing in a pure function will know, because
knowing would mean a DNS lookup. Closing that needs the same check repeated at the socket, which is
an HTTP-client concern and belongs wherever the client lives - `:shared` deliberately has none. The
policy raises the cost of that attack from trivial to deliberate; it does not remove it, and it
should not be described as though it did.

**Cleartext is reported, not refused.** 2,273 of those 12,785 channels are `http`. Refusing them
would refuse the format, so the rule the project has always had - never *silently* downgrade -
is kept by counting them and letting the screen say so.

**Two smaller rules with security reasons.** A channel's identity is a SHA-256 prefix of its
address, never the address, because a *provider's* playlist puts the username and password inside
every stream URL and an id made from one would carry them into the database, into watch progress and
into an exported file. And `tvg-logo` addresses go through the same policy as streams, because
loading a logo is a request to a host the playlist chose, and it tells that host who is watching.

### A playlist address on Android is a credential, and is stored like one

The Windows client keeps a playlist address in memory unless the viewer ticks *Stay signed in*.
Android has no such box: its vault always stores, sealed by the Android Keystore, because the app is
expected to survive being closed.

That is the right behaviour and it has one consequence worth stating. A **public** playlist address
is not a secret, but a **provider's own** playlist address carries the account inside it - and the
sign-in cannot tell the two apart, nor should it try. So a playlist address goes into the same
encrypted record a password does, and `CredentialCodec` version 2 carries `LibrarySource` beside it.
It is never written to preferences, never logged, and never printed in a failure: the sign-in's
error names the reason and not the address, for the same reason the desktop's does.

`XtreamCredentials.toString()` is still `REDACTED`, which now covers a playlist address too.

### Which playlists, and which emphatically not

Asked by the owner on 22 August 2026 and settled the same day, recorded here so it is not reasoned
out again from scratch.

**A credential-free public playlist is in scope.** The viewer pastes a URL; the project ships no
list of its own, because a bundled list would be exactly the provider discovery the invariants rule
out. An index like iptv-org carries no account, indexes channels their broadcasters publish openly,
and is auditable, which is why it is also the first source this project has ever had that can serve
decodable media to a test.

**The lists of `get.php?username=…&password=…` lines circulating on document-sharing sites are
not.** Those are working subscriber credentials belonging to people who paid for them; using one is
unauthorised access to a system, and in Germany specifically § 202a StGB, however publicly the line
is posted. It is also the worse engineering choice: no TLS, an unknown operator who both learns the
viewer's address and controls every byte handed to the decoder, and a credential that stops working
as soon as the provider notices it is shared.

No feature in this project should assume, encourage or depend on such a list. Note that nothing
needs to be built for them either - they are ordinary Xtream links that the existing sign-in already
accepts, so this is a question of what the project endorses rather than of what it can parse.

## Developer verification checklist

Before distributing an APK:

1. Search tracked source/resources for test server hostnames, usernames, passwords, `player_api.php?username=`, `get.php?username=`, `/live/user/password/`, and `/movie/user/password/` samples; examples must remain fictitious.
2. Inspect the final dependency graph for newly introduced analytics, ads, crash uploaders, or network logging.
3. Confirm release builds do not enable verbose HTTP/Media3 URI logging.
4. Verify HTTPS rejects an invalid/self-signed certificate and that no code silently retries over HTTP.
5. Verify a cleartext login always shows an explicit warning.
6. Verify connection testing does not persist credentials; Connect does, encrypted. Repeat through the M3U URL login mode and confirm no complete credential-bearing link is logged.
7. Verify logout and app-data clearing remove local session access.
8. On a disposable authorized test account, review a locally retained Logcat capture for credential or authenticated-URL leakage before sharing any output. Delete the capture afterward; do not upload it.
9. Verify Android backup remains disabled and the credential preference file remains in no-backup storage.
10. Run automated tests with MockWebServer/fakes only. Never make CI depend on a real IPTV account.
11. Confirm that no keystore, `keystore.properties`, password, or encoded key material is tracked, and that only the placeholder `keystore.properties.example` appears. Report file names and status only, never contents. The signing rules live in `docs/RELEASE.md`, which is kept with the development repository rather than published.

For the desktop client, additionally:

12. Search `%LOCALAPPDATA%\KilluaIPTV` for your own server address, username and password. None of the four files may contain any of them; the account appears only as a hex fingerprint.
13. Confirm the artwork directory contains only hash-named files, with no readable URL, host or title.
14. Confirm the console output of a full session is still only the two startup lines — no URL, no account, nothing added by a later slice.
15. Verify that signing in over HTTP shows the cleartext warning **before** the library appears, and that *Back* leaves nothing behind.

## Residual risks and current limitations

- Cleartext traffic is application-wide when `http://` resources are used.
- Valid provider artwork may itself use HTTP; those image requests are cleartext and do not receive a separate per-image warning.
- No certificate pinning is provided. Pinning is difficult for user-configured hosts and platform trust is the current policy.
- No biometric lock, local PIN, screenshot blocking, or rooted-device detection is implemented.
- Room is not independently encrypted: cached metadata and recent-channel history rely on Android's application sandbox and device encryption. They remain readable to a rooted/compromised device even though credentials are kept in the separate Keystore-backed vault.
- Provider-returned artwork URLs may contact third-party hosts controlled by the provider.
- Same-protocol playback redirects can move media loading to a provider-selected host; its redirect URL may contain provider tokens or credentials.
- Media3 and Android system services own transient playback state; a compromised device can inspect app memory or traffic after unlock.
- One active encrypted credential record is supported; multi-account key management is not implemented.
- Export and import exist on both clients. The file contains no password, no username and no server address — the account is a one-way fingerprint over host and username — but it does contain what a viewer has watched and marked, so it should be treated as personal data when it is moved between devices.
- The desktop client's files are plain JSON and cached images under the user's own profile directory, protected by nothing but the operating system's file permissions. Any process running as that user can read what has been watched and marked, and — where the library cache is left on — what their provider carries. None of those files contains a credential, which is the risk that mattered most, but this is a weaker resting place than the phone's application sandbox.
- The desktop client's stored sign-in is sealed by DPAPI against the logged-in Windows account. Anything running as that user, while that user is logged in, can ask Windows to unseal it — DPAPI protects a file at rest and a copy carried elsewhere, not a session already unlocked. It is opt-in, and not storing it remains a supported way to run the client.
- No formal external security audit has been performed.

## Privacy expectations for future work

New features should remain local-first. Favorites, watchlist, watch progress, history, hidden categories, PIN state, and preferences belong on device and must be keyed by account. Diagnostics must be opt-in, sanitized, visible to the user, and local unless the user explicitly exports them. Adding any third-party telemetry or cloud service requires a deliberate product/privacy decision, documentation update, and user consent—not an incidental SDK dependency.
