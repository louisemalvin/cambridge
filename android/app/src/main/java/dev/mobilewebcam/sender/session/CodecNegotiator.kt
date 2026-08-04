package dev.mobilewebcam.sender.session

import dev.mobilewebcam.sender.model.CodecPreference
import dev.mobilewebcam.sender.model.ReceiverCapabilities
import dev.mobilewebcam.sender.model.SenderCapabilities
import dev.mobilewebcam.sender.model.StreamFailure
import dev.mobilewebcam.sender.model.StreamFailureException
import dev.mobilewebcam.sender.model.VideoCodec
import dev.mobilewebcam.sender.model.VideoProfile

class CodecNegotiator {
    fun negotiate(
        preference: CodecPreference,
        sender: SenderCapabilities,
        receiver: ReceiverCapabilities,
        profile: VideoProfile,
    ): VideoCodec {
        val candidates = when (preference) {
            CodecPreference.AUTO_PREFER_H265 -> listOf(VideoCodec.H265, VideoCodec.H264)
            CodecPreference.FORCE_H264 -> listOf(VideoCodec.H264)
            CodecPreference.FORCE_H265 -> listOf(VideoCodec.H265)
        }
        val selected = candidates.firstOrNull { codec ->
            sender.supports(
                codec,
                profile.id,
                allowKnownSoftware = preference != CodecPreference.AUTO_PREFER_H265 ||
                    codec == VideoCodec.H264,
            ) &&
                receiver.supports(codec)
        }
        return selected ?: throw StreamFailureException(
            when (preference) {
                CodecPreference.FORCE_H264 -> StreamFailure.ForcedCodecUnsupported(
                    VideoCodec.H264,
                    profile,
                )
                CodecPreference.FORCE_H265 -> StreamFailure.ForcedCodecUnsupported(
                    VideoCodec.H265,
                    profile,
                )
                CodecPreference.AUTO_PREFER_H265 -> StreamFailure.NoCompatibleCodec(profile)
            },
        )
    }
}
