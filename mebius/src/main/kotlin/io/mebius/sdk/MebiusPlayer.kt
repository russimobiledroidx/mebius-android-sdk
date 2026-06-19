package io.mebius.sdk

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import io.mebius.sdk.internal.GatewayConfig
import io.mebius.sdk.internal.LowLatencyPlayEngine
import io.mebius.sdk.internal.PlayEngine
import io.mebius.sdk.internal.ScalePlayEngine
import io.mebius.sdk.internal.SignalingClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Plays back a Mebius stream into a [MebiusVideoView].
 *
 * Obtain an instance via [MebiusClient.createPlayer]. The [PlaybackMode] chosen
 * at creation time determines the latency/scalability trade-off; the SDK selects
 * the appropriate transport internally.
 *
 * ### Events
 * Subscribe either with a [MebiusPlayerListener] (set via [listener]) or with the
 * coroutine [events] flow. Both deliver the same information.
 */
@OptIn(UnstableApi::class)
public class MebiusPlayer internal constructor(
    context: Context,
    config: GatewayConfig,
    tokenProvider: () -> String,
    private val mode: PlaybackMode,
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val signaling = SignalingClient(config, tokenProvider)

    private fun mainPost(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    // Transport is auto-selected from the requested mode and never exposed.
    private val engine: PlayEngine = when (mode) {
        PlaybackMode.LOW_LATENCY -> LowLatencyPlayEngine(appContext, signaling, ::mainPost)
        PlaybackMode.SCALE -> ScalePlayEngine(appContext, signaling, ::mainPost)
    }

    private var boundView: MebiusVideoView? = null

    /** Optional listener for player events. Callbacks run on the main thread. */
    public var listener: MebiusPlayerListener? = null

    private val _events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 16)

    /**
     * A hot [Flow] of player events, for coroutine-based consumers. Mirrors the
     * callbacks delivered to [listener].
     */
    public val events: Flow<PlayerEvent> = _events.asSharedFlow()

    /** Events emitted by a [MebiusPlayer]. */
    public sealed interface PlayerEvent {
        /** Playback is rendering frames. */
        public data object Playing : PlayerEvent

        /** Playback is buffering / stalled. */
        public data object Buffering : PlayerEvent

        /** Playback ended. */
        public data object Ended : PlayerEvent

        /** Periodic statistics. */
        public data class Stats(val stats: MebiusPlaybackStats) : PlayerEvent

        /** An error occurred. */
        public data class Error(val error: MebiusError) : PlayerEvent
    }

    private val engineCallbacks = object : PlayEngine.Callbacks {
        override fun onPlaying() = dispatch(PlayerEvent.Playing) { it.onPlaying() }
        override fun onBuffering() = dispatch(PlayerEvent.Buffering) { it.onBuffering() }
        override fun onEnded() = dispatch(PlayerEvent.Ended) { it.onEnded() }
        override fun onError(error: MebiusError) =
            dispatch(PlayerEvent.Error(error)) { it.onError(error) }
    }

    /**
     * Starts playback of [streamId] into [view].
     *
     * @param streamId the stream identifier to watch.
     * @param view the [MebiusVideoView] to render into.
     */
    public fun play(streamId: String, view: MebiusVideoView) {
        boundView = view
        engine.play(streamId, view, engineCallbacks)
    }

    /** Stops playback and releases the rendering pipeline. */
    public fun stop() {
        engine.stop()
        boundView?.detach()
        boundView?.removeScaleSurface()
        boundView = null
    }

    /**
     * Sets the output volume.
     *
     * @param volume a value in the range `0f` (muted) to `1f` (full volume).
     */
    public fun setVolume(volume: Float) {
        engine.setVolume(volume.coerceIn(0f, 1f))
    }

    /** Releases all resources held by this player. Call from your teardown. */
    public fun release() {
        stop()
    }

    private fun dispatch(event: PlayerEvent, toListener: (MebiusPlayerListener) -> Unit) {
        _events.tryEmit(event)
        mainPost { listener?.let(toListener) }
    }
}
