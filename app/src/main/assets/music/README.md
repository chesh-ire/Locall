# Local Spotify catalogue (optional fast-path)

`catalogue.json` is an **optional** local lookup table of favourite songs for
*instant* direct playback — an array of `{ "title", "artist", "uri" }`, where
`uri` is a `spotify:track:<id>` deep link.

How playback works for a spoken "play …":

1. **Catalogue hit** → opens that exact `spotify:track:` URI and nudges it to
   start playing. Fast, no model, most reliable.
2. **Anything else** → LocaLL opens Spotify search and uses the **Accessibility
   service** to tap the top result, so *any* song plays — it doesn't need to be
   catalogued. (Enable Accessibility for LocaLL for this to work; without it, the
   app just opens the search page.)

LocaLL itself makes **no network requests** — it only fires Android `ACTION_VIEW`
intents and drives Spotify's on-screen UI. Spotify does its own networking; that's
fine.

Curating the catalogue is purely a speed/reliability optimisation for songs you
play a lot. To add one, in Spotify tap ⋯ → *Share* → *Copy Spotify URI* and paste
the `spotify:track:…` value. A wrong id just opens the wrong song (treated as
data, never crashes).
