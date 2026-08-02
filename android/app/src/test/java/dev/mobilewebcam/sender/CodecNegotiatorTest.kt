package dev.mobilewebcam.sender

import dev.mobilewebcam.sender.config.VideoProfiles
import dev.mobilewebcam.sender.model.CodecPreference
import dev.mobilewebcam.sender.model.DecoderAcceleration
import dev.mobilewebcam.sender.model.EncoderAcceleration
import dev.mobilewebcam.sender.model.EncoderCapability
import dev.mobilewebcam.sender.model.OutputPixelFormat
import dev.mobilewebcam.sender.model.ReceiverCapabilities
import dev.mobilewebcam.sender.model.ReceiverCodecCapability
import dev.mobilewebcam.sender.model.SenderCapabilities
import dev.mobilewebcam.sender.model.VideoCodec
import dev.mobilewebcam.sender.session.CodecNegotiator
import org.junit.Assert.assertEquals
import org.junit.Test

class CodecNegotiatorTest {
    private val profile = VideoProfiles.default
    private val receiverBoth = receiver(h264 = true, h265 = true)
    private val senderBoth = sender(h264 = true, h265 = true)

    @Test
    fun autoPrefersH265WhenBothEndpointsSupportIt() {
        assertEquals(
            VideoCodec.H265,
            CodecNegotiator().negotiate(
                CodecPreference.AUTO_PREFER_H265,
                senderBoth,
                receiverBoth,
                profile,
            ),
        )
    }

    @Test
    fun autoFallsBackToH264WhenReceiverOnlySupportsH264() {
        assertEquals(
            VideoCodec.H264,
            CodecNegotiator().negotiate(
                CodecPreference.AUTO_PREFER_H265,
                senderBoth,
                receiver(h264 = true, h265 = false),
                profile,
            ),
        )
    }

    @Test
    fun autoFallsBackToH264WhenSenderOnlySupportsH264() {
        assertEquals(
            VideoCodec.H264,
            CodecNegotiator().negotiate(
                CodecPreference.AUTO_PREFER_H265,
                sender(h264 = true, h265 = false),
                receiverBoth,
                profile,
            ),
        )
    }

    @Test(expected = IllegalStateException::class)
    fun forcedH265DoesNotSilentlyFallback() {
        CodecNegotiator().negotiate(
            CodecPreference.FORCE_H265,
            senderBoth,
            receiver(h264 = true, h265 = false),
            profile,
        )
    }

    private fun sender(h264: Boolean, h265: Boolean): SenderCapabilities =
        SenderCapabilities(
            listOf(
                capability(VideoCodec.H264, h264),
                capability(VideoCodec.H265, h265),
            ),
        )

    private fun capability(codec: VideoCodec, supported: Boolean) =
        EncoderCapability(
            codec = codec,
            profileId = profile.id,
            supported = supported,
            acceleration = EncoderAcceleration.HARDWARE,
            encoderName = "test-$codec",
        )

    private fun receiver(h264: Boolean, h265: Boolean) =
        ReceiverCapabilities(
            protocolVersion = 1,
            codecs = listOf(
                ReceiverCodecCapability(VideoCodec.H264, h264, DecoderAcceleration.UNKNOWN),
                ReceiverCodecCapability(VideoCodec.H265, h265, DecoderAcceleration.UNKNOWN),
            ),
            outputDevice = "/dev/video10",
            pixelFormats = listOf(OutputPixelFormat.YUY2),
            activeSession = false,
        )
}
