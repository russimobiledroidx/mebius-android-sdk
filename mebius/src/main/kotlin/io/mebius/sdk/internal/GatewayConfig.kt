package io.mebius.sdk.internal

/**
 * Process-wide configuration captured at Mebius.init().
 *
 * Internal only — never exposed on the public surface.
 *
 * @property appId the Mebius application id.
 * @property gateway the Mebius signaling endpoint (NOT a MediaMTX URL). All
 *  signaling and media transport is routed through this base URL.
 */
internal data class GatewayConfig(
    val appId: String,
    val gateway: String,
) {
    /** Normalized base URL with any trailing slash removed. */
    val baseUrl: String = gateway.trimEnd('/')

    // ---- Internal protocol endpoint builders (line comments only). ----

    // POST {gateway}/whip/{streamId}  — Content-Type: application/sdp, Authorization: Bearer <token>
    internal fun publishEndpoint(streamId: String): String = "$baseUrl/whip/$streamId"

    // POST {gateway}/whep/{streamId}  — Content-Type: application/sdp, Authorization: Bearer <token>
    internal fun lowLatencyPlayEndpoint(streamId: String): String = "$baseUrl/whep/$streamId"

    // GET {gateway}/hls/{streamId}/index.m3u8 — scalable playback manifest.
    internal fun scalePlayManifest(streamId: String): String = "$baseUrl/hls/$streamId/index.m3u8"
}
