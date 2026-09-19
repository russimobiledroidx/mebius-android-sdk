package io.mebius.sdk

import android.os.Looper
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mebius.sdk.internal.readToken
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * CR-1 on Android: a session that outlives the credential it opened with.
 *
 * What is worth proving here, in order:
 *  * without `getToken`, 0.2.x behaviour is untouched — no renewal, no new event;
 *  * with it, the credential is renewed and swapped in place;
 *  * a provider that fails does not end the broadcast while the old token lives;
 *  * a token for another stream is refused, and refusing does not damage the
 *    session.
 */
@RunWith(AndroidJUnit4::class)
class TokenRefreshTest {
    @Before
    fun setUp() {
        Mebius.init(
            RuntimeEnvironment.getApplication(),
            appId = "app",
            gateway = "https://gw.mebius.io",
        )
    }

    @After
    fun tearDown() {
        Mebius.reset()
    }

    @Test
    fun `readToken reads exp and streamId and shrugs off anything else`() {
        val info = readToken(jwt(streamId = "s_match"))
        assertEquals("s_match", info.streamId)
        assertTrue(info.expiresAtMillis!! > System.currentTimeMillis())

        for (junk in listOf("", "not-a-jwt", "a.b", "a.!!!.c")) {
            assertNull(junk, readToken(junk).expiresAtMillis)
            assertNull(junk, readToken(junk).streamId)
        }
    }

    @Test
    fun `without getToken nothing is renewed and nothing new is reported`() {
        val events = mutableListOf<String>()
        // Already expired. Any renewal that was going to happen would be due
        // immediately, and any new proactive error would fire now — neither may.
        val client = Mebius.connect(jwt(expiresInMillis = -ONE_HOUR_MS))
        client.listener = recordingListener(events)
        settle()

        assertTrue(client.isConnected)
        assertTrue(events.toString(), events.isEmpty())
    }

    @Test
    fun `getToken renews and swaps the credential in place`() {
        var mints = 0
        val events = mutableListOf<String>()
        // Expiry already inside the renewal margin, so the renewal is due at once
        // and the test does not have to wait an hour for it.
        val client =
            Mebius.connect(
                token = jwt(streamId = "s_match", expiresInMillis = THIRTY_SECONDS_MS),
                getToken = {
                    mints += 1
                    jwt(streamId = "s_match", expiresInMillis = ONE_HOUR_MS * (mints + 1))
                },
            )
        client.listener = recordingListener(events)
        // Wait on the EVENT, not on the counter: the provider increments `mints`
        // before the rest of the renewal runs, so a counter-based wait can win the
        // race against the callback it is really waiting for.
        await { events.contains("refreshed") }

        assertEquals(1, mints)
        assertTrue(events.toString(), events.contains("refreshed"))
        // Renewing is not reconnecting: the session was never torn down.
        assertTrue(client.isConnected)
    }

    @Test
    fun `a failing provider does not end a session whose token is still valid`() {
        var attempts = 0
        val events = mutableListOf<String>()
        val client =
            Mebius.connect(
                // Half a minute of life left: there IS a window to retry inside.
                token = jwt(expiresInMillis = THIRTY_SECONDS_MS),
                getToken = {
                    attempts += 1
                    error("backend down")
                },
            )
        client.listener = recordingListener(events)
        await { attempts >= 1 }
        settle()

        assertTrue(attempts >= 1)
        // The old credential is still good, so expiry must not be reported yet.
        assertTrue(events.toString(), events.none { it.startsWith("error") })
    }

    @Test
    fun `a token that is not newer is retried, not reported, while the old one lives`() {
        val stale = jwt(expiresInMillis = THIRTY_SECONDS_MS)
        var attempts = 0
        val events = mutableListOf<String>()
        val client =
            Mebius.connect(
                token = stale,
                getToken = {
                    attempts += 1
                    stale
                },
            )
        client.listener = recordingListener(events)
        await { attempts >= 2 }
        settle()

        // A provider stuck on a cached credential is a FAILED mint, not a dead
        // session: the old token is good for another half minute, so ending the
        // broadcast now would be worse than not renewing at all.
        assertTrue(attempts >= 2)
        assertTrue(events.toString(), events.none { it.startsWith("error") })
        assertTrue(client.isConnected)
    }

