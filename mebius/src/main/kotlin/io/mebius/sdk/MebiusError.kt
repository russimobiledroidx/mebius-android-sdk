package io.mebius.sdk

/**
 * Errors surfaced by the Mebius SDK.
 *
 * All errors are expressed in Mebius' own vocabulary; the underlying transport
 * details are never leaked. Match on the [code] for programmatic handling and
 * read [message] for a human-readable description.
 *
 * @property code stable, machine-readable error code (see [Code]).
 * @property message human-readable description, safe to log.
 * @property cause optional underlying throwable for debugging.
 */
public sealed class MebiusError(
    public val code: Code,
    override val message: String,
    override val cause: Throwable? = null,
) : Exception(message, cause) {
    /**
     * Stable error codes. These values are part of the public contract and are
     * identical across every Mebius client SDK (Kotlin/Swift/Dart/TS).
     */
    public enum class Code {
        /** The supplied token has expired. The app should refresh it and reconnect. */
        TOKEN_EXPIRED,

        /** A required runtime permission (camera and/or microphone) was not granted. */
        PERMISSION_DENIED,

        /** Could not establish or maintain a connection to the Mebius gateway. */
        CONNECTION_FAILED,

        /** An operation requiring an active connection was attempted while disconnected. */
        NOT_CONNECTED,

        /** The requested stream could not be found. */
        STREAM_NOT_FOUND,

        /** An unexpected error occurred. */
        UNKNOWN,
    }

    /** The supplied token has expired; refresh it via your backend and reconnect. */
    public class TokenExpired(
        message: String = "The Mebius token has expired. Refresh it from your backend and reconnect.",
        cause: Throwable? = null,
    ) : MebiusError(Code.TOKEN_EXPIRED, message, cause)

    /** A required runtime permission was not granted. */
    public class PermissionDenied(
        message: String = "Camera and/or microphone permission was denied.",
        cause: Throwable? = null,
    ) : MebiusError(Code.PERMISSION_DENIED, message, cause)

    /** Could not establish or maintain a connection to the Mebius gateway. */
    public class ConnectionFailed(
        message: String = "Failed to connect to the Mebius gateway.",
        cause: Throwable? = null,
    ) : MebiusError(Code.CONNECTION_FAILED, message, cause)

    /** An operation requiring an active connection was attempted while disconnected. */
    public class NotConnected(
        message: String = "Not connected to Mebius. Call Mebius.connect(token) first.",
        cause: Throwable? = null,
    ) : MebiusError(Code.NOT_CONNECTED, message, cause)

    /** The requested stream could not be found. */
    public class StreamNotFound(
        message: String = "The requested stream could not be found.",
        cause: Throwable? = null,
    ) : MebiusError(Code.STREAM_NOT_FOUND, message, cause)

    /** An unexpected error occurred. */
    public class Unknown(
        message: String = "An unexpected Mebius error occurred.",
        cause: Throwable? = null,
    ) : MebiusError(Code.UNKNOWN, message, cause)
}
