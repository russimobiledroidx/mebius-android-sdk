package io.mebius.sdk.internal

import android.util.Base64
import org.json.JSONObject

/**
 * Reads — never VERIFIES — a Mebius access token.
 *
 * The token is a short-lived JWT minted by the application backend; only the
 * gateway holds the secret, so the client can do nothing but peek at the payload.
 * Two fields matter:
 *
 *  * `exp`      — when to renew, so a session can outlive one credential.
 *  * `streamId` — what the credential is for, so swapping in a token minted for
 *                 another stream fails loudly instead of quietly breaking the
 *                 session on its next request.
 *
 * Anything unreadable yields null. A malformed token is the gateway's problem to
 * reject, and guessing here would turn a clear 401 into a client-side mystery.
 */
internal data class TokenInfo(
    /** Expiry in epoch milliseconds, or null when there is no readable `exp`. */
    val expiresAtMillis: Long? = null,
    /** The stream this token is scoped to, or null when unreadable. */
    val streamId: String? = null,
)

/** Decodes the payload of [token]. Never throws. */
internal fun readToken(token: String): TokenInfo {
    val parts = token.split(".")
    if (parts.size < 2) return TokenInfo()
    return try {
        val json =
            JSONObject(
                String(
                    Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP),
                    Charsets.UTF_8,
                ),
            )
        TokenInfo(
            expiresAtMillis = if (json.has("exp")) (json.getDouble("exp") * 1000).toLong() else null,
            streamId = json.optString("streamId").takeIf { it.isNotEmpty() },
        )
    } catch (_: Exception) {
        // Deliberately broad: a token is opaque, externally-supplied input here, and
        // every way it can fail to decode — bad base64, bad JSON, wrong shape —
        // means exactly one thing: nothing can be read from it.
        TokenInfo()
    }
}
