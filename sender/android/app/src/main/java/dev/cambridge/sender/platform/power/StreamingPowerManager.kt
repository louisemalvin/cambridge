package dev.cambridge.sender.platform.power

interface StreamingPowerManager {
    fun acquire()
    fun release()
}
