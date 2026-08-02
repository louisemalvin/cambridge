package dev.mobilewebcam.sender

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.mobilewebcam.sender.camera.CameraController
import dev.mobilewebcam.sender.discovery.PairingStore
import dev.mobilewebcam.sender.discovery.SenderConnectionCoordinator
import dev.mobilewebcam.sender.discovery.SenderControlServer
import dev.mobilewebcam.sender.platform.StreamingPowerManager
import dev.mobilewebcam.sender.session.StreamSessionController
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
