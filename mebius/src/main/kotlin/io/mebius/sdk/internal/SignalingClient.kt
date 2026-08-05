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
 *   Content-Type: application/sdp, token in the ?token= query
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
    fun exchangeSdp(
        streamId: String,
        direction: Direction,
        offerSdp: String,
    ): String {
        val endpoint =
            when (direction) {
                // WHIP publish endpoint.
                Direction.PUBLISH -> config.publishEndpoint(streamId, tokenProvider())
                // WHEP play endpoint.
                Direction.PLAY -> config.lowLatencyPlayEndpoint(streamId, tokenProvider())
            }

        val connection =
            (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                // application/sdp per the WHIP/WHEP wire contract.
                setRequestProperty("Content-Type", "application/sdp")
                setRequestProperty("Accept", "application/sdp")
                setRequestProperty("Authorization", "Bearer ${tokenProvider()}")
            }

        // The transport failures live in send/read, so this function raises exactly
        // one error of its own: the one the gateway's status code means.
        try {
            val status = send(connection, offerSdp)
            if (status == HttpURLConnection.HTTP_OK || status == HttpURLConnection.HTTP_CREATED) {
                return read(connection)
            }
            throw errorFor(status)
        } finally {
            connection.disconnect()
        }
    }

    /** Writes the offer and returns the response status. */
    private fun send(
        connection: HttpURLConnection,
        offerSdp: String,
    ): Int =
        try {
            connection.outputStream.use { it.write(offerSdp.toByteArray(Charsets.UTF_8)) }
            connection.responseCode
        } catch (
            // Deliberately broad, and it stays broad. An SDK must turn every failure
            // into a MebiusError the integrator can handle; letting an unexpected type
            // escape would crash their app instead. Narrowing to IOException would
            // trade a handled error for a crash on anything else the stack throws.
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            throw MebiusError.ConnectionFailed("Failed to reach the Mebius gateway.", e)
        }

    /** Reads the SDP answer body. */
    private fun read(connection: HttpURLConnection): String =
        try {
            connection.inputStream.bufferedReader().use(BufferedReader::readText)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            throw MebiusError.ConnectionFailed("Failed to read the Mebius gateway response.", e)
        }

    /** Maps an HTTP status the gateway returned to the error it means. */
    private fun errorFor(status: Int): MebiusError =
        when (status) {
            HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN -> MebiusError.TokenExpired()
            HttpURLConnection.HTTP_NOT_FOUND -> MebiusError.StreamNotFound()
            else -> MebiusError.ConnectionFailed("Mebius gateway returned an unexpected status ($status).")
        }

    /** Returns the tokenized origin manifest URL for [streamId]. */
    fun originManifestUrl(streamId: String): String = config.originPlayManifest(streamId, tokenProvider())

    /**
     * Returns the tokenized URL for a route the gateway offered.
     *
     * [path] must already have passed `MebiusDelivery.isResolvable`; this method
     * does not re-check it, so passing an absolute path here would leak the token.
     */
    fun deliveryUrl(path: String): String = config.deliveryUrl(path, tokenProvider())

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 10_000
    }
}
