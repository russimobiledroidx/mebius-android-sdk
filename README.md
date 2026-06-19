# Mebius Android SDK

Broadcast and watch live streams from your Android app with a single, simple API.

[![Maven Central](https://img.shields.io/maven-central/v/io.mebius/mebius-android-sdk.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.mebius/mebius-android-sdk)
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

The SDK is published to **Maven Central**.

**Kotlin DSL** (`build.gradle.kts`):

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("io.mebius:mebius-android-sdk:0.1.0")
}
```

**Groovy DSL** (`build.gradle`):

```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation 'io.mebius:mebius-android-sdk:0.1.0'
}
```

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

// After fetching a token from YOUR backend:
val client = Mebius.connect(token = backendIssuedToken)

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
val player = client.createPlayer(PlaybackMode.LOW_LATENCY) // or PlaybackMode.SCALE

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

> Use `PlaybackMode.LOW_LATENCY` for interactive, real-time viewing and
> `PlaybackMode.SCALE` for large audiences. The SDK picks the optimal delivery
> path for you automatically.

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
    val player = remember { client.createPlayer(PlaybackMode.LOW_LATENCY) }

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
| `MebiusClient` | `createPlayer` | `fun createPlayer(mode: PlaybackMode): MebiusPlayer` |
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

#### 0.1.0
- Initial release: `init`/`connect`, broadcaster (start/stop/switchCamera/mic/camera), player (low-latency & scale modes, volume), `MebiusVideoView`, listener + Flow event APIs.

---

## 9. License

Released under the [MIT License](LICENSE).
