package io.mebius.sdk.internal

import io.mebius.sdk.MebiusError
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Performs the SDP offer/answer exchange with the Mebius gateway.
 *
 * Internal only. This is the WHIP (publish) / WHEP (play) HTTP signaling
 * implementation. The public surface never sees any of these terms.
 *
 * Wire contract (line comments):
 *   POST {gateway}/whip/{streamId}   body=SDP offer  -> 201 + SDP answer
 *   POST {gateway}/whep/{streamId}   body=SDP offer  -> 201 + SDP answer
 *   Content-Type: application/sdp, Authorization: Bearer <token>
 */
internal class SignalingClient(
    private val config: GatewayConfig,
    private val tokenProvider: () -> String,
) {

    enum class Direction { PUBLISH, PLAY }

    /**
     * Sends the local SDP [offerSdp] to the gateway and returns the remote SDP answer.
     *
     * @throws MebiusError on any failure (mapped from the HTTP status).
     */
    fun exchangeSdp(streamId: String, direction: Direction, offerSdp: String): String {
        val endpoint = when (direction) {
            // WHIP publish endpoint.
            Direction.PUBLISH -> config.publishEndpoint(streamId)
            // WHEP play endpoint.
            Direction.PLAY -> config.lowLatencyPlayEndpoint(streamId)
        }

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            // application/sdp per the WHIP/WHEP wire contract.
            setRequestProperty("Content-Type", "application/sdp")
            setRequestProperty("Accept", "application/sdp")
            setRequestProperty("Authorization", "Bearer ${tokenProvider()}")
        }

        try {
            connection.outputStream.use { it.write(offerSdp.toByteArray(Charsets.UTF_8)) }

            return when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_OK,
                HttpURLConnection.HTTP_CREATED,
                -> connection.inputStream.bufferedReader().use(BufferedReader::readText)

                HttpURLConnection.HTTP_UNAUTHORIZED,
                HttpURLConnection.HTTP_FORBIDDEN,
                -> throw MebiusError.TokenExpired()

                HttpURLConnection.HTTP_NOT_FOUND -> throw MebiusError.StreamNotFound()

                else -> throw MebiusError.ConnectionFailed(
                    "Mebius gateway returned an unexpected status ($status).",
                )
            }
        } catch (e: MebiusError) {
            throw e
        } catch (e: Exception) {
            throw MebiusError.ConnectionFailed("Failed to reach the Mebius gateway.", e)
        } finally {
            connection.disconnect()
        }
    }

    /** Returns the scalable-playback manifest URL for [streamId]. */
    fun scaleManifestUrl(streamId: String): String = config.scalePlayManifest(streamId)

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 10_000
    }
}
