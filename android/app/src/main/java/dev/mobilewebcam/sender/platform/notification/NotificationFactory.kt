package dev.mobilewebcam.sender.platform.notification

import dev.mobilewebcam.sender.platform.service.ForegroundStreamingService

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

class NotificationFactory(private val context: Context) {
    fun createStreamingNotification(): Notification {
        createChannel()
        val stopIntent = Intent(context, ForegroundStreamingService::class.java)
            .setAction(ForegroundStreamingService.ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            context,
            STOP_ACTION_REQUEST_CODE,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Mobile Webcam")
            .setContentText("Streaming camera video")
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "Stop", stopPendingIntent).build())
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Camera streaming",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private companion object {
        const val CHANNEL_ID = "mobile-webcam-streaming"
        const val STOP_ACTION_REQUEST_CODE = 1
    }
}
