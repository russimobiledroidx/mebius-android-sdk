package io.mebius.sdk

import org.json.JSONArray
import org.json.JSONObject

/**
 * One playback route Mebius has prepared for a stream.
 *
 * Your backend receives this list alongside the access token; hand it to
 * [Mebius.connect] untouched. Both fields are opaque: [kind] is a Mebius intent
 * label, not a media format, and [path] is resolved by Mebius against its own
 * gateway. Reading either as a format or a URL will break as soon as Mebius
 * changes how a route is served — which is the reason the list exists.
 *
 * @property kind Mebius intent label for this route.
 * @property path Mebius-relative path for this route.
 */
public data class MebiusDelivery(
    val kind: String,
    val path: String,
) {
    /**
     * Whether this route is safe to resolve against the Mebius gateway.
     *
     * The access token is a bearer credential and a delivery path arrives as data
     * in a response. An absolute or protocol-relative path would send that token
     * to a host Mebius did not choose, so those are rejected rather than fetched.
     */
    public val isResolvable: Boolean
        get() = path.startsWith("/") && !path.startsWith("//") && !path.contains("://")

    public companion object {
        /**
         * Parses the `deliveries` array from your token response, skipping any
         * entry that is malformed.
         *
         * Skipping rather than throwing is deliberate: the list arrives over the
         * network, and one bad entry must cost a viewer one route, not a crash.
         *
         * @param json the raw `deliveries` value, or null if the field was absent.
         */
        @JvmStatic
        public fun fromJson(json: JSONArray?): List<MebiusDelivery> {
            if (json == null) return emptyList()
            return (0 until json.length()).mapNotNull { i -> parse(json.optJSONObject(i)) }
        }

        /** One entry, or null when it is unusable. */
        private fun parse(item: JSONObject?): MebiusDelivery? {
            val kind = item?.optString("kind", "").orEmpty()
            val path = item?.optString("path", "").orEmpty()
            if (kind.isEmpty() || path.isEmpty()) return null
            return MebiusDelivery(kind = kind, path = path)
        }

        /** Convenience overload for a whole token-response body. */
        @JvmStatic
        public fun fromTokenResponse(body: JSONObject?): List<MebiusDelivery> = fromJson(body?.optJSONArray("deliveries"))
    }
}
