package dev.cambridge.sender

import dev.cambridge.sender.model.EncoderAcceleration
import dev.cambridge.sender.model.EncoderCapability
import dev.cambridge.sender.model.EncoderModeCapability
import dev.cambridge.sender.model.StreamOrientation
import dev.cambridge.sender.model.StreamVideoConfiguration
import dev.cambridge.sender.model.VideoCodec
import dev.cambridge.sender.model.VideoProfile
import dev.cambridge.sender.session.EncoderCatalog
import dev.cambridge.sender.session.VideoConfigurationResolver
import dev.cambridge.sender.session.VideoProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EncoderCatalogTest {
    @Test
    fun defaultUsesFirstEligibleHardwareInReportedOrder() {
        val encoders = listOf(
            encoder("software", EncoderAcceleration.SOFTWARE),
            encoder("hardware-one", EncoderAcceleration.HARDWARE),
            encoder("hardware-two", EncoderAcceleration.HARDWARE),
        )

        val eligible = EncoderCatalog.eligible(encoders, listOf(VideoProfiles.default))

        assertEquals("hardware-one", EncoderCatalog.default(eligible)?.implementationName)
    }

    @Test
    fun softwareIsUsedWhenNoEligibleHardwareExists() {
        val encoders = listOf(
            encoder("hardware-incomplete", EncoderAcceleration.HARDWARE, cbrSupported = false),
            encoder("software", EncoderAcceleration.SOFTWARE),
        )

        val eligible = EncoderCatalog.eligible(encoders, listOf(VideoProfiles.default))

        assertEquals(listOf("software"), eligible.map { it.implementationName })
        assertEquals("software", EncoderCatalog.default(eligible)?.implementationName)
    }

    @Test
    fun encoderWithNoCompleteModeIsOmitted() {
        val encoders = listOf(
            encoder(
                name = "no-surface",
                acceleration = EncoderAcceleration.HARDWARE,
                surfaceInputSupported = false,
            ),
            encoder(name = "complete", acceleration = EncoderAcceleration.HARDWARE),
        )

        val eligible = EncoderCatalog.eligible(encoders, listOf(VideoProfiles.default))

        assertEquals(listOf("complete"), eligible.map { it.implementationName })
    }

    @Test
    fun disappearingSavedEncoderFallsBackAndNormalizesDependentSettings() {
        val current = configuration(
            encoderName = "removed",
            profile = VideoProfiles.default,
            bitrateBps = 5_000_000,
        )
        val replacement = encoder(
            name = "replacement",
            acceleration = EncoderAcceleration.HARDWARE,
            minimumBitrateBps = 6_500_000,
            maximumBitrateBps = 12_500_000,
        )

        val resolved = VideoConfigurationResolver.resolve(
            current = current,
            modes = listOf(VideoProfiles.default),
            cameraSupportedModeIds = setOf(VideoProfiles.default.id),
            encoders = listOf(replacement),
        ) ?: error("expected a replacement encoder")

        assertEquals("replacement", resolved.configuration.encoderName)
        assertEquals(7_000_000, resolved.configuration.bitrateBps)
    }

    @Test
    fun changingEncoderRecomputesModeChoicesFromTheNewEncoder() {
        val first = encoder(
            name = "first",
            acceleration = EncoderAcceleration.HARDWARE,
            modes = listOf(VideoProfiles.PROFILE_1080P30),
        )
        val second = encoder(
            name = "second",
            acceleration = EncoderAcceleration.SOFTWARE,
            modes = listOf(VideoProfiles.PROFILE_2K30),
        )

        val resolved = VideoConfigurationResolver.resolve(
            current = configuration("second", VideoProfiles.PROFILE_1080P30, 5_000_000),
            modes = listOf(VideoProfiles.PROFILE_1080P30, VideoProfiles.PROFILE_2K30),
            cameraSupportedModeIds = setOf(
                VideoProfiles.PROFILE_1080P30.id,
                VideoProfiles.PROFILE_2K30.id,
            ),
            encoders = listOf(first, second),
        ) ?: error("expected an encoder configuration")

        assertEquals(VideoProfiles.PROFILE_2K30.id, resolved.configuration.profile.id)
        assertTrue(resolved.capabilities.single { it.mode.id == VideoProfiles.PROFILE_2K30.id }.isSupported)
        assertFalse(resolved.capabilities.single { it.mode.id == VideoProfiles.PROFILE_1080P30.id }.isSupported)
    }

    private fun configuration(
        encoderName: String,
        profile: VideoProfile,
        bitrateBps: Int,
    ) = StreamVideoConfiguration(
        encoderName = encoderName,
        profile = profile,
        bitrateBps = bitrateBps,
        streamOrientation = StreamOrientation.LANDSCAPE,
    )

    private fun encoder(
        name: String,
        acceleration: EncoderAcceleration,
        surfaceInputSupported: Boolean = true,
        cbrSupported: Boolean = true,
        minimumBitrateBps: Int = VideoProfiles.default.minimumBitrateBps,
        maximumBitrateBps: Int = VideoProfiles.default.maximumBitrateBps,
        modes: List<VideoProfile> = listOf(VideoProfiles.default),
    ) = EncoderCapability(
        codec = VideoCodec.H264,
        implementationName = name,
        acceleration = acceleration,
        surfaceInputSupported = surfaceInputSupported,
        cbrSupported = cbrSupported,
        modes = modes.map { mode ->
            EncoderModeCapability(
                modeId = mode.id,
                sizeAndRateSupported = true,
                minimumBitrateBps = minimumBitrateBps,
                maximumBitrateBps = maximumBitrateBps,
            )
        },
    )
}
