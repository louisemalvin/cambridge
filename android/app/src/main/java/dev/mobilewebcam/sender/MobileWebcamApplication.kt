package dev.mobilewebcam.sender

import android.app.Application
import dev.mobilewebcam.sender.connection.discovery.SenderControlServer
import dagger.hilt.android.HiltAndroidApp
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
