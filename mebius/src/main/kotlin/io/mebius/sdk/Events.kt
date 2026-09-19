package io.mebius.sdk

/**
 * Playback mode for a [MebiusPlayer].
 *
 * The mode determines the latency/scalability trade-off. The SDK selects the
 * appropriate transport internally; you never deal with protocol details.
 */
public enum class PlaybackMode {
    /**
     * Let Mebius choose per viewer, and fall back on its own if the chosen route
     * stops delivering frames. The recommended default.
     */
    AUTO,

    /**
     * Lowest possible latency, for interactive/real-time viewing. Costs one
     * per-viewer session on Mebius, so it is not the right choice for a plain
     * audience — use [AUTO] for that, or `createMonitor()` for a co-broadcast.
     */
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

    /**
     * A fresh access token was fetched and is now in use.
     *
     * Purely informational: publishing and playback continue uninterrupted and
     * nothing needs to be done in response.
     */
    public fun onTokenRefreshed() {}
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
 * One selectable rendition of a stream.
 *
 * Mebius does not transcode into a ladder today, so a live stream has exactly one
 * rendition and [MebiusPlayer.qualities] is empty. That emptiness is the signal,
 * not an omission: a player UI can hide its quality menu because the list says
 * there is nothing to choose, rather than because someone guessed.
 *
 * @property id stable id to pass to [MebiusPlayer.setQuality].
 * @property label human-readable label, e.g. `"720p"`. Safe to show as-is.
 * @property heightPx frame height in pixels, or 0 when the rendition has no fixed one.
 * @property bitrateKbps nominal video bitrate, or 0 when unknown.
 */
public data class MebiusQuality(
    val id: String,
    val label: String,
    val heightPx: Int = 0,
    val bitrateKbps: Int = 0,
)

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

    /**
     * The selectable renditions changed, because the player moved to a different
     * delivery route. Fires once per accepted route, carrying the list as it now
     * stands — today always empty, since no route offers a ladder.
     */
    public fun onQualitiesChanged(qualities: List<MebiusQuality>) {}
}
