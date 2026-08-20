package dev.cambridge.sender.media.streaming.cambridge

import dev.cambridge.sender.connection.control.cambridge.CamBridgeStreamContract
import java.io.ByteArrayOutputStream

internal object H264AccessUnitNormalizer {
    fun normalize(data: ByteArray): ByteArray {
        require(data.isNotEmpty()) { "H.264 output is empty" }
        require(data.size <= CamBridgeStreamContract.MAXIMUM_ACCESS_UNIT_BYTES) {
            "H.264 output exceeds the configured maximum"
        }
        if (startsWithAnnexB(data)) {
            annexBNalUnits(data)
            return data
        }
        if (data[EMPTY_OFFSET].toInt() and BYTE_MASK == AVC_CONFIGURATION_VERSION) {
            return avcConfigurationToAnnexB(data)
        }
        return lengthPrefixedToAnnexB(data)
    }

    fun normalizeCodecConfiguration(data: ByteArray): ByteArray {
        val normalized = normalize(data)
        require(containsRequiredParameterSets(normalized)) {
            "H.264 codec configuration must contain SPS and PPS"
        }
        return parameterSetsFromAnnexB(normalized)
    }

    fun normalizeParameterSets(sps: ByteArray, pps: ByteArray): ByteArray {
        val normalizedSps = toAnnexBNal(sps)
        val normalizedPps = toAnnexBNal(pps)
        val spsUnits = annexBNalUnits(normalizedSps)
        val ppsUnits = annexBNalUnits(normalizedPps)
        require(spsUnits.size == SINGLE_NAL_COUNT) {
            "H.264 csd-0 contains multiple NAL units"
        }
        require(ppsUnits.size == SINGLE_NAL_COUNT) {
            "H.264 csd-1 contains multiple NAL units"
        }
        require(nalType(spsUnits.single()) == SPS_NAL_TYPE) { "H.264 csd-0 is not an SPS" }
        require(nalType(ppsUnits.single()) == PPS_NAL_TYPE) { "H.264 csd-1 is not a PPS" }
        return normalizedSps + normalizedPps
    }

    fun toAnnexBNal(data: ByteArray): ByteArray {
        require(data.isNotEmpty()) { "H.264 parameter set is empty" }
        if (startsWithAnnexB(data)) {
            require(annexBNalUnits(data).size == SINGLE_NAL_COUNT) {
                "H.264 parameter set contains multiple NAL units"
            }
            return data
        }
        require(nalType(data) != EMPTY_NAL_TYPE) { "H.264 parameter set has no NAL header" }
        return START_CODE + data
    }

    fun containsRequiredParameterSets(annexB: ByteArray): Boolean {
        val types = annexBNalUnits(annexB).map(::nalType).toSet()
        return SPS_NAL_TYPE in types && PPS_NAL_TYPE in types
    }

    fun parameterSetsFromAnnexB(annexB: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        annexBNalUnits(annexB).forEach { nal ->
            if (nalType(nal) == SPS_NAL_TYPE || nalType(nal) == PPS_NAL_TYPE) {
                appendNal(output, nal)
            }
        }
        require(output.size() > EMPTY_OUTPUT_BYTES) { "H.264 output contains no parameter sets" }
        return output.toByteArray()
    }

