package dev.cambridge.sender.feature.setup

import dev.cambridge.sender.app.model.UiText
import dev.cambridge.sender.model.VideoProfile
import dev.cambridge.sender.session.PhoneVideoModeCapability
import dev.cambridge.sender.session.VideoProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamSetupOptionResolverTest {
    @Test
    fun supportedResolutionDoesNotInheritTheUnsupportedSiblingFrameRateReason() {
        val selectedProfile = VideoProfiles.PROFILE_1080P30
        val frameRateReason = "The phone H.264 encoder does not provide 60 fps"
        val options = StreamSetupOptionResolver.resolutionOptions(
            selectedProfile = selectedProfile,
            capabilities = listOf(
                supportedCapability(VideoProfiles.PROFILE_1080P30),
                unsupportedCapability(VideoProfiles.PROFILE_1080P60, frameRateReason),
            ),
        )

        val resolution = options.first { option -> option.isSelected }
        assertTrue(resolution.isEnabled)
        assertNull(resolution.disabledReason)
    }

    @Test
    fun frameRateOptionRetainsTheSpecificReasonForAUnsupportedSixtyFpsMode() {
        val frameRateReason = "The phone H.264 encoder does not provide 60 fps"
        val options = StreamSetupOptionResolver.frameRateOptions(
            selectedProfile = VideoProfiles.PROFILE_1080P30,
            capabilities = listOf(
                supportedCapability(VideoProfiles.PROFILE_1080P30),
                unsupportedCapability(VideoProfiles.PROFILE_1080P60, frameRateReason),
            ),
        )

        val sixtyFps = options.first { option -> option.key == "60" }
        assertFalse(sixtyFps.isEnabled)
        assertEquals(UiText.Plain(frameRateReason), sixtyFps.disabledReason)
    }

    @Test
    fun resolutionReasonIsShownWhenEveryFrameRateForThatResolutionIsUnsupported() {
        val reason = "The camera does not provide this size and frame rate"
        val options = StreamSetupOptionResolver.resolutionOptions(
            selectedProfile = VideoProfiles.PROFILE_1080P30,
            capabilities = listOf(
                supportedCapability(VideoProfiles.PROFILE_1080P30),
                unsupportedCapability(VideoProfiles.PROFILE_2K30, reason),
                unsupportedCapability(VideoProfiles.PROFILE_2K60, reason),
            ),
        )

        val twoK = options.first { option -> option.key == VideoProfiles.PROFILE_2K30.id }
        assertFalse(twoK.isEnabled)
        assertEquals(UiText.Plain(reason), twoK.disabledReason)
    }

    private fun supportedCapability(profile: VideoProfile) = PhoneVideoModeCapability(
        mode = profile,
        cameraSupported = true,
        encoderSupported = true,
        encoderMinimumBitrateBps = profile.minimumBitrateBps,
        encoderMaximumBitrateBps = profile.maximumBitrateBps,
    )

    private fun unsupportedCapability(profile: VideoProfile, reason: String) = PhoneVideoModeCapability(
        mode = profile,
        cameraSupported = true,
        encoderSupported = false,
        encoderMinimumBitrateBps = profile.minimumBitrateBps,
        encoderMaximumBitrateBps = profile.maximumBitrateBps,
        reason = reason,
    )
}
