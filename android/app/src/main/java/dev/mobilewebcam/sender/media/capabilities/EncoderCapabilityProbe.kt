package dev.mobilewebcam.sender.media.capabilities

import dev.mobilewebcam.sender.model.EncoderCapability
import dev.mobilewebcam.sender.model.VideoProfile

interface EncoderCapabilityProbe {
    suspend fun getCapabilities(profiles: List<VideoProfile>): List<EncoderCapability>
}