    @Test
    fun `a renewal for the wrong stream is dropped instead of installed`() {
        var attempts = 0
        val events = mutableListOf<String>()
        val client =
            Mebius.connect(
                token = jwt(streamId = "s_match", expiresInMillis = THIRTY_SECONDS_MS),
                // A provider closure holding a stale stream id. Installing this would
                // break the session on its next request, far from the cause.
                getToken = {
                    attempts += 1
                    jwt(streamId = "s_other", expiresInMillis = ONE_HOUR_MS * 5)
                },
            )
        client.listener = recordingListener(events)
        await { attempts >= 1 }
        settle()

        assertTrue(events.toString(), events.none { it == "refreshed" })
        assertTrue(client.isConnected)
    }

    @Test
    fun `disconnect during a renewal leaves nothing running`() {
        val gate = java.util.concurrent.CountDownLatch(1)
        val entered = java.util.concurrent.CountDownLatch(1)
        val events = mutableListOf<String>()
        val client =
            Mebius.connect(
                token = jwt(expiresInMillis = THIRTY_SECONDS_MS),
                getToken = {
                    entered.countDown()
                    gate.await()
                    jwt(expiresInMillis = ONE_HOUR_MS)
                },
            )
        client.listener = recordingListener(events)
        entered.await(5, java.util.concurrent.TimeUnit.SECONDS)

        client.disconnect()
        gate.countDown()
        // Give the released provider time to try to apply its answer.
        Thread.sleep(200)
        settle()

        // The late answer must not revive a disconnected session.
        assertFalse(client.isConnected)
        assertTrue(events.toString(), events.none { it == "refreshed" })
    }

    @Test
    fun `updateToken replaces the credential without restarting anything`() {
        val client = Mebius.connect(jwt(streamId = "s_match"))
        client.updateToken(jwt(streamId = "s_match", expiresInMillis = ONE_HOUR_MS * 2))
        assertTrue(client.isConnected)
    }

    @Test
    fun `updateToken refuses a token for another stream and the session survives`() {
        val client = Mebius.connect(jwt(streamId = "s_match"))
        assertThrows(IllegalArgumentException::class.java) {
            client.updateToken(jwt(streamId = "s_other"))
        }
        // A rejected swap must not be a way to break a live broadcast.
        assertTrue(client.isConnected)
    }

    @Test
    fun `updateToken refuses a blank token`() {
        val client = Mebius.connect(jwt())
        assertThrows(IllegalArgumentException::class.java) { client.updateToken("  ") }
        assertTrue(client.isConnected)
    }

    @Test
    fun `a player reports no renditions, so a UI can hide its quality menu`() {
        val player = Mebius.connect(jwt()).createPlayer()
        assertTrue(player.qualities.isEmpty())
    }

    @Test
    fun `setQuality accepts auto and refuses anything not on offer`() {
        val player = Mebius.connect(jwt()).createPlayer()
        player.setQuality("auto")
        assertThrows(IllegalArgumentException::class.java) { player.setQuality("ngawur") }
    }

    private fun recordingListener(into: MutableList<String>) =
        object : MebiusClientListener {
            override fun onTokenRefreshed() {
                into += "refreshed"
            }

            override fun onError(error: MebiusError) {
                into += "error:${error.code}"
            }

            override fun onDisconnected() {
                into += "disconnected"
            }
        }

    /** Runs the main looper so listener callbacks posted to it actually land. */
    private fun settle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * Waits for background work to reach [done].
     *
     * Renewal runs on [kotlinx.coroutines.Dispatchers.Default], which Robolectric
     * does not drive, so this polls a real (short) wall clock rather than advancing
     * a virtual one. The main looper is idled each round so callbacks posted to it
     * are visible to the predicate.
     */
    private fun await(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline && !done()) {
            settle()
            Thread.sleep(AWAIT_POLL_MS)
        }
        settle()
    }

    private companion object {
        const val ONE_HOUR_MS = 60 * 60 * 1000L
        const val THIRTY_SECONDS_MS = 30_000L
        const val AWAIT_TIMEOUT_MS = 5_000L
        const val AWAIT_POLL_MS = 20L

        /**
         * An unsigned JWT carrying just the claims this SDK reads. Nothing verifies
         * it here; only the gateway holds the secret.
         */
        fun jwt(
            streamId: String? = null,
            expiresInMillis: Long = ONE_HOUR_MS,
        ): String {
            val payload =
                JSONObject().apply {
                    if (streamId != null) put("streamId", streamId)
                    put("exp", (System.currentTimeMillis() + expiresInMillis) / 1000)
                }
            val encoded =
                Base64.encodeToString(
                    payload.toString().toByteArray(Charsets.UTF_8),
                    Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
                )
            return "header.$encoded.sig"
        }
    }
}
