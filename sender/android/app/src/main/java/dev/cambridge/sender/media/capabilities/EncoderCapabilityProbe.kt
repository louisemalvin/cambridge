package dev.cambridge.sender.media.capabilities

import dev.cambridge.sender.model.EncoderCapability
import dev.cambridge.sender.model.VideoProfile

interface EncoderCapabilityProbe {
    suspend fun getCapabilities(profiles: List<VideoProfile>): List<EncoderCapability>
}
