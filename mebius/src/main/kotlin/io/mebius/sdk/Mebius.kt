package io.mebius.sdk

import android.content.Context
import io.mebius.sdk.internal.GatewayConfig

/**
 * Entry point to the Mebius SDK.
 *
 * Initialize once (typically in your `Application.onCreate`) with [init], then
 * create an authenticated [MebiusClient] with [connect].
 *
 * ```kotlin
 * // In Application.onCreate:
 * Mebius.init(context, appId = "my-app-id", gateway = "https://gateway.mebius.io")
 *
 * // Later, after fetching a short-lived token from your backend:
 * val client = Mebius.connect(token)
 * ```
 *
 * The token is a short-lived credential minted by your backend; your application
 * secret never reaches the device.
 */
public object Mebius {
    @Volatile
    private var config: GatewayConfig? = null

    @Volatile
    private var appContext: Context? = null

    /** The SDK version. */
    public const val VERSION: String = BuildConfig.MEBIUS_VERSION

    /**
     * Initializes the SDK. Call once before [connect].
     *
     * @param context any [Context]; the application context is retained.
     * @param appId your Mebius application id.
     * @param gateway the Mebius signaling endpoint base URL (for example
     *  `https://gateway.mebius.io`). All signaling and media flows through it.
     * @throws IllegalArgumentException if [appId] or [gateway] is blank.
     */
    @JvmStatic
    public fun init(
        context: Context,
        appId: String,
        gateway: String,
    ) {
        require(appId.isNotBlank()) { "appId must not be blank." }
        require(gateway.isNotBlank()) { "gateway must not be blank." }
        appContext = context.applicationContext
        config = GatewayConfig(appId = appId, gateway = gateway)
    }

    /**
     * Connects using a short-lived [token] obtained from your backend and returns
     * an authenticated [MebiusClient].
     *
     * @param token a short-lived JWT minted by your backend. Never embed your
     *  application secret in the app.
     * @param deliveries the route list your backend returned with the token. Pass it
     *  through as-is; Mebius orders it and picks from it. Optional, but without it
     *  every viewer is served from Mebius origin instead of the nearest edge — on
     *  mobile that is billed per viewer.
     * @return a connected [MebiusClient].
     * @throws IllegalStateException if [init] has not been called.
     * @throws IllegalArgumentException if [token] is blank.
     */
    @JvmStatic
    @JvmOverloads
    public fun connect(
        token: String,
        deliveries: List<MebiusDelivery> = emptyList(),
    ): MebiusClient {
        val cfg = checkNotNull(config) { "Mebius.init(...) must be called before connect()." }
        val ctx = checkNotNull(appContext) { "Mebius.init(...) must be called before connect()." }
        require(token.isNotBlank()) { "token must not be blank." }

        return MebiusClient(ctx, cfg, token, deliveries).also { it.markConnected() }
    }

    /** Whether [init] has been called. */
    @JvmStatic
    public val isInitialized: Boolean
        get() = config != null

    /** Resets SDK state. Primarily for tests. */
    @JvmStatic
    public fun reset() {
        config = null
        appContext = null
    }
}
