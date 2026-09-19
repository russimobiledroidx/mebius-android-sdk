# Changelog

## 0.3.0

- A session can now outlive the token it opened with. Pass `getToken` to
  `Mebius.connect` and the SDK mints a fresh credential shortly *before* `exp`
  rather than reacting to `TOKEN_EXPIRED` afterwards, retrying with backoff for
  as long as the current token is still valid — so `TOKEN_EXPIRED` now means the
  credential genuinely ran out, not that one mint failed. Each renewal emits
  `ClientEvent.TokenRefreshed` and calls `MebiusClientListener.onTokenRefreshed`.

  This is what a camera publisher needed: a match longer than the token's life
  used to cost a visible reconnect in the middle of it.

  A renewed token is refused — and retried, not fatal — when it is scoped to a
  different stream than the session, or when it does not outlive the token it
  replaces. A provider's late answer is also dropped if `updateToken` replaced
  the credential while it was being fetched, so the app's own swap always wins.

- `updateToken` now refuses a blank token, and one scoped to a different stream,
  with `IllegalArgumentException`. Silently accepting a credential for another
  stream would not renew the session — it would break it on the next request,
  far from the line that caused it. Replacing a valid token is unchanged: in
  place, no renegotiation, no reconnect.

- `MebiusPlayer.qualities` reports the renditions a stream can actually be
  switched between, and `setQuality(id)` selects one. `qualities` is empty for
  every Mebius stream today — the engine publishes a single rendition and does
  no ladder transcoding — which is the cue for a UI to HIDE its quality menu
  rather than offer a choice that does not exist. `setQuality` throws for an id
  that is not on offer instead of silently doing nothing; throwing does not
  touch playback. The list is per delivery route, announced through
  `PlayerEvent.QualitiesChanged` / `onQualitiesChanged` once per accepted route.

- Behaviour without `getToken` is unchanged. No renewal is scheduled, no new job
  is armed, no coroutine scope is even allocated, and an expired token still
  surfaces exactly when and how it did in 0.2.3 — proven by a test rather than
  asserted.

- **Call `disconnect()` when you are done with a client.** That was good hygiene
  before; with `getToken` it is required. A renewing session re-arms itself after
  every successful mint, and a pending renewal is held by the coroutine scheduler
  rather than by your object graph — so a client that is merely dropped keeps
  running, and keeps whatever `listener` points at (commonly an Activity or
  ViewModel) reachable for the life of the process.
