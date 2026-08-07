package dev.cambridge.sender.platform.service

interface ForegroundStreamingController {
    fun start(): Result<Unit>
    fun stop()
}
