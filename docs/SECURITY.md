# Security and privacy

## Intended use and trust boundary

Killua IPTV is a private client for content and an Xtream-compatible account the user is legally authorized to access. It does not ship content, discover providers, redistribute streams, bypass DRM, evade connection limits, or operate a project-owned backend.

The configured IPTV provider is inside the functional trust boundary: it receives the account credentials and returns account metadata, library metadata, artwork URLs, and media locations. The app cannot make an untrusted provider safe. Prefer a provider you trust and an HTTPS endpoint with a valid certificate.

## Data-flow summary

| Data | Where it originates | Where it goes |
| --- | --- | --- |
| Server URL, username, password | Separate login fields, parsed credential-bearing Xtream URL, or encrypted local vault | Only the configured Xtream API and its conventional stream URL |
| Account/library metadata | Configured provider | Room on the device and app UI |
| Artwork requests | Provider response | HTTP(S) artwork hosts returned by the provider, via Coil |
| Playback traffic | Generated provider live URL | Configured provider; same-protocol redirects may reach a provider-selected media host |
| Theme/PiP preference | User/app defaults | Local no-backup DataStore |
| Recent channel timestamp | Playback selection | Local Room database |

There is no analytics SDK, advertising SDK, telemetry endpoint, cloud account, remote configuration service, or project-owned API in the current dependency graph.

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

## Cleartext HTTP

The manifest currently permits cleartext traffic because some authorized Xtream providers expose only HTTP. The user must enter `http://` explicitly. **Connect** refuses an HTTP account until **Test** succeeds and leaves the cleartext warning visible; changing any credential field resets that acknowledgement.

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
11. Confirm that no keystore, `keystore.properties`, password, or encoded key material is tracked, and that only the placeholder `keystore.properties.example` appears. Report file names and status only, never contents.

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
- There is no export/import yet. A future backup must exclude passwords by default or encrypt them separately with a user-controlled secret.
- No formal external security audit has been performed.

## Privacy expectations for future work

New features should remain local-first. Favorites, watchlist, watch progress, history, hidden categories, PIN state, and preferences belong on device and must be keyed by account. Diagnostics must be opt-in, sanitized, visible to the user, and local unless the user explicitly exports them. Adding any third-party telemetry or cloud service requires a deliberate product/privacy decision, documentation update, and user consent—not an incidental SDK dependency.
