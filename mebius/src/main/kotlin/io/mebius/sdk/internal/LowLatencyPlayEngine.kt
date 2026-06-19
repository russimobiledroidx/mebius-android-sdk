package io.mebius.sdk.internal

import android.content.Context
import io.mebius.sdk.MebiusError
import io.mebius.sdk.MebiusVideoView
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Low-latency playback transport: WHEP over WebRTC. Internal only.
 */
internal class LowLatencyPlayEngine(
    private val context: Context,
    private val signaling: SignalingClient,
    private val mainPost: (() -> Unit) -> Unit,
) : PlayEngine {

    private val factory = WebRtcCore.peerConnectionFactory(context)
    private var peerConnection: PeerConnection? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var audioEnabled = true
    private var volume = 1.0

    override fun play(streamId: String, view: MebiusVideoView, callbacks: PlayEngine.Callbacks) {
        try {
            val pc = factory.createPeerConnection(
                PeerConnection.RTCConfiguration(emptyList()).apply {
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                },
                object : NoopPcObserver() {
                    override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out org.webrtc.MediaStream>?) {
                        val track = receiver?.track() ?: return
                        if (track.kind() == MediaStreamTrack.VIDEO_TRACK_KIND && track is VideoTrack) {
                            remoteVideoTrack = track
                            mainPost {
                                view.attach(track)
                                callbacks.onPlaying()
                            }
                        }
                    }

                    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                        when (state) {
                            PeerConnection.IceConnectionState.CHECKING ->
                                mainPost { callbacks.onBuffering() }
                            PeerConnection.IceConnectionState.DISCONNECTED,
                            PeerConnection.IceConnectionState.CLOSED,
                            -> mainPost { callbacks.onEnded() }
                            PeerConnection.IceConnectionState.FAILED ->
                                mainPost { callbacks.onError(MebiusError.ConnectionFailed()) }
                            else -> Unit
                        }
                    }
                },
            ) ?: throw MebiusError.ConnectionFailed("Could not create a media session.")
            peerConnection = pc

            // We only receive in WHEP playback.
            pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
            )
            pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
            )

            mainPost { callbacks.onBuffering() }

            val offer = createOffer(pc)
            setLocalBlocking(pc, offer)

            // WHEP exchange.
            val answer = signaling.exchangeSdp(streamId, SignalingClient.Direction.PLAY, offer.description)
            setRemoteBlocking(pc, SessionDescription(SessionDescription.Type.ANSWER, answer))
        } catch (e: MebiusError) {
            mainPost { callbacks.onError(e) }
        } catch (e: Exception) {
            mainPost { callbacks.onError(MebiusError.ConnectionFailed(cause = e)) }
        }
    }

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 1f).toDouble()
        // libwebrtc remote audio tracks are auto-rendered; gain is applied here.
        audioEnabled = this.volume > 0.0
        peerConnection?.receivers?.forEach { receiver ->
            (receiver.track() as? org.webrtc.AudioTrack)?.let { audio ->
                audio.setEnabled(audioEnabled)
                audio.setVolume(this.volume * MAX_GAIN)
            }
        }
    }

    override fun stop() {
        remoteVideoTrack = null
        peerConnection?.dispose()
        peerConnection = null
    }

    private fun createOffer(pc: PeerConnection): SessionDescription {
        val latch = CountDownLatch(1)
        var result: SessionDescription? = null
        var failure: String? = null
        pc.createOffer(
            CreateSdpObserver(
                onSuccess = { result = it; latch.countDown() },
                onFailure = { failure = it; latch.countDown() },
            ),
            MediaConstraints(),
        )
        await(latch)
        return result ?: throw MebiusError.ConnectionFailed(failure ?: "Failed to create offer.")
    }

    private fun setLocalBlocking(pc: PeerConnection, sdp: SessionDescription) {
        val latch = CountDownLatch(1)
        var failure: String? = null
        pc.setLocalDescription(
            SetSdpObserver(onSuccess = { latch.countDown() }, onFailure = { failure = it; latch.countDown() }),
            sdp,
        )
        await(latch)
        failure?.let { throw MebiusError.ConnectionFailed(it) }
    }

    private fun setRemoteBlocking(pc: PeerConnection, sdp: SessionDescription) {
        val latch = CountDownLatch(1)
        var failure: String? = null
        pc.setRemoteDescription(
            SetSdpObserver(onSuccess = { latch.countDown() }, onFailure = { failure = it; latch.countDown() }),
            sdp,
        )
        await(latch)
        failure?.let { throw MebiusError.ConnectionFailed(it) }
    }

    private fun await(latch: CountDownLatch) {
        if (!latch.await(SDP_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            throw MebiusError.ConnectionFailed("Timed out negotiating playback.")
        }
    }

    private companion object {
        const val SDP_TIMEOUT_SEC = 15L
        const val MAX_GAIN = 10.0
    }
}
