package dev.cambridge.sender.media.streaming.cambridge

import dev.cambridge.sender.connection.control.cambridge.CamBridgeStreamContract
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class RtpPacketizer(
    private val sendPacket: (ByteArray) -> Boolean,
) {
    fun sendAccessUnit(
        annexB: ByteArray,
        timestampUs: Long,
        initialSequence: Int,
        ssrc: Int,
    ): Result<Int> = runCatching {
        require(annexB.isNotEmpty()) { "An RTP access unit cannot be empty" }
        require(annexB.size <= CamBridgeStreamContract.MAXIMUM_ACCESS_UNIT_BYTES) {
            "The access unit exceeds the configured maximum"
        }
        val nals = annexB.nalRanges()
        require(nals.isNotEmpty()) { "The access unit does not contain Annex-B NAL units" }
        var sequence = initialSequence and SEQUENCE_MASK
        val timestamp = ((timestampUs * CamBridgeStreamContract.RTP_CLOCK_RATE_HZ) / MICROSECONDS_PER_SECOND).toInt()
        nals.forEachIndexed { index, range ->
            val isLastNal = index == nals.lastIndex
            val nal = annexB.copyOfRange(range.first, range.last + RANGE_END_INCLUSIVE_OFFSET)
            require(nal.isNotEmpty()) { "An RTP NAL unit cannot be empty" }
            sequence = sendNal(nal, isLastNal, timestamp, sequence, ssrc)
        }
        sequence
    }

    private fun sendNal(
        nal: ByteArray,
        isLastNal: Boolean,
        timestamp: Int,
        initialSequence: Int,
        ssrc: Int,
    ): Int {
        val maximumPayload = CamBridgeStreamContract.RTP_MTU_BYTES - CamBridgeStreamContract.RTP_HEADER_BYTES
        if (nal.size <= maximumPayload) {
            return sendDatagram(nal, isLastNal, timestamp, initialSequence, ssrc)
        }
        val maximumFragment = maximumPayload - FU_PAYLOAD_HEADER_BYTES
        require(maximumFragment > EMPTY_VALUE) { "RTP MTU leaves no FU-A payload" }
        val indicator = (nal[EMPTY_OFFSET].toInt() and NRI_MASK) or FU_A_TYPE
        val nalType = nal[EMPTY_OFFSET].toInt() and NAL_TYPE_MASK
        var sequence = initialSequence
        var offset = NAL_HEADER_BYTES
        var first = true
        while (offset < nal.size) {
            val fragmentSize = minOf(maximumFragment, nal.size - offset)
            val end = offset + fragmentSize == nal.size
            val payload = ByteArray(FU_PAYLOAD_HEADER_BYTES + fragmentSize)
            payload[EMPTY_OFFSET] = indicator.toByte()
            payload[ONE_BYTE_OFFSET] = ((if (first) FU_START_MASK else EMPTY_VALUE) or
                (if (end) FU_END_MASK else EMPTY_VALUE) or nalType).toByte()
            nal.copyInto(payload, FU_PAYLOAD_HEADER_BYTES, offset, offset + fragmentSize)
            sequence = sendDatagram(payload, end && isLastNal, timestamp, sequence, ssrc)
            offset += fragmentSize
            first = false
        }
        return sequence
    }

    private fun sendDatagram(
        payload: ByteArray,
        marker: Boolean,
        timestamp: Int,
        sequence: Int,
        ssrc: Int,
    ): Int {
        require(payload.isNotEmpty()) { "An RTP payload cannot be empty" }
        val packet = ByteArray(CamBridgeStreamContract.RTP_HEADER_BYTES + payload.size)
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        buffer.put((RTP_VERSION shl RTP_VERSION_SHIFT).toByte())
        buffer.put(((if (marker) RTP_MARKER_MASK else EMPTY_VALUE) or CamBridgeStreamContract.RTP_PAYLOAD_TYPE).toByte())
        buffer.putShort((sequence and SEQUENCE_MASK).toShort())
        buffer.putInt(timestamp)
        buffer.putInt(ssrc)
        payload.copyInto(packet, CamBridgeStreamContract.RTP_HEADER_BYTES)
        check(sendPacket(packet)) { "The RTP socket rejected a packet" }
        return (sequence + SEQUENCE_INCREMENT) and SEQUENCE_MASK
    }

    private fun ByteArray.nalRanges(): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var offset = EMPTY_OFFSET
        while (offset < size) {
            val (start, codeBytes) = findStartCode(offset) ?: break
            val nalStart = start + codeBytes
            require(nalStart < size) { "Annex-B start code has no NAL" }
            val next = findStartCode(nalStart)
            var nalEnd = next?.first ?: size
            while (nalEnd > nalStart && this[nalEnd - ONE_BYTE_OFFSET] == ZERO_BYTE) {
                nalEnd -= ONE_BYTE_OFFSET
            }
            require(nalEnd > nalStart) { "Annex-B contains an empty NAL" }
            ranges += nalStart until nalEnd
            offset = next?.first ?: size
        }
        return ranges
    }

    private fun ByteArray.findStartCode(from: Int): Pair<Int, Int>? {
        var index = from
        while (index + THREE_BYTE_START_CODE_BYTES <= size) {
            if (this[index] == ZERO_BYTE && this[index + ONE_BYTE_OFFSET] == ZERO_BYTE) {
                if (this[index + TWO_BYTE_OFFSET] == ONE_BYTE) return index to THREE_BYTE_START_CODE_BYTES
                if (index + FOUR_BYTE_START_CODE_BYTES <= size &&
                    this[index + TWO_BYTE_OFFSET] == ZERO_BYTE && this[index + THREE_BYTE_OFFSET] == ONE_BYTE
                ) {
                    return index to FOUR_BYTE_START_CODE_BYTES
                }
            }
            index += ONE_BYTE_OFFSET
        }
        return null
    }

    private companion object {
        const val MICROSECONDS_PER_SECOND = 1_000_000L
        const val SEQUENCE_MASK = 0xffff
        const val RTP_VERSION = 2
        const val RTP_VERSION_SHIFT = 6
        const val RTP_MARKER_MASK = 0x80
        const val NAL_HEADER_BYTES = 1
        const val FU_PAYLOAD_HEADER_BYTES = 2
        const val NRI_MASK = 0xe0
        const val FU_A_TYPE = 28
        const val NAL_TYPE_MASK = 0x1f
        const val FU_START_MASK = 0x80
        const val FU_END_MASK = 0x40
        const val EMPTY_VALUE = 0
        const val EMPTY_OFFSET = 0
        const val SEQUENCE_INCREMENT = 1
        const val ONE_BYTE_OFFSET = 1
        const val TWO_BYTE_OFFSET = 2
        const val THREE_BYTE_OFFSET = 3
        const val THREE_BYTE_START_CODE_BYTES = 3
        const val FOUR_BYTE_START_CODE_BYTES = 4
        const val RANGE_END_INCLUSIVE_OFFSET = 1
        val ZERO_BYTE = 0.toByte()
        val ONE_BYTE = 1.toByte()
    }
}
