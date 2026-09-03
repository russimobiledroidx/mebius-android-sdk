package io.mebius.sdk

import io.mebius.sdk.internal.FirstFrameSink
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import org.webrtc.VideoFrame
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The first-frame signal for the real-time route.
 *
 * A route that connects and sends nothing produces no error at all, which is the
 * whole reason the player arms a first-frame watchdog. The signal it reads must
 * therefore mean "a picture arrived". `onAddTrack` does not: a track is handed
 * over the moment the session is negotiated, and it stays there whether or not a
 * single frame follows.
 */
class FirstFrameSinkTest {
    private fun frame(): VideoFrame = mockk(relaxed = true)

    @Test
    fun `stays silent until a frame arrives`() {
        val fired = AtomicInteger(0)
        FirstFrameSink { fired.incrementAndGet() }

        // Constructed and attached is not the same as playing. This is exactly the
        // state the old signal reported as success.
        assertEquals(0, fired.get())
    }

    @Test
    fun `fires on the first frame`() {
        val fired = AtomicInteger(0)
        val sink = FirstFrameSink { fired.incrementAndGet() }

        sink.onFrame(frame())

        assertEquals(1, fired.get())
    }

    @Test
    fun `fires once, however many frames follow`() {
        val fired = AtomicInteger(0)
        val sink = FirstFrameSink { fired.incrementAndGet() }

        repeat(30) { sink.onFrame(frame()) }

        assertEquals(1, fired.get())
    }

    @Test
    fun `ignores a null frame`() {
        val fired = AtomicInteger(0)
        val sink = FirstFrameSink { fired.incrementAndGet() }

        sink.onFrame(null)

        assertEquals(0, fired.get())
    }

    @Test
    fun `fires once under concurrent delivery`() {
        // Frames arrive on libwebrtc's own thread while the player may still be
        // wiring up on the main one. Reporting "playing" twice would advance the
        // route list from under a route that is working.
        val fired = AtomicInteger(0)
        val sink = FirstFrameSink { fired.incrementAndGet() }
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)

        repeat(8) {
            Thread {
                start.await()
                sink.onFrame(frame())
                done.countDown()
            }.start()
        }
        start.countDown()
        done.await(5, TimeUnit.SECONDS)

        assertEquals(1, fired.get())
    }
}