    private fun avcConfigurationToAnnexB(data: ByteArray): ByteArray {
        require(data.size >= AVC_CONFIGURATION_MINIMUM_BYTES) { "AVC configuration is truncated" }
        require(data[AVC_CONFIGURATION_VERSION_OFFSET].toInt() and BYTE_MASK == AVC_CONFIGURATION_VERSION) {
            "Unsupported AVC configuration version"
        }
        val lengthBytes =
            (data[AVC_LENGTH_SIZE_OFFSET].toInt() and AVC_LENGTH_SIZE_MASK) + ONE_BYTE_COUNT
        require(lengthBytes in MINIMUM_NAL_LENGTH_BYTES..MAXIMUM_NAL_LENGTH_BYTES) {
            "Unsupported AVC NAL length size"
        }
        var offset = AVC_CONFIGURATION_SPS_COUNT_OFFSET
        val output = ByteArrayOutputStream()
        val spsCount = data[offset].toInt() and AVC_SPS_COUNT_MASK
        require(spsCount > EMPTY_OUTPUT_BYTES) { "AVC configuration has no SPS" }
        offset += ONE_BYTE_COUNT
        repeat(spsCount) {
            val nal = readConfigurationNal(data, offset).also { offset = it.second }.first
            require(nalType(nal) == SPS_NAL_TYPE) { "AVC configuration contains a non-SPS NAL" }
            appendNal(output, nal)
        }
        require(offset < data.size) { "AVC configuration has no PPS count" }
        val ppsCount = data[offset].toInt() and BYTE_MASK
        require(ppsCount > EMPTY_OUTPUT_BYTES) { "AVC configuration has no PPS" }
        offset += ONE_BYTE_COUNT
        repeat(ppsCount) {
            val nal = readConfigurationNal(data, offset).also { offset = it.second }.first
            require(nalType(nal) == PPS_NAL_TYPE) { "AVC configuration contains a non-PPS NAL" }
            appendNal(output, nal)
        }
        require(offset == data.size) { "AVC configuration contains trailing data" }
        return output.toByteArray()
    }

    private fun readConfigurationNal(data: ByteArray, offset: Int): Pair<ByteArray, Int> {
        require(offset + AVC_CONFIGURATION_NAL_LENGTH_BYTES <= data.size) {
            "AVC configuration NAL length is truncated"
        }
        val nalSize = ((data[offset].toInt() and BYTE_MASK) shl BYTE_SHIFT) or
            (data[offset + ONE_BYTE_OFFSET].toInt() and BYTE_MASK)
        val start = offset + AVC_CONFIGURATION_NAL_LENGTH_BYTES
        require(nalSize > EMPTY_OUTPUT_BYTES && nalSize <= data.size - start) {
            "AVC configuration NAL is truncated"
        }
        return data.copyOfRange(start, start + nalSize) to (start + nalSize)
    }

    private fun lengthPrefixedToAnnexB(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(data.size + START_CODE.size)
        var offset = EMPTY_OFFSET
        var nalCount = EMPTY_OUTPUT_BYTES
        while (offset < data.size) {
            require(data.size - offset >= LENGTH_PREFIX_BYTES) {
                "Malformed AVC length-prefixed output"
            }
            val nalSize = readLength(data, offset)
            offset += LENGTH_PREFIX_BYTES
            require(nalSize > EMPTY_OUTPUT_BYTES && nalSize <= data.size - offset) {
                "Malformed AVC length-prefixed output"
            }
            val nal = data.copyOfRange(offset, offset + nalSize)
            require(nalType(nal) != EMPTY_NAL_TYPE) { "AVC output contains an invalid NAL header" }
            appendNal(output, nal)
            offset += nalSize
            nalCount += ONE_NAL_COUNT
        }
        require(nalCount > EMPTY_OUTPUT_BYTES) { "AVC output contains no NAL units" }
        return output.toByteArray()
    }

