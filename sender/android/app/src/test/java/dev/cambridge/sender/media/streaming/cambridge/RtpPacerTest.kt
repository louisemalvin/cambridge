package dev.cambridge.sender.media.streaming.cambridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RtpPacerTest {
    @Test
    fun firstPacketIsImmediateAndFollowingPacketsUseTheConfiguredWireRate() {
        var nowNs = 0L
        val waits = mutableListOf<Long>()
        val pacer = RtpPacer(
            bitrateBps = TEST_BITRATE_BPS,
            nanoTime = { nowNs },
            waitNanos = { durationNs ->
                waits += durationNs
                nowNs += durationNs
            },
        )

        pacer.await(TEST_PACKET_BYTES)
        pacer.await(TEST_PACKET_BYTES)

        assertEquals(listOf(EXPECTED_PACKET_INTERVAL_NS), waits)
    }

    @Test
    fun aLateSenderDoesNotBuildAnUnboundedPacingBacklog() {
        var nowNs = 0L
        val waits = mutableListOf<Long>()
        val pacer = RtpPacer(
            bitrateBps = TEST_BITRATE_BPS,
            nanoTime = { nowNs },
            waitNanos = { durationNs ->
                waits += durationNs
                nowNs += durationNs
            },
        )

        pacer.await(TEST_PACKET_BYTES)
        nowNs += LATE_SENDER_NS
        pacer.await(TEST_PACKET_BYTES)
        pacer.await(TEST_PACKET_BYTES)

        assertEquals(listOf(EXPECTED_PACKET_INTERVAL_NS), waits)
        assertTrue(nowNs >= LATE_SENDER_NS + EXPECTED_PACKET_INTERVAL_NS)
    }

    private companion object {
        const val TEST_BITRATE_BPS = 8_000_000L
        const val TEST_PACKET_BYTES = 1_000
        const val BITS_PER_BYTE = 8L
        const val NANOSECONDS_PER_SECOND = 1_000_000_000L
        const val EXPECTED_PACKET_INTERVAL_NS =
            TEST_PACKET_BYTES * BITS_PER_BYTE * NANOSECONDS_PER_SECOND / TEST_BITRATE_BPS
        const val LATE_SENDER_NS = EXPECTED_PACKET_INTERVAL_NS * 4
    }
}
