package dev.cambridge.sender

import dev.cambridge.sender.model.StreamConfiguration
import dev.cambridge.sender.model.VideoCodec
import dev.cambridge.sender.session.StreamConfigurationValidator
import dev.cambridge.sender.session.VideoProfiles
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamConfigurationValidatorTest {
    @Test
    fun acceptsPhoneMinimumDefaultMaximumAndIntermediateBitrates() {
        val profile = VideoProfiles.PROFILE_2K30

        listOf(
            profile.minimumBitrateBps,
            profile.defaultBitrateBps,
            profile.maximumBitrateBps,
            11_000_000,
        ).forEach { bitrateBps ->
            assertTrue(StreamConfigurationValidator.validate(configuration(profile, bitrateBps)).isSuccess)
        }
    }

    @Test
    fun rejectsBitrateThatIsNotAPhoneCatalogStep() {
        val profile = VideoProfiles.PROFILE_2K30

        assertTrue(
            StreamConfigurationValidator.validate(configuration(profile, 18_500_000)).isFailure,
        )
    }

    private fun configuration(
        profile: dev.cambridge.sender.model.VideoProfile,
        bitrateBps: Int,
    ) = StreamConfiguration(
        codec = VideoCodec.H264,
        encoderName = "test-h264",
        profile = profile,
        bitrateBps = bitrateBps,
        keyframeIntervalSeconds = profile.keyframeIntervalSeconds,
    )
}
