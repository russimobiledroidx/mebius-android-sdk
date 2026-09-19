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
import io.mebius.sdk.internal.RecoveryPolicy
import io.mebius.sdk.internal.STALL_RECOVERY_MS
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

    // Reopening a route that WAS delivering and then stopped. See loseRoute().
    private val recovery = RecoveryPolicy()
    private var recovering = false
    private var recoveryCause: MebiusError? = null
    private var recoveryTask: Runnable? = null
    private var stallTask: Runnable? = null

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

        /** The selectable renditions changed because the route did. */
        public data class QualitiesChanged(
            val qualities: List<MebiusQuality>,
        ) : PlayerEvent
    }

    @Volatile
    private var renditions: List<MebiusQuality> = emptyList()

    /**
     * Renditions this stream can actually be switched between.
     *
     * Empty means there is exactly one rendition — or a route with no such concept —
     * and a UI should HIDE its quality menu rather than offer a choice that does not
     * exist. That is the whole reason this exists: a player built against an HLS
     * ladder has a menu, and without a programmatic answer the only options were to
     * show a fake one or to delete the feature on a hunch.
     *
     * It is empty for every Mebius stream today: the engine publishes one rendition
     * and does no ladder transcoding. The property is here so a client can be written
     * once, against the honest answer, and keep working unchanged if that changes.
     *
     * The list is per ROUTE, so it is re-read on failover and announced through
     * [PlayerEvent.QualitiesChanged] / [MebiusPlayerListener.onQualitiesChanged].
     */
    public val qualities: List<MebiusQuality> get() = renditions

    /**
     * Chooses a rendition, or `"auto"` to let Mebius decide (the default).
     *
     * @throws IllegalArgumentException if [id] is not `"auto"` and not in
     *  [qualities] — a UI that asks for a rendition and gets no error would
     *  otherwise show the wrong state forever. Rejecting does not touch playback.
     */
    public fun setQuality(id: String) {
        require(id == "auto" || renditions.any { it.id == id }) {
            "Unknown quality \"$id\". Pass \"auto\", or an id from player.qualities."
        }
        // With one rendition there is nothing to switch to, so an accepted call is a
        // no-op. No state is kept for it: an unread "selected id" would be a second
        // source of truth to keep in step with the route, for no reader.
    }

    /**
     * Re-reads the renditions for the route now serving and tells listeners.
     *
     * Emitted on route acceptance, not only when the list differs: "the route
     * changed, here is what it offers" is the fact a client acts on, and suppressing
     * an identical list would make the event fire or not depending on which route
     * happened to win.
     *
     * No Mebius route exposes a ladder — the engine publishes a single rendition
     * (`hlsVariant: lowLatency`, no ABR). Empty is the truthful answer, and this is
     * the one place that has to change if that stops being true.
     */
    private fun publishQualities() {
        renditions = emptyList()
        dispatch(PlayerEvent.QualitiesChanged(renditions)) { it.onQualitiesChanged(renditions) }
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
            cancelStall()
            // Proven healthy, so the recovery budget starts over. It counts
            // CONSECUTIVE failures, not failures for the life of the player.
            recovering = false
            recoveryCause = null
            recovery.reset()
            // Routes may differ in what they can offer, so the list is published per
            // accepted route rather than once per player.
            publishQualities()
            dispatch(PlayerEvent.Playing) { it.onPlaying() }
        }

        override fun onBuffering() {
            if (!isCurrent()) return
            // A stall that never ends is the black screen this exists for, and it
            // arrives as one more buffering report that is never followed by
            // playback. Start the countdown on the first one and let onPlaying
            // cancel it; re-arming on every repeat would push the deadline out
            // forever, because a frozen ExoPlayer keeps reporting.
            if (accepted) armStall()
            dispatch(PlayerEvent.Buffering) { it.onBuffering() }
        }

        override fun onEnded() {
            if (!isCurrent()) return
            // Not necessarily the end of the broadcast. A segmented route reports
            // the end of what IT can serve — the publisher reconnected, the edge
            // recycled the session, the playlist went away for a moment — and on a
            // broadcast that runs for days that happens long before the host stops.
            // So it is treated as a lost route and PROVEN to be an ending; Ended is
            // dispatched from giveUp() once reopening has failed.
            //
            // Before the route ever delivered a frame it is simpler still: it
            // closed on us, so move to the next one rather than spending the rest
            // of the first-frame budget on information already in hand.
            if (!accepted) {
                advance(MebiusError.ConnectionFailed("A Mebius route closed before delivering."))
                return
            }
            loseRoute(null)
        }

        override fun onError(error: MebiusError) {
            if (!isCurrent()) return
            // A route that has already delivered video and then fails is worth
            // trying to get back before it is reported: the same failure that ends
            // a watch is the one a reopen fixes. One that fails before that is just
            // a route to skip.
            if (accepted) {
                loseRoute(error)
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
        recovering = false
        recoveryCause = null
        recovery.reset()
        cancelStall()
        cancelRecovery()
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
            // Inside a recovery cycle this is not a verdict, it is one attempt that
            // found nothing serving. The next attempt is the answer.
            if (recovering) {
                scheduleRecoveryAttempt()
                return
            }
            dispatch(PlayerEvent.Error(lastError)) { it.onError(lastError) }
            return
        }
        startCurrentRoute()
    }

    /**
     * Treats the serving route as dead and starts reopening the stream.
     *
     * This is the difference between a broadcast a viewer can leave running and
     * one that has to be restarted by hand. Route selection ran once, in [play]:
     * whichever route produced a frame served the rest of the session, and when it
     * later died — a CDN edge restarting, the publisher reconnecting, the phone
     * changing network — playback stopped and stayed stopped.
     *
     * The reopen walks the full route list again rather than retrying the dead
     * one, because the usual causes take out one route and not the others. The
     * token needs no handling here: [MebiusClient] renews it on its own schedule,
     * and every route stamps the current token as it builds its URL.
     *
     * @param cause the failure that lost the route, or null when it simply ended.
     *  Kept so that giving up reports what actually happened rather than a guess.
     */
    private fun loseRoute(cause: MebiusError?) {
        if (recovering || !accepted) return
        recovering = true
        recoveryCause = cause
        cancelWatchdog()
        cancelStall()
        // Tell the app before the first backoff. A spinner a second late still
        // beats a still picture with nothing said about it.
        dispatch(PlayerEvent.Buffering) { it.onBuffering() }
        engine?.stop()
        engine = null
        scheduleRecoveryAttempt()
    }

    private fun scheduleRecoveryAttempt() {
        cancelWatchdog()
        cancelRecovery()
        if (recovery.exhausted) {
            giveUp()
            return
        }
        val delay = recovery.nextDelayMs()
        val task =
            Runnable {
                recoveryTask = null
                // stop() can land anywhere inside the backoff, and reopening into a
                // view the app has released is worse than not recovering at all.
                if (playingStreamId == null || boundView == null) return@Runnable
                routeIndex = 0
                accepted = false
                startCurrentRoute()
            }
        recoveryTask = task
        main.postDelayed(task, delay)
    }

    /**
     * Every route refused for the whole budget. Either the broadcast really is
     * over or this device is off the network; both end the session as far as the
     * app is concerned, and the reason reported is the one that lost the route.
     */
    private fun giveUp() {
        recovering = false
        engine?.stop()
        engine = null
        val cause = recoveryCause
        recoveryCause = null
        if (cause != null) {
            dispatch(PlayerEvent.Error(cause)) { it.onError(cause) }
        } else {
            dispatch(PlayerEvent.Ended) { it.onEnded() }
        }
    }

    private fun armStall() {
        if (stallTask != null) return
        val task =
            Runnable {
                stallTask = null
                loseRoute(null)
            }
        stallTask = task
        main.postDelayed(task, STALL_RECOVERY_MS)
    }

    private fun cancelStall() {
        stallTask?.let { main.removeCallbacks(it) }
        stallTask = null
    }

    private fun cancelRecovery() {
        recoveryTask?.let { main.removeCallbacks(it) }
        recoveryTask = null
    }

    /** Stops playback and releases the rendering pipeline. */
    public fun stop() {
        cancelWatchdog()
        cancelStall()
        cancelRecovery()
        recovering = false
        recoveryCause = null
        recovery.reset()
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
