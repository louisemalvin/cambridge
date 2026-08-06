package dev.mobilewebcam.sender.media.streaming.direct

import dev.mobilewebcam.sender.connection.control.direct.DirectStreamContract
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RtpPacketizerTest {
    @Test
    fun packetizesACompleteSingleNalWithTheMarkerBit() {
        val packets = mutableListOf<ByteArray>()
        val packetizer = RtpPacketizer { packet -> packets += packet; true }
        val accessUnit = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3)

        val nextSequence = packetizer.sendAccessUnit(
            annexB = accessUnit,
            timestampUs = MICROSECONDS_PER_FRAME,
            initialSequence = INITIAL_SEQUENCE,
            ssrc = TEST_SSRC,
        ).getOrThrow()

        assertEquals(1, packets.size)
        assertEquals(INITIAL_SEQUENCE, sequence(packets.single()))
        assertEquals(EXPECTED_TIMESTAMP, timestamp(packets.single()))
        assertTrue(marker(packets.single()))
        assertArrayEquals(byteArrayOf(0x65, 1, 2, 3), payload(packets.single()))
        assertEquals(INITIAL_SEQUENCE + 1, nextSequence)
    }

    @Test
    fun fragmentsLargeNalsAndWrapsTheSequenceNumber() {
        val packets = mutableListOf<ByteArray>()
        val packetizer = RtpPacketizer { packet -> packets += packet; true }
        val nal = ByteArray(LARGE_NAL_BYTES) { index ->
            if (index == 0) NAL_TYPE_IDR else index.toByte()
        }
        val accessUnit = byteArrayOf(0, 0, 1) + nal

        val nextSequence = packetizer.sendAccessUnit(
            annexB = accessUnit,
            timestampUs = 0,
            initialSequence = MAX_SEQUENCE,
            ssrc = TEST_SSRC,
        ).getOrThrow()

        assertEquals(EXPECTED_FRAGMENT_COUNT, packets.size)
        assertEquals(MAX_SEQUENCE, sequence(packets.first()))
        assertEquals(0, sequence(packets[1]))
        assertEquals(1, sequence(packets.last()))
        assertEquals(2, nextSequence)
        packets.forEach { packet ->
            assertTrue(packet.size <= DirectStreamContract.RTP_MTU_BYTES)
            assertTrue(isFuA(payload(packet)))
        }
        assertTrue(fuStart(payload(packets.first())))
        assertFalse(fuEnd(payload(packets.first())))
        assertFalse(fuStart(payload(packets.last())))
        assertTrue(fuEnd(payload(packets.last())))
        assertFalse(marker(packets.first()))
        assertTrue(marker(packets.last()))
    }

    @Test
    fun rejectsAccessUnitsWithoutAnnexBStartCodes() {
        val packetizer = RtpPacketizer { true }

        val result = packetizer.sendAccessUnit(
            annexB = byteArrayOf(0x65, 1, 2),
            timestampUs = 0,
            initialSequence = 0,
            ssrc = TEST_SSRC,
        )

        assertTrue(result.isFailure)
    }

    private fun sequence(packet: ByteArray): Int =
        ByteBuffer.wrap(packet, SEQUENCE_OFFSET, SEQUENCE_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .short
            .toInt() and MAX_SEQUENCE

    private fun timestamp(packet: ByteArray): Int =
        ByteBuffer.wrap(packet, TIMESTAMP_OFFSET, TIMESTAMP_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .int

    private fun marker(packet: ByteArray): Boolean = packet[MARKER_OFFSET].toInt() and MARKER_MASK != 0

    private fun payload(packet: ByteArray): ByteArray =
        packet.copyOfRange(DirectStreamContract.RTP_HEADER_BYTES, packet.size)

    private fun isFuA(payload: ByteArray): Boolean = payload[0].toInt() and NAL_TYPE_MASK == FU_A_TYPE

    private fun fuStart(payload: ByteArray): Boolean = payload[1].toInt() and FU_START_MASK != 0

    private fun fuEnd(payload: ByteArray): Boolean = payload[1].toInt() and FU_END_MASK != 0

    private companion object {
        const val MICROSECONDS_PER_FRAME = 1_000_000L
        const val INITIAL_SEQUENCE = 4_000
        const val MAX_SEQUENCE = 0xffff
        const val TEST_SSRC = 0x10203040
        const val LARGE_NAL_BYTES = 2_500
        const val EXPECTED_FRAGMENT_COUNT = 3
        const val EXPECTED_TIMESTAMP = 90_000
        const val SEQUENCE_OFFSET = 2
        const val SEQUENCE_BYTES = 2
        const val TIMESTAMP_OFFSET = 4
        const val TIMESTAMP_BYTES = 4
        const val MARKER_OFFSET = 1
        const val MARKER_MASK = 0x80
        const val NAL_TYPE_MASK = 0x1f
        const val FU_A_TYPE = 28
        const val FU_START_MASK = 0x80
        const val FU_END_MASK = 0x40
        const val NAL_TYPE_IDR: Byte = 0x65
    }
}
