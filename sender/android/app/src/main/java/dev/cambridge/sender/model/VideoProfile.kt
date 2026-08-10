package dev.cambridge.sender.model

data class VideoProfile(
    val id: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val minimumBitrateBps: Int,
    val defaultBitrateBps: Int,
    val maximumBitrateBps: Int,
    val bitrateStepBps: Int,
    val keyframeIntervalSeconds: Int,
) {
    init {
        require(id.isNotBlank()) { "Video profile ID must not be blank" }
        require(width > ZERO_VALUE && height > ZERO_VALUE && fps > ZERO_VALUE) {
            "Video dimensions and FPS must be positive"
        }
        require(minimumBitrateBps > ZERO_VALUE) { "Minimum bitrate must be positive" }
        require(defaultBitrateBps in minimumBitrateBps..maximumBitrateBps) {
            "Default bitrate must be within the mode bitrate range"
        }
        require(maximumBitrateBps >= minimumBitrateBps) {
            "Maximum bitrate must not be below the minimum"
        }
        require(bitrateStepBps > ZERO_VALUE) { "Bitrate step must be positive" }
        require(keyframeIntervalSeconds > ZERO_VALUE) { "Keyframe interval must be positive" }
    }

    fun steppedBitrates(encoderMinimumBps: Int, encoderMaximumBps: Int): IntRange {
        val minimum = maxOf(minimumBitrateBps, encoderMinimumBps)
        val maximum = minOf(maximumBitrateBps, encoderMaximumBps)
        if (minimum > maximum) return IntRange.EMPTY
        val first = ceilToStep(minimum)
        val last = floorToStep(maximum)
        return if (first > last) IntRange.EMPTY else first..last
    }

    fun clampToStep(valueBps: Int, encoderMinimumBps: Int, encoderMaximumBps: Int): Int? {
        val range = steppedBitrates(encoderMinimumBps, encoderMaximumBps)
        if (range.isEmpty()) return null
        val bounded = valueBps.coerceIn(range.first, range.last)
        val stepped = range.first + ((bounded - range.first) / bitrateStepBps) * bitrateStepBps
        return stepped.coerceIn(range.first, range.last)
    }

    private fun ceilToStep(value: Int): Int {
        val remainder = value % bitrateStepBps
        return if (remainder == ZERO_VALUE) value else value + bitrateStepBps - remainder
    }

    private fun floorToStep(value: Int): Int = value - (value % bitrateStepBps)

    private companion object {
        const val ZERO_VALUE = 0
    }
}