    private fun readLength(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and BYTE_MASK) shl THREE_BYTE_SHIFT) or
            ((data[offset + ONE_BYTE_OFFSET].toInt() and BYTE_MASK) shl TWO_BYTE_SHIFT) or
            ((data[offset + TWO_BYTE_OFFSET].toInt() and BYTE_MASK) shl BYTE_SHIFT) or
            (data[offset + THREE_BYTE_OFFSET].toInt() and BYTE_MASK)

    private fun annexBNalUnits(data: ByteArray): List<ByteArray> {
        require(startsWithAnnexB(data)) { "H.264 data is not Annex-B" }
        val units = mutableListOf<ByteArray>()
        var offset = EMPTY_OFFSET
        while (offset < data.size) {
            val startCode = findStartCode(data, offset)
            require(startCode != null && startCode.first == offset) {
                "Malformed Annex-B H.264 output"
            }
            val nalStart = startCode.first + startCode.second
            require(nalStart < data.size) { "Annex-B start code has no NAL" }
            val nextStart = findStartCode(data, nalStart)
            val nalEnd = nextStart?.first ?: data.size
            require(nalEnd > nalStart) { "Annex-B contains an empty NAL" }
            val nal = data.copyOfRange(nalStart, nalEnd)
            require(nalType(nal) != EMPTY_NAL_TYPE) { "Annex-B contains an invalid NAL header" }
            units += nal
            offset = nextStart?.first ?: data.size
        }
        require(units.isNotEmpty()) { "Annex-B contains no NAL units" }
        return units
    }

    private fun startsWithAnnexB(data: ByteArray): Boolean =
        data.size >= THREE_BYTE_START_CODE_BYTES &&
            data[EMPTY_OFFSET] == ZERO_BYTE &&
            data[ONE_BYTE_OFFSET] == ZERO_BYTE &&
            (data[TWO_BYTE_OFFSET] == ONE_BYTE ||
                (data.size >= FOUR_BYTE_START_CODE_BYTES &&
                    data[TWO_BYTE_OFFSET] == ZERO_BYTE &&
                    data[THREE_BYTE_OFFSET] == ONE_BYTE))

    private fun findStartCode(data: ByteArray, from: Int): Pair<Int, Int>? {
        var offset = from
        while (offset + THREE_BYTE_START_CODE_BYTES <= data.size) {
            if (data[offset] == ZERO_BYTE && data[offset + ONE_BYTE_OFFSET] == ZERO_BYTE) {
                if (data[offset + TWO_BYTE_OFFSET] == ONE_BYTE) {
                    return offset to THREE_BYTE_START_CODE_BYTES
                }
                if (offset + FOUR_BYTE_START_CODE_BYTES <= data.size &&
                    data[offset + TWO_BYTE_OFFSET] == ZERO_BYTE &&
                    data[offset + THREE_BYTE_OFFSET] == ONE_BYTE
                ) {
                    return offset to FOUR_BYTE_START_CODE_BYTES
                }
            }
            offset += ONE_BYTE_COUNT
        }
        return null
    }

    private fun nalType(nal: ByteArray): Int {
        require(nal.isNotEmpty()) { "H.264 NAL is empty" }
        return nal[EMPTY_OFFSET].toInt() and NAL_TYPE_MASK
    }

    private fun appendNal(output: ByteArrayOutputStream, nal: ByteArray) {
        require(output.size() <= CamBridgeStreamContract.MAXIMUM_ACCESS_UNIT_BYTES - START_CODE.size - nal.size) {
            "Normalized H.264 output exceeds the configured maximum"
        }
        output.write(START_CODE)
        output.write(nal)
    }

    private const val EMPTY_OFFSET = 0
    private const val EMPTY_OUTPUT_BYTES = 0
    private const val ONE_BYTE_COUNT = 1
    private const val ONE_NAL_COUNT = 1
    private const val ONE_BYTE_OFFSET = 1
    private const val TWO_BYTE_OFFSET = 2
    private const val THREE_BYTE_OFFSET = 3
    private const val BYTE_SHIFT = 8
    private const val TWO_BYTE_SHIFT = 16
    private const val THREE_BYTE_SHIFT = 24
    private const val BYTE_MASK = 0xff
    private const val NAL_TYPE_MASK = 0x1f
    private const val EMPTY_NAL_TYPE = 0
    private const val SPS_NAL_TYPE = 7
    private const val PPS_NAL_TYPE = 8
    private const val THREE_BYTE_START_CODE_BYTES = 3
    private const val FOUR_BYTE_START_CODE_BYTES = 4
    private const val LENGTH_PREFIX_BYTES = 4
    private const val AVC_CONFIGURATION_VERSION_OFFSET = 0
    private const val AVC_CONFIGURATION_VERSION = 1
    private const val AVC_CONFIGURATION_MINIMUM_BYTES = 6
    private const val AVC_CONFIGURATION_SPS_COUNT_OFFSET = 5
    private const val AVC_CONFIGURATION_NAL_LENGTH_BYTES = 2
    private const val AVC_LENGTH_SIZE_OFFSET = 4
    private const val AVC_LENGTH_SIZE_MASK = 3
    private const val AVC_SPS_COUNT_MASK = 0x1f
    private const val MINIMUM_NAL_LENGTH_BYTES = 1
    private const val MAXIMUM_NAL_LENGTH_BYTES = 4
    private const val SINGLE_NAL_COUNT = 1
    private val ZERO_BYTE = 0.toByte()
    private val ONE_BYTE = 1.toByte()
    private val START_CODE = byteArrayOf(ZERO_BYTE, ZERO_BYTE, ZERO_BYTE, ONE_BYTE)
}
