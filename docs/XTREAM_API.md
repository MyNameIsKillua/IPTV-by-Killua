# Xtream API adapter

## Supported surface

The current adapter implements the common Xtream-compatible `player_api.php` surface needed for authentication and Live TV:

| Purpose | Request |
| --- | --- |
| Authenticate/account info | `GET player_api.php?username=...&password=...` |
| Live categories | `GET player_api.php?username=...&password=...&action=get_live_categories` |
| Live channels | `GET player_api.php?username=...&password=...&action=get_live_streams` |
| Live media | `GET live/{username}/{password}/{streamId}.{m3u8|ts}` |
| Movie categories | `GET player_api.php?username=...&password=...&action=get_vod_categories` |
| Movie listing | `GET player_api.php?username=...&password=...&action=get_vod_streams` |
| Movie details | `GET player_api.php?username=...&password=...&action=get_vod_info&vod_id=...` |
| Movie media | `GET movie/{username}/{password}/{movieId}.{extension}` |
| Series categories | `GET player_api.php?username=...&password=...&action=get_series_categories` |
| Series listing | `GET player_api.php?username=...&password=...&action=get_series` |
| Series details | `GET player_api.php?username=...&password=...&action=get_series_info&series_id=...` |
| Episode media | `GET series/{username}/{password}/{episodeId}.{extension}` |

The VOD listing and detail actions are cached and displayed by the Movies screens, and Movie media URLs are built and played by the Movie player.

The Series rows are the adapter, parser, and URL-construction layer only. Nothing caches, displays, or plays a Series yet; that arrives with the Room and UI slices. They have never been exercised against a real provider.

`get_short_epg` is implemented for one channel at a time. The whole-guide XMLTV file is deliberately not used: a provider with 60,000 channels would answer it with something the size of the listings this project already had to learn to stream, and a guide is only ever read one channel at a time.

## Login input and normalization

The login screen supports two ways to supply the same Xtream account:

1. **Credentials** accepts a server origin/provider base path plus separate username and password fields.
2. **M3U URL** accepts a provider-issued credential-bearing URL whose final endpoint is `get.php` or `player_api.php` and whose query contains exactly one non-blank `username` and `password` value.

Examples below use intentionally fictitious credentials:

```text
https://provider.example:8443/
https://provider.example/xtream/
https://provider.example:8443/get.php?username=demo-user&password=demo-pass&type=m3u_plus&output=ts
https://provider.example/xtream/player_api.php?username=demo-user&password=demo-pass
```

The M3U URL option is a convenience parser for an Xtream account link, not a general playlist importer. It extracts the server, username, and password, then runs the same `player_api.php` test/connect flow as manual entry. Extra provider query parameters such as `type` and `output` do not change authentication. Arbitrary remote/local `.m3u` or `.m3u8` playlists without embedded Xtream credentials are explicitly unsupported.

`XtreamM3uUrlParser` trims surrounding whitespace, adds HTTPS for a valid scheme-less host, accepts only HTTP(S), and requires the recognized endpoint plus one username and one password. Credential parameter names are case-insensitive, their order is arbitrary, and URL-decoding follows normal HTTP URL rules. Ports and provider base subpaths are preserved. The parser rejects malformed URLs, user-info, fragments, unsupported endpoints, repeated/blank/missing credentials, control characters, and oversized input. Parsed credential objects redact their `toString()` value.

For separate account entry, `ServerUrlNormalizer` applies these rules before any request:

- trims surrounding whitespace and a leading byte-order mark;
- adds `https://` for normal scheme-less domain, IP, `localhost`, and valid hostname-with-port inputs;
- accepts only `http` and `https`;
- rejects embedded `user:password@host` user-info, fragments, control characters, and malformed/missing hosts;
- preserves an intentional provider base path;
- removes a trailing `player_api.php`, `get.php`, or `xmltv.php` endpoint;
- removes the query from those recognized endpoint URLs, including pasted credentials;
- rejects arbitrary query strings on base URLs;
- ensures the normalized base ends in `/`;
- reports that HTTPS was added, an endpoint/query was removed, or the result uses cleartext HTTP.

