package io.mebius.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MebiusErrorTest {

    @Test
    fun `error codes map to expected subclasses`() {
        assertEquals(MebiusError.Code.TOKEN_EXPIRED, MebiusError.TokenExpired().code)
        assertEquals(MebiusError.Code.PERMISSION_DENIED, MebiusError.PermissionDenied().code)
        assertEquals(MebiusError.Code.CONNECTION_FAILED, MebiusError.ConnectionFailed().code)
        assertEquals(MebiusError.Code.NOT_CONNECTED, MebiusError.NotConnected().code)
        assertEquals(MebiusError.Code.STREAM_NOT_FOUND, MebiusError.StreamNotFound().code)
        assertEquals(MebiusError.Code.UNKNOWN, MebiusError.Unknown().code)
    }

    @Test
    fun `errors are throwable and carry a message`() {
        val error: MebiusError = MebiusError.ConnectionFailed("boom")
        assertTrue(error is Exception)
        assertEquals("boom", error.message)
    }

    @Test
    fun `error code enum has exactly the six contract codes`() {
        assertEquals(6, MebiusError.Code.entries.size)
    }
}
