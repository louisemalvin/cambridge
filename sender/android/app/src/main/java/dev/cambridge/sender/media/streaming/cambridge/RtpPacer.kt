package dev.cambridge.sender.media.streaming.cambridge

import java.util.concurrent.locks.LockSupport

internal class RtpPacer(
    private val bitrateBps: Long,
    private val nanoTime: () -> Long = System::nanoTime,
    private val waitNanos: (Long) -> Unit = { durationNanos -> LockSupport.parkNanos(durationNanos) },
) {
    init {
        require(bitrateBps > 0) { "RTP pacing bitrate must be positive" }
    }

    private var nextSendAtNs: Long? = null

    fun await(packetBytes: Int) {
        require(packetBytes > 0) { "RTP pacing packet size must be positive" }
        val now = nanoTime()
        val scheduledSendAt = nextSendAtNs ?: now
        if (scheduledSendAt > now) {
            waitNanos(scheduledSendAt - now)
        }
        val sendStartedAt = nanoTime()
        val pacingInterval = packetDurationNs(packetBytes)
        nextSendAtNs = addWithoutOverflow(maxOf(scheduledSendAt, sendStartedAt), pacingInterval)
    }

    private fun packetDurationNs(packetBytes: Int): Long =
        (packetBytes.toLong() * BITS_PER_BYTE * NANOSECONDS_PER_SECOND / bitrateBps)
            .coerceAtLeast(MINIMUM_PACING_INTERVAL_NS)

    private fun addWithoutOverflow(base: Long, increment: Long): Long =
        if (Long.MAX_VALUE - base < increment) Long.MAX_VALUE else base + increment

    private companion object {
        const val BITS_PER_BYTE = 8L
        const val NANOSECONDS_PER_SECOND = 1_000_000_000L
        const val MINIMUM_PACING_INTERVAL_NS = 1L
    }
}
