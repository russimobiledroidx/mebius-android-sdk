package io.mebius.sdk.internal

import io.mebius.sdk.MebiusDelivery
import io.mebius.sdk.PlaybackMode

/**
 * How long one route gets to report playback before the player moves to the next.
 *
 * Not arbitrary. A route can look healthy and deliver nothing: an edge with no
 * ingest yet answers 200 with an empty stream, and a WebRTC connection reports
 * `connected` while zero frames arrive. 8s survives a slow first segment on mobile
 * data and is short enough that the viewer has not left yet. Mirrored across every
 * Mebius SDK so a viewer sees the same behaviour on each platform.
 */
internal const val FIRST_FRAME_TIMEOUT_MS: Long = 8_000

/** Delivery kinds the gateway may offer. Neutral labels, never protocol names. */
internal const val KIND_FAST = "fast"
internal const val KIND_WIDE = "wide"
internal const val KIND_LOCAL = "local"

/**
 * One route to attempt, in the order the gateway prefers.
 *
 * [deliveryPath] is null for the real-time route (signaled, so it never appears in
 * the delivery list) and for the origin fallback (addressed by stream id).
 */
internal data class PlaybackRoute(
    val realtime: Boolean,
    val deliveryPath: String? = null,
)

/**
 * Builds the ordered route list for [mode].
 *
 * The gateway's ordering is preserved verbatim — it knows which routes are actually
 * serving and what each costs to serve. The origin route is always appended last,
 * both as a guaranteed fallback and because every byte of it is billed to us,
 * unlike an edge route.
 *
 * [KIND_FAST] is deliberately skipped on Android: it needs a media-source-based
 * player this platform does not have, so offering it would be a route that can
 * never play — the exact mistake of shipping a mode nothing serves.
 */
internal fun buildRoutes(
    mode: PlaybackMode,
    deliveries: List<MebiusDelivery>,
): List<PlaybackRoute> {
    val out = ArrayList<PlaybackRoute>()
    if (mode == PlaybackMode.LOW_LATENCY) {
        out += PlaybackRoute(realtime = true)
    }
    for (d in deliveries) {
        if (!d.isResolvable) continue
        if (d.kind == KIND_WIDE || d.kind == KIND_LOCAL) {
            out += PlaybackRoute(realtime = false, deliveryPath = d.path)
        }
    }
    out += PlaybackRoute(realtime = false)
    return out
}
