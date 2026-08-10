package dev.cambridge.sender.session

import dev.cambridge.sender.model.EncoderCapability
import dev.cambridge.sender.model.VideoProfile
import dev.cambridge.sender.model.VideoCodec

/** The phone-side intersection of Camera2, MediaCodec, and the bounded product catalog. */
data class PhoneVideoModeCapability(
    val mode: VideoProfile,
    val cameraSupported: Boolean,
    val encoderSupported: Boolean,
    val encoderMinimumBitrateBps: Int,
    val encoderMaximumBitrateBps: Int,
    val reason: String? = null,
) {
    val bitrateRange = mode.steppedBitrates(encoderMinimumBitrateBps, encoderMaximumBitrateBps)
    val isSupported: Boolean
        get() = cameraSupported && encoderSupported && !bitrateRange.isEmpty()
}

object PhoneVideoCapabilities {
    fun resolve(
        modes: List<VideoProfile>,
        cameraSupportedModeIds: Set<String>,
        encoderCapabilities: List<EncoderCapability>,
    ): List<PhoneVideoModeCapability> = modes.map { mode ->
        val encoder = encoderCapabilities.firstOrNull {
            it.codec == VideoCodec.H264 && it.profileId == mode.id
        }
        val encoderMinimum = encoder?.minimumBitrateBps ?: mode.minimumBitrateBps
        val encoderMaximum = encoder?.maximumBitrateBps ?: mode.maximumBitrateBps
        PhoneVideoModeCapability(
            mode = mode,
            cameraSupported = mode.id in cameraSupportedModeIds,
            encoderSupported = encoder?.supported == true,
            encoderMinimumBitrateBps = encoderMinimum,
            encoderMaximumBitrateBps = encoderMaximum,
            reason = when {
                mode.id !in cameraSupportedModeIds -> "The camera does not provide this size and frame rate"
                encoder?.supported != true -> encoder?.reason ?: "The phone H.264 encoder does not provide this mode"
                else -> null
            },
        )
    }
}
