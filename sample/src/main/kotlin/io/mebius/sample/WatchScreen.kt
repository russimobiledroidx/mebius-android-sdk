package io.mebius.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
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
import io.mebius.sdk.MebiusPlayer
import io.mebius.sdk.MebiusVideoView
import io.mebius.sdk.PlaybackMode

/**
 * Watch screen: play/stop, toggle playback mode, adjust volume.
 * Recreates the player when the mode changes (mode is fixed per player).
 */
@Composable
fun WatchScreen(client: MebiusClient, onBack: () -> Unit) {
    var mode by remember { mutableStateOf(PlaybackMode.AUTO) }
    var player by remember { mutableStateOf<MebiusPlayer?>(null) }
    var videoView by remember { mutableStateOf<MebiusVideoView?>(null) }
    var playing by remember { mutableStateOf(false) }
    var volume by remember { mutableStateOf(1f) }

    DisposableEffect(Unit) {
        onDispose {
            player?.release()
            videoView?.release()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { ctx -> MebiusVideoView(ctx).also { videoView = it } },
        )

        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val view = videoView ?: return@Button
                    if (playing) {
                        player?.stop()
                    } else {
                        val p = client.createPlayer(mode).also { player = it }
                        p.setVolume(volume)
                        p.play("demo-stream", view)
                    }
                    playing = !playing
                },
            ) { Text(if (playing) "Stop" else "Play") }

            Button(
                enabled = !playing,
                onClick = {
                    // AUTO -> LOW_LATENCY -> SCALE -> AUTO
                    mode = when (mode) {
                        PlaybackMode.AUTO -> PlaybackMode.LOW_LATENCY
                        PlaybackMode.LOW_LATENCY -> PlaybackMode.SCALE
                        PlaybackMode.SCALE -> PlaybackMode.AUTO
                    }
                },
            ) {
                Text(
                    when (mode) {
                        PlaybackMode.AUTO -> "Mode: Auto"
                        PlaybackMode.LOW_LATENCY -> "Mode: Low latency"
                        PlaybackMode.SCALE -> "Mode: Scale"
                    },
                )
            }

            Button(onClick = onBack) { Text("Back") }
        }

        Text("Volume", modifier = Modifier.padding(top = 8.dp))
        Slider(
            value = volume,
            onValueChange = {
                volume = it
                player?.setVolume(it)
            },
        )
    }
}
