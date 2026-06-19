package io.mebius.sdk.internal

import android.content.Context
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory

/**
 * Lazily-initialized, process-wide WebRTC core (libwebrtc / org.webrtc).
 *
 * Internal only. Owns the shared [EglBase] context and the
 * [PeerConnectionFactory] used by both the publisher and the low-latency player.
 *
 * Implementation note: this is the WHIP/WHEP transport backbone and must never
 * be referenced from the public surface.
 */
internal object WebRtcCore {

    @Volatile
    private var factory: PeerConnectionFactory? = null

    /** Shared EGL context for hardware video encode/decode and rendering. */
    val eglBase: EglBase by lazy { EglBase.create() }

    /** Returns the shared [PeerConnectionFactory], initializing it on first use. */
    @Synchronized
    fun peerConnectionFactory(context: Context): PeerConnectionFactory {
        factory?.let { return it }

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions
                .builder(context.applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions(),
        )

        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase.eglBaseContext,
            /* enableIntelVp8Encoder = */ true,
            /* enableH264HighProfile = */ true,
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        return PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
            .also { factory = it }
    }

    /** Releases the shared factory. Safe to call multiple times. */
    @Synchronized
    fun dispose() {
        factory?.dispose()
        factory = null
    }
}
