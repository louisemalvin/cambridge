package dev.mobilewebcam.sender

import dev.mobilewebcam.sender.model.StreamConfiguration
import dev.mobilewebcam.sender.model.VideoCodec
import dev.mobilewebcam.sender.model.VideoProfile
import dev.mobilewebcam.sender.streaming.rootencoder.toRootEncoderVideo
import org.junit.Assert.assertEquals
import org.junit.Test

class RootEncoderConfigurationMapperTest {
    @Test
    fun encodedDimensionsRemainTheNegotiatedProfileDimensions() {
        val configuration = StreamConfiguration(
            codec = VideoCodec.H264,
            profile = profile,
            bitrateBps = profile.h264BitrateBps,
            keyframeIntervalSeconds = profile.keyframeIntervalSeconds,
        )

        val video = configuration.toRootEncoderVideo()

        assertEquals(profile.width, video.width)
        assertEquals(profile.height, video.height)
        assertEquals(0, video.rotationDegrees)
    }

    private companion object {
        val profile = VideoProfile(
            id = "test-profile",
            width = 2560,
            height = 1440,
            fps = 30,
            h264BitrateBps = 18_000_000,
            h265BitrateBps = 12_000_000,
            keyframeIntervalSeconds = 1,
        )
    }
}
