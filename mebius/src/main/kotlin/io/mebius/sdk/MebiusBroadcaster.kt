package io.mebius.sdk

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.mebius.sdk.internal.GatewayConfig
import io.mebius.sdk.internal.PublishEngine
import io.mebius.sdk.internal.SignalingClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.Executors

/**
 * Broadcasts the device camera and/or microphone to a Mebius stream.
 *
 * Obtain an instance via [MebiusClient.createBroadcaster]. A broadcaster owns
 * the capture pipeline; call [stop] (or [MebiusClient.disconnect]) to release
 * camera/microphone resources.
 *
 * ### Events
 * Subscribe either with a [MebiusBroadcasterListener] (set via [listener]) or
 * with the coroutine [events] flow. Both deliver the same information.
 */
public class MebiusBroadcaster internal constructor(
    context: Context,
    config: GatewayConfig,
    tokenProvider: () -> String,
    private val withVideo: Boolean,
    private val withAudio: Boolean,
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "mebius-broadcaster") }

    private val engine = PublishEngine(appContext, SignalingClient(config, tokenProvider))

    @Volatile
    private var started = false

    /** Optional listener for broadcaster events. Callbacks run on the main thread. */
    public var listener: MebiusBroadcasterListener? = null

    private val _events = MutableSharedFlow<BroadcasterEvent>(extraBufferCapacity = 16)

    /**
     * A hot [Flow] of broadcaster events, for coroutine-based consumers. Mirrors
     * the callbacks delivered to [listener].
     */
    public val events: Flow<BroadcasterEvent> = _events.asSharedFlow()

    /** Events emitted by a [MebiusBroadcaster]. */
    public sealed interface BroadcasterEvent {
        /** Broadcasting started for [streamId]. */
        public data class Started(val streamId: String) : BroadcasterEvent

        /** Broadcasting stopped. */
        public data object Stopped : BroadcasterEvent

        /** Periodic statistics. */
        public data class Stats(val stats: MebiusBroadcastStats) : BroadcasterEvent

        /** An error occurred. */
        public data class Error(val error: MebiusError) : BroadcasterEvent
    }

    /**
     * Attaches a preview of the local camera to [view]. Call after construction
     * and before or after [start]; the preview reflects the currently selected
     * camera and respects [setCameraEnabled].
     */
    public fun attachPreview(view: MebiusVideoView) {
        main.post { engine.previewTrack?.let { view.attach(it) } }
    }

    /**
     * Starts broadcasting to the stream identified by [streamId].
     *
     * @param streamId the stream identifier provided by your application/backend.
     */
    public fun start(streamId: String) {
        worker.execute {
            try {
                engine.start(streamId, withVideo, withAudio)
                started = true
                emit(BroadcasterEvent.Started(streamId)) { it.onStarted(streamId) }
            } catch (e: MebiusError) {
                emit(BroadcasterEvent.Error(e)) { it.onError(e) }
            } catch (e: Exception) {
                val err = MebiusError.Unknown(cause = e)
                emit(BroadcasterEvent.Error(err)) { it.onError(err) }
            }
        }
    }

    /** Stops broadcasting and releases the capture pipeline. */
    public fun stop() {
        worker.execute {
            engine.stop()
            started = false
            emit(BroadcasterEvent.Stopped) { it.onStopped() }
        }
    }

    /** Toggles between the front and back camera. No-op if broadcasting audio only. */
    public fun switchCamera() {
        worker.execute { engine.switchCamera() }
    }

    /** Enables or disables the microphone without renegotiating the stream. */
    public fun setMicEnabled(enabled: Boolean) {
        worker.execute { engine.setMicEnabled(enabled) }
    }

    /** Enables or disables the camera without renegotiating the stream. */
    public fun setCameraEnabled(enabled: Boolean) {
        worker.execute { engine.setCameraEnabled(enabled) }
    }

    /**
     * Releases all resources held by this broadcaster. Equivalent to [stop] plus
     * shutting down the internal worker. Call from your Activity/Fragment teardown.
     */
    public fun release() {
        stop()
        worker.shutdown()
    }

    private fun emit(event: BroadcasterEvent, toListener: (MebiusBroadcasterListener) -> Unit) {
        _events.tryEmit(event)
        main.post { listener?.let(toListener) }
    }
}
