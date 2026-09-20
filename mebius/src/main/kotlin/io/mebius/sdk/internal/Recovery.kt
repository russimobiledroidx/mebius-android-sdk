package io.mebius.sdk.internal

/**
 * Ceiling on what a publisher's video encoder may send, in kbps.
 *
 * 2500 matches what the studio's OBS encoder is configured to send, so a broadcast
 * costs the same whichever path it came from — a host on a phone and a host in the
 * studio bill identically.
 *
 * A ceiling, not a target: WebRTC still spends less on still scenes. What it
 * removes is the open end, where a capable device answered high-motion content
 * with whatever it could encode.
 *
 * Every Mebius SDK carries this same number. Changing it in one place without the
 * others makes the cost of a broadcast depend on which device made it.
 */
internal const val DEFAULT_MAX_BITRATE_KBPS: Int = 2500

/**
 * How long the picture may stand still before its route is treated as dead.
 *
 * A route that stops delivering does not announce it: ExoPlayer reports one more
 * buffering state, a peer connection goes on reporting `connected`, and the
 * surface keeps its last decoded frame — which is what a viewer calls a black
 * screen. Deliberately longer than [FIRST_FRAME_TIMEOUT_MS], because a route that
 * is merely slow deserves to finish and reopening a healthy stream costs the
 * viewer a rebuffer for nothing.
 */
internal const val STALL_RECOVERY_MS: Long = 10_000

/**
 * How many times a lost route is reopened before the session is declared over.
 *
 * The SDK cannot tell "the broadcast ended" from "the edge dropped us" — both
 * look like a route that stopped producing frames. So it assumes the recoverable
 * case, which is the common one on a long broadcast, and spends a bounded amount
 * of time proving itself wrong.
 */
internal const val MAX_RECOVERY_ATTEMPTS: Int = 5

private const val RECOVERY_BASE_MS: Long = 1_000

/**
 * Ceiling on the reopen delay. Bounded because every viewer of one broadcast
 * fails at the same instant — an edge restart is not an individual event — and an
 * unbounded retry storm from a full room is how a recovery mechanism becomes the
 * outage.
 */
private const val RECOVERY_MAX_MS: Long = 30_000

/**
 * Counts reopen attempts and spaces them out.
 *
 * Separate from the player because it is the only part of recovery with no
 * Android in it: the player owns the Handler, this owns the arithmetic.
 */
internal class RecoveryPolicy {
    /** Attempts spent since the last [reset]. */
    var attempts: Int = 0
        private set

    /** Whether the budget is used up and the session should be declared over. */
    val exhausted: Boolean get() = attempts >= MAX_RECOVERY_ATTEMPTS

    /**
     * Delay before the next attempt, consuming one attempt from the budget.
     * Doubles per attempt: 1s, 2s, 4s, 8s, 16s.
     */
    fun nextDelayMs(): Long {
        val ms = RECOVERY_BASE_MS shl attempts
        attempts += 1
        return if (ms > RECOVERY_MAX_MS) RECOVERY_MAX_MS else ms
    }

    /**
     * Forgets the attempts spent, once playback is proven healthy again.
     *
     * The budget is for CONSECUTIVE failures: a long broadcast that loses its
     * route once an hour must not run out of attempts by the afternoon.
     */
    fun reset() {
        attempts = 0
    }
}
