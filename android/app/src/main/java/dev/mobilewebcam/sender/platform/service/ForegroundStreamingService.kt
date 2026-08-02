package dev.mobilewebcam.sender.platform.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import dev.mobilewebcam.sender.media.streaming.session.StreamSessionController
import dev.mobilewebcam.sender.platform.notification.NotificationFactory
import dev.mobilewebcam.sender.platform.power.StreamingPowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class ForegroundStreamingService : Service() {
    @Inject
    lateinit var sessionController: StreamSessionController

    @Inject
    lateinit var powerManager: StreamingPowerManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            serviceScope.launch {
                sessionController.stop()
                stopSelfResult(startId)
            }
            return START_NOT_STICKY
        }
        val notification = NotificationFactory(this).createStreamingNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (::powerManager.isInitialized) {
            powerManager.release()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "dev.mobilewebcam.sender.action.START_STREAMING"
        const val ACTION_STOP = "dev.mobilewebcam.sender.action.STOP_STREAMING"
        private const val NOTIFICATION_ID = 1001
    }
}
