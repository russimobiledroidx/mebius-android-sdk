package io.mebius.sdk

import io.mebius.sdk.internal.MAX_RECOVERY_ATTEMPTS
import io.mebius.sdk.internal.RecoveryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic behind reopening a route that stopped delivering.
 *
 * Route selection ran once, in play(). Whatever produced a frame served the rest
 * of the session, and when it later died playback stopped and stayed stopped — a
 * black screen the viewer could only fix by leaving and coming back. On a
 * 90-minute watch that looked like bad luck; on a broadcast that runs for a day
 * it is a certainty.
 *
 * The Handler lives in the player. What is pinned here is what it decides.
 */
class RecoveryPolicyTest {
    @Test
    fun `spaces attempts out instead of hammering the edge`() {
        val p = RecoveryPolicy()
        assertEquals(1_000L, p.nextDelayMs())
        assertEquals(2_000L, p.nextDelayMs())
        assertEquals(4_000L, p.nextDelayMs())
        assertEquals(8_000L, p.nextDelayMs())
        assertEquals(16_000L, p.nextDelayMs())
    }

    @Test
    fun `gives up after a bounded number of attempts`() {
        val p = RecoveryPolicy()
        repeat(MAX_RECOVERY_ATTEMPTS) {
            assertFalse("budget must last the advertised number of attempts", p.exhausted)
            p.nextDelayMs()
        }
        assertTrue(p.exhausted)
    }

    @Test
    fun `caps the wait, so a long outage is not an hour of silence`() {
        val p = RecoveryPolicy()
        repeat(12) {
            assertTrue("delay must stay bounded", p.nextDelayMs() <= 30_000L)
        }
    }

    @Test
    fun `counts consecutive failures, so a flapping route can recover all day`() {
        val p = RecoveryPolicy()
        p.nextDelayMs()
        p.nextDelayMs()
        p.reset()
        assertFalse(p.exhausted)
        assertEquals(1_000L, p.nextDelayMs())
    }
}
