package io.mebius.sdk

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.mebius.sdk.internal.GatewayConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * An authenticated session with the Mebius gateway.
 *
 * Obtain an instance from [Mebius.connect]. From a client you can create a
 * [MebiusBroadcaster] to publish or a [MebiusPlayer] to watch a stream.
 *
 * The client holds the short-lived token supplied to [Mebius.connect]; it never
 * sees your application secret. When the token expires, an [MebiusError.TokenExpired]
 * error is reported — refresh the token via your backend and reconnect.
 */
public class MebiusClient internal constructor(
    context: Context,
    private val config: GatewayConfig,
    token: String,
    private val deliveries: List<MebiusDelivery> = emptyList(),
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var token: String = token

    @Volatile
    private var connected: Boolean = false

    /** Optional listener for client lifecycle events. Callbacks run on the main thread. */
    public var listener: MebiusClientListener? = null

    private val _events = MutableSharedFlow<ClientEvent>(extraBufferCapacity = 16)

    /** A hot [Flow] of client lifecycle events. Mirrors the callbacks on [listener]. */
    public val events: Flow<ClientEvent> = _events.asSharedFlow()

    /** Events emitted by a [MebiusClient]. */
    public sealed interface ClientEvent {
        /** The client connected to the gateway. */
        public data object Connected : ClientEvent

        /** The client disconnected from the gateway. */
        public data object Disconnected : ClientEvent

        /** An error occurred. */
        public data class Error(
            val error: MebiusError,
        ) : ClientEvent
    }

    /** True while this client is connected. */
    public val isConnected: Boolean get() = connected

    // The token is short-lived; the transport reads the latest value lazily on each request.
    private val tokenProvider: () -> String = { token }

    internal fun markConnected() {
        connected = true
        _events.tryEmit(ClientEvent.Connected)
        main.post { listener?.onConnected() }
    }

    /**
     * Replaces the session token, e.g. after refreshing an expired one from your
     * backend. Existing broadcasters/players pick up the new token on their next
     * gateway request.
     */
    public fun updateToken(newToken: String) {
        token = newToken
    }

    /**
     * Creates a [MebiusBroadcaster] for publishing.
     *
     * @param video whether to capture and publish the camera. Requires the
     *  `CAMERA` runtime permission. Defaults to `true`.
     * @param audio whether to capture and publish the microphone. Requires the
     *  `RECORD_AUDIO` runtime permission. Defaults to `true`.
     */
    public fun createBroadcaster(
        video: Boolean = true,
        audio: Boolean = true,
    ): MebiusBroadcaster {
        requireConnected()
        return MebiusBroadcaster(appContext, config, tokenProvider, video, audio)
    }

    /**
     * Creates a [MebiusPlayer] for watching a stream.
     *
     * @param mode the [PlaybackMode] (latency vs. scalability trade-off).
     */
    @JvmOverloads
    public fun createPlayer(mode: PlaybackMode = PlaybackMode.AUTO): MebiusPlayer {
        requireConnected()
        return MebiusPlayer(appContext, config, tokenProvider, mode, deliveries)
    }

    /**
     * Creates a player for a stream you are interacting WITH — the other side of a
     * co-broadcast — where a second of delay makes the interaction feel broken.
     *
     * Same API as a player; only the delay budget differs. It starts on the
     * real-time route and falls back by itself if that route sends no video, which
     * is the part apps used to hand-roll and get wrong in front of a live audience.
     */
    public fun createMonitor(): MebiusPlayer = createPlayer(PlaybackMode.LOW_LATENCY)

    /** Disconnects this client and notifies listeners. */
    public fun disconnect() {
        if (!connected) return
        connected = false
        _events.tryEmit(ClientEvent.Disconnected)
        main.post { listener?.onDisconnected() }
    }

    private fun requireConnected() {
        if (!connected) throw MebiusError.NotConnected()
    }
}
