package io.mebius.sdk

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mebius.sdk.internal.FIRST_FRAME_TIMEOUT_MS
import io.mebius.sdk.internal.GatewayConfig
import io.mebius.sdk.internal.buildRoutes
import io.mebius.sdk.internal.qualityIdForHeight
import io.mebius.sdk.internal.rungForMaxHeight
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// Robolectric runner, because the plain unit-test classpath ships a stub org.json
// whose methods return defaults — MebiusDelivery.fromJson would parse nothing and
// the parsing tests would pass for the wrong reason.
@RunWith(AndroidJUnit4::class)
class PlaybackRoutesTest {
    /** The delivery list a gateway with a CDN configured returns today. */
    private val deliveries =
        listOf(
            MebiusDelivery(kind = "fast", path = "/d/fast/s1"),
            MebiusDelivery(kind = "wide", path = "/d/wide/s1"),
            MebiusDelivery(kind = "local", path = "/live/s1/index.m3u8"),
        )

    @Test
    fun `gateway order is preserved and the origin route comes last`() {
        val routes = buildRoutes(PlaybackMode.AUTO, deliveries)
        // wide + local from the gateway, then origin. Origin must be last: every
        // byte of it is our bandwidth, unlike an edge route.
        assertEquals(3, routes.size)
        assertEquals("/d/wide/s1", routes[0].deliveryPath)
        assertEquals("/live/s1/index.m3u8", routes[1].deliveryPath)
        assertNull("origin route is addressed by stream id", routes[2].deliveryPath)
    }

    @Test
    fun `the buffered route is never offered on this platform`() {
        // No media-source player here. Declaring it would repeat the exact mistake of
        // shipping a mode nothing can play.
        val paths = buildRoutes(PlaybackMode.AUTO, deliveries).map { it.deliveryPath }
        assertFalse(paths.contains("/d/fast/s1"))
    }

    @Test
    fun `low latency tries the realtime route first and can still fall back`() {
        val routes = buildRoutes(PlaybackMode.LOW_LATENCY, deliveries)
        assertTrue(routes.first().realtime)
        assertTrue("must be able to fall back", routes.size > 1)
    }

    @Test
    fun `a route whose path is not resolvable is dropped`() {
        val routes =
            buildRoutes(
                PlaybackMode.AUTO,
                listOf(MebiusDelivery(kind = "wide", path = "https://evil.example/x")),
            )
        assertEquals(1, routes.size)
        assertNull(routes.single().deliveryPath)
    }

    @Test
    fun `an unknown kind is skipped rather than guessed at`() {
        val routes =
            buildRoutes(
                PlaybackMode.AUTO,
                listOf(MebiusDelivery(kind = "quantum", path = "/d/quantum/s1")),
            )
        assertEquals(1, routes.size)
    }

    @Test
    fun `there is always a playable route even with no deliveries`() {
        // Every existing integration passes none. They must keep working.
        assertEquals(1, buildRoutes(PlaybackMode.SCALE, emptyList()).size)
    }

    @Test
    fun `isResolvable rejects anything that could send the token elsewhere`() {
        // The access token is a bearer credential; a delivery path is untrusted
        // response data. An absolute or protocol-relative path is how that token
        // would end up at a host Mebius did not choose.
        for (path in listOf("https://evil.example/x", "//evil.example/x", "d/wide/s1", "")) {
            assertFalse("accepted $path", MebiusDelivery("wide", path).isResolvable)
        }
        assertTrue(MebiusDelivery("wide", "/d/wide/s1").isResolvable)
    }

    @Test
    fun `parsing skips malformed entries instead of throwing`() {
        // The list arrives over the network. One bad entry must cost a viewer one
        // route, never a crash on their device.
        val json =
            JSONArray(
                """
                [null, "nope", {"kind":"wide"}, {"path":"/d/wide/s1"},
                 {"kind":"wide","path":"/d/wide/s1"}]
                """.trimIndent(),
            )
        val parsed = MebiusDelivery.fromJson(json)
        assertEquals(1, parsed.size)
        assertEquals("/d/wide/s1", parsed.single().path)
    }

    @Test
    fun `a missing deliveries field parses to an empty list`() {
        assertTrue(MebiusDelivery.fromJson(null).isEmpty())
        assertTrue(MebiusDelivery.fromTokenResponse(null).isEmpty())
    }

