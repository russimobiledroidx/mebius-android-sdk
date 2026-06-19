package io.mebius.sdk

/**
 * Playback mode for a [MebiusPlayer].
 *
 * The mode determines the latency/scalability trade-off. The SDK selects the
 * appropriate transport internally; you never deal with protocol details.
 */
public enum class PlaybackMode {
    /** Lowest possible latency, best for interactive/real-time viewing. */
    LOW_LATENCY,

    /** Optimized for large audiences; slightly higher latency, highly scalable. */
    SCALE,
}

/**
 * Connection statistics for a [MebiusClient].
 *
 * @property roundTripMillis last measured round-trip time to the gateway, or -1 if unknown.
 */
public data class MebiusConnectionStats(
    val roundTripMillis: Long = -1,
)

/**
 * Live statistics for an active broadcast.
 *
 * @property bitrateKbps current outbound video bitrate in kilobits per second.
 * @property framesPerSecond current outbound frame rate.
 * @property widthPx encoded frame width in pixels.
 * @property heightPx encoded frame height in pixels.
 */
public data class MebiusBroadcastStats(
    val bitrateKbps: Int = 0,
    val framesPerSecond: Int = 0,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
)

/**
 * Live statistics for active playback.
 *
 * @property bitrateKbps current inbound video bitrate in kilobits per second.
 * @property framesPerSecond current inbound frame rate.
 * @property bufferedMillis amount of buffered media ahead of the playhead, in ms.
 */
public data class MebiusPlaybackStats(
    val bitrateKbps: Int = 0,
    val framesPerSecond: Int = 0,
    val bufferedMillis: Long = 0,
)

/**
 * Listener for [MebiusClient] lifecycle events.
 *
 * All callbacks are delivered on the Android main thread.
 */
public interface MebiusClientListener {
    /** The client has connected to the Mebius gateway. */
    public fun onConnected() {}

    /** The client has disconnected from the Mebius gateway. */
    public fun onDisconnected() {}

    /** An error occurred. See [error] for the code and message. */
    public fun onError(error: MebiusError) {}
}

/**
 * Listener for [MebiusBroadcaster] events.
 *
 * All callbacks are delivered on the Android main thread.
 */
public interface MebiusBroadcasterListener {
    /** Broadcasting has started for the given [streamId]. */
    public fun onStarted(streamId: String) {}

    /** Broadcasting has stopped. */
    public fun onStopped() {}

    /** Periodic broadcast statistics. */
    public fun onStats(stats: MebiusBroadcastStats) {}

    /** An error occurred while broadcasting. */
    public fun onError(error: MebiusError) {}
}

/**
 * Listener for [MebiusPlayer] events.
 *
 * All callbacks are delivered on the Android main thread.
 */
public interface MebiusPlayerListener {
    /** Playback has started rendering frames. */
    public fun onPlaying() {}

    /** Playback is buffering / stalled. */
    public fun onBuffering() {}

    /** Playback has ended (stream finished or stopped by the publisher). */
    public fun onEnded() {}

    /** Periodic playback statistics. */
    public fun onStats(stats: MebiusPlaybackStats) {}

    /** An error occurred during playback. */
    public fun onError(error: MebiusError) {}
}
