# Changelog

## 0.3.0

- A delivery route that stops delivering is now reopened instead of leaving a
  frozen frame. Route selection ran exactly once, when playback started:
  whichever route produced the first frame served the rest of the session, and
  when it later died — a CDN edge restarting, the publisher reconnecting, the
  device changing network — the picture simply stopped. An `onEnded` after that
  point ended the watch outright, and a failure was reported with nothing
  attempted.

  On a 90-minute watch that looked like bad luck. On a channel that runs for a
  day it is a certainty, because every one of those causes happens more than
  once a day, and the viewer's word for it is a black screen.

  The player now supervises the route it accepted. A route that reports it
  ended, that fails after delivering, or that stalls for longer than ten
  seconds, is treated as lost: it is torn down and the full route list is walked
  again, because the usual causes take out one route and not the others.
  Reopening backs off (1s, 2s, 4s, 8s, 16s) and gives up after five consecutive
  attempts — bounded on purpose, since every viewer of one broadcast fails at
  the same instant and an unbounded retry from a full room is how a recovery
  mechanism becomes the outage. `PlayerEvent.Buffering` is emitted as soon as
  reopening starts, `Playing` when a route is serving again, and `Ended` — or
  the failure that lost the route — only once the budget is spent.

  Refreshed credentials need no handling here: the client renews the token on
  its own schedule whether or not anything is playing, and every route stamps
  the current token as it builds its URL, so a route reopened after a long stall
  connects with today's credential.

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
