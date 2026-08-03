package dev.mobilewebcam.sender.session

import dev.mobilewebcam.sender.model.StreamConfiguration

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
        if (configuration.bitrateBps <= ZERO_VALUE ||
            configuration.keyframeIntervalSeconds <= ZERO_VALUE
        ) {
            return Result.failure(IllegalArgumentException("Bitrate and keyframe interval must be positive"))
        }
        return Result.success(Unit)
    }

    private const val ZERO_VALUE = 0
    private const val EVEN_DIMENSION_DIVISOR = 2
}
