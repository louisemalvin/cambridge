package dev.mobilewebcam.sender

import dev.mobilewebcam.sender.connection.ReconnectPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {
    @Test
    fun exponentialDelayIsBoundedAndJittered() {
        val policy = ReconnectPolicy(
            initialDelayMillis = INITIAL_DELAY_MILLIS,
            maximumDelayMillis = MAXIMUM_DELAY_MILLIS,
            jitterFraction = JITTER_FRACTION,
        )

        assertEquals(INITIAL_DELAY_MILLIS, policy.delayMillis(FIRST_ATTEMPT, NEUTRAL_JITTER))
        assertTrue(policy.delayMillis(LARGE_ATTEMPT, MINIMUM_JITTER) >= INITIAL_DELAY_MILLIS)
        assertEquals(MAXIMUM_DELAY_MILLIS, policy.delayMillis(LARGE_ATTEMPT, MAXIMUM_JITTER))
    }

    private companion object {
        const val INITIAL_DELAY_MILLIS = 250L
        const val MAXIMUM_DELAY_MILLIS = 5_000L
        const val JITTER_FRACTION = 0.2
        const val FIRST_ATTEMPT = 0
        const val LARGE_ATTEMPT = 20
        const val MINIMUM_JITTER = 0.0
        const val NEUTRAL_JITTER = 0.5
        const val MAXIMUM_JITTER = 1.0
    }
}
