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

        /** The decoded video height changed. Only routes with a ladder report it. */
        fun onVideoHeight(height: Int) {}

        /** A pinned rendition has no matching variant on this route. */
        fun onPinUnavailable() {}
    }

    /** Begins playback of [streamId] into [view]. */
    fun play(
        streamId: String,
        view: io.mebius.sdk.MebiusVideoView,
        callbacks: Callbacks,
    )

    /** Sets output volume in the range 0f..1f. */
    fun setVolume(volume: Float)

    /**
     * Constrains which rendition plays: [pinHeight] > 0 pins the rung of that
     * height, otherwise adaptive selection runs within [minHeight]..[maxHeight].
     * Never called for a route without a ladder.
     */
    fun setVideoHeights(
        pinHeight: Int,
        minHeight: Int,
        maxHeight: Int,
    ) {}

    /** Re-resolves the playlist URL and reloads it. */
    fun reload() {}

    /** Stops playback and releases all resources. */
    fun stop()
}
