package dev.cambridge.sender.media.streaming.cambridge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class H264AccessUnitNormalizerTest {
    @Test
    fun annexBOutputIsPreserved() {
        val annexB = START_CODE + SPS + START_CODE + PPS + START_CODE + IDR

        assertArrayEquals(annexB, H264AccessUnitNormalizer.normalize(annexB))
        assertTrue(H264AccessUnitNormalizer.containsRequiredParameterSets(annexB))
    }

    @Test
    fun fourByteLengthPrefixedAccessUnitPreservesNalBoundaries() {
        val lengthPrefixed = lengthPrefixed(SPS, PPS, IDR)
        val expected = START_CODE + SPS + START_CODE + PPS + START_CODE + IDR

        assertArrayEquals(expected, H264AccessUnitNormalizer.normalize(lengthPrefixed))
    }

    @Test
    fun avcConfigurationRecordBecomesSpsAndPpsAnnexB() {
        val configuration = avcConfigurationRecord(SPS, PPS)
        val expected = START_CODE + SPS + START_CODE + PPS

        assertArrayEquals(expected, H264AccessUnitNormalizer.normalizeCodecConfiguration(configuration))
    }

    @Test
    fun separateCodecParameterSetsBecomeAnnexB() {
        val expected = START_CODE + SPS + START_CODE + PPS

        assertArrayEquals(expected, H264AccessUnitNormalizer.normalizeParameterSets(SPS, PPS))
    }

    @Test
    fun codecConfigurationCanBePrependedToASeparatedKeyFrame() {
        val configuration = H264AccessUnitNormalizer.normalizeCodecConfiguration(avcConfigurationRecord(SPS, PPS))
        val keyFrame = H264AccessUnitNormalizer.normalize(lengthPrefixed(IDR))
        val expected = START_CODE + SPS + START_CODE + PPS + START_CODE + IDR

        assertArrayEquals(expected, configuration + keyFrame)
        assertTrue(H264AccessUnitNormalizer.containsRequiredParameterSets(configuration))
        assertFalse(H264AccessUnitNormalizer.containsRequiredParameterSets(keyFrame))
    }

    @Test
    fun malformedLengthPrefixedOutputIsRejected() {
        assertFailure(lengthPrefixedWithLength(TRUNCATED_NAL_LENGTH, IDR.copyOfRange(0, TRUNCATED_NAL_BYTES)))
        assertFailure(byteArrayOf(0, 0, 0))
    }

    @Test
    fun malformedAvcConfigurationIsRejected() {
        assertFailure(avcConfigurationRecord(SPS))
        assertFailure(avcConfigurationRecord(SPS, PPS) + TRAILING_CONFIGURATION_BYTE)
    }

    @Test
    fun missingParameterSetsAreRejectedBeforeMediaConfigurationIsAccepted() {
        val spsOnly = lengthPrefixed(SPS)

        assertFailure { H264AccessUnitNormalizer.normalizeCodecConfiguration(spsOnly) }
        assertFalse(H264AccessUnitNormalizer.containsRequiredParameterSets(H264AccessUnitNormalizer.normalize(spsOnly)))
    }

    private fun assertFailure(data: ByteArray) {
        assertFailure { H264AccessUnitNormalizer.normalize(data) }
    }

    private fun assertFailure(action: () -> Unit) {
        var failed = false
        try {
            action()
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue("Expected malformed H.264 data to be rejected", failed)
    }

    private fun lengthPrefixed(vararg nals: ByteArray): ByteArray =
        nals.fold(ByteArray(EMPTY_BYTES)) { output, nal ->
            output + lengthPrefixedWithLength(nal.size, nal)
        }

    private fun lengthPrefixedWithLength(length: Int, nal: ByteArray): ByteArray =
        byteArrayOf(
            (length shr THREE_BYTE_SHIFT).toByte(),
            (length shr TWO_BYTE_SHIFT).toByte(),
            (length shr BYTE_SHIFT).toByte(),
            length.toByte(),
        ) + nal

    private fun avcConfigurationRecord(sps: ByteArray, pps: ByteArray? = null): ByteArray {
        val parameterSet = pps ?: return byteArrayOf(
            AVC_CONFIGURATION_VERSION,
            sps[PROFILE_OFFSET],
            sps[COMPATIBILITY_OFFSET],
            sps[LEVEL_OFFSET],
            AVC_LENGTH_SIZE_FIELD,
            SPS_COUNT_FIELD,
            (sps.size shr BYTE_SHIFT).toByte(),
            sps.size.toByte(),
        ) + sps
        return byteArrayOf(
            AVC_CONFIGURATION_VERSION,
            sps[PROFILE_OFFSET],
            sps[COMPATIBILITY_OFFSET],
            sps[LEVEL_OFFSET],
            AVC_LENGTH_SIZE_FIELD,
            SPS_COUNT_FIELD,
            (sps.size shr BYTE_SHIFT).toByte(),
            sps.size.toByte(),
        ) + sps + byteArrayOf(
            PPS_COUNT_FIELD,
            (parameterSet.size shr BYTE_SHIFT).toByte(),
            parameterSet.size.toByte(),
        ) + parameterSet
    }

    private companion object {
        const val EMPTY_BYTES = 0
        const val BYTE_SHIFT = 8
        const val TWO_BYTE_SHIFT = 16
        const val THREE_BYTE_SHIFT = 24
        const val PROFILE_OFFSET = 1
        const val COMPATIBILITY_OFFSET = 2
        const val LEVEL_OFFSET = 3
        const val AVC_CONFIGURATION_VERSION: Byte = 1
        const val AVC_LENGTH_SIZE_FIELD: Byte = -1
        const val SPS_COUNT_FIELD: Byte = -31
        const val PPS_COUNT_FIELD: Byte = 1
        const val TRUNCATED_NAL_LENGTH = 5
        const val TRUNCATED_NAL_BYTES = 2
        const val TRAILING_CONFIGURATION_BYTE: Byte = 0
        val START_CODE = byteArrayOf(0, 0, 0, 1)
        val SPS = byteArrayOf(0x67, 0x42, 0x00, 0x1f, 0x01)
        val PPS = byteArrayOf(0x68, 0xce.toByte(), 0x06, 0xe2.toByte())
        val IDR = byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte(), 0x21)
    }
}
