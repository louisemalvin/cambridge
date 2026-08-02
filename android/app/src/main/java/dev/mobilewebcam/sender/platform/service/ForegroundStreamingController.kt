package dev.mobilewebcam.sender.platform.service

interface ForegroundStreamingController {
    fun start(): Result<Unit>
    fun stop()
}
