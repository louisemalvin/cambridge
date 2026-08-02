package dev.mobilewebcam.sender

import android.app.Application
import dev.mobilewebcam.sender.connection.control.http.HttpReceiverControlClient
import dev.mobilewebcam.sender.connection.discovery.PairingStore
import dev.mobilewebcam.sender.connection.discovery.SenderConnectionCoordinator
import dev.mobilewebcam.sender.connection.discovery.SenderControlServer
import dev.mobilewebcam.sender.media.camera.CameraController
import dev.mobilewebcam.sender.media.capabilities.mediacodec.MediaCodecCapabilityProbe
import dev.mobilewebcam.sender.media.streaming.rootencoder.RootEncoderStreamEngine
import dev.mobilewebcam.sender.media.streaming.session.StreamSessionController
import dev.mobilewebcam.sender.media.streaming.session.StreamSessionControllerImpl
import dev.mobilewebcam.sender.platform.power.AndroidStreamingPowerManager
import dev.mobilewebcam.sender.platform.power.StreamingPowerManager
import dev.mobilewebcam.sender.platform.service.AndroidForegroundStreamingController

class MobileWebcamApplication : Application() {
    lateinit var sessionController: StreamSessionController
        private set

    lateinit var connectionCoordinator: SenderConnectionCoordinator
        private set

    lateinit var pairings: PairingStore
        private set

    lateinit var cameraController: CameraController
        private set

    lateinit var senderControlServer: SenderControlServer
        private set

    lateinit var powerManager: StreamingPowerManager
        private set

    override fun onCreate() {
        super.onCreate()
        pairings = PairingStore(this)
        powerManager = AndroidStreamingPowerManager(this)
        val engine = RootEncoderStreamEngine(this)
        cameraController = engine
        val codecProbe = MediaCodecCapabilityProbe(this)
        val controlClient = HttpReceiverControlClient(this)
        val foregroundController = AndroidForegroundStreamingController(this)
        sessionController = StreamSessionControllerImpl(
            engine = engine,
            capabilityProbe = codecProbe,
            controlClient = controlClient,
            foregroundController = foregroundController,
            powerManager = powerManager,
        )
        connectionCoordinator = SenderConnectionCoordinator(
            pairings = pairings,
            sessionController = sessionController,
        )
        senderControlServer = SenderControlServer(
            context = this,
            coordinator = connectionCoordinator,
        )
        senderControlServer.start()
    }

    override fun onTerminate() {
        if (::senderControlServer.isInitialized) {
            senderControlServer.stop()
        }
        super.onTerminate()
    }
}
