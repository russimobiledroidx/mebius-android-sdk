package io.mebius.sdk.internal

import android.content.Context
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule

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

        val encoderFactory =
            DefaultVideoEncoderFactory(
                eglBase.eglBaseContext,
                // enableIntelVp8Encoder =
                true,
                // enableH264HighProfile =
                true,
            )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        // The same module the factory would create by default; held so the
        // mic can be muted on it (see setMicrophoneMute).
        val adm = JavaAudioDeviceModule.builder(context.applicationContext).createAudioDeviceModule()
        audioDeviceModule = adm

        return PeerConnectionFactory
            .builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
            .also { factory = it }
    }

    @Volatile
    private var audioDeviceModule: JavaAudioDeviceModule? = null

    /**
     * Mutes the microphone at the audio device module, process-wide.
     *
     * Never mute by disabling the audio track: libwebrtc then stops the
     * recorder once every send stream is muted (`OnMuteStreamChanged:
     * should_record = 0` → `StopRecording`), so the publish carries no audio
     * packets at all. The CDN relay writes a video-only FLV under an audio
     * header and web viewers' MSE buffers (audio ∩ video) stall until unmute.
     * The ADM mute keeps recording and feeds zeros, so silence keeps flowing.
     */
    fun setMicrophoneMute(muted: Boolean) {
        audioDeviceModule?.setMicrophoneMute(muted)
    }

    /** Releases the shared factory. Safe to call multiple times. */
    @Synchronized
    fun dispose() {
        factory?.dispose()
        factory = null
        // The factory does not own a module it was handed.
        audioDeviceModule?.release()
        audioDeviceModule = null
    }
}
