package dev.mobilewebcam.sender.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.mobilewebcam.sender.connection.discovery.SenderControlServer
import javax.inject.Inject

@HiltAndroidApp
class MobileWebcamApplication : Application() {
    @Inject
    lateinit var senderControlServer: SenderControlServer

    override fun onCreate() {
        super.onCreate()
        senderControlServer.start()
    }
}