Explicit `http://` is never silently upgraded because some legitimate private providers support only HTTP. For an HTTP account, **Connect** is refused until **Test** has succeeded; that result keeps a cleartext warning visible so the risk is acknowledged before credentials are persisted.

A credential-bearing M3U URL is as sensitive as writing the username and password in plain text. Do not include a real link in screenshots, clipboard-sharing tools, issues, logs, source control, or messages. The **Xtream M3U URL** field is masked by default; the original link remains only in that field for test/retry, never populates the three separate credential fields, and is cleared immediately after a successful login. **Connect** stores the normalized server and parsed credentials through the existing Keystore-encrypted flow.

## Request construction

Retrofit uses a complete dynamic endpoint URL built from the normalized base path plus `player_api.php`. Username, password, and action are added with Retrofit query encoding. The static Retrofit base URL is a placeholder only; provider selection happens per request.

Response-body reading and defensive JSON parsing execute under `Dispatchers.IO`, keeping large provider responses away from the main UI thread.

### Streaming the large listings

`get_live_streams` and `get_vod_streams` return the entire library in one response. Reading that into a String and then building a complete `JsonElement` tree costs several times the payload size: a 70 MB response became roughly 140 MB of `String` plus a much larger object tree, which exceeded Android's 192 MB heap growth limit and crashed the process with an `OutOfMemoryError`.

Both listings are therefore decoded straight from the response stream, one element at a time, and handed to the caller as a lazy sequence that the repository writes to the database in batches. **Neither the response nor the parsed collection may be held whole**: streaming only the response was not enough, because 150,000 domain objects plus the entities mapped from them still exhausted the heap. De-duplication keeps just the seen provider IDs, a few megabytes even for a very large library.

Measured against a synthetic listing of 153,000 titles with realistic title and artwork-URL lengths, 69 MB of JSON, the heap stays around 45 MB instead of hitting the ceiling. The same listing crashed both the fully buffered and the response-only-streaming versions.

The streaming path applies only to a top-level JSON array, which is the shape providers actually use for these two endpoints. A leading byte-order mark is skipped. Any other root — an object keyed by ID, an HTML error page, a small error object — is buffered and handed to the original parser, so compatibility is unchanged. Malformed JSON discovered mid-stream is reduced to `InvalidServerResponse` exactly like the buffered path, while a genuine read error stays an `IOException` so the retry policy still applies.

Account, category, and `get_vod_info` responses remain buffered; they are small by construction.

API-client behavior:

- connect timeout: 10 seconds;
- read timeout: 25 seconds;
- write timeout: 15 seconds;
- whole-call timeout: 35 seconds;
- HTTP redirects disabled;
- HTTPS/cross-protocol redirects disabled;
- OkHttp automatic connection retry disabled.

Redirects are deliberately rejected for credential-bearing API calls. Enter the provider's final canonical base URL instead of relying on a redirect.

## Defensive response parsing

The JSON parser is lenient about unknown fields and common type inconsistencies, but strict about the response being usable.

Authentication accepts boolean-like `auth` values (`true`, `1`, `yes`) and normalizes:

- status (`Active`, `Expired`, `Disabled`, or `Unknown`);
- expiry timestamps in seconds or milliseconds;
- active and maximum connection counts represented as numbers or strings;
- server timezone;
- allowed `m3u8`/`ts` output formats represented as an array or delimited string.

An explicit expired/disabled status, or an expiry timestamp in the past, is rejected even when `auth` is true.

Live parsing:

- accepts either a JSON array or an object whose values are objects;
- skips entries without a stable category/stream ID;
- de-duplicates by provider ID, keeping the first occurrence;
- supplies a neutral fallback name for a missing name;
- treats `null`, `undefined`, and `N/A` strings as missing;
- accepts only valid HTTP(S) logo/direct-source URLs;
- accepts only `m3u8` and `ts` container extensions;
- retains unknown/missing category IDs so the app can surface **Uncategorized** rather than dropping channels.

