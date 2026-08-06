package io.mebius.sdk.internal

import android.content.Context
import io.mebius.sdk.MebiusError
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnection.IceConnectionState
import org.webrtc.RtpCapabilities
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Drives a single outbound (publish) WebRTC session against the gateway via WHIP.
 * Internal only — public terms never leak here.
 */
internal class PublishEngine(
    private val context: Context,
    private val signaling: SignalingClient,
) {
    private val factory = WebRtcCore.peerConnectionFactory(context)

    private var peerConnection: PeerConnection? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var surfaceHelper: SurfaceTextureHelper? = null

    private var usingFrontCamera = true
    private val enumerator: CameraEnumerator =
        if (Camera2Enumerator.isSupported(context)) {
            Camera2Enumerator(context)
        } else {
            Camera1Enumerator(false)
        }

    /** The local video track, exposed so the preview view can render it. */
    val previewTrack: VideoTrack? get() = localVideoTrack

    /**
     * Creates local tracks, builds a peer connection, performs the WHIP SDP
     * exchange and begins publishing [streamId].
     *
     * @throws MebiusError on failure.
     */
    fun start(
        streamId: String,
        enableVideo: Boolean,
        enableAudio: Boolean,
    ) {
        val pc =
            factory.createPeerConnection(
                PeerConnection.RTCConfiguration(emptyList()).apply {
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                },
                object : NoopPcObserver() {
                    override fun onIceConnectionChange(state: IceConnectionState?) = Unit
                },
            ) ?: throw MebiusError.ConnectionFailed("Could not create a media session.")
        peerConnection = pc

        if (enableVideo) {
            createVideoTrack().let { track ->
                pc.addTrack(track, listOf(MEDIA_STREAM_ID))
            }
        }
        if (enableAudio) {
            createAudioTrack().let { track ->
                pc.addTrack(track, listOf(MEDIA_STREAM_ID))
            }
        }

        // We publish: send-only transceivers.
        pc.transceivers.forEach { it.direction = RtpTransceiver.RtpTransceiverDirection.SEND_ONLY }

        preferH264(pc)

        val offer = createOffer(pc)
        pc.setLocalDescriptionBlocking(offer)

        // WHIP exchange.
        val answerSdp = signaling.exchangeSdp(streamId, SignalingClient.Direction.PUBLISH, offer.description)
        pc.setRemoteDescriptionBlocking(SessionDescription(SessionDescription.Type.ANSWER, answerSdp))
    }

    /**
     * Offers H264 ahead of VP8 for the outgoing video track.
     *
     * libwebrtc negotiates VP8 by default, and VP8 is a dead end for every
     * viewer who is not on the real-time route: the gateway's segment-based
     * deliveries cannot carry it, so they drop the video track and the
     * broadcast arrives as audio only. Nothing on the device reports this —
     * preview, bitrate and connection state all look healthy.
     *
     * H264 is moved to the front rather than made exclusive: a device with no
     * H264 encoder must still be able to broadcast, so the rest of the list
     * follows in its original order. Every failure path leaves negotiation
     * exactly as it was.
     */
    private fun preferH264(pc: PeerConnection) {
        try {
            val codecs = factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
            val ordered = orderH264First(codecs)
            if (ordered.isEmpty()) return
            pc.transceivers
                .filter { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }
                .forEach { it.setCodecPreferences(ordered) }
        } catch (_: Throwable) {
            // Older prebuilts without the method, or a device that rejects the
            // list: the broadcast still goes out with the previous order. This
            // SDK has no logger, and a codec preference is not worth inventing
            // one for — the negotiated codec is visible in the SDP either way.
        }
    }

    private fun createVideoTrack(): VideoTrack {
        val capturer =
            createCameraCapturer()
                ?: throw MebiusError.PermissionDenied("No camera is available.")
        videoCapturer = capturer

        val helper = SurfaceTextureHelper.create("MebiusCapture", WebRtcCore.eglBase.eglBaseContext)
        surfaceHelper = helper

        val source = factory.createVideoSource(capturer.isScreencast)
        videoSource = source
        capturer.initialize(helper, context, source.capturerObserver)
        capturer.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS)

        return factory.createVideoTrack(VIDEO_TRACK_ID, source).also { localVideoTrack = it }
    }

    private fun createAudioTrack(): AudioTrack {
        val source = factory.createAudioSource(MediaConstraints())
        audioSource = source
        return factory.createAudioTrack(AUDIO_TRACK_ID, source).also { localAudioTrack = it }
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        val names = enumerator.deviceNames
        // Prefer front camera first.
        names.firstOrNull { enumerator.isFrontFacing(it) }?.let {
            usingFrontCamera = true
            return enumerator.createCapturer(it, null)
        }
        names.firstOrNull { enumerator.isBackFacing(it) }?.let {
            usingFrontCamera = false
            return enumerator.createCapturer(it, null)
        }
        return null
    }

    fun switchCamera() {
        videoCapturer?.switchCamera(
            object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFront: Boolean) {
                    usingFrontCamera = isFront
                }

                override fun onCameraSwitchError(error: String?) = Unit
            },
        )
    }

    fun setMicEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
    }

    fun setCameraEnabled(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
        try {
            if (enabled) {
                videoCapturer?.startCapture(VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS)
            } else {
                videoCapturer?.stopCapture()
            }
        } catch (_: InterruptedException) {
            // Capture toggle interrupted; track-level enable still applies.
        }
    }

    fun stop() {
        try {
            videoCapturer?.stopCapture()
        } catch (_: InterruptedException) {
            // ignore
        }
        videoCapturer?.dispose()
        videoCapturer = null

        localVideoTrack?.dispose()
        localVideoTrack = null
        localAudioTrack?.dispose()
        localAudioTrack = null

        videoSource?.dispose()
        videoSource = null
        audioSource?.dispose()
        audioSource = null

        surfaceHelper?.dispose()
        surfaceHelper = null

        peerConnection?.dispose()
        peerConnection = null
    }

    // ---- Blocking SDP helpers (the WHIP exchange itself is synchronous HTTP). ----

    private fun createOffer(pc: PeerConnection): SessionDescription {
        val latch = CountDownLatch(1)
        var result: SessionDescription? = null
        var failure: String? = null
        pc.createOffer(
            CreateSdpObserver(
                onSuccess = {
                    result = it
                    latch.countDown()
                },
                onFailure = {
                    failure = it
                    latch.countDown()
                },
            ),
            MediaConstraints(),
        )
        awaitOrThrow(latch)
        return result ?: throw MebiusError.ConnectionFailed(failure ?: "Failed to create offer.")
    }

    private fun PeerConnection.setLocalDescriptionBlocking(sdp: SessionDescription) {
        val latch = CountDownLatch(1)
        var failure: String? = null
        setLocalDescription(
            SetSdpObserver(onSuccess = { latch.countDown() }, onFailure = {
                failure = it
                latch.countDown()
            }),
            sdp,
        )
        awaitOrThrow(latch)
        failure?.let { throw MebiusError.ConnectionFailed(it) }
    }

    private fun PeerConnection.setRemoteDescriptionBlocking(sdp: SessionDescription) {
        val latch = CountDownLatch(1)
        var failure: String? = null
        setRemoteDescription(
            SetSdpObserver(onSuccess = { latch.countDown() }, onFailure = {
                failure = it
                latch.countDown()
            }),
            sdp,
        )
        awaitOrThrow(latch)
        failure?.let { throw MebiusError.ConnectionFailed(it) }
    }

    private fun awaitOrThrow(latch: CountDownLatch) {
        if (!latch.await(SDP_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            throw MebiusError.ConnectionFailed("Timed out negotiating the media session.")
        }
    }

    private companion object {
        const val MEDIA_STREAM_ID = "mebius-stream"
        const val VIDEO_TRACK_ID = "mebius-video"
        const val AUDIO_TRACK_ID = "mebius-audio"
        const val VIDEO_WIDTH = 1280
        const val VIDEO_HEIGHT = 720
        const val VIDEO_FPS = 30
        const val SDP_TIMEOUT_SEC = 15L
    }
}

/**
 * H264 entries first, everything else in its original order. Split out from
 * [PublishEngine] so the ordering itself is unit-testable without a device.
 */
internal fun orderH264First(codecs: List<RtpCapabilities.CodecCapability>): List<RtpCapabilities.CodecCapability> {
    val (h264, rest) = codecs.partition { it.mimeType.equals("video/H264", ignoreCase = true) }
    if (h264.isEmpty()) return emptyList()
    return h264 + rest
}
