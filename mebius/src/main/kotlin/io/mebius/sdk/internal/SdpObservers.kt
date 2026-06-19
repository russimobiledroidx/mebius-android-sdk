package io.mebius.sdk.internal

import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

/**
 * Minimal [SdpObserver] adapters so we can use lambdas with libwebrtc.
 * Internal only.
 */
internal class CreateSdpObserver(
    private val onSuccess: (SessionDescription) -> Unit,
    private val onFailure: (String) -> Unit,
) : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) = onSuccess(sdp)
    override fun onCreateFailure(error: String?) = onFailure(error ?: "createSdp failed")
    override fun onSetSuccess() = Unit
    override fun onSetFailure(error: String?) = Unit
}

internal class SetSdpObserver(
    private val onSuccess: () -> Unit,
    private val onFailure: (String) -> Unit,
) : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription?) = Unit
    override fun onCreateFailure(error: String?) = Unit
    override fun onSetSuccess() = onSuccess()
    override fun onSetFailure(error: String?) = onFailure(error ?: "setSdp failed")
}
