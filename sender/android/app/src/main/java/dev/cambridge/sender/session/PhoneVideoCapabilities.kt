package dev.cambridge.sender.session

import dev.cambridge.sender.model.EncoderCapability
import dev.cambridge.sender.model.EncoderModeCapability
import dev.cambridge.sender.model.VideoProfile

/** The phone-side intersection of Camera2, MediaCodec, and the bounded product catalog. */
data class PhoneVideoModeCapability(
    val mode: VideoProfile,
    val cameraSupported: Boolean,
    val encoderImplementationName: String?,
    val encoderSizeAndRateSupported: Boolean,
    val encoderSurfaceInputSupported: Boolean,
    val encoderCbrSupported: Boolean,
    val encoderMinimumBitrateBps: Int?,
    val encoderMaximumBitrateBps: Int?,
    val reason: String? = null,
) {
    val bitrateRange: IntRange = if (
        encoderMinimumBitrateBps != null && encoderMaximumBitrateBps != null
    ) {
        mode.steppedBitrates(encoderMinimumBitrateBps, encoderMaximumBitrateBps)
    } else {
        IntRange.EMPTY
    }

    val isSupported: Boolean
        get() = cameraSupported &&
            encoderSizeAndRateSupported &&
            encoderSurfaceInputSupported &&
            encoderCbrSupported &&
            !bitrateRange.isEmpty()
}

object PhoneVideoCapabilities {
    fun resolve(
        modes: List<VideoProfile>,
        cameraSupportedModeIds: Set<String>,
        selectedEncoder: EncoderCapability?,
    ): List<PhoneVideoModeCapability> = modes.map { mode ->
        val encoderMode = selectedEncoder?.modeFor(mode.id)
        val cameraSupported = mode.id in cameraSupportedModeIds
        val sizeAndRateSupported = encoderMode?.sizeAndRateSupported == true
        val surfaceInputSupported = selectedEncoder?.surfaceInputSupported == true
        val cbrSupported = selectedEncoder?.cbrSupported == true
        val minimumBitrate = encoderMode?.minimumBitrateBps
        val maximumBitrate = encoderMode?.maximumBitrateBps
        val bitrateRange = if (minimumBitrate != null && maximumBitrate != null) {
            mode.steppedBitrates(minimumBitrate, maximumBitrate)
        } else {
            IntRange.EMPTY
        }
        PhoneVideoModeCapability(
            mode = mode,
            cameraSupported = cameraSupported,
            encoderImplementationName = selectedEncoder?.implementationName,
            encoderSizeAndRateSupported = sizeAndRateSupported,
            encoderSurfaceInputSupported = surfaceInputSupported,
            encoderCbrSupported = cbrSupported,
            encoderMinimumBitrateBps = minimumBitrate,
            encoderMaximumBitrateBps = maximumBitrate,
            reason = reason(
                cameraSupported = cameraSupported,
                encoderMode = encoderMode,
                surfaceInputSupported = surfaceInputSupported,
                cbrSupported = cbrSupported,
                bitrateRange = bitrateRange,
            ),
        )
    }

    private fun reason(
        cameraSupported: Boolean,
        encoderMode: EncoderModeCapability?,
        surfaceInputSupported: Boolean,
        cbrSupported: Boolean,
        bitrateRange: IntRange,
    ): String? = when {
        !cameraSupported -> "Camera2 does not provide this size and frame rate"
        encoderMode == null -> "The selected H.264 encoder does not report this mode"
        !encoderMode.sizeAndRateSupported -> encoderMode.reason
            ?: "The selected encoder does not support this exact size and frame rate"
        !surfaceInputSupported -> "The selected encoder does not accept surface input"
        !cbrSupported -> "The selected encoder does not support CBR"
        bitrateRange.isEmpty() -> "The selected encoder bitrate range has no valid product bitrate"
        else -> null
    }
}
