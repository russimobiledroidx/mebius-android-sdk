package io.mebius.sdk.internal

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import io.mebius.sdk.MebiusError
import io.mebius.sdk.MebiusVideoView

/**
 * Scalable playback transport: HLS over ExoPlayer (androidx.media3). Internal only.
 *
 * Holds no signaling client: the URL it plays is handed in, because the same class
 * serves both a route the gateway offered and the origin fallback.
 *
 * All callbacks here already arrive on the application main thread (ExoPlayer
 * dispatches Player.Listener on the thread that created it), so no extra posting
 * is required, but [mainPost] is accepted to mirror the low-latency engine and to
 * guarantee thread-safety regardless of the creation thread.
 */
@UnstableApi
internal class ScalePlayEngine(
    private val context: Context,
    private val mainPost: (() -> Unit) -> Unit,
    /**
     * Resolves the manifest URL for a stream. Supplied by the caller so one engine
     * class serves both a gateway-offered route and the origin fallback; the engine
     * itself has no opinion on which route it is playing.
     */
    private val manifestUrl: (String) -> String,
) : PlayEngine {
    private var player: ExoPlayer? = null

    private var streamId: String? = null
    private var callbacks: PlayEngine.Callbacks? = null

    // 0 = adaptive. Kept so a pin requested before the master playlist has loaded
    // is applied once its variants are known.
    private var pinHeight = 0
    private var minHeight = 0
    private var maxHeight = Int.MAX_VALUE

    override fun play(
        streamId: String,
        view: MebiusVideoView,
        callbacks: PlayEngine.Callbacks,
    ) {
        try {
            val exo = ExoPlayer.Builder(context).build()
            player = exo
            this.streamId = streamId
            this.callbacks = callbacks

            exo.setVideoSurfaceView(view.scaleSurfaceView())
            exo.addListener(
                object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_BUFFERING -> callbacks.onBuffering()
                            Player.STATE_READY -> callbacks.onPlaying()
                            Player.STATE_ENDED -> callbacks.onEnded()
                            else -> Unit
                        }
                    }

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        if (videoSize.height > 0) callbacks.onVideoHeight(videoSize.height)
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        // The variants only exist once the master playlist is read, and
                        // a reload replaces the group an earlier override pointed at.
                        val pinned =
                            tracks.groups.any {
                                it.type == C.TRACK_TYPE_VIDEO &&
                                    exo.trackSelectionParameters.overrides.containsKey(it.mediaTrackGroup)
                            }
                        if (pinHeight > 0 && !pinned) applyHeights(exo)
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        val mapped =
                            when (error.errorCode) {
                                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                                -> MebiusError.StreamNotFound()
                                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                                -> MebiusError.ConnectionFailed(cause = error)
                                else -> MebiusError.Unknown(cause = error)
                            }
                        callbacks.onError(mapped)
                    }
                },
            )

            prepareSource(exo, streamId)
            exo.playWhenReady = true
        } catch (
            // Deliberately broad: an SDK converts any failure into an event rather
            // than crashing the host app. Narrowing this would let an unexpected type
            // escape into the integrator's code.
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            mainPost { callbacks.onError(MebiusError.ConnectionFailed(cause = e)) }
        }
    }

    /** (Re)loads the playlist; the URL is re-resolved so it carries the current `q`. */
    private fun prepareSource(
        exo: ExoPlayer,
        streamId: String,
    ) {
        val source =
            HlsMediaSource
                .Factory(DefaultHttpDataSource.Factory())
                .createMediaSource(MediaItem.fromUri(manifestUrl(streamId)))
        exo.setMediaSource(source)
        exo.prepare()
    }

    override fun reload() {
        val exo = player ?: return
        prepareSource(exo, streamId ?: return)
    }

    override fun setVideoHeights(
        pinHeight: Int,
        minHeight: Int,
        maxHeight: Int,
    ) {
        this.pinHeight = pinHeight
        this.minHeight = minHeight
        this.maxHeight = maxHeight
        player?.let(::applyHeights)
    }

    /**
     * Pin = an override on the variant of [pinHeight]; adaptive = no override,
     * ABR bounded to [minHeight]..[maxHeight]. Setting identical parameters is a
     * no-op in ExoPlayer, so re-applying from onTracksChanged cannot loop.
     *
     * A pin with no variant of that height (once variants are known) is reported
     * rather than approximated: the player falls back to auto and says so.
     */
    private fun applyHeights(exo: ExoPlayer) {
        val params =
            exo.trackSelectionParameters
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                .setMinVideoSize(0, minHeight)
                .setMaxVideoSize(Int.MAX_VALUE, maxHeight)
        val group = exo.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }
        if (pinHeight > 0 && group != null) {
            val track = (0 until group.length).firstOrNull { group.getTrackFormat(it).height == pinHeight }
            if (track == null) {
                callbacks?.onPinUnavailable()
                return
            }
            params.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track))
        }
        exo.trackSelectionParameters = params.build()
    }

    override fun setVolume(volume: Float) {
        player?.volume = volume.coerceIn(0f, 1f)
    }

    override fun stop() {
        player?.release()
        player = null
        callbacks = null
    }
}
