package io.mebius.sample

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.mebius.sdk.Mebius
import io.mebius.sdk.MebiusDelivery
import io.mebius.sdk.MebiusClient

/**
 * Sample app entry point. Demonstrates initializing Mebius, connecting with a
 * (placeholder) backend-issued token, and navigating between the broadcast and
 * watch screens.
 */
class MainActivity : ComponentActivity() {

    private lateinit var client: MebiusClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1) Initialize once. In a real app this lives in Application.onCreate.
        Mebius.init(
            context = this,
            appId = "demo-app-id",
            gateway = "https://gateway.mebius.io",
        )

        // 2) Connect with a SHORT-LIVED token fetched from YOUR backend.
        //    The app secret never lives on the device. Replace with a real token.
        // Your backend returns `token` AND `deliveries` from one call; pass both.
        // Dropping `deliveries` still plays, but serves every viewer from Mebius
        // origin instead of the nearest edge — on mobile that is billed per viewer.
        client = Mebius.connect(
            token = "REPLACE_WITH_BACKEND_ISSUED_TOKEN",
            deliveries = MebiusDelivery.fromTokenResponse(null), // REPLACE with your parsed body
        )

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(client)
                }
            }
        }
    }

    override fun onDestroy() {
        client.disconnect()
        super.onDestroy()
    }
}

private enum class Screen { HOME, BROADCAST, WATCH }

@Composable
private fun AppRoot(client: MebiusClient) {
    var screen by remember { mutableStateOf(Screen.HOME) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result.values.all { it }
        if (granted) screen = Screen.BROADCAST
    }

    when (screen) {
        Screen.HOME -> Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Mebius Sample", style = MaterialTheme.typography.headlineMedium)
            Button(onClick = {
                permissionLauncher.launch(
                    arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                )
            }) { Text("Broadcast") }
            Button(onClick = { screen = Screen.WATCH }) { Text("Watch") }
        }

        Screen.BROADCAST -> BroadcastScreen(client, onBack = { screen = Screen.HOME })
        Screen.WATCH -> WatchScreen(client, onBack = { screen = Screen.HOME })
    }
}
