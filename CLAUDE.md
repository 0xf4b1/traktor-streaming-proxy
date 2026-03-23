# traktor-streaming-proxy

## What This Project Does

A Kotlin/Ktor API server that impersonates Beatport's streaming API so Traktor DJ can stream music from YouTube Music, Spotify, and Tidal instead of Beatport. It works by DNS-spoofing `api.beatport.com` to localhost and serving responses in the format Traktor expects.

## Architecture

### Request Flow
1. Traktor sends HTTPS requests to `api.beatport.com`
2. DNS/hosts file redirects to `127.0.0.1:8443`
3. Proxy serves fake auth/license responses and translates music requests to the configured streaming sources
4. Tracks are downloaded/converted to MP4 and served back to Traktor

### Key Components

- **`Main.kt`** - Ktor server with all route handlers. Implements the subset of Beatport v4 API that Traktor uses (auth, genres, playlists, search, download). Global state maps track IDs between sources and Traktor's Long-based ID system.
- **`sources/ISource.kt`** - Interface all streaming providers implement. Pagination is state-based: `reset=true` starts fresh, `reset=false` gets next page.
- **`sources/Spotify.kt`** - Uses librespot for OAuth + streaming. Downloads OGG Vorbis, pipes through `ffmpeg` to convert to MP4. Requires ffmpeg on the system.
- **`sources/Tidal.kt`** - Device-flow OAuth via tidal-kt. Downloads DASH segments and concatenates to MP4. Persists auth tokens to config.properties.
- **`sources/Youtube.kt`** - Uses NewPipeExtractor (no API key needed). Downloads m4a audio directly.
- **`beatport/api/Data.kt`** - Serializable data models for all API responses.
- **`beatport/api/Utils.kt`** - Encodes alphanumeric track IDs into Longs (Traktor's format). Custom 6-bit-per-char encoding, max 10 chars; longer IDs split and stored in a lookup map.

### Beatport UI Mapping
Traktor's Beatport Streaming UI categories map to source features:
- **Genres** = streaming sources (Spotify, Tidal, YouTube) → saved/liked tracks
- **Curated Playlists** = followed artists per source
- **Playlists** = user playlists (all sources merged)
- **Top 100** = new releases / trending per source
- **Search** = queries across enabled sources (prefix with `spotify:` or `youtube:` to target one)

### ID Encoding
Playlist IDs are composite strings: `{1-based source index}{playlist index}`. Track IDs are encoded from alphanumeric strings to Longs via `Utils.encode()`. IDs longer than 10 chars are split: first 10 encoded, remainder stored in `traktorIdToTrackId` map.

## Build & Run

```bash
./gradlew build           # Build
./gradlew run             # Run locally
./gradlew installDist     # Create distribution in build/install/
```

- **Kotlin 2.0.20**, **Ktor 3.1.3**, **JVM 18+**
- Docker build available for Windows deployment

## Configuration

`config.properties` in the working directory:
- `sources.enabled` - comma-separated: `youtube`, `spotify`, `tidal`
- `search.enabled` - subset of sources for search (empty = all)
- `beatport.license` - `macos` or `windows`
- `server.useKeystore` - `true` for pre-built JKS (Docker/Windows), `false` for runtime-generated cert
- Tidal credentials: `tidal.clientId`, `tidal.clientSecret`
- Tidal tokens auto-persisted: `tidal.accessToken`, `tidal.refreshToken`, etc.

## Tests

```bash
./gradlew test
```

Unit tests in `src/test/kotlin/Test.kt` cover `Utils.encode()`/`decode()` round-trips.

## SSL Setup

- **macOS**: Runtime-generated self-signed cert + `SecTrustEvaluateStub.dylib` (DYLD_INSERT_LIBRARIES) to bypass Traktor's cert validation
- **Windows**: Pre-generated keystore.jks + Traktor.exe binary patching (public key replacement)