HTML, empty bodies, malformed JSON, and incompatible root types become `InvalidServerResponse`; raw bodies are not shown to the user.

### Movie (VOD) parsing

Movie categories and listings reuse the live rules above: array or keyed object, entries without a stable ID skipped, de-duplication by provider ID keeping the first occurrence, neutral fallback names, and `null`/`undefined`/`N/A` treated as missing.

Movie-specific handling:

- ratings accept numbers or strings, treat zero/blank/unparseable values as "not rated", and clamp to the 0-10 scale;
- release years are extracted from `year`, `releasedate`, or `release_date`, accepting `2019`, `2019-05-24`, and `24-05-2019`, and rejecting years outside 1880-2200;
- durations accept a `duration_secs` count or a `HH:MM:SS`/`MM:SS` string, and must be positive;
- posters fall back from `stream_icon` to `cover`, and from `movie_image` to `cover_big`;
- `backdrop_path` is accepted as either a single URL or an array, taking the first valid HTTP(S) entry;
- container extensions are validated against the safe list below and dropped when unrecognized.

`get_vod_info` splits its payload across a descriptive `info` object and an identity-bearing `movie_data` object. Either may be absent; a payload containing neither is `InvalidServerResponse`. When `movie_data.stream_id` is present and does not match the requested ID, the response is rejected rather than attributed to the requested title, so one movie's metadata can never be shown for another. A blank movie ID is refused before any request is sent, and `vod_id` is added through Retrofit query encoding so a value containing `&` or `=` cannot inject extra parameters.

`youtube_trailer`, `direct_source`, and external subtitle URLs are deliberately not parsed for Movies. Each would introduce a second, unreviewed network destination and needs its own trust/redirect design first.

## Retry and failure mapping

The adapter retries a retryable API operation at most twice after the initial attempt, with 200 ms and 600 ms delays. It does not retry authentication/authorization, expiry, disabled-account, TLS, or malformed-response failures.

| Signal | Application result |
| --- | --- |
| HTTP 401/403 | Authentication failed |
| Auth-endpoint HTTP 404 | Incompatible server |
| Other HTTP 404 | Stream unavailable |
| HTTP 408/429/502/503/504 | Temporary server failure, retryable |
| HTTP 509 | Connection limit reached |
| Other HTTP 5xx | Server unavailable, retryable |
| Socket timeout | Timeout, retryable |
| Unknown host/connect failure | Server unavailable, retryable |
| No active Android network | No network, retryable |
| TLS validation failure | TLS failure, not retryable |
| Other response/HTTP status | Invalid server response |

The network status check identifies whether Android currently has an active network; it does not claim that network has validated Internet access.

## Live stream URL selection

`XtreamStreamUrlFactory` chooses a format in this order:

1. channel says `m3u8` and the account advertises `m3u8`;
2. account advertises `m3u8`;
3. channel says `ts`;
4. account advertises `ts`;
5. channel says `m3u8`;
6. otherwise `ts`.

The resulting URL uses encoded path segments under `live/`. HLS is declared to Media3 as `application/x-mpegURL`; TS is declared as MPEG-2 TS.

The provider's `direct_source` field is parsed when valid, but the current Room entity does not persist it and playback does not follow it. This avoids an unreviewed second credential/host path in the first milestone, but some provider-specific channels may therefore fail until direct-source policy is implemented.

## Movie stream URL construction

`XtreamStreamUrlFactory.buildMovieUrl` produces `movie/{username}/{password}/{movieId}.{extension}` using encoded path segments, exactly like the live builder. The URL embeds the account credentials in its path, so it must never be logged, stored in UI state, or carried through navigation.

Only these container extensions are accepted: `mp4`, `mkv`, `avi`, `m4v`, `mov`, `ts`, `webm`. Building a URL with anything else fails immediately, so an arbitrary provider string cannot reach a media URL or a decoder.

`sanitizeVodExtension` normalizes case and a leading dot and returns null for anything off the list. `selectVodExtension` applies the Xtream-conventional `mp4` default when a provider omits or misreports the container; that keeps such a title playable rather than unreachable, and a genuinely different container fails later with the normal safe playback error. Movie playback calls both. The whitelist is shared with Series episodes, which is why it is named for on-demand content rather than for Movies.

