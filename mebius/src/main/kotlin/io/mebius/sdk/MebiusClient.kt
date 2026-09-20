package io.mebius.sdk

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.mebius.sdk.internal.DEFAULT_MAX_BITRATE_KBPS
import io.mebius.sdk.internal.GatewayConfig
import io.mebius.sdk.internal.TokenInfo
import io.mebius.sdk.internal.readToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * An authenticated session with the Mebius gateway.
 *
 * Obtain an instance from [Mebius.connect]. From a client you can create a
 * [MebiusBroadcaster] to publish or a [MebiusPlayer] to watch a stream.
 *
 * The client holds the short-lived token supplied to [Mebius.connect]; it never
 * sees your application secret. When the token expires, an [MebiusError.TokenExpired]
 * error is reported — refresh the token via your backend and reconnect, or pass
 * `getToken` to [Mebius.connect] and let the session renew itself.
 *
 * Always call [disconnect] when you are done with a client. That was good hygiene
 * before; with `getToken` it is required. A renewing session re-arms itself after
 * every successful mint, and a pending renewal is held by the coroutine scheduler
 * rather than by your object graph — so a client that is merely dropped keeps
 * running, and keeps whatever [listener] points at (commonly an Activity or
 * ViewModel) reachable for the life of the process.
 */
