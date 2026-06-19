package io.mebius.sdk

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment

@RunWith(AndroidJUnit4::class)
class MebiusInitTest {

    @After
    fun tearDown() {
        Mebius.reset()
    }

    @Test
    fun `connect before init throws`() {
        Mebius.reset()
        assertFalse(Mebius.isInitialized)
        assertThrows(IllegalStateException::class.java) {
            Mebius.connect("token")
        }
    }

    @Test
    fun `init then connect yields a connected client`() {
        Mebius.init(RuntimeEnvironment.getApplication(), appId = "app", gateway = "https://gw.mebius.io")
        assertTrue(Mebius.isInitialized)
        val client = Mebius.connect("a.b.c")
        assertTrue(client.isConnected)
    }

    @Test
    fun `blank appId rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            Mebius.init(RuntimeEnvironment.getApplication(), appId = "", gateway = "https://gw")
        }
    }

    @Test
    fun `creating a broadcaster while disconnected throws NotConnected`() {
        Mebius.init(RuntimeEnvironment.getApplication(), appId = "app", gateway = "https://gw.mebius.io")
        val client = Mebius.connect("a.b.c")
        client.disconnect()
        val error = assertThrows(MebiusError.NotConnected::class.java) {
            client.createPlayer(PlaybackMode.SCALE)
        }
        assertTrue(error.code == MebiusError.Code.NOT_CONNECTED)
    }
}
