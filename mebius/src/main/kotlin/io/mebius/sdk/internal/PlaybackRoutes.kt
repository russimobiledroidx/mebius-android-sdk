package io.mebius.sdk.internal

import io.mebius.sdk.MebiusDelivery
import io.mebius.sdk.MebiusQuality
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
    /** The ladder this route serves; empty when it serves one rendition. */
    val renditions: List<MebiusQuality> = emptyList(),
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
            out += PlaybackRoute(realtime = false, deliveryPath = d.path, renditions = d.renditions)
        }
    }
    out += PlaybackRoute(realtime = false)
    return out
}

/**
 * The rendition a frame of [height] belongs to, or null with no ladder.
 *
 * Nearest rather than exact: a decoder can report 1088 for a 1080 rung, and the
 * ids are the gateway's, so a frame must always map to one of them.
 */
internal fun qualityIdForHeight(
    renditions: List<MebiusQuality>,
    height: Int,
): String? {
    if (height <= 0) return null
    return renditions.minByOrNull { kotlin.math.abs(it.heightPx - height) }?.id
}

/**
 * The tallest rung no taller than [maxHeight] — or the shortest rung when none
 * fits, because a cap below the ladder still has to play something.
 */
internal fun rungForMaxHeight(
    renditions: List<MebiusQuality>,
    maxHeight: Int,
): MebiusQuality? =
    renditions.filter { it.heightPx <= maxHeight }.maxByOrNull { it.heightPx }
        ?: renditions.minByOrNull { it.heightPx }

/**
 * The rungs of [ladder] in [allowed], in ladder order. Null, empty, or no
 * overlap means the whole ladder: a filter that leaves nothing to play is a
 * filter that was not meant.
 */
internal fun allowedRungs(
    ladder: List<MebiusQuality>,
    allowed: List<String>?,
): List<MebiusQuality> = ladder.filter { allowed == null || it.id in allowed }.ifEmpty { ladder }

/**
 * [path] with `q=<ids>` when [allowed] narrows [ladder], so the gateway serves a
 * master playlist with only those variants. Unchanged otherwise — a stream
 * without a ladder never sees the parameter.
 */
internal fun withQualityFilter(
    path: String,
    ladder: List<MebiusQuality>,
    allowed: List<String>?,
): String {
    val rungs = allowedRungs(ladder, allowed)
    if (rungs.size == ladder.size) return path
    val sep = if (path.contains('?')) "&" else "?"
    return path + sep + "q=" + rungs.joinToString(",") { java.net.URLEncoder.encode(it.id, "UTF-8") }
}
