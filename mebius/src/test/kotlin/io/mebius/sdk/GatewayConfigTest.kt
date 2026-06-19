package io.mebius.sdk

import io.mebius.sdk.internal.GatewayConfig
import org.junit.Assert.assertEquals
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
        assertEquals("https://gw.mebius.io/whip/s1", cfg.publishEndpoint("s1"))
        assertEquals("https://gw.mebius.io/whep/s1", cfg.lowLatencyPlayEndpoint("s1"))
        assertEquals("https://gw.mebius.io/hls/s1/index.m3u8", cfg.scalePlayManifest("s1"))
    }
}
