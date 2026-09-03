package io.mebius.sdk.internal

import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reports the moment the real-time route actually renders a picture.
 *
 * The player arms a first-frame watchdog because a route can connect and then
 * send nothing, producing no error to react to. That only works if "playing"
 * means a frame arrived. `onAddTrack` does not mean that: libwebrtc hands the
 * track over as soon as the session is negotiated, and it stays there whether or
 * not media follows — so reading it as the frame defused the watchdog in exactly
 * the case it was written for, and the viewer was left on a black frame.
 *
 * A frame delivered to a sink is the frame. No polling, no stats, no timers.
 *
 * [onFirstFrame] runs on libwebrtc's rendering thread and fires at most once,
 * however many frames follow and however many threads deliver them: a second
 * "playing" would advance the route list away from a route that is working.
 * Marshal to the main thread in the callback if the receiver needs it.
 */
internal class FirstFrameSink(
    private val onFirstFrame: () -> Unit,
) : VideoSink {
    private val fired = AtomicBoolean(false)

    override fun onFrame(frame: VideoFrame?) {
        if (frame == null) return
        if (fired.compareAndSet(false, true)) {
            onFirstFrame()
        }
    }
}
