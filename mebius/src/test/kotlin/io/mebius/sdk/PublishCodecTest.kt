package io.mebius.sdk

import io.mebius.sdk.internal.orderH264First
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.webrtc.RtpCapabilities

/**
 * Codec ORDER is the whole point: the gateway's segment-based deliveries cannot
 * carry VP8, so a VP8-first offer reaches those viewers as audio only while the
 * device shows a healthy preview.
 */
class PublishCodecTest {
    private fun codec(mimeType: String): RtpCapabilities.CodecCapability =
        RtpCapabilities.CodecCapability().also { it.mimeType = mimeType }

    @org.junit.Test
    fun `moves H264 to the front and keeps the rest in order`() {
        val ordered = orderH264First(listOf(codec("video/VP8"), codec("video/H264"), codec("video/AV1")))
        assertEquals(listOf("video/H264", "video/VP8", "video/AV1"), ordered.map { it.mimeType })
    }

    @org.junit.Test
    fun `keeps every other codec as a fallback rather than forcing H264`() {
        val ordered = orderH264First(listOf(codec("video/VP8"), codec("video/H264")))
        assertTrue(ordered.any { it.mimeType == "video/VP8" })
    }

    @org.junit.Test
    fun `returns nothing when the device cannot encode H264`() {
        // Empty means "do not touch the transceiver": forcing a preference list
        // without H264 would only reorder what libwebrtc already chose.
        assertTrue(orderH264First(listOf(codec("video/VP8"))).isEmpty())
    }

    @org.junit.Test
    fun `matches the mime type case-insensitively`() {
        val ordered = orderH264First(listOf(codec("video/VP8"), codec("video/h264")))
        assertEquals("video/h264", ordered.first().mimeType)
    }
}
