package io.mebius.sdk

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import io.mebius.sdk.internal.WebRtcCore
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSink
import org.webrtc.VideoTrack

/**
 * A view that renders Mebius video — either a local broadcast preview or remote
 * playback. Wraps an internal surface renderer; you do not interact with the
 * renderer directly.
 *
 * Usage in XML:
 * ```xml
 * <io.mebius.sdk.MebiusVideoView
 *     android:id="@+id/videoView"
 *     android:layout_width="match_parent"
 *     android:layout_height="match_parent" />
 * ```
 *
 * Lifecycle: the view initializes its renderer lazily. Always call [release] when
 * the hosting Activity/Fragment is destroyed (or rely on a [MebiusBroadcaster] /
 * [MebiusPlayer] `stop()`, which detaches the track). Releasing twice is safe.
 */
public class MebiusVideoView
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : FrameLayout(context, attrs, defStyleAttr) {
        private val renderer: SurfaceViewRenderer = SurfaceViewRenderer(context)

        // Plain surface used by the scalable playback path (ExoPlayer-backed).
        private var scaleSurface: SurfaceView? = null
        private var initialized = false
        private var released = false

        // The currently-attached video track, if any. Internal wiring only.
        private var attachedTrack: VideoTrack? = null

        init {
            addView(
                renderer,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
            )
            renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            renderer.setEnableHardwareScaler(true)
        }

        /**
         * Whether the rendered video should be mirrored horizontally. Useful for
         * front-camera preview. Defaults to `false`.
         */
        public var mirror: Boolean = false
            set(value) {
                field = value
                renderer.setMirror(value)
            }

        private fun ensureInitialized() {
            if (!initialized && !released) {
                renderer.init(WebRtcCore.eglBase.eglBaseContext, null)
                initialized = true
            }
        }

        // ---- Internal API used by the broadcaster/player. ----

        /** Returns the underlying [VideoSink] for the transport to render into. */
        internal fun sink(): VideoSink {
            ensureInitialized()
            return renderer
        }

        /** Attaches a remote/local [VideoTrack] to this view. */
        internal fun attach(track: VideoTrack) {
            ensureInitialized()
            attachedTrack?.removeSink(renderer)
            attachedTrack = track
            track.addSink(renderer)
        }

        /** Detaches any currently-attached track without releasing the renderer. */
        internal fun detach() {
            attachedTrack?.removeSink(renderer)
            attachedTrack = null
        }

        /**
         * Returns (creating if necessary) a plain [SurfaceView] for the scalable
         * playback path to render into. The WebRTC renderer is hidden while this is
         * in use.
         */
        internal fun scaleSurfaceView(): SurfaceView {
            scaleSurface?.let { return it }
            val sv = SurfaceView(context)
            scaleSurface = sv
            renderer.visibility = View.GONE
            addView(sv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            return sv
        }

        /** Removes the scalable playback surface and restores the WebRTC renderer. */
        internal fun removeScaleSurface() {
            scaleSurface?.let { removeView(it) }
            scaleSurface = null
            renderer.visibility = View.VISIBLE
        }

        /**
         * Releases the underlying renderer and all native resources. Call this from
         * your Activity/Fragment `onDestroy()`. Idempotent.
         */
        public fun release() {
            if (released) return
            released = true
            detach()
            removeScaleSurface()
            if (initialized) {
                renderer.release()
            }
        }

        override fun onDetachedFromWindow() {
            // Defensive: ensure native resources are freed if the host forgot to.
            release()
            super.onDetachedFromWindow()
        }
    }
