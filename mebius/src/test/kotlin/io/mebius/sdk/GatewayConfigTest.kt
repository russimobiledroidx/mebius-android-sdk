package io.mebius.sdk

import io.mebius.sdk.internal.GatewayConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayConfigTest {
    @Test
    fun `trailing slash is normalized`() {
        val cfg = GatewayConfig(appId = "app", gateway = "https://gw.mebius.io/")
        assertEquals("https://gw.mebius.io", cfg.baseUrl)
    }

    @Test
    fun `endpoints are built under the gateway base url`() {
        val cfg = GatewayConfig(appId = "app", gateway = "https://gw.mebius.io")
        assertEquals("https://gw.mebius.io/whip/s1?token=tok", cfg.publishEndpoint("s1", "tok"))
        assertEquals("https://gw.mebius.io/whep/s1?token=tok", cfg.lowLatencyPlayEndpoint("s1", "tok"))
    }

    /**
     * Regression: WHIP/WHEP carried the token ONLY as an `Authorization: Bearer`
     * header. The gateway's auth hook reads `?token=` from the query and ignores the
     * header, so publishing and sub-second playback were rejected with 401 on every
     * attempt, however valid the token was.
     */
    @Test
    fun `every real-time endpoint carries the token in the query`() {
        val cfg = GatewayConfig(appId = "app", gateway = "https://gw.mebius.io")
        for (url in listOf(cfg.publishEndpoint("s1", "tok"), cfg.lowLatencyPlayEndpoint("s1", "tok"))) {
            assertTrue("token missing from query: $url", url.contains("?token=tok"))
        }
    }

    /**
     * Regression: the origin manifest used to be built as `/hls/{id}/index.m3u8`
     * with no token. That prefix is not routed or allowlisted by the gateway, and
     * the playback gate rejects a request without a token, so scalable playback on
     * Android could only ever return 404 or 401.
     */
    @Test
    fun `origin manifest is routed and tokenized`() {
        val cfg = GatewayConfig(appId = "app", gateway = "https://gw.mebius.io")
        val url = cfg.originPlayManifest("s1", "tok")
        assertEquals("https://gw.mebius.io/live/s1/index.m3u8?token=tok", url)
        assertFalse("must not use the unrouted prefix", url.contains("/hls/"))
    }

    @Test
    fun `a gateway-offered route resolves under our own host with the token`() {
        val cfg = GatewayConfig(appId = "app", gateway = "https://gw.mebius.io")
        assertEquals(
            "https://gw.mebius.io/d/wide/s1?token=tok",
            cfg.deliveryUrl("/d/wide/s1", "tok"),
        )
    }

    @Test
    fun `the token is escaped rather than pasted into the url`() {
        // A token is opaque and may contain characters that would otherwise start a
        // new query parameter, silently truncating the credential.
        val cfg = GatewayConfig(appId = "app", gateway = "https://gw.mebius.io")
        val url = cfg.deliveryUrl("/d/wide/s1", "a b&c=d")
        assertTrue(url.endsWith("?token=a+b%26c%3Dd"))
    }
}
