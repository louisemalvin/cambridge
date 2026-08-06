package dev.mobilewebcam.sender.connection

import kotlin.math.roundToLong

data class ReconnectPolicy(
    val initialDelayMillis: Long = DEFAULT_INITIAL_DELAY_MILLIS,
    val maximumDelayMillis: Long = DEFAULT_MAXIMUM_DELAY_MILLIS,
    val jitterFraction: Double = DEFAULT_JITTER_FRACTION,
) {
    init {
        require(initialDelayMillis > ZERO_MILLIS)
        require(maximumDelayMillis >= initialDelayMillis)
        require(jitterFraction in ZERO_FRACTION..MAXIMUM_FRACTION)
    }

    fun delayMillis(attempt: Int, jitterSample: Double): Long {
        require(attempt >= ZERO_ATTEMPT)
        require(jitterSample in ZERO_FRACTION..MAXIMUM_FRACTION)
        val shift = attempt.coerceAtMost(MAXIMUM_BACKOFF_SHIFT)
        val baseDelay = (initialDelayMillis shl shift).coerceAtMost(maximumDelayMillis)
        val jitterSpan = (baseDelay * jitterFraction).roundToLong()
        val jitterOffset = ((jitterSample * FULL_JITTER_RANGE) - ONE_FRACTION) * jitterSpan
        return (baseDelay + jitterOffset.roundToLong()).coerceIn(initialDelayMillis, maximumDelayMillis)
    }

    private companion object {
        const val DEFAULT_INITIAL_DELAY_MILLIS = 250L
        const val DEFAULT_MAXIMUM_DELAY_MILLIS = 5_000L
        const val DEFAULT_JITTER_FRACTION = 0.2
        const val MAXIMUM_BACKOFF_SHIFT = 5
        const val ZERO_MILLIS = 0L
        const val ZERO_ATTEMPT = 0
        const val ZERO_FRACTION = 0.0
        const val ONE_FRACTION = 1.0
        const val TWO_FRACTION = 2.0
        const val MAXIMUM_FRACTION = 1.0
        const val FULL_JITTER_RANGE = TWO_FRACTION
    }
}