## Episode stream URL construction

`XtreamStreamUrlFactory.buildEpisodeUrl` produces `series/{username}/{password}/{episodeId}.{extension}` with encoded path segments, and accepts the same container whitelist as Movies. Episodes live under `series/`, not `movie/`.

An episode is addressed by the provider's own episode ID. Season and episode numbers are display values — providers repeat, renumber, and sometimes omit them — so identity is never derived from them. Nothing calls this yet; the Series player slice does.

### Series parsing

`get_series` reuses the live and VOD listing rules: array or keyed object, entries without a stable `series_id` skipped, de-duplication keeping the first occurrence, neutral fallback names, and `null`/`undefined`/`N/A` treated as missing. It is **streamed** like the other two listings from the start, because a provider carrying six figures of movies carries a Series listing of the same order.

`get_series_info` splits descriptive fields into `info` and episodes into an `episodes` object keyed by season number; an array is accepted too. Episodes are flattened into one list ordered by season, then episode number, then ID, with the season on the episode itself winning over the key it was filed under, because providers leave those inconsistent. An episode without a provider ID is skipped rather than given a synthesised one. A payload whose `info.series_id` differs from the requested ID is rejected rather than attributed to the requested series.

## Real-provider verification

Because Xtream is a de facto ecosystem with provider differences, release confidence requires manual testing with an authorized account. Do not commit captured responses unless every credential, authenticated URL, customer identifier, and private host has been replaced.

Verify on a physical device where possible:

1. Test a canonical HTTPS base URL with separate username/password fields and confirm account status, expiry, and connection counts match the provider portal.
2. Test the same disposable account through both a `get.php?...` link and a `player_api.php?...` link. Confirm the extracted server path/port and account are identical to manual entry, without recording the real links.
3. Verify malformed links, missing/repeated credential parameters, and a credential-free arbitrary M3U playlist are rejected locally with a safe message.
4. If the provider requires a base subpath or port, verify both are preserved.
5. Verify category and channel totals, Unicode names, missing artwork, missing/unknown category IDs, and duplicate IDs.
6. Verify at least one advertised HLS stream and one TS stream if both are available.
7. Verify a wrong password, expired account if safely available, unreachable host, timeout, and exceeded connection limit produce safe messages.
8. Relaunch offline after one successful refresh and confirm cached data remains intact.
9. Review debug output for accidental full URLs or credentials before sharing any diagnostic artifact.

The VOD listing and detail actions have been exercised against the user's own authorized provider through the Movies screens: browsing, filtering, and sorting all work there. Movie media URLs remain unverified because no screen plays one yet.

For HTTP-only providers, test only on a trusted network and treat the warning as a real security limitation, not a cosmetic alert.

## Known compatibility limits

- No MAG/Stalker, arbitrary credential-free M3U playlist import, portal scraping, or DRM support. Credential-bearing Xtream `get.php`/`player_api.php` links are accepted only as an alternative way to enter the same account details.
- No provider-specific headers/cookies or configurable user-agent mode. API calls use OkHttp's default; playback sends the fixed `KilluasIPTV/0.1` user agent.
- No API redirect following.
- Of the EPG actions only `get_short_epg` is used; there is no XMLTV import and no whole-guide download. Provider titles and descriptions are usually Base64 and sometimes plain, so both are accepted; an entry without usable `start_timestamp`/`stop_timestamp` values is dropped rather than placed from the offset-free `start`/`end` strings. VOD listing and detail actions work against the user's provider; VOD media URLs are exercised by Movie playback but have not yet been verified against a real provider. Series actions exist at the adapter level only and have never been exercised against a real provider.
- No server-side pagination; the standard full live arrays are downloaded on refresh.
- Only `m3u8` and `ts` live URL layouts are generated.
- Format selection happens before playback; a failed HLS URL is not automatically retried as TS, or vice versa.
- A server that returns an authenticated but structurally incompatible payload is rejected rather than guessed at.
