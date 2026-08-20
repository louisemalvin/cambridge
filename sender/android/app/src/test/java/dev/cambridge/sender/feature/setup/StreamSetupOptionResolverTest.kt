package dev.cambridge.sender.feature.setup

import dev.cambridge.sender.model.VideoProfile
import dev.cambridge.sender.session.PhoneVideoModeCapability
import dev.cambridge.sender.session.VideoProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamSetupOptionResolverTest {
    @Test
    fun unsupportedSixtyFpsEntryIsHiddenWhileThirtyFpsRemainsVisible() {
        val options = StreamSetupOptionResolver.frameRateOptions(
            selectedProfile = VideoProfiles.PROFILE_1080P30,
            capabilities = listOf(
                supportedCapability(VideoProfiles.PROFILE_1080P30),
                unsupportedCapability(VideoProfiles.PROFILE_1080P60),
            ),
        )

        assertEquals(listOf("30"), options.map { it.key })
        assertTrue(options.single().isEnabled)
    }

    @Test
    fun resolutionWithNoCompleteModeIsAbsentFromTheSelector() {
        val options = StreamSetupOptionResolver.resolutionOptions(
            selectedProfile = VideoProfiles.PROFILE_1080P30,
            capabilities = listOf(
                supportedCapability(VideoProfiles.PROFILE_1080P30),
                unsupportedCapability(VideoProfiles.PROFILE_2K30),
                unsupportedCapability(VideoProfiles.PROFILE_2K60),
            ),
        )

        assertEquals(listOf(VideoProfiles.PROFILE_1080P30.id), options.map { it.key })
        assertFalse(options.any { it.key == VideoProfiles.PROFILE_2K30.id })
    }

    private fun supportedCapability(profile: VideoProfile) = PhoneVideoModeCapability(
        mode = profile,
        cameraSupported = true,
        encoderImplementationName = "test-h264",
        encoderSizeAndRateSupported = true,
        encoderSurfaceInputSupported = true,
        encoderCbrSupported = true,
        encoderMinimumBitrateBps = profile.minimumBitrateBps,
        encoderMaximumBitrateBps = profile.maximumBitrateBps,
    )

    private fun unsupportedCapability(profile: VideoProfile) = PhoneVideoModeCapability(
        mode = profile,
        cameraSupported = true,
        encoderImplementationName = "test-h264",
        encoderSizeAndRateSupported = false,
        encoderSurfaceInputSupported = true,
        encoderCbrSupported = true,
        encoderMinimumBitrateBps = profile.minimumBitrateBps,
        encoderMaximumBitrateBps = profile.maximumBitrateBps,
    )
}
