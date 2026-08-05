package io.mebius.sdk

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.util.UnstableApi
import io.mebius.sdk.internal.FIRST_FRAME_TIMEOUT_MS
import io.mebius.sdk.internal.GatewayConfig
import io.mebius.sdk.internal.LowLatencyPlayEngine
import io.mebius.sdk.internal.PlayEngine
import io.mebius.sdk.internal.PlaybackRoute
import io.mebius.sdk.internal.ScalePlayEngine
import io.mebius.sdk.internal.SignalingClient
import io.mebius.sdk.internal.buildRoutes
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
    /**
     * The playback mode this player was created with.
     *
     * Public because an app has to be able to show a viewer which route it is on, and
     * because the other Mebius SDKs expose it (`player.mode` on Flutter, `mode` on
     * iOS). It was `private` here through 0.2.0, so Android was the one platform where
     * an integrator could not read it back — invisible in this repo, because nothing
     * in it consumed the published artifact.
     */
    public val mode: PlaybackMode,
    deliveries: List<MebiusDelivery> = emptyList(),
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val signaling = SignalingClient(config, tokenProvider)

    private fun mainPost(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    // Routes are ordered by the gateway; the transport for each is chosen here and
    // never exposed. A LIST rather than one engine is the point: a route that opens
    // successfully is not yet a route that plays, so the player must be able to move.
    private val routes: List<PlaybackRoute> = buildRoutes(mode, deliveries)

    private fun engineFor(route: PlaybackRoute): PlayEngine =
        when {
            route.realtime -> LowLatencyPlayEngine(appContext, signaling, ::mainPost)
            route.deliveryPath != null ->
                ScalePlayEngine(appContext, ::mainPost) {
                    signaling.deliveryUrl(route.deliveryPath)
                }
            else -> ScalePlayEngine(appContext, ::mainPost) { signaling.originManifestUrl(it) }
        }

    private var engine: PlayEngine? = null
    private var routeIndex = 0
    private var accepted = false
    private var watchdog: Runnable? = null
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
        public data class Stats(
            val stats: MebiusPlaybackStats,
        ) : PlayerEvent

        /** An error occurred. */
        public data class Error(
            val error: MebiusError,
        ) : PlayerEvent
    }

    /**
     * Callbacks for ONE route.
     *
     * [forEngine] is captured so a route the player has already abandoned cannot
     * emit into the public surface. Without that check a late error from a dead
     * route would surface as a failure of the route that is currently playing fine.
     */
    private inner class RouteCallbacks(
        private val forEngine: () -> PlayEngine?,
    ) : PlayEngine.Callbacks {
        private fun isCurrent(): Boolean = forEngine() === engine

        override fun onPlaying() {
            if (!isCurrent()) return
            // First report of playback is what accepts a route. Until it arrives the
            // route is still on probation, however healthy its connection looks.
            accepted = true
            cancelWatchdog()
            dispatch(PlayerEvent.Playing) { it.onPlaying() }
        }

        override fun onBuffering() {
            if (!isCurrent()) return
            dispatch(PlayerEvent.Buffering) { it.onBuffering() }
        }

        override fun onEnded() {
            if (!isCurrent()) return
            dispatch(PlayerEvent.Ended) { it.onEnded() }
        }

        override fun onError(error: MebiusError) {
            if (!isCurrent()) return
            // A route that has already delivered video and then fails is a real
            // failure to report. One that fails before that is just a route to skip.
            if (accepted) {
                dispatch(PlayerEvent.Error(error)) { it.onError(error) }
                return
            }
            advance(error)
        }
    }

    /**
     * Starts playback of [streamId] into [view].
     *
     * @param streamId the stream identifier to watch.
     * @param view the [MebiusVideoView] to render into.
     */
    public fun play(
        streamId: String,
        view: MebiusVideoView,
    ) {
        boundView = view
        playingStreamId = streamId
        routeIndex = 0
        accepted = false
        startCurrentRoute()
    }

    private var playingStreamId: String? = null

    private fun startCurrentRoute() {
        val view = boundView ?: return
        val streamId = playingStreamId ?: return
        val route = routes.getOrNull(routeIndex) ?: return
        val started = engineFor(route)
        engine = started
        val callbacks = RouteCallbacks { started }
        started.play(streamId, view, callbacks)
        started.setVolume(volumeLevel)
        armWatchdog()
    }

    /**
     * Gives the current route [FIRST_FRAME_TIMEOUT_MS] to report playback.
     *
     * This is the whole reason a route list exists. A route that connects and sends
     * nothing produces no error at all, so without a timer playback simply sits on a
     * black frame forever — which is what happened before this existed.
     */
    private fun armWatchdog() {
        cancelWatchdog()
        val task =
            Runnable {
                if (accepted) return@Runnable
                advance(MebiusError.ConnectionFailed("A Mebius route delivered no video."))
            }
        watchdog = task
        main.postDelayed(task, FIRST_FRAME_TIMEOUT_MS)
    }

    private fun cancelWatchdog() {
        watchdog?.let { main.removeCallbacks(it) }
        watchdog = null
    }

    /** Tears the dead route down and tries the next one, or reports [lastError]. */
    private fun advance(lastError: MebiusError) {
        cancelWatchdog()
        // Release before opening the next route: an ExoPlayer or peer connection left
        // attached to the same surface leaks for the whole session and can keep
        // rendering over the route that replaces it.
        engine?.stop()
        engine = null
        routeIndex += 1
        if (routeIndex >= routes.size) {
            dispatch(PlayerEvent.Error(lastError)) { it.onError(lastError) }
            return
        }
        startCurrentRoute()
    }

    /** Stops playback and releases the rendering pipeline. */
    public fun stop() {
        cancelWatchdog()
        playingStreamId = null
        engine?.stop()
        engine = null
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
        volumeLevel = volume.coerceIn(0f, 1f)
        engine?.setVolume(volumeLevel)
    }

    // Retained so a route opened by a fallback starts at the volume the app set,
    // rather than silently resetting to full on every fallback.
    private var volumeLevel: Float = 1f

    /** Releases all resources held by this player. Call from your teardown. */
    public fun release() {
        stop()
    }

    private fun dispatch(
        event: PlayerEvent,
        toListener: (MebiusPlayerListener) -> Unit,
    ) {
        _events.tryEmit(event)
        mainPost { listener?.let(toListener) }
    }
}
