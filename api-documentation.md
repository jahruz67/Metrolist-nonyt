# Stash API Documentation — Music Services

> **Purpose:** Document every API Stash uses to discover, search, match, download, and stream music — excluding pure authentication/authorization flows (Google OAuth device flow, Spotify auth token exchange, etc.).

---

## Table of Contents

1. [Spotify Web API v1](#1-spotify-web-api-v1)
2. [Spotify GraphQL Partner API](#2-spotify-graphql-partner-api)
3. [YouTube Music InnerTube API](#3-youtube-music-innertube-api)
4. [squid.wtf — Qobuz Proxy (Primary)](#4-squidwtf--qobuz-proxy-primary)
5. [kennyy.com.br — Qobuz Proxy (No-Captcha)](#5-kennyycombr--qobuz-proxy-no-captcha)
6. [arcod.xyz — Qobuz Proxy (Authenticated)](#6-arcodxyz--qobuz-proxy-authenticated)
7. [amz.squid.wtf — Amazon Music Proxy](#7-amzsquidwtf--amazon-music-proxy)
8. [LRCLIB — Lyrics API](#8-lrclib--lyrics-api)
9. [YouTube Music InnerTube Lyrics](#9-youtube-music-innertube-lyrics)
10. [Last.fm API (via Cloudflare Worker)](#10-lastfm-api-via-cloudflare-worker)
11. [yt-dlp — YouTube Extraction Backend](#11-yt-dlp--youtube-extraction-backend)
12. [Source Resolution Order](#12-source-resolution-order)

---

## 1. Spotify Web API v1

### What It Is

The official Spotify Web API at `api.spotify.com/v1`. Stash uses this with **client_credentials** OAuth2 (Spotify's public-application flow) to fetch **public playlist track data** without needing the user's personal token. This avoids the aggressive 429 rate-limiting that Spotify applies to sp_dc-derived tokens on this API.

- **Base URL:** `https://api.spotify.com/v1`
- **Type:** REST (GET)
- **Library:** OkHttp (raw HTTP calls, no official Spotify SDK)

### Authentication

Uses well-known client credentials from SpotDL (an open-source Spotify-Downloader project). Token is obtained via POST to `https://accounts.spotify.com/api/token` with `grant_type=client_credentials`. The resulting `access_token` is used as a Bearer token.

### Key Endpoints Used

#### `GET /playlists/{playlist_id}/tracks`

Fetch tracks from a public playlist.

| Parameter | Type | Description |
|-----------|------|-------------|
| `playlist_id` | path | Spotify playlist ID |
| `limit` | query | Max items per page (default: 50) |
| `offset` | query | Pagination offset |
| `fields` | query | Response field filter for performance |

**Response shape:**
```json
{
  "items": [
    {
      "track": {
        "id": "track_id",
        "name": "Track Name",
        "artists": [{ "id": "...", "name": "Artist" }],
        "album": { "id": "...", "name": "Album", "images": [...] },
        "duration_ms": 240000,
        "uri": "spotify:track:...",
        "external_ids": { "isrc": "US..." }
      }
    }
  ],
  "total": 100,
  "next": "https://api.spotify.com/v1/playlists/.../tracks?offset=50&limit=50"
}
```

#### `GET /tracks/{id}`

Fetch a single track's metadata, including ISRC.

#### `GET /search`

Search the Spotify catalog (used by SpotifyUriResolver for cross-matching YouTube tracks).

### Rate Limiting

The `client_credentials` token path gets rate-limited by Spotify at a much higher threshold than user tokens. Stash uses `SpotifyRateLimitException` detection: on 429 the caller pauses and retries with exponential backoff ([500ms, 1500ms]).

### Source Code Reference

| File | Purpose |
|------|---------|
| `data/spotify/.../SpotifyApiClient.kt` | Main API client with all Spotify endpoints |
| `data/spotify/.../model/SpotifyModels.kt` | Response DTOs |
| `data/download/.../lossless/spotifyresolve/SpotifyUriResolver.kt` | Resolves non-Spotify tracks to Spotify URLs |

---

## 2. Spotify GraphQL Partner API

### What It Is

Spotify's internal GraphQL API at `api-partner.spotify.com`. This is **not** an official public API — it's the same endpoint the Spotify web player uses. Stash hits it to fetch **user-specific data** that the public Web API won't serve with client_credentials: the user's library playlists, Daily Mixes, and Liked Songs.

- **Base URL:** `https://api-partner.spotify.com`
- **Type:** GraphQL (sent as GET with query params)
- **Library:** OkHttp (raw HTTP)

### Authentication

Uses a **two-token** scheme:
1. **Access token** — derived from the user's `sp_dc` browser cookie via Spotify's web-player token endpoint
2. **Client token** — acquired from `https://clienttoken.spotify.com/clienttoken` using the clientId from the access-token response

Both are sent as headers:
```
Authorization: Bearer <access_token>
Client-Token: <client_token>
Additional headers mimic the Spotify web player
```

### Key Operations

#### `fetchPlaylist` — Fetch tracks from a user playlist

GraphQL query sent as GET param `operationName=fetchPlaylist&variables={...}&extensions={...}`:

```graphql
query fetchPlaylist($uri: URI!, $offset: Int, $limit: Int) {
  playlistV2(uri: $uri) {
    content {
      items { ... on PlaylistItem { item { ...trackFields } } }
      totalCount
      pagingInfo { nextOffset }
    }
  }
}
```

#### `libraryV3` — Fetch user's library playlists

GraphQL query to list all playlists the user has created or saved.

#### `libraryTracks` — Fetch Liked Songs

GraphQL query for the user's Liked Songs (tracks with the red heart). Uses `offset`/`limit` pagination.

### Error Handling

- **401 →** Refresh the access token (sp_dc re-exchange) and retry once
- **400 →** Client token may be stale; re-acquire it and retry
- **Other non-2xx →** Return null (graceful degradation)

### Source Code Reference

| File | Purpose |
|------|---------|
| `data/spotify/.../SpotifyApiClient.kt` | All GraphQL operation builders + response parsers |
| `data/spotify/.../SpotifyLibraryParser.kt` | Parses library responses into unified models |
| `data/spotify/.../SpotifyTrackParser.kt` | Parses track-level responses |

---

## 3. YouTube Music InnerTube API

### What It Is

Google's internal, undocumented API that powers youtube.com and music.youtube.com. Stash uses it directly (without any official SDK) to search YouTube Music, browse the user's library, get player URLs, and fetch lyrics. This is the **core API** for all YouTube Music interactions.

- **Base URL:** `https://music.youtube.com/youtubei/v1` (primary) or `https://www.youtube.com/youtubei/v1` (with API key)
- **Type:** REST (POST with JSON body)
- **Library:** OkHttp (raw HTTP)

### Authentication

Optional. For unauthenticated requests (search, player), no auth is needed. For user-specific data (library, playlists), Stash sends a **YouTube session cookie** and an **`SAPISIDHASH` authorization header** derived from the SAPISID cookie value.

### Key Endpoints

#### `POST /youtubei/v1/search`

Search YouTube Music. Request body includes a `context` with client info and a `query` parameter.

**Request body:**
```json
{
  "context": {
    "client": {
      "clientName": "WEB_REMIX",
      "clientVersion": "1.20250227.01.00",
      "hl": "en",
      "gl": "US"
    }
  },
  "query": "Radiohead Karma Police"
}
```

**Response** is deeply nested InnerTube renderers. Stash parses:
- `musicShelfRenderer` → song/album/artist results
- `musicResponsiveListItemRenderer` → individual items
- `musicTwoRowItemRenderer` → grid items (albums, playlists)

#### `POST /youtubei/v1/browse`

Browse a specific page by ID. Used for:
- **Home feed:** browseId `FEmusic_home`
- **Library playlists:** browseId `FEmusic_liked_playlists`
- **Album/artist pages:** browseId from search results
- **Continuation pagination:** using `ctoken` and `continuation` query params

#### `POST /youtubei/v1/next`

Get the "watch next" response for a video. Used for lyrics discovery (finding the `MPLY...` lyrics browseId).

#### `POST /youtubei/v1/player`

Get streaming data (audio URLs) for a video. Used in the InnerTube fast lane for previews.

### InnerTube Client Variants

Stash sends requests with **different client identities** to exploit YouTube's varying defenses:

| Variant | clientName | clientVersion | Notes |
|---------|-----------|---------------|-------|
| `ANDROID_VR` | `ANDROID_VR` | `1.60.19` | Oculus Quest 3 — often returns unciphered URLs |
| `IOS` | `IOS` | `21.02.3` | iPhone — direct audio URLs, fast lane |
| `TVHTML5_SIMPLY` | `TVHTML5_SIMPLY` | `7.20250220` | Smart TV — less stringent ciphering |
| `ANDROID_MUSIC` | `ANDROID_MUSIC` | `7.02.52` | Android YT Music app |
| `WEB_REMIX` | `WEB_REMIX` | `1.20250227.01.00` | Web player — most restrictive, last resort |

### Source Code Reference

| File | Purpose |
|------|---------|
| `data/ytmusic/.../InnerTubeClient.kt` | Low-level HTTP client for InnerTube requests |
| `data/ytmusic/.../YTMusicApiClient.kt` | High-level wrapper that parses InnerTube responses |
| `data/ytmusic/.../SearchResponseParser.kt` | Parses search results |
| `data/ytmusic/.../AlbumResponseParser.kt` | Parses album details |
| `data/ytmusic/.../ArtistResponseParser.kt` | Parses artist profiles |
| `data/ytmusic/.../PlaybackTrackingParser.kt` | Parses playback tracking data |
| `data/ytmusic/.../ResponseParserHelpers.kt` | Shared JSON navigation helpers |

---

## 4. squid.wtf — Qobuz Proxy (Primary)

### What It Is

A community-run proxy (`qobuz.squid.wtf`) that wraps the official Qobuz API behind the operator's own paid credentials. Stash uses it to **search the Qobuz catalog** and **download lossless FLAC files**. No Qobuz subscription required from the user.

- **Base URL:** `https://qobuz.squid.wtf/api`
- **Type:** REST (GET)
- **Library:** OkHttp (raw HTTP)
- **Also available:** `us.qobuz.squid.wtf`, `eu.qobuz.squid.wtf` (regional mirrors)

### Authentication

**Captcha cookie gated:** The `/api/download-music` endpoint requires a `captcha_verified_at` cookie, obtained by solving an ALTCHA proof-of-work on the squid.wtf website. The cookie is a millisecond timestamp with a ~30-minute sliding window.

### Key Endpoints

#### `GET /api/get-music` — Search Qobuz catalog

| Parameter | Type | Description |
|-----------|------|-------------|
| `q` | query | Free-text search query |
| `offset` | query | Pagination offset (server limit=10) |
| `tokenCountry` | header | Optional ISO-2 country code for region-bypass |

**Response:**
```json
{
  "success": true,
  "data": {
    "tracks": { "items": [{
      "id": 12345678,
      "title": "Karma Police",
      "album": { "title": "OK Computer" },
      "performer": { "name": "Radiohead" },
      "isrc": "GBAYE9700013",
      "duration": 264,
      "maximumBitDepth": 24,
      "maximumSamplingRate": 192000,
      "streamable": true
    }]}
  }
}
```

#### `GET /api/download-music` — Get signed CDN URL

| Parameter | Type | Description |
|-----------|------|-------------|
| `track_id` | query | Qobuz track ID from search |
| `quality` | query | 5=MP3 320, 6=FLAC CD, 7=FLAC Hi-Res 96, 27=FLAC Hi-Res 192 |

**Response:**
```json
{
  "success": true,
  "data": {
    "url": "https://akamai.cdn.qobuz.com/...?etsp=1719000000",
    "codec": "flac",
    "bitrate": 2116,
    "sampling_rate": 192000,
    "bit_depth": 24
  }
}
```

The `etsp` param is a Unix-epoch-seconds **signature expiry**. Stash parses it to manage URL caching.

### Rate Limiting

**1 request per second** via `AggregatorRateLimiter` (in-memory token bucket). Deliberately conservative — squid.wtf operates one paid Qobuz account for all users.

### Source Code Reference

| File | Purpose |
|------|---------|
| `data/download/.../lossless/qobuz/QobuzApiClient.kt` | HTTP client for squid.wtf |
| `data/download/.../lossless/qobuz/QobuzSource.kt` | LosslessSource implementation with scoring |
| `data/download/.../lossless/qobuz/QobuzModels.kt` | Response DTOs |
| `data/download/.../lossless/squid/SquidWtfCaptchaInterceptor.kt` | Attaches captcha cookie |
| `data/download/.../lossless/squid/AltchaSolver.kt` | ALTCHA proof-of-work solver |

---

## 5. kennyy.com.br — Qobuz Proxy (No-Captcha)

### What It Is

A secondary Qobuz proxy at `qobuz.kennyy.com.br`. Runs the same Qobuz-DL codebase as squid.wtf but **no captcha gate** — the download endpoint is openly accessible without any cookie.

- **Base URL:** `https://qobuz.kennyy.com.br/api`
- **Type:** REST (GET)
- **Library:** OkHttp (raw HTTP)

### Authentication

**None required.** The client only sends a custom User-Agent:
```
User-Agent: Stash-Android/1.0 (+https://github.com/rawnaldclark/Stash)
```

### Key Endpoints

Identical shape to squid.wtf:

#### `GET /api/get-music` — Search Qobuz catalog (same params as squid.wtf)

#### `GET /api/download-music` — Get signed CDN URL (same params as squid.wtf)

### Response Shape

Same `{success, data, error}` envelope and same `etsp`-signed CDN URLs. Stash reuses `QobuzModels` DTOs and `QobuzApiException` types.

### Rate Limiting

Same `AggregatorRateLimiter` with 1 req/s default, keyed as `kennyy_qobuz`.

### Source Code Reference

| File | Purpose |
|------|---------|
| `data/download/.../lossless/kennyy/KennyyApiClient.kt` | HTTP client for kennyy.com.br |
| `data/download/.../lossless/kennyy/KennyySource.kt` | LosslessSource implementation |
| `core/media/.../streaming/KennyyStreamResolver.kt` | Streaming resolver |
| `core/media/.../streaming/KennyyHealthMonitor.kt` | Health monitoring |

---

## 6. arcod.xyz — Qobuz Proxy (Authenticated)

### What It Is

A third Qobuz proxy at `arcod.xyz`. Uses a **Supabase-authenticated job queue** for downloads and has a **private streaming endpoint** (base URL injected at build time, never committed to the public repo).

- **Base URL:** `https://arcod.xyz/api` (catalog API)
- **Stream URL:** Configured via `arcod.streamBase` in `local.properties` or `ARCOD_STREAM_BASE` env var
- **Type:** REST (GET/POST)
- **Library:** OkHttp (raw HTTP)

### Authentication

1. **Catalog API** (`/api/get-music`): Open, no auth
2. **Download/Stream API**: Requires a Supabase Bearer token (managed by `ArcodAuthInterceptor` + `ArcodTokenRefresher`)

### Key Endpoints

#### `GET /api/get-music?q=<query>&offset=0`

Search Qobuz catalog. Response same shape as squid.wtf.

#### `POST /api/v2/downloads`

Create a download job (enqueue a FLAC render). Processed asynchronously.

#### `GET /api/v2/downloads/<id>`

Poll job status until `completed`. Completed response carries a short-lived, open, Range-capable download URL.

#### Stream Endpoint (private)

GET on `streamBase/{trackId}?quality=<code>`. Returns either a bare URL string or JSON with `url`/`streamUrl`/`downloadUrl`.

### Rate Limiting

Detects HTTP 429 (`ArcodRateLimitedException`) and applies source-specific backoff.

### Source Code Reference

| File | Purpose |
|------|---------|
| `data/download/.../lossless/arcod/ArcodClient.kt` | HTTP client |
| `data/download/.../lossless/arcod/ArcodSource.kt` | LosslessSource implementation |
| `data/download/.../lossless/arcod/ArcodMatcher.kt` | Track matching/scoring |
| `data/download/.../lossless/arcod/ArcodApiModels.kt` | Response DTOs |
| `data/download/.../lossless/arcod/ArcodAuthInterceptor.kt` | Adds Bearer token |
| `data/download/.../lossless/arcod/ArcodCredentialStore.kt` | Stores user credentials |
| `data/download/.../lossless/arcod/ArcodTokenRefresher.kt` | Refreshes the Supabase token |
| `core/media/.../streaming/ArcodStreamResolver.kt` | Streaming resolver |

---

## 7. amz.squid.wtf — Amazon Music Proxy

### What It Is

A community-run proxy at `amz.squid.wtf/api` for **Amazon Music's lossless catalog**. Amazon serves CENC-encrypted CMAF that must be **decrypted client-side** with an AES key before playback.

- **Base URL:** `https://amz.squid.wtf/api`
- **Type:** REST (POST for search/track, GET for stream)
- **Library:** OkHttp (raw HTTP)

### Authentication

Uses an `x-captcha-token` header attached automatically by `AmzCaptchaInterceptor` (ALTCHA-based captcha solving).

### Key Endpoints

#### `POST /api/search`

Search Amazon Music catalog.

**Request body:**
```json
{
  "query": "Radiohead Karma Police",
  "country": "US",
  "content_type": "TRACK",
  "limit": 25
}
```

**Response:** Array of `{asin, artist, title, album, cover, duration, isrc}` objects.

#### `POST /api/track`

Resolve an ASIN to full metadata including AES decryption key.

**Request body:** `{"asin": "B0XXXXXXXX", "country": "US"}`

**Response:** `{asin, meta, streamUrl, decryptionKey, codec, sampleRateHz, bitrateBps}`

#### `GET /api/stream`

Get encrypted stream URL. Parameters: `asin`, `country=US`, `tier` (best/high/medium/low).

### Client-Side Decryption

1. Fetch encrypted bytes from stream URL
2. Decrypt with `ffmpeg -decryption_key <hex_key>`
3. Return `file://` URL to player

### Source Code Reference

| File | Purpose |
|------|---------|
| `data/download/.../lossless/amz/AmzApiClient.kt` | HTTP client |
| `data/download/.../lossless/amz/AmzSource.kt` | LosslessSource implementation |
| `data/download/.../lossless/amz/AmzMatcher.kt` | Track matching |
| `data/download/.../lossless/amz/AmzDecryptor.kt` | CENC CMAF decryption |
| `data/download/.../lossless/amz/AmzCaptchaInterceptor.kt` | x-captcha-token header |
| `data/download/.../lossless/amz/AmzAltchaSolver.kt` | Captcha solving |
| `data/download/.../lossless/amz/AmzStreamFileProvider.kt` | Decrypted file cache |
| `core/media/.../streaming/AmzStreamResolver.kt` | Streaming resolver |

---

## 8. LRCLIB — Lyrics API

### What It Is

An open, community-maintained lyrics API at `lrclib.net`. Provides both **plain-text and synced (LRC) lyrics**. This is Stash's **primary lyrics source**.

- **Base URL:** `https://lrclib.net/`
- **Type:** REST (GET)
- **Library:** OkHttp (raw HTTP)
- **Auth:** None required

### Key Endpoints

#### `GET /api/get`

Look up lyrics by exact track parameters.

| Parameter | Type | Description |
|-----------|------|-------------|
| `track_name` | query | Track title |
| `artist_name` | query | Artist name |
| `album_name` | query | (Optional) Album name |
| `duration` | query | Track duration in seconds |

**Response:**
```json
{
  "id": 123456,
  "trackName": "Karma Police",
  "artistName": "Radiohead",
  "plainLyrics": "I lost my shape...",
  "syncedLyrics": "[00:12.00] I lost my shape...",
  "instrumental": false
}
```

#### `GET /api/search`

Search by query string when exact lookup fails. Parameter: `q=Artist Title`.

### Search Strategy

Uses a **duration ladder** [0, -1, +1, -2, +2, ..., -5, +5] to find matches with slightly-off durations. Falls back to `/api/search` with **Jaro-Winkler similarity** (threshold ≥ 0.85) + ±5 sec duration filter.

### Source Code Reference

| File | Purpose |
|------|---------|
| `data/lyrics/.../source/LrclibApi.kt` | API response DTO |
| `data/lyrics/.../source/LrclibLyricsSource.kt` | Full API client |
| `data/lyrics/.../source/LyricsSource.kt` | LyricsSource interface |

---

## 9. YouTube Music InnerTube Lyrics

### What It Is

A **secondary lyrics source** using YouTube Music's InnerTube API. Queried when LRCLIB misses (common for non-Western catalog). **Plain-text only** — no synced LRC.

### How It Works

**Step 1:** `POST /youtubei/v1/next` with `videoId` → find the `MPLY...` lyrics browseId in the tabs array.

**Step 2:** `POST /youtubei/v1/browse` with the `MPLY...` browseId → extract text from `musicDescriptionShelfRenderer.description.runs[*].text`.

Requires a `youtubeVideoId` on the track. Short-circuits to null if unavailable.

### Source Code Reference

| File | Purpose |
|------|---------|
| `data/lyrics/.../source/InnerTubeLyricsGateway.kt` | Interface |
| `data/lyrics/.../source/InnerTubeLyricsGatewayImpl.kt` | InnerTubeClient wrapper |
| `data/lyrics/.../source/YtMusicLyricsSource.kt` | LyricsSource implementation |

---

## 10. Last.fm API (via Cloudflare Worker)

### What It Is

Stash uses Last.fm's API to power **Stash Mixes** (radio stations). Generic reads are routed through a **Cloudflare Worker proxy** with edge caching to avoid rate-limiting the shared API key.

- **Upstream:** `https://ws.audioscrobbler.com/2.0/`
- **Proxy:** Configurable Cloudflare Worker URL
- **Type:** REST (GET)
- **Cache TTL:** 14 days

### Allowed Methods

| Method | Purpose |
|--------|---------|
| `tag.getTopTracks` | Top tracks for a tag |
| `tag.getTopArtists` | Top artists for a tag |
| `artist.getSimilar` | Similar artists |
| `artist.getTopTracks` | Artist's top tracks |
| `track.getSimilar` | Similar tracks |
| `track.getInfo` | Track metadata |

**Blocked params:** `api_key`, `api_sig`, `sk`, `user`, `username` (prevents signed/per-user leakage).

### Endpoint

```
GET /lastfm?method=tag.getTopTracks&tag=shoegaze&limit=50
```

Returns raw Last.fm JSON. Header `X-Stash-Cache: HIT | MISS`.

### Source Code Reference

| File | Purpose |
|------|---------|
| `infra/lastfm-proxy/src/index.js` | Cloudflare Worker |
| `infra/lastfm-proxy/wrangler.toml` | Deployment config |
| `infra/lastfm-proxy/README.md` | Deployment instructions |

---

## 11. yt-dlp — YouTube Extraction Backend

### What It Is

**yt-dlp** is a command-line program for downloading audio/video from YouTube. Stash bundles it as a native binary via the `youtubedl-android` library (JunkFood02). It serves as the **reliable fallback** for YouTube audio extraction when InnerTube's fast path fails.

- **Library:** `com.yausername:youtubedl-android` (JNI bindings)
- **Also bundled:** `ffmpeg` + `aria2c`, `QuickJS-NG` (for JS signature solving)

### How It's Used

#### Search: `ytsearch<N>:<query>`

```bash
ytsearch5:Radiohead Karma Police --dump-json --no-download --flat-playlist
```

Each result is one JSON line with `id`, `title`, `uploader`, `duration`, `webpage_url`.

#### YouTube Audio Extraction

Two modes:
1. **Fast (InnerTube)** — ~200ms. Direct InnerTube player API. Returns URL from `streamingData.adaptiveFormats[*].url`.
2. **Full (yt-dlp)** — ~11-35s. Runs yt-dlp with QuickJS for signature solving.

### Concurrency Limits

- **InnerTube fast path:** 8 concurrent
- **yt-dlp fallback:** 2 concurrent (CPU-intensive)
- **yt-dlp full extraction (streaming):** Serial (cap-1)

### Self-Updating

Updated to **nightly** channel on first download in a session.

### Source Code Reference

| File | Purpose |
|------|---------|
| `data/download/.../ytdlp/YtDlpManager.kt` | Binary lifecycle (init, update, warmup) |
| `data/download/.../preview/PreviewUrlExtractor.kt` | InnerTube + yt-dlp race logic |
| `data/download/.../matching/YouTubeSearchExecutor.kt` | yt-dlp-based search |
| `data/download/.../matching/InnerTubeSearchExecutor.kt` | InnerTube-based search |

---

## 12. Source Resolution Order

### Download (Lossless) Chain

1. **kennyy.com.br** — Qobuz proxy, no captcha
2. **squid.wtf** — Qobuz proxy, captcha-gated
3. **arcod.xyz** — Qobuz proxy, authenticated job queue
4. **amz.squid.wtf** — Amazon Music proxy, encrypted
5. **yt-dlp / InnerTube** — YouTube extraction (lossy)

Each source scores candidates by ISRC (0.95 confidence), title/artist similarity (Jaccard), and duration proximity. Minimum confidence: 0.5.

### Streaming Chain

1. **kennyy.com.br** — Qobuz lossless (primary)
2. **squid.wtf** — Qobuz lossless (captcha-gated backup)
3. **arcod.xyz** — Qobuz lossless (authenticated, foreground-only)
4. **amz.squid.wtf** — Amazon Music lossless (foreground-only, slow decrypt)
5. **YouTube (InnerTube → yt-dlp)** — Lossy fallback

### Search Chain

1. **InnerTube search** — ~200ms, single HTTP POST
2. **yt-dlp search** — ~3s, subprocess, fallback

### Lyrics Chain

1. **LRCLIB** — Primary, synced + plain-text
2. **YouTube Music InnerTube** — Fallback, plain-text only

---

## Summary Table

| API | Base URL | Auth | Rate Limit | Key Feature |
|-----|----------|------|------------|-------------|
| **Spotify Web API v1** | `api.spotify.com/v1` | client_credentials | High threshold | Public playlist data |
| **Spotify GraphQL** | `api-partner.spotify.com` | sp_dc + Client-Token | Medium | User library, mixes |
| **InnerTube (YT Music)** | `music.youtube.com/youtubei/v1` | Optional cookie | Unknown | Search, browse, player |
| **squid.wtf (Qobuz)** | `qobuz.squid.wtf/api` | Captcha cookie | 1 req/s | Lossless FLAC download |
| **kennyy.com.br (Qobuz)** | `qobuz.kennyy.com.br/api` | None | 1 req/s | Lossless FLAC, no captcha |
| **arcod.xyz (Qobuz)** | `arcod.xyz/api` | Supabase Bearer | 1 req/s | Job-based FLAC download |
| **amz.squid.wtf** | `amz.squid.wtf/api` | Captcha token | 1 req/s | Amazon Music lossless (encrypted) |
| **LRCLIB** | `lrclib.net` | None | Generous | Synced & plain lyrics |
| **Last.fm (proxy)** | Worker URL | Server-side key | 14d cache | Mixes recommendations |
| **yt-dlp** | N/A (native binary) | Cookie optional | 2 concurrent | YouTube audio extraction |

---

*Document generated from Stash codebase analysis — covers all music-related APIs used for discovery, search, match, download, and streaming.*