    /**
     * `mode` must be readable by an integrator, as it is on Flutter and iOS. It was
     * private through 0.2.0; the compiler error only appeared when a project outside
     * this repo consumed the published aar, which is why no test here caught it.
     */
    @Test
    fun `the mode a player was created with is readable`() {
        val cfg = GatewayConfig(appId = "app", gateway = "https://gw.mebius.io")
        val player =
            MebiusPlayer(
                context = ApplicationProvider.getApplicationContext(),
                config = cfg,
                tokenProvider = { "tok" },
                mode = PlaybackMode.SCALE,
            )
        assertEquals(PlaybackMode.SCALE, player.mode)
    }

    @Test
    fun `the first-frame budget matches the other Mebius SDKs`() {
        // Mirrored in web, Flutter and iOS so a viewer sees the same behaviour on
        // each platform. Changing it here alone is a bug.
        assertEquals(8_000L, FIRST_FRAME_TIMEOUT_MS)
    }

    private val ladder =
        listOf(
            MebiusQuality("1080p", "1080p", heightPx = 1080, bitrateKbps = 5000),
            MebiusQuality("720p", "720p", heightPx = 720, bitrateKbps = 2800),
            MebiusQuality("480p", "480p", heightPx = 480, bitrateKbps = 1000),
        )

    @Test
    fun `renditions are parsed when present, skipping unusable rungs and unknown fields`() {
        val json =
            JSONArray(
                """
                [{"kind":"wide","path":"/d/wide/s1","future":1,"renditions":[
                  {"id":"1080p","height":1080,"bitrateKbps":5000,"path":"/d/wide/s1/1080p","codec":"x"},
                  {"id":"720p","height":720,"bitrateKbps":2800,"path":"/d/wide/s1/720p"},
                  {"id":"480p","height":480,"path":"/d/wide/s1/480p"},
                  {"height":360}, {"id":"nohe"}, null]}]
                """.trimIndent(),
            )
        val r = MebiusDelivery.fromJson(json).single().renditions
        assertEquals(listOf("1080p", "720p", "480p"), r.map { it.id })
        assertEquals(5000, r[0].bitrateKbps)
        assertEquals(0, r[2].bitrateKbps)
        assertEquals(1080, buildRoutes(PlaybackMode.AUTO, MebiusDelivery.fromJson(json))[0].renditions[0].heightPx)
    }

    @Test
    fun `no renditions field means no ladder, exactly as before`() {
        val parsed = MebiusDelivery.fromJson(JSONArray("""[{"kind":"wide","path":"/d/wide/s1"}]"""))
        assertEquals(listOf(MebiusDelivery("wide", "/d/wide/s1")), parsed)
        assertTrue(buildRoutes(PlaybackMode.AUTO, parsed).all { it.renditions.isEmpty() })
        assertNull(qualityIdForHeight(emptyList(), 1080))
        assertNull(rungForMaxHeight(emptyList(), 720))
    }

    @Test
    fun `a decoded height maps to the nearest rendition id`() {
        assertEquals("1080p", qualityIdForHeight(ladder, 1080))
        assertEquals("1080p", qualityIdForHeight(ladder, 1088))
        assertEquals("720p", qualityIdForHeight(ladder, 720))
        assertEquals("480p", qualityIdForHeight(ladder, 360))
        assertNull("no frame yet", qualityIdForHeight(ladder, 0))
    }

    @Test
    fun `a max cap picks the tallest rung that fits, else the shortest`() {
        assertEquals("1080p", rungForMaxHeight(ladder, 2160)?.id)
        assertEquals("720p", rungForMaxHeight(ladder, 720)?.id)
        assertEquals("480p", rungForMaxHeight(ladder, 600)?.id)
        assertEquals("480p", rungForMaxHeight(ladder, 240)?.id)
    }

    @Test
    fun `a player without renditions offers no qualities and accepts only auto`() {
        val player =
            MebiusPlayer(
                context = ApplicationProvider.getApplicationContext(),
                config = GatewayConfig(appId = "app", gateway = "https://gw.mebius.io"),
                tokenProvider = { "tok" },
                mode = PlaybackMode.SCALE,
                deliveries = listOf(MebiusDelivery("wide", "/d/wide/s1")),
            )
        assertTrue(player.qualities.isEmpty())
        player.setQuality("auto")
        player.setMaxQuality(720)
        assertThrows(IllegalArgumentException::class.java) { player.setQuality("720p") }
    }
}
