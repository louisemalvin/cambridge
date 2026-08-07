package dev.cambridge.sender.platform.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.cambridge.sender.platform.power.StreamingPowerManager

class AndroidForegroundStreamingController(
    context: Context,
    private val powerManager: StreamingPowerManager,
) : ForegroundStreamingController {
    private val applicationContext = context.applicationContext

    override fun start(): Result<Unit> = runCatching {
        val intent = Intent(applicationContext, ForegroundStreamingService::class.java)
            .setAction(ForegroundStreamingService.ACTION_START)
        ContextCompat.startForegroundService(applicationContext, intent)
        powerManager.acquire()
    }

    override fun stop() {
        applicationContext.stopService(Intent(applicationContext, ForegroundStreamingService::class.java))
        powerManager.release()
    }
}
