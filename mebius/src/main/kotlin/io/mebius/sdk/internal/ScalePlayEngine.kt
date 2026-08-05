package io.mebius.sdk.internal

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
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

    override fun play(
        streamId: String,
        view: MebiusVideoView,
        callbacks: PlayEngine.Callbacks,
    ) {
        try {
            val exo = ExoPlayer.Builder(context).build()
            player = exo

            val manifestUrl = manifestUrl(streamId)
            val dataSourceFactory = DefaultHttpDataSource.Factory()
            val source =
                HlsMediaSource
                    .Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(manifestUrl))

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

            exo.setMediaSource(source)
            exo.prepare()
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

    override fun setVolume(volume: Float) {
        player?.volume = volume.coerceIn(0f, 1f)
    }

    override fun stop() {
        player?.release()
        player = null
    }
}
