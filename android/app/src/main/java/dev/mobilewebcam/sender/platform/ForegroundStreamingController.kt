package dev.mobilewebcam.sender.platform

interface ForegroundStreamingController {
    fun start(): Result<Unit>
    fun stop()
}
