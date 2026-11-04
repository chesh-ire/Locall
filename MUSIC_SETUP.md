# Music: search-first playback + personal listening history

LocaLL plays music by voice through Spotify. Two pieces matter:

1. **Search-first resolution** — any spoken request ("play Teardrop by Massive Attack") is resolved
   to a real Spotify track via the Web API, then played deterministically by opening its
   `spotify:track:…` URI. No fixed in-app catalogue is required.
2. **Local listening history** — every play is recorded on-device (Room), enabling personal commands
   like "play my most played", "play my favourite artist", "play what I was listening to".

## Playback paths (in priority order)

| Path | When | Reliability |
|------|------|-------------|
| Catalogue (`assets/music/catalogue.json`) | Known track, offline cold-start | Deterministic (URI) |
| **Spotify Web API → URI** | Any request, credentials configured | Deterministic (URI) |
| Accessibility search (`playViaSpotifySearch`) | API disabled / no match | Best-effort (drives Spotify's search UI) |
| Open Spotify search screen | All else failed | User taps manually |

The catalogue is now only an **offline cold-start seed** — the Web API supersedes it for anything
not already listed. You can prune it to a few favourites or leave it.

## Spotify credentials (optional but recommended)

Track search uses the **client-credentials** flow — an app token, **no user login**.

1. Create an app at <https://developer.spotify.com/dashboard> → copy the **Client ID** and
   **Client Secret**. (No redirect URI or user scopes are needed for `/v1/search`.)
2. Add them to `local.properties` (git-ignored — never commit secrets):

   ```properties
   spotify.clientId=xxxxxxxxxxxxxxxxxxxxxxxx
   spotify.clientSecret=xxxxxxxxxxxxxxxxxxxxxxxx
   ```

   CI/headless builds may instead export `SPOTIFY_CLIENTID` / `SPOTIFY_CLIENTSECRET`.
3. Rebuild. `app/build.gradle.kts` injects these into `BuildConfig`; `SpotifyApi.isConfigured`
   flips on automatically.

**Without credentials** the app still builds and runs — `SpotifyApi` disables itself and playback
falls back to the Accessibility search flow.

## Personal commands

Backed by `voice/music/history/` (one Room row per track: title, artist, URI, request count,
first/last played). Recognized by `MusicIntents.personal()`:

- "play my most played" / "the songs I listen to the most" → `PlayMostPlayed`
- "play my favourite artist" / "something by my favourite artist" → `PlayFavouriteArtist`
- "play what I was listening to" / "what I played recently" → `PlayRecent`

Each resolves to a stored URI and plays deterministically; if a history row lacks a URI it falls
back through the normal resolve-or-search path.
