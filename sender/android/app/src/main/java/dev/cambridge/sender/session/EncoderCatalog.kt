package dev.cambridge.sender.session

import dev.cambridge.sender.model.EncoderAcceleration
import dev.cambridge.sender.model.EncoderCapability
import dev.cambridge.sender.model.VideoCodec
import dev.cambridge.sender.model.VideoProfile

object EncoderCatalog {
    fun eligible(
        encoders: List<EncoderCapability>,
        modes: List<VideoProfile>,
    ): List<EncoderCapability> = encoders.filter { encoder ->
        encoder.codec == VideoCodec.H264 &&
            encoder.acceleration != EncoderAcceleration.UNKNOWN &&
            encoder.surfaceInputSupported &&
            modes.any { mode -> hasCompleteMode(encoder, mode) }
    }

    fun default(encoders: List<EncoderCapability>): EncoderCapability? =
        encoders.firstOrNull { it.acceleration == EncoderAcceleration.HARDWARE }
            ?: encoders.firstOrNull { it.acceleration == EncoderAcceleration.SOFTWARE }

    fun hasCompleteMode(
        encoder: EncoderCapability,
        mode: VideoProfile,
    ): Boolean {
        if (!encoder.surfaceInputSupported) return false
        val modeCapability = encoder.modeFor(mode.id) ?: return false
        val minimumBitrate = modeCapability.minimumBitrateBps ?: return false
        val maximumBitrate = modeCapability.maximumBitrateBps ?: return false
        return modeCapability.sizeAndRateSupported &&
            !mode.steppedBitrates(minimumBitrate, maximumBitrate).isEmpty()
    }
}
