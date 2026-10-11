package io.mebius.sdk

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mebius.sdk.internal.GatewayConfig
import io.mebius.sdk.internal.PlayEngine
import io.mebius.sdk.internal.withQualityFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/** Drives [MebiusPlayer] through a fake transport, so no ExoPlayer is needed. */
@RunWith(AndroidJUnit4::class)
class QualitySelectionTest {
    private class FakeEngine : PlayEngine {
        lateinit var callbacks: PlayEngine.Callbacks
        val heights = mutableListOf<Triple<Int, Int, Int>>()
        var reloads = 0

        override fun play(
            streamId: String,
            view: MebiusVideoView,
            callbacks: PlayEngine.Callbacks,
        ) {
            this.callbacks = callbacks
        }

        override fun setVolume(volume: Float) = Unit

        override fun stop() = Unit

        override fun setVideoHeights(
            pinHeight: Int,
            minHeight: Int,
            maxHeight: Int,
        ) {
            heights += Triple(pinHeight, minHeight, maxHeight)
        }

        override fun reload() {
            reloads++
        }
    }

    private val ladder =
        listOf(
            MebiusQuality("1080p", "1080p", heightPx = 1080),
            MebiusQuality("720p", "720p", heightPx = 720),
            MebiusQuality("480p", "480p", heightPx = 480),
        )

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val engines = mutableListOf<FakeEngine>()
    private val engine get() = engines.last()
    private val seen = mutableListOf<Pair<String, Boolean>>()

    private fun player(
        renditions: List<MebiusQuality>,
        extra: List<MebiusDelivery> = emptyList(),
    ): MebiusPlayer =
        MebiusPlayer(
            context = context,
            config = GatewayConfig(appId = "app", gateway = "https://gw.mebius.io"),
            tokenProvider = { "tok" },
            mode = PlaybackMode.SCALE,
            deliveries = listOf(MebiusDelivery("wide", "/d/wide/s1", renditions)) + extra,
            engineFactory = { FakeEngine().also { engines += it } },
        ).also {
            it.listener =
                object : MebiusPlayerListener {
                    override fun onQualityChanged(
                        id: String,
                        auto: Boolean,
                    ) {
                        seen += id to auto
                    }
                }
            it.play("s1", MebiusVideoView(context))
        }

    @Test
    fun `without renditions the engine is never constrained and nothing is reported`() {
        val p = player(emptyList())
        engine.callbacks.onPlaying()
        engine.callbacks.onVideoHeight(1080)
        p.setQuality("auto")
        p.setMaxQuality(720)
        p.setAllowedQualities(listOf("720p"))
        assertTrue(p.qualities.isEmpty())
        assertTrue("setVideoHeights called: ${engine.heights}", engine.heights.isEmpty())
        assertEquals(0, engine.reloads)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun `a manual pick is reported at once, auto reports the rung on screen`() {
        val p = player(ladder)
        engine.callbacks.onPlaying()
        engine.callbacks.onVideoHeight(480)
        assertEquals(listOf("480p" to true), seen)
        p.setQuality("1080p")
        assertEquals("1080p" to false, seen.last())
        engine.callbacks.onVideoHeight(480) // stale frame before the switch lands
        assertEquals("1080p" to false, seen.last())
        assertEquals(1080, engine.heights.last().first)
        p.setQuality("auto")
        assertEquals("480p" to true, seen.last())
    }

    @Test
    fun `a pin with no variant falls back to auto and says so`() {
        val p = player(ladder)
        engine.callbacks.onPlaying()
        engine.callbacks.onVideoHeight(720)
        p.setQuality("1080p")
        engine.callbacks.onPinUnavailable()
        assertEquals("720p" to true, seen.last())
        assertEquals(0, engine.heights.last().first)
    }

    @Test
    fun `allowed qualities narrow the list, the selection and the bounds`() {
        val p = player(ladder)
        engine.callbacks.onPlaying()
        p.setQuality("480p")
        p.setAllowedQualities(listOf("1080p", "720p", "nope"))
        assertEquals(listOf("1080p", "720p"), p.qualities.map { it.id })
        assertEquals("pin outside the set falls back", Triple(0, 720, 1080), engine.heights.last())
        assertEquals(1, engine.reloads)
        assertThrows(IllegalArgumentException::class.java) { p.setQuality("480p") }
        p.setAllowedQualities(listOf("nope"))
        assertEquals(3, p.qualities.size)
        assertEquals(Triple(0, 0, Int.MAX_VALUE), engine.heights.last())
    }

    @Test
    fun `the gateway is asked for the allowed variants only when the set narrows`() {
        assertEquals("/d/wide/s1", withQualityFilter("/d/wide/s1", ladder, null))
        assertEquals("/d/wide/s1", withQualityFilter("/d/wide/s1", ladder, listOf("x")))
        assertEquals("/d/wide/s1", withQualityFilter("/d/wide/s1", emptyList(), listOf("720p")))
        assertEquals("/d/wide/s1?q=1080p,720p", withQualityFilter("/d/wide/s1", ladder, listOf("720p", "1080p")))
    }

    @Test
    fun `a pin is kept on a route that offers it and dropped before one that does not`() {
        val p = player(ladder, extra = listOf(MebiusDelivery("local", "/live/s1/index.m3u8")))
        engine.callbacks.onPlaying()
        p.setQuality("1080p")
        engine.callbacks.onEnded() // lost after delivering: reopen from the top
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
        assertEquals("same wide route, pin kept", 1080, engine.heights.last().first)
        engine.callbacks.onError(MebiusError.ConnectionFailed("x")) // before acceptance: next route
        assertEquals(3, engines.size)
        engine.callbacks.onVideoHeight(1080)
        engine.callbacks.onPlaying()
        assertTrue(p.qualities.isEmpty())
        assertEquals(listOf("1080p" to false), seen)
    }

    @Test
    fun `a fallback to auto is announced once, not again after recovery`() {
        val p = player(ladder)
        engine.callbacks.onPlaying()
        engine.callbacks.onVideoHeight(720)
        p.setQuality("1080p")
        engine.callbacks.onError(MebiusError.ConnectionFailed("x"))
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
        engine.callbacks.onVideoHeight(720)
        engine.callbacks.onPlaying()
        assertEquals(listOf("720p" to true, "1080p" to false, "720p" to true), seen)
    }

    @Test
    fun `after narrowing, a frame from outside the set is not labelled`() {
        val p = player(ladder)
        engine.callbacks.onPlaying()
        engine.callbacks.onVideoHeight(480)
        p.setAllowedQualities(listOf("1080p", "720p"))
        engine.callbacks.onVideoHeight(480) // old playlist, before the reload lands
        assertEquals(listOf("480p" to true), seen)
        engine.callbacks.onVideoHeight(720)
        assertEquals("720p" to true, seen.last())
    }
}
