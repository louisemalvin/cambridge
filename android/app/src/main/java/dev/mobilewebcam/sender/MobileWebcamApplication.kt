package dev.mobilewebcam.sender

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.mobilewebcam.sender.connection.discovery.PairingStore
import dev.mobilewebcam.sender.connection.discovery.SenderConnectionCoordinator
import dev.mobilewebcam.sender.connection.discovery.SenderControlServer
import dev.mobilewebcam.sender.media.camera.CameraController
import dev.mobilewebcam.sender.media.streaming.session.StreamSessionController
import dev.mobilewebcam.sender.platform.power.StreamingPowerManager
import javax.inject.Inject

@HiltAndroidApp
class MobileWebcamApplication : Application() {
    @Inject
    lateinit var sessionController: StreamSessionController

    @Inject
    lateinit var connectionCoordinator: SenderConnectionCoordinator

    @Inject
    lateinit var pairings: PairingStore

    @Inject
    lateinit var cameraController: CameraController

    @Inject
    lateinit var senderControlServer: SenderControlServer

    @Inject
    lateinit var powerManager: StreamingPowerManager

    override fun onTerminate() {
        if (::senderControlServer.isInitialized) {
            senderControlServer.stop()
        }
        super.onTerminate()
    }
}
