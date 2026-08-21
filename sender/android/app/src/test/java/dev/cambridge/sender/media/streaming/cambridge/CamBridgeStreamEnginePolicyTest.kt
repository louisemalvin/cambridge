package dev.cambridge.sender.media.streaming.cambridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CamBridgeStreamEnginePolicyTest {
    @Test
    fun fullTransportQueueDropsTheCurrentAccessUnitAndRequestsRecovery() {
        assertEquals(
            TransportQueueAction.DROP_AND_REQUEST_KEYFRAME,
            transportQueueAction(
                waitingForRecoveryKeyframe = false,
                keyFrame = false,
                offerSucceeded = false,
            ),
        )
        assertEquals(
            TransportQueueAction.DROP_AND_REQUEST_KEYFRAME,
            transportQueueAction(
                waitingForRecoveryKeyframe = false,
                keyFrame = true,
                offerSucceeded = false,
            ),
        )
    }

    @Test
    fun nonKeyFramesAreDroppedWhileWaitingForRecovery() {
        assertEquals(
            TransportQueueAction.DROP_WAITING_FOR_KEYFRAME,
            transportQueueAction(
                waitingForRecoveryKeyframe = true,
                keyFrame = false,
                offerSucceeded = false,
            ),
        )
    }

    @Test
    fun firstRecoveryKeyFrameIsAccepted() {
        assertEquals(
            TransportQueueAction.ENQUEUE_RECOVERY_KEYFRAME,
            transportQueueAction(
                waitingForRecoveryKeyframe = true,
                keyFrame = true,
                offerSucceeded = false,
            ),
        )
        assertEquals(
            TransportQueueAction.ENQUEUE,
            transportQueueAction(
                waitingForRecoveryKeyframe = false,
                keyFrame = false,
                offerSucceeded = true,
            ),
        )
    }

    @Test
    fun adaptiveBitrateHonoursTimeAndChangeThresholds() {
        val updateIntervalNs = 250_000_000L
        val selectedBitrateBps = 16_000_000

        assertFalse(
            shouldApplyAdaptiveBitrate(
                previousBitrateBps = selectedBitrateBps,
                requestedBitrateBps = 8_000_000,
                elapsedNs = updateIntervalNs - 1,
            ),
        )
        assertFalse(
            shouldApplyAdaptiveBitrate(
                previousBitrateBps = selectedBitrateBps,
                requestedBitrateBps = 15_900_000,
                elapsedNs = updateIntervalNs,
            ),
        )
        assertTrue(
            shouldApplyAdaptiveBitrate(
                previousBitrateBps = selectedBitrateBps,
                requestedBitrateBps = 15_700_000,
                elapsedNs = updateIntervalNs,
            ),
        )
        assertTrue(
            shouldApplyAdaptiveBitrate(
                previousBitrateBps = selectedBitrateBps,
                requestedBitrateBps = 15_200_000,
                elapsedNs = updateIntervalNs,
            ),
        )
    }
}
