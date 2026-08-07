package dev.cambridge.sender.platform.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.cambridge.sender.session.StreamSessionController
import dev.cambridge.sender.platform.notification.NotificationFactory
import dev.cambridge.sender.platform.power.StreamingPowerManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ForegroundStreamingService : Service() {
    @Inject
    lateinit var sessionController: StreamSessionController

    @Inject
    lateinit var powerManager: StreamingPowerManager

    @Inject
    lateinit var notificationFactory: NotificationFactory

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSessionAndService(startId)
            return START_NOT_STICKY
        }
        val notification = notificationFactory.createStreamingNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSessionAndService()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (::powerManager.isInitialized) {
            powerManager.release()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun stopSessionAndService(startId: Int? = null) {
        serviceScope.launch {
            try {
                sessionController.stop()
            } finally {
                if (startId == null) {
                    stopSelf()
                } else {
                    stopSelfResult(startId)
                }
            }
        }
    }

    companion object {
        const val ACTION_START = "dev.cambridge.sender.action.START_STREAMING"
        const val ACTION_STOP = "dev.cambridge.sender.action.STOP_STREAMING"
        private const val NOTIFICATION_ID = 1001
    }
}
