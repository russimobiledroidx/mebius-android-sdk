package io.mebius.sdk.internal

/**
 * Common contract for the two internal playback transports.
 *
 * Concrete implementations:
 *  - [LowLatencyPlayEngine] : WHEP over WebRTC.
 *  - [ScalePlayEngine]      : HLS over ExoPlayer (androidx.media3).
 *
 * The public [io.mebius.sdk.MebiusPlayer] selects the implementation from the
 * requested [io.mebius.sdk.PlaybackMode]; callers never see which is used.
 */
internal interface PlayEngine {
    /** Callbacks bridged to the public player listener. */
    interface Callbacks {
        fun onPlaying()

        fun onBuffering()

        fun onEnded()

        fun onError(error: io.mebius.sdk.MebiusError)
    }

    /** Begins playback of [streamId] into [view]. */
    fun play(
        streamId: String,
        view: io.mebius.sdk.MebiusVideoView,
        callbacks: Callbacks,
    )

    /** Sets output volume in the range 0f..1f. */
    fun setVolume(volume: Float)

    /** Stops playback and releases all resources. */
    fun stop()
}
