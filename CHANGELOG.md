# Changelog

All notable changes to the Mebius Android SDK.

This SDK follows [Semantic Versioning](https://semver.org/). The public API is
stable within a major version; breaking changes to the API contract result in a
major version bump across **all** Mebius client SDKs simultaneously.

## 0.2.3
- **Fixed: a viewer could get stuck on a black frame forever on the real-time
  route.** `PlayerEvent.Playing` fired the moment libwebrtc handed over the video
  track, which happens at negotiation and holds whether or not media follows. That
  cancelled the player's 8-second first-frame watchdog in exactly the case the
  watchdog exists for, so a route that connected and sent nothing reported success,
  the player never advanced to the next route, and there was no error to react to.
  `Playing` now means the decoder produced a frame.
- Two consequences worth knowing. `LOW_LATENCY` can now legitimately fall off the
  real-time route onto a buffered one — that is the fix working, not a regression.
  And the 8-second budget now has to cover the first keyframe as well as
  negotiation; a publisher that is slow to send one will fall back to HLS rather
  than fail.
- A sink is no longer leaked when playback stops. The field holding it was written
  on libwebrtc's signaling thread and read on the main thread with no barrier, so
  teardown could miss it — and `removeSink` is what frees the native wrapper.

  Known limitation, unchanged: a broadcast with no camera — audio only — cannot be
  played on the real-time route, on any Mebius SDK. Audio arrives, but the
  first-frame budget is waiting for a picture that never comes. Publish with video
  if you need the real-time route.

## 0.2.2
- No entry was recorded at release. It published H264 preference on the publishing
  transceiver: libwebrtc negotiated VP8, which the gateway's segment-based
  deliveries cannot carry, so viewers off the real-time route received audio only
  while the device showed a healthy preview.

## 0.2.1
- `MebiusPlayer.mode` is now public. It was private through 0.2.0, so Android was
  the only Mebius SDK where an app could not read back which route a player was on
  (Flutter and iOS both expose it). Nothing in this repo could catch that: every
  consumer here lives in the same module, and `private` only bites from outside.

## 0.2.0
- **Breaking:** `PlaybackMode` gains `AUTO`, which breaks exhaustive `when`
  expressions, and `createPlayer()` defaults to it instead of `LOW_LATENCY`. The old
  default opened a per-viewer real-time session for every member of an audience that
  did not need one; real-time is now opt-in via `createMonitor()`.
- **Fixed: scalable playback could never have worked.** The manifest URL was built as
  `{gateway}/hls/{id}/index.m3u8`, a prefix the gateway neither routes nor
  allowlists, and it carried no token — so the request could only return 404 or 401.
  A test had pinned that broken URL as correct.
- **Fixed: publishing and sub-second playback could never have worked.** WHIP/WHEP
  sent the token only as an `Authorization: Bearer` header, which the gateway's auth
  hook does not read; it reads `?token=` from the query.
- `Mebius.connect(token, deliveries)` accepts the ordered route list your backend
  receives with the token. Forwarding it is what puts a viewer on the nearest edge;
  without it every viewer is served from Mebius origin, which on mobile is a
  per-viewer bill rather than none. `MebiusDelivery.isResolvable` refuses any path
  that is not plainly gateway-relative, because a delivery path is untrusted response
  data and the token is a bearer credential.
- Playback walks that list with an 8-second first-frame budget per route. A route
  that opens is not yet a route that plays: an edge with no ingest answers 200 with an
  empty stream, and a real-time connection reports itself connected while zero frames
  arrive. Neither raises an error, so playback previously sat on a black frame.
- New `createMonitor()` for watching the other side of a co-broadcast.
- The sample app compiles again — it imported `androidx.compose.foundation.layout.weight`,
  which resolves to an internal declaration. Broken before this release, unrelated to it.

## 0.1.0
- Initial release: `init`/`connect`, broadcaster (start/stop/switchCamera/mic/camera), player (low-latency & scale modes, volume), `MebiusVideoView`, listener + Flow event APIs.
