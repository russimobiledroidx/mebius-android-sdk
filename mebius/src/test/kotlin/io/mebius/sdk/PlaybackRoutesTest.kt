package io.mebius.sdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mebius.sdk.internal.FIRST_FRAME_TIMEOUT_MS
import io.mebius.sdk.internal.buildRoutes
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `the first-frame budget matches the other Mebius SDKs`() {
        // Mirrored in web, Flutter and iOS so a viewer sees the same behaviour on
        // each platform. Changing it here alone is a bug.
        assertEquals(8_000L, FIRST_FRAME_TIMEOUT_MS)
    }
}
