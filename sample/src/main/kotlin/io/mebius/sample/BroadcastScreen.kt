package io.mebius.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.mebius.sdk.MebiusClient
import io.mebius.sdk.MebiusVideoView

/**
 * Broadcast screen: start/stop, switch camera, mute mic, toggle camera.
 * Shows View/Compose interop via [AndroidView] wrapping [MebiusVideoView].
 */
@Composable
fun BroadcastScreen(client: MebiusClient, onBack: () -> Unit) {
    val broadcaster = remember { client.createBroadcaster(video = true, audio = true) }
    var videoView by remember { mutableStateOf<MebiusVideoView?>(null) }
    var broadcasting by remember { mutableStateOf(false) }
    var micEnabled by remember { mutableStateOf(true) }
    var cameraEnabled by remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        onDispose {
            broadcaster.release()
            videoView?.release()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { ctx ->
                MebiusVideoView(ctx).also {
                    it.mirror = true
                    videoView = it
                }
            },
        )

        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (broadcasting) {
                        broadcaster.stop()
                    } else {
                        broadcaster.start("demo-stream")
                        videoView?.let(broadcaster::attachPreview)
                    }
                    broadcasting = !broadcasting
                },
            ) { Text(if (broadcasting) "Stop" else "Start") }

            Button(onClick = { broadcaster.switchCamera() }) { Text("Flip") }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                micEnabled = !micEnabled
                broadcaster.setMicEnabled(micEnabled)
            }) { Text(if (micEnabled) "Mute" else "Unmute") }

            Button(onClick = {
                cameraEnabled = !cameraEnabled
                broadcaster.setCameraEnabled(cameraEnabled)
            }) { Text(if (cameraEnabled) "Camera off" else "Camera on") }

            Button(onClick = onBack) { Text("Back") }
        }
    }
}
