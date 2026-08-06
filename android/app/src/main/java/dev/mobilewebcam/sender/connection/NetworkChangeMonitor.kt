package dev.mobilewebcam.sender.connection

import kotlinx.coroutines.flow.Flow

interface NetworkChangeMonitor {
    val changes: Flow<Unit>

    fun start()

    fun stop()
}
