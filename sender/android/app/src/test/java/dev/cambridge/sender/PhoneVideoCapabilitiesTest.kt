package dev.cambridge.sender

import dev.cambridge.sender.model.EncoderAcceleration
import dev.cambridge.sender.model.EncoderCapability
import dev.cambridge.sender.model.EncoderModeCapability
import dev.cambridge.sender.model.VideoCodec
import dev.cambridge.sender.model.VideoProfile
import dev.cambridge.sender.session.PhoneVideoCapabilities
import dev.cambridge.sender.session.VideoProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneVideoCapabilitiesTest {
    @Test
    fun generatedCompatibilityProfilesUseSharedProductDefaults() {
        assertEquals(1_000_000, VideoProfiles.PROFILE_1080P30.minimumBitrateBps)
        assertEquals(5_000_000, VideoProfiles.PROFILE_1080P30.defaultBitrateBps)
        assertEquals(100_000_000, VideoProfiles.PROFILE_1080P30.maximumBitrateBps)
        assertEquals(10_000_000, VideoProfiles.PROFILE_1080P60.defaultBitrateBps)
        assertEquals(9_000_000, VideoProfiles.PROFILE_2K30.defaultBitrateBps)
        assertEquals(18_000_000, VideoProfiles.PROFILE_2K60.defaultBitrateBps)
        assertEquals("sender", VideoProfiles.PROFILE_ID)
    }

    @Test
    fun effectiveBitrateRangeIntersectsEncoderRangeOnPhone() {
        val mode = VideoProfiles.PROFILE_1080P30
        val capability = PhoneVideoCapabilities.resolve(
            modes = listOf(mode),
            cameraSupportedModeIds = setOf(mode.id),
            selectedEncoder = supportedEncoder(
                mode,
                minimumBitrateBps = 6_500_000,
                maximumBitrateBps = 12_500_000,
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
            selectedEncoder = supportedEncoder(mode),
        ).single()
        val encoderUnsupported = PhoneVideoCapabilities.resolve(
            modes = listOf(mode),
            cameraSupportedModeIds = setOf(mode.id),
            selectedEncoder = supportedEncoder(mode, sizeAndRateSupported = false),
        ).single()

        assertFalse(cameraUnsupported.isSupported)
        assertFalse(encoderUnsupported.isSupported)
        assertTrue(encoderUnsupported.reason?.contains("exact size") == true)
    }

    @Test
    fun surfaceAndCbrAreRequiredForACompleteMode() {
        val mode = VideoProfiles.PROFILE_1080P60
        val noSurface = PhoneVideoCapabilities.resolve(
            modes = listOf(mode),
            cameraSupportedModeIds = setOf(mode.id),
            selectedEncoder = supportedEncoder(mode, surfaceInputSupported = false),
        ).single()
        val noCbr = PhoneVideoCapabilities.resolve(
            modes = listOf(mode),
            cameraSupportedModeIds = setOf(mode.id),
            selectedEncoder = supportedEncoder(mode, cbrSupported = false),
        ).single()

        assertFalse(noSurface.isSupported)
        assertFalse(noCbr.isSupported)
    }

    @Test
    fun bitrateIntersectionFailureExplainsWhyTheModeIsUnavailable() {
        val mode = VideoProfiles.PROFILE_1080P60
        val capability = PhoneVideoCapabilities.resolve(
            modes = listOf(mode),
            cameraSupportedModeIds = setOf(mode.id),
            selectedEncoder = supportedEncoder(
                mode,
                minimumBitrateBps = mode.maximumBitrateBps + mode.bitrateStepBps,
                maximumBitrateBps = mode.maximumBitrateBps + mode.bitrateStepBps,
            ),
        ).single()

        assertFalse(capability.isSupported)
        assertEquals(
            "The selected encoder bitrate range has no valid product bitrate",
            capability.reason,
        )
    }

    private fun supportedEncoder(
        mode: VideoProfile,
        sizeAndRateSupported: Boolean = true,
        surfaceInputSupported: Boolean = true,
        cbrSupported: Boolean = true,
        minimumBitrateBps: Int = mode.minimumBitrateBps,
        maximumBitrateBps: Int = mode.maximumBitrateBps,
    ) = EncoderCapability(
        codec = VideoCodec.H264,
        implementationName = "test-h264",
        acceleration = EncoderAcceleration.HARDWARE,
        surfaceInputSupported = surfaceInputSupported,
        cbrSupported = cbrSupported,
        modes = listOf(
            EncoderModeCapability(
                modeId = mode.id,
                sizeAndRateSupported = sizeAndRateSupported,
                minimumBitrateBps = minimumBitrateBps,
                maximumBitrateBps = maximumBitrateBps,
            ),
        ),
    )
}
