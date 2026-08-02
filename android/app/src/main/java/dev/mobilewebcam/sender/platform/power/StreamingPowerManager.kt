package dev.mobilewebcam.sender.platform.power

interface StreamingPowerManager {
    fun acquire()
    fun release()
}
