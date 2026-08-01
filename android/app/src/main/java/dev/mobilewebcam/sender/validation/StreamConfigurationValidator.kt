package dev.mobilewebcam.sender.validation

import dev.mobilewebcam.sender.model.StreamConfiguration

object StreamConfigurationValidator {
    fun validate(configuration: StreamConfiguration): Result<Unit> {
        val profile = configuration.profile
        if (profile.width <= 0 || profile.height <= 0 || profile.fps <= 0) {
            return Result.failure(IllegalArgumentException("Video dimensions and FPS must be positive"))
        }
        if (profile.width % 2 != 0 || profile.height % 2 != 0) {
            return Result.failure(IllegalArgumentException("Video dimensions must be even"))
        }
        if (configuration.bitrateBps <= 0 || configuration.keyframeIntervalSeconds <= 0) {
            return Result.failure(IllegalArgumentException("Bitrate and keyframe interval must be positive"))
        }
        return Result.success(Unit)
    }
}
