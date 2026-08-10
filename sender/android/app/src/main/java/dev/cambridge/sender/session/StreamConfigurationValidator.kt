package dev.cambridge.sender.session

import dev.cambridge.sender.connection.control.cambridge.CamBridgeStreamContract
import dev.cambridge.sender.model.StreamConfiguration

object StreamConfigurationValidator {
    fun validate(configuration: StreamConfiguration): Result<Unit> {
        val profile = configuration.profile
        if (profile.width <= ZERO_VALUE || profile.height <= ZERO_VALUE || profile.fps <= ZERO_VALUE) {
            return Result.failure(IllegalArgumentException("Video dimensions and FPS must be positive"))
        }
        if (profile.width % EVEN_DIMENSION_DIVISOR != ZERO_VALUE ||
            profile.height % EVEN_DIMENSION_DIVISOR != ZERO_VALUE
        ) {
            return Result.failure(IllegalArgumentException("Video dimensions must be even"))
        }
        val longEdge = maxOf(profile.width, profile.height)
        val shortEdge = minOf(profile.width, profile.height)
        if (longEdge > CamBridgeStreamContract.MAXIMUM_LONG_EDGE ||
            shortEdge > CamBridgeStreamContract.MAXIMUM_SHORT_EDGE
        ) {
            return Result.failure(IllegalArgumentException("Video dimensions exceed the CamBridge stream bounds"))
        }
        configuration.sessionTransform?.let { transform ->
            if (transform.codedWidth != profile.width || transform.codedHeight != profile.height) {
                return Result.failure(IllegalArgumentException("Session geometry does not match the selected profile"))
            }
        }
        if (configuration.bitrateBps !in CamBridgeStreamContract.MINIMUM_BITRATE_BPS..CamBridgeStreamContract.MAXIMUM_BITRATE_BPS ||
            profile.clampToStep(
                valueBps = configuration.bitrateBps,
                encoderMinimumBps = profile.minimumBitrateBps,
                encoderMaximumBps = profile.maximumBitrateBps,
            ) != configuration.bitrateBps ||
            configuration.keyframeIntervalSeconds <= ZERO_VALUE
        ) {
            return Result.failure(IllegalArgumentException("Bitrate and keyframe interval must be positive"))
        }
        return Result.success(Unit)
    }

    private const val ZERO_VALUE = 0
    private const val EVEN_DIMENSION_DIVISOR = 2
}
