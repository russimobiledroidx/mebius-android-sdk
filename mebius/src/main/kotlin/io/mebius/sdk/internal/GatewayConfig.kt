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

    // POST {gateway}/whip/{streamId}?token=<jwt>  — Content-Type: application/sdp
    //
    // The token goes in the QUERY. The gateway's MediaMTX auth hook reads
    // `?token=` and ignores the Authorization header, so a header-only request is
    // rejected with 401 however valid the token is. The header is still sent as a
    // courtesy to gateways that prefer it, but the query is what is enforced.
    internal fun publishEndpoint(
        streamId: String,
        token: String,
    ): String = withToken("$baseUrl/whip/$streamId", token)

    // POST {gateway}/whep/{streamId}?token=<jwt>  — Content-Type: application/sdp
    internal fun lowLatencyPlayEndpoint(
        streamId: String,
        token: String,
    ): String = withToken("$baseUrl/whep/$streamId", token)

    // GET {gateway}/live/{streamId}/index.m3u8?token=<jwt> — origin playback manifest.
    //
    // Two bugs lived in this one line. It built "/hls/…", a prefix the gateway has
    // never routed or allowlisted, and it carried no token, which the playback gate
    // requires — so scalable playback on Android could only ever return 404 or 401.
    // The token goes in the QUERY because that is what the gateway enforces, and
    // because segment URIs inside the manifest inherit it automatically (the gateway
    // rewrites them), which a request header could not do.
    internal fun originPlayManifest(
        streamId: String,
        token: String,
    ): String = withToken("$baseUrl/live/$streamId/index.m3u8", token)

    // GET {gateway}{path}?token=<jwt> — a route the gateway itself offered.
    // The caller must have checked MebiusDelivery.isResolvable first, so an
    // absolute path never reaches here and the token cannot leave our host.
    internal fun deliveryUrl(
        path: String,
        token: String,
    ): String = withToken("$baseUrl$path", token)

    private fun withToken(
        url: String,
        token: String,
    ): String {
        val sep = if (url.contains('?')) "&" else "?"
        return url + sep + "token=" + java.net.URLEncoder.encode(token, "UTF-8")
    }
}
