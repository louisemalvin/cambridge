package dev.cambridge.sender

import dev.cambridge.sender.model.EncoderAcceleration
import dev.cambridge.sender.model.EncoderCapability
import dev.cambridge.sender.model.VideoCodec
import dev.cambridge.sender.session.PhoneVideoCapabilities
import dev.cambridge.sender.session.VideoProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneVideoCapabilitiesTest {
    @Test
    fun productCatalogKeepsThePhoneOwnedModeRangesAndDefaults() {
        assertEquals(2_000_000, VideoProfiles.PROFILE_720P30.minimumBitrateBps)
        assertEquals(4_000_000, VideoProfiles.PROFILE_720P30.defaultBitrateBps)
        assertEquals(8_000_000, VideoProfiles.PROFILE_720P30.maximumBitrateBps)
        assertEquals(4_000_000, VideoProfiles.PROFILE_1080P30.minimumBitrateBps)
        assertEquals(8_000_000, VideoProfiles.PROFILE_1080P30.defaultBitrateBps)
        assertEquals(16_000_000, VideoProfiles.PROFILE_1080P30.maximumBitrateBps)
        assertEquals(8_000_000, VideoProfiles.PROFILE_1080P60.minimumBitrateBps)
        assertEquals(16_000_000, VideoProfiles.PROFILE_1080P60.defaultBitrateBps)
        assertEquals(32_000_000, VideoProfiles.PROFILE_1080P60.maximumBitrateBps)
        assertEquals(9_000_000, VideoProfiles.PROFILE_2K30.minimumBitrateBps)
        assertEquals(18_000_000, VideoProfiles.PROFILE_2K30.defaultBitrateBps)
        assertEquals(36_000_000, VideoProfiles.PROFILE_2K30.maximumBitrateBps)
        assertEquals(18_000_000, VideoProfiles.PROFILE_2K60.minimumBitrateBps)
        assertEquals(36_000_000, VideoProfiles.PROFILE_2K60.defaultBitrateBps)
        assertEquals(72_000_000, VideoProfiles.PROFILE_2K60.maximumBitrateBps)
    }

    @Test
    fun effectiveBitrateRangeIntersectsEncoderRangeOnPhone() {
        val mode = VideoProfiles.PROFILE_1080P30
        val capability = PhoneVideoCapabilities.resolve(
            modes = listOf(mode),
            cameraSupportedModeIds = setOf(mode.id),
            encoderCapabilities = listOf(
                EncoderCapability(
                    codec = VideoCodec.H264,
                    profileId = mode.id,
                    supported = true,
                    acceleration = EncoderAcceleration.HARDWARE,
                    encoderName = "test-h264",
                    minimumBitrateBps = 6_500_000,
                    maximumBitrateBps = 12_500_000,
                ),
            ),
        ).single()

        assertTrue(capability.isSupported)
        assertEquals(7_000_000..12_000_000, capability.bitrateRange)
    }

    @Test
    fun cameraOrEncoderSupportFailureDisablesOnlyThatMode() {
        val mode = VideoProfiles.PROFILE_2K30
        val cameraUnsupported = PhoneVideoCapabilities.resolve(
            modes = listOf(mode),
            cameraSupportedModeIds = emptySet(),
            encoderCapabilities = listOf(supportedEncoder(mode)),
        ).single()
        val encoderUnsupported = PhoneVideoCapabilities.resolve(
            modes = listOf(mode),
            cameraSupportedModeIds = setOf(mode.id),
            encoderCapabilities = listOf(
                supportedEncoder(mode).copy(supported = false, reason = "test encoder rejection"),
            ),
        ).single()

        assertFalse(cameraUnsupported.isSupported)
        assertFalse(encoderUnsupported.isSupported)
        assertEquals("test encoder rejection", encoderUnsupported.reason)
    }

    private fun supportedEncoder(mode: dev.cambridge.sender.model.VideoProfile) = EncoderCapability(
        codec = VideoCodec.H264,
        profileId = mode.id,
        supported = true,
        acceleration = EncoderAcceleration.HARDWARE,
        encoderName = "test-h264",
        minimumBitrateBps = mode.minimumBitrateBps,
        maximumBitrateBps = mode.maximumBitrateBps,
    )
}
