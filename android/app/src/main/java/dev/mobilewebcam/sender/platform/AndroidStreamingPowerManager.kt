package dev.mobilewebcam.sender.platform

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager

class AndroidStreamingPowerManager(context: Context) : StreamingPowerManager {
    private val applicationContext = context.applicationContext
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Synchronized
    override fun acquire() {
        if (wakeLock == null) {
            val powerManager = applicationContext.getSystemService(PowerManager::class.java)
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "MobileWebcam::Streaming",
            ).apply {
                setReferenceCounted(false)
            }
        }
        if (wakeLock?.isHeld != true) wakeLock?.acquire()
        acquireWifiLock()
    }

    @Synchronized
    override fun release() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
    }

    private fun acquireWifiLock() {
        if (wifiLock == null) {
            val wifiManager = applicationContext.getSystemService(WifiManager::class.java)
            wifiLock = wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "MobileWebcam::Streaming",
            ).apply {
                setReferenceCounted(false)
            }
        }
        runCatching {
            if (wifiLock?.isHeld != true) wifiLock?.acquire()
        }
    }
}