public class MebiusClient internal constructor(
    context: Context,
    private val config: GatewayConfig,
    token: String,
    private val deliveries: List<MebiusDelivery> = emptyList(),
    private val getToken: (suspend () -> String)? = null,
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    /**
     * Created only if this session actually renews. A client built without a
     * `getToken` provider allocates no scope and launches nothing, which is what
     * makes the 0.2.x compatibility claim literal rather than approximate.
     */
    private val scope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @Volatile
    private var refreshJob: Job? = null

    @Volatile
    private var refreshFailures: Int = 0

    /**
     * Bumped whenever the credential is replaced by anyone.
     *
     * An automatic renewal suspends inside an app-supplied provider, and during
     * that suspension the app may call [updateToken] itself. Without this counter
     * the provider's late answer would silently overwrite the token the app just
     * set — and re-arm a schedule for it. A refresh holding a stale generation has
     * been superseded and drops its result. Atomic because the two writers are on
     * different threads.
     */
    private val tokenGeneration = AtomicInteger(0)

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

        /**
         * A fresh access token was fetched and is now in use.
         *
         * Purely informational: publishing and playback continue uninterrupted and
         * nothing needs to be done in response. Useful for logging that an
         * unattended long-running session is still renewing itself.
         */
        public data object TokenRefreshed : ClientEvent
    }

    /** True while this client is connected. */
    public val isConnected: Boolean get() = connected

    // The token is short-lived; the transport reads the latest value lazily on each request.
    private val tokenProvider: () -> String = { token }

    internal fun markConnected() {
        connected = true
        _events.tryEmit(ClientEvent.Connected)
        main.post { listener?.onConnected() }
        // Guarded rather than unconditional: with no provider there is nothing to
        // schedule, so there is no reason to decode the token either.
        if (getToken != null) scheduleRefresh(readToken(token).expiresAtMillis)
    }

    /**
     * Replaces the credential this session authenticates with, in place.
     *
     * Publishing and playback are NOT stopped: there is no renegotiation, no track
     * rebuild and no reconnect. Existing broadcasters and players read the token
     * lazily, so the new one is simply what their next gateway request carries. For
     * a camera publisher that is the difference between a six-hour broadcast and one
     * that drops mid-match to reconnect.
     *
     * @throws IllegalArgumentException if [newToken] is blank, or is scoped to a
     *  different stream than the current one — swapping in a credential for another
     *  stream would not renew this session, it would break it on the next request,
     *  far from the line that caused it.
     */
    public fun updateToken(newToken: String) {
        require(newToken.isNotBlank()) { "updateToken requires a non-blank token." }
        val next = readToken(newToken)
        val current = readToken(token)
        require(next.streamId == null || current.streamId == null || next.streamId == current.streamId) {
            "This token is for stream \"${next.streamId}\", but the session is on " +
                "\"${current.streamId}\". Mint a token for the stream in use."
        }
        token = newToken
        refreshFailures = 0
        tokenGeneration.incrementAndGet()
        if (isConnected) scheduleRefresh(next.expiresAtMillis)
    }

    /**
     * Arms the renewal that keeps this session alive past [expiresAtMillis].
     *
     * Nothing is armed without a `getToken` provider. That is deliberate and is the
     * compatibility guarantee: an app written against 0.2.x sees exactly the
     * behaviour it saw before — the gateway rejects the expired token on the next
     * request and the SDK reports it then, at the same moment it always did. No new
     * job, no new error, no new event.
     */
    private fun scheduleRefresh(expiresAtMillis: Long?) {
        val provider = getToken ?: return
        // An unreadable expiry is not a reason to DISARM. A token this SDK cannot
        // parse is still one the gateway may well accept, and silently turning
        // auto-renewal off for the rest of the session — with no event and no
        // error — is the worst available answer. Leave whatever is already armed.
        if (expiresAtMillis == null) return
        refreshJob?.cancel()
        refreshJob = null
        val lead = expiresAtMillis - System.currentTimeMillis() - REFRESH_MARGIN_MS
        refreshJob =
            scope.launch {
                delay(lead.coerceAtLeast(0L))
                refresh(provider, expiresAtMillis)
            }
    }

    private suspend fun refresh(
        provider: suspend () -> String,
        previousExpiryMillis: Long,
    ) {
        if (!isConnected) return
        val generation = tokenGeneration.get()
        val next =
            try {
                provider()
            } catch (e: CancellationException) {
                // Cancellation is not a failed mint. Swallowing it here would keep a
                // renewal alive after disconnect() cancelled the scope, which is the
                // one way this feature could outlive the session that owns it.
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // Otherwise broad on purpose: whatever the app's backend client
                // throws, the decision is the same — the old credential is still
                // valid, so retry inside its window rather than ending a live
                // broadcast.
                onRefreshFailed(provider, previousExpiryMillis, e)
                return
            }
        // Disconnected, or the credential was replaced by updateToken while the
        // provider was thinking. Either way this answer is stale: applying it would
        // revive a dead session or undo the app's own swap.
        if (!isConnected || generation != tokenGeneration.get()) return
        val info = readToken(next)
        val rejection = rejectionFor(next, info, previousExpiryMillis)
        if (rejection != null) {
            onRefreshFailed(provider, previousExpiryMillis, rejection)
            return
        }
        refreshFailures = 0
        token = next
        tokenGeneration.incrementAndGet()
        _events.tryEmit(ClientEvent.TokenRefreshed)
        main.post { listener?.onTokenRefreshed() }
        scheduleRefresh(info.expiresAtMillis)
    }

    /**
     * Why a freshly minted token cannot be used, or null when it can.
     *
     * Both answers are treated as a FAILED MINT by the caller rather than as the
     * end of the session, and that is the point of returning a reason instead of
     * reporting one here: the current credential is usually still valid, so the
     * right response is to retry inside its window, not to end a live broadcast.
     */
    private fun rejectionFor(
        next: String,
        info: TokenInfo,
        previousExpiryMillis: Long,
    ): IllegalStateException? {
        val current = readToken(token)
        if (info.streamId != null &&
            current.streamId != null &&
            info.streamId != current.streamId
        ) {
            // The same guard updateToken applies by hand. A provider closure holding
            // a stale stream id would otherwise install a credential that breaks the
            // session on its next request, with nothing pointing at the cause.
            return IllegalStateException(
                "Mebius token refresh returned a token for stream " +
                    "\"${info.streamId}\", but the session is on \"${current.streamId}\".",
            )
        }
        val expiry = info.expiresAtMillis
        if (next.isBlank() || (expiry != null && expiry <= previousExpiryMillis)) {
            // A token that does not outlive the one it replaces cannot keep the
            // session alive. A provider can be briefly serving a cached response and
            // hand back a genuinely newer token moments later; reporting expiry here
            // would end the broadcast a full margin early.
            return IllegalStateException(
                "Mebius token refresh returned a token that is not newer.",
            )
        }
        return null
    }

    /**
     * A failed renewal is not a dead session: the current token is valid until
     * [expiryMillis] and the broadcast is still live. Retry inside that window, and
     * report expiry only once the window has actually run out.
     */
    private fun onRefreshFailed(
        provider: suspend () -> String,
        expiryMillis: Long,
        cause: Throwable,
    ) {
        // disconnect() may have run while the provider was suspended. Re-arming
        // here would launch onto a cancelled scope at best, and at worst keep a
        // renewal loop alive past the session that owns it.
        if (!isConnected) return
        val remaining = expiryMillis - System.currentTimeMillis()
        if (remaining <= 0L) {
            emitError(
                MebiusError.TokenExpired(
                    "The connection token expired and could not be renewed: ${cause.message}",
                ),
            )
            return
        }
        refreshFailures += 1
        // Clamp the shift, not just the result. `2000L shl 63` overflows a Long to
        // a negative number, so an unclamped exponent turns a long outage into a
        // NEGATIVE delay — a job that fires immediately, forever. The cap is 5
        // because 2s shl 5 already exceeds RETRY_MAX_MS; beyond it the arithmetic
        // is dead and the failure mode is live.
        val backoff = RETRY_BASE_MS shl (refreshFailures - 1).coerceIn(0, MAX_BACKOFF_SHIFT)
        val delayMs = minOf(backoff, RETRY_MAX_MS, remaining)
        refreshJob?.cancel()
        refreshJob =
            scope.launch {
                delay(delayMs)
                refresh(provider, expiryMillis)
            }
    }

    private fun emitError(error: MebiusError) {
        _events.tryEmit(ClientEvent.Error(error))
        main.post { listener?.onError(error) }
    }

    /**
     * Creates a [MebiusBroadcaster] for publishing.
     *
     * @param video whether to capture and publish the camera. Requires the
     *  `CAMERA` runtime permission. Defaults to `true`.
     * @param audio whether to capture and publish the microphone. Requires the
     *  `RECORD_AUDIO` runtime permission. Defaults to `true`.
     * @param maxBitrateKbps ceiling on what the video encoder may send. Defaults to
     *  the ceiling every Mebius SDK uses, which matches the studio's OBS encoder —
     *  so a broadcast costs the same whichever path it came from. Pass 0 to lift it
     *  and let WebRTC decide.
     *
     *  Worth understanding before changing: nothing transcodes downstream, so every
     *  viewer is delivered at exactly the bitrate published here. One broadcaster's
     *  setting is multiplied by the size of its audience — a number that looks
     *  generous for one host is a bandwidth bill for a thousand viewers.
     */
    public fun createBroadcaster(
        video: Boolean = true,
        audio: Boolean = true,
        maxBitrateKbps: Int = DEFAULT_MAX_BITRATE_KBPS,
    ): MebiusBroadcaster {
        requireConnected()
        return MebiusBroadcaster(appContext, config, tokenProvider, video, audio, maxBitrateKbps)
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
        refreshJob?.cancel()
        refreshJob = null
        scope.cancel()
        _events.tryEmit(ClientEvent.Disconnected)
        main.post { listener?.onDisconnected() }
    }

    private fun requireConnected() {
        if (!connected) throw MebiusError.NotConnected()
    }

    private companion object {
        /**
         * Renew this far ahead of expiry.
         *
         * Wide enough that a slow backend, a couple of retries and a job delayed by a
         * backgrounded process all still land before the old credential dies. The old
         * one keeps working the whole time, so being early costs nothing.
         */
        const val REFRESH_MARGIN_MS = 60_000L

        /** First retry delay after a failed renewal; doubles up to [RETRY_MAX_MS]. */
        const val RETRY_BASE_MS = 2_000L
        const val RETRY_MAX_MS = 30_000L

        /** Largest doubling applied to [RETRY_BASE_MS]. See `onRefreshFailed`. */
        const val MAX_BACKOFF_SHIFT = 5
    }
}
