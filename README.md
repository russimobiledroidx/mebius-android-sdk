# Mebius Android SDK

Broadcast and watch live streams from your Android app with a single, simple API.

[![JitPack](https://img.shields.io/badge/install-JitPack-brightgreen.svg)](https://jitpack.io/#russimobiledroidx/mebius-android-sdk)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

---

## 1. Requirements

| | |
|---|---|
| **minSdk** | 24 (Android 7.0) |
| **compileSdk** | 35 |
| **Language** | Kotlin |
| **Supported ABIs** | `armeabi-v7a`, `arm64-v8a`, `x86_64` |

> **Why minSdk 24?** The SDK bundles native media libraries whose prebuilts target API 24+. This is the lowest version supported across all media features.

### Permissions

Add to your app's `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<!-- Broadcasting only: -->
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

`INTERNET` is required for everything. `CAMERA` and `RECORD_AUDIO` are required **only when broadcasting**; watch-only apps may omit them.

> **Runtime permissions:** `CAMERA` and `RECORD_AUDIO` are dangerous permissions. You must request them at runtime (e.g. with `ActivityResultContracts.RequestMultiplePermissions`) **before** calling `broadcaster.start(...)`. If they are not granted, broadcasting fails with `MebiusError.PermissionDenied`.

---

## 2. Install

Two ways in. JitPack works today; Maven Central needs no extra repository line but
is not published yet.

### JitPack (available now)

The repo is public, so no auth token is needed. [JitPack](https://jitpack.io) builds
the SDK from a git tag and caches the `.aar`.

`settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

`build.gradle.kts`:

```kotlin
dependencies {
    // <ref> = a release tag (v0.2.1), a commit hash, or `main-SNAPSHOT`
    implementation("com.github.russimobiledroidx:mebius-android-sdk:v0.2.1")
}
```

Your project also needs `android.useAndroidX=true` in `gradle.properties` — the SDK
depends on AndroidX and media3, and Gradle fails the build without it rather than
warning.

The first resolve of a new tag is slower: JitPack builds it once on demand.

### Maven Central (not published yet)

Wired and ready — `:mebius:publishToMavenLocal` already produces the `.aar`,
`-sources.jar`, `-javadoc.jar` and a POM with the licence, developer and scm blocks
Central requires. What is missing is account-side only: a verified `io.mebius`
namespace and a GPG signing key. See [PUBLISHING.md](PUBLISHING.md).

Once it is live the coordinate becomes `io.mebius:mebius-android-sdk`, and the
`maven { }` line above is no longer needed because `mavenCentral()` is already in
almost every project.

---

## 3. Quick Start

### Authentication

Mebius authenticates with a **short-lived token** that your backend mints for the
signed-in user. **Your application secret never lives on the device.** When a
token expires you receive a `MebiusError` with code `TOKEN_EXPIRED`; fetch a fresh
token from your backend and reconnect.

### Initialize & connect

```kotlin
// Once, e.g. in Application.onCreate:
Mebius.init(
    context = this,
    appId = "your-app-id",
    gateway = "https://gateway.mebius.io", // your Mebius signaling endpoint
)

// After fetching a token from YOUR backend. The same response carries a
// `deliveries` array — pass it through as-is. Mebius orders it and picks from it.
// Without it playback still works, but every viewer is served from Mebius origin
// instead of the nearest edge, which on mobile is billed per viewer.
val body = JSONObject(responseText)
val client = Mebius.connect(
    token = body.getString("token"),
    deliveries = MebiusDelivery.fromTokenResponse(body),
)

client.listener = object : MebiusClientListener {
    override fun onConnected() { /* ready */ }
    override fun onDisconnected() { /* closed */ }
    override fun onError(error: MebiusError) {
        if (error.code == MebiusError.Code.TOKEN_EXPIRED) {
            // refresh token from backend, then reconnect
        }
    }
}
```

### Broadcast

```kotlin
val broadcaster = client.createBroadcaster(video = true, audio = true)

broadcaster.listener = object : MebiusBroadcasterListener {
    override fun onStarted(streamId: String) { /* live */ }
    override fun onStopped() { /* ended */ }
    override fun onError(error: MebiusError) { /* handle */ }
}

// Attach a preview view (see MebiusVideoView below):
broadcaster.attachPreview(videoView)

broadcaster.start("my-stream-id")

broadcaster.switchCamera()         // flip front/back
broadcaster.setMicEnabled(false)   // mute
broadcaster.setCameraEnabled(false) // hide camera

broadcaster.stop()
```

### Watch

```kotlin
val player = client.createPlayer() // PlaybackMode.AUTO

player.listener = object : MebiusPlayerListener {
    override fun onPlaying() {}
    override fun onBuffering() {}
    override fun onEnded() {}
    override fun onError(error: MebiusError) {}
}

player.play("my-stream-id", videoView)
player.setVolume(0.5f) // 0f..1f
player.stop()
```

| Mode | When to use |
| --- | --- |
| `AUTO` (default) | Recommended. Mebius picks per viewer and re-picks if a route stops delivering video. |
| `LOW_LATENCY` | Two-way interaction (co-broadcast), sub-second delay. Costs one per-viewer session, so it is not for a plain audience. |
| `SCALE` | Largest audiences and unstable networks. |

Whatever the mode, playback walks an ordered route list with an 8-second budget per
route. A route that opens is not yet a route that plays: an edge with no ingest
answers 200 with an empty stream, and a real-time connection reports `connected`
while zero frames arrive. Either case used to leave the viewer on a black frame
with no error to react to; now the player moves on by itself.

For watching the other side of a co-broadcast, use `client.createMonitor()` — same
API as a player, different delay budget.

---

## 4. Integration

### View / XML

```xml
<io.mebius.sdk.MebiusVideoView
    android:id="@+id/videoView"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
```

```kotlin
val videoView = findViewById<MebiusVideoView>(R.id.videoView)
player.play("my-stream-id", videoView)

// In onDestroy():
videoView.release()
```

### Jetpack Compose

Wrap `MebiusVideoView` with `AndroidView`:

```kotlin
@Composable
fun PlayerView(client: MebiusClient, streamId: String) {
    var view by remember { mutableStateOf<MebiusVideoView?>(null) }
    val player = remember { client.createPlayer() }

    DisposableEffect(Unit) {
        onDispose {
            player.release()
            view?.release()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx -> MebiusVideoView(ctx).also { view = it } },
        update = { it /* nothing dynamic */ },
    )

    LaunchedEffect(view) {
        view?.let { player.play(streamId, it) }
    }
}
```

A full Compose sample (broadcast + watch) lives in the [`sample/`](sample) module.

---

## 5. API Reference

### Classes & methods

| Class | Member | Signature |
|---|---|---|
| `Mebius` | `init` | `fun init(context: Context, appId: String, gateway: String)` |
| `Mebius` | `connect` | `fun connect(token: String): MebiusClient` |
| `MebiusClient` | `createBroadcaster` | `fun createBroadcaster(video: Boolean = true, audio: Boolean = true): MebiusBroadcaster` |
| `MebiusClient` | `createPlayer` | `fun createPlayer(mode: PlaybackMode = PlaybackMode.AUTO): MebiusPlayer` |
| `MebiusClient` | `createMonitor` | `fun createMonitor(): MebiusPlayer` |
| `MebiusDelivery` | `fromTokenResponse` | `fun fromTokenResponse(body: JSONObject?): List<MebiusDelivery>` |
| `MebiusClient` | `updateToken` | `fun updateToken(newToken: String)` |
| `MebiusClient` | `disconnect` | `fun disconnect()` |
| `MebiusBroadcaster` | `attachPreview` | `fun attachPreview(view: MebiusVideoView)` |
| `MebiusBroadcaster` | `start` | `fun start(streamId: String)` |
| `MebiusBroadcaster` | `stop` | `fun stop()` |
| `MebiusBroadcaster` | `switchCamera` | `fun switchCamera()` |
| `MebiusBroadcaster` | `setMicEnabled` | `fun setMicEnabled(enabled: Boolean)` |
| `MebiusBroadcaster` | `setCameraEnabled` | `fun setCameraEnabled(enabled: Boolean)` |
| `MebiusBroadcaster` | `release` | `fun release()` |
| `MebiusPlayer` | `play` | `fun play(streamId: String, view: MebiusVideoView)` |
| `MebiusPlayer` | `stop` | `fun stop()` |
| `MebiusPlayer` | `setVolume` | `fun setVolume(volume: Float)` |
| `MebiusPlayer` | `release` | `fun release()` |
| `MebiusVideoView` | `release` | `fun release()` |
| `MebiusVideoView` | `mirror` | `var mirror: Boolean` |

### Events

| Source | Event | Listener callback | Flow event |
|---|---|---|---|
| `MebiusClient` | connected | `onConnected()` | `ClientEvent.Connected` |
| `MebiusClient` | disconnected | `onDisconnected()` | `ClientEvent.Disconnected` |
| `MebiusClient` | error | `onError(MebiusError)` | `ClientEvent.Error` |
| `MebiusBroadcaster` | started | `onStarted(streamId)` | `BroadcasterEvent.Started` |
| `MebiusBroadcaster` | stopped | `onStopped()` | `BroadcasterEvent.Stopped` |
| `MebiusBroadcaster` | stats | `onStats(MebiusBroadcastStats)` | `BroadcasterEvent.Stats` |
| `MebiusPlayer` | playing | `onPlaying()` | `PlayerEvent.Playing` |
| `MebiusPlayer` | buffering | `onBuffering()` | `PlayerEvent.Buffering` |
| `MebiusPlayer` | ended | `onEnded()` | `PlayerEvent.Ended` |
| `MebiusPlayer` | stats | `onStats(MebiusPlaybackStats)` | `PlayerEvent.Stats` |

### Listener interfaces

```kotlin
interface MebiusClientListener {
    fun onConnected() {}
    fun onDisconnected() {}
    fun onError(error: MebiusError) {}
}

interface MebiusBroadcasterListener {
    fun onStarted(streamId: String) {}
    fun onStopped() {}
    fun onStats(stats: MebiusBroadcastStats) {}
    fun onError(error: MebiusError) {}
}

interface MebiusPlayerListener {
    fun onPlaying() {}
    fun onBuffering() {}
    fun onEnded() {}
    fun onStats(stats: MebiusPlaybackStats) {}
    fun onError(error: MebiusError) {}
}
```

### Coroutine / Flow API

Every event source also exposes a hot `Flow`, mirroring the listener:

```kotlin
lifecycleScope.launch {
    player.events.collect { event ->
        when (event) {
            is MebiusPlayer.PlayerEvent.Playing -> { /* ... */ }
            is MebiusPlayer.PlayerEvent.Error -> handle(event.error)
            else -> {}
        }
    }
}
```

`MebiusClient.events`, `MebiusBroadcaster.events`, and `MebiusPlayer.events` are all available.

---

## 6. Error Handling

All errors are reported as `MebiusError`, a sealed class with a stable `code`:

```kotlin
sealed class MebiusError(val code: Code, message: String, cause: Throwable?) : Exception {
    enum class Code {
        TOKEN_EXPIRED,
        PERMISSION_DENIED,
        CONNECTION_FAILED,
        NOT_CONNECTED,
        STREAM_NOT_FOUND,
        UNKNOWN,
    }
}
```

| Code | Meaning | Recommended recovery |
|---|---|---|
| `TOKEN_EXPIRED` | The session token has expired. | Fetch a new token from your backend, call `client.updateToken(...)` or reconnect via `Mebius.connect(...)`. |
| `PERMISSION_DENIED` | Camera/microphone permission not granted. | Request runtime permissions, then retry `start()`. |
| `CONNECTION_FAILED` | Could not reach/maintain the gateway connection. | Check connectivity and retry with backoff. |
| `NOT_CONNECTED` | Operation attempted while disconnected. | Ensure `Mebius.connect(...)` succeeded before creating broadcasters/players. |
| `STREAM_NOT_FOUND` | The requested stream does not exist. | Verify the `streamId`; surface a "stream offline" UI. |
| `UNKNOWN` | Unexpected error. | Inspect `cause`, log, and retry. |

```kotlin
override fun onError(error: MebiusError) {
    when (error.code) {
        MebiusError.Code.TOKEN_EXPIRED -> refreshTokenAndReconnect()
        MebiusError.Code.PERMISSION_DENIED -> requestPermissions()
        else -> showError(error.message)
    }
}
```

---

## 7. Troubleshooting

**Broadcast immediately errors with `PERMISSION_DENIED`.**
Request `CAMERA` and `RECORD_AUDIO` at runtime before `start()`. The manifest declaration alone is not enough on Android 6+.

**ProGuard / R8 strips the SDK in release builds.**
The SDK ships **consumer ProGuard rules** that are applied automatically — no action is required in most projects. If you have an aggressive custom configuration, ensure these are kept:

```proguard
-keep public class io.mebius.sdk.** { public protected *; }
-keep class org.webrtc.** { *; }
-keep class androidx.media3.** { *; }
```

**Black screen / leaked native resources.**
Always release media resources on teardown:

```kotlin
override fun onDestroy() {
    broadcaster.release()   // or player.release()
    videoView.release()
    client.disconnect()
    super.onDestroy()
}
```

In Compose, use `DisposableEffect(Unit) { onDispose { ... } }`. `MebiusVideoView` also releases itself in `onDetachedFromWindow()` as a safety net, but explicit release is recommended.

---

## 8. Versioning & Changelog

This SDK follows [Semantic Versioning](https://semver.org/). The public API is
stable within a major version; breaking changes to the API contract result in a
major version bump across **all** Mebius client SDKs simultaneously.

### Changelog

#### 0.2.1
- `MebiusPlayer.mode` is now public. It was private through 0.2.0, so Android was
  the only Mebius SDK where an app could not read back which route a player was on
  (Flutter and iOS both expose it). Nothing in this repo could catch that: every
  consumer here lives in the same module, and `private` only bites from outside.

#### 0.2.0
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

#### 0.1.0
- Initial release: `init`/`connect`, broadcaster (start/stop/switchCamera/mic/camera), player (low-latency & scale modes, volume), `MebiusVideoView`, listener + Flow event APIs.

---

## 9. License

Released under the [MIT License](LICENSE).
