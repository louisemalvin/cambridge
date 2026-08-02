package dev.mobilewebcam.sender

import android.app.Application
import android.os.Build
import dev.mobilewebcam.sender.capabilities.mediacodec.MediaCodecCapabilityProbe
import dev.mobilewebcam.sender.control.http.HttpReceiverControlClient
import dev.mobilewebcam.sender.discovery.PairingStore
import dev.mobilewebcam.sender.discovery.SenderConnectionCoordinator
import dev.mobilewebcam.sender.discovery.SenderControlServer
import dev.mobilewebcam.sender.platform.AndroidForegroundStreamingController
import dev.mobilewebcam.sender.platform.AndroidStreamingPowerManager
import dev.mobilewebcam.sender.platform.StreamingPowerManager
import dev.mobilewebcam.sender.session.CodecNegotiator
import dev.mobilewebcam.sender.session.StreamSessionController
import dev.mobilewebcam.sender.session.StreamSessionControllerImpl
import dev.mobilewebcam.sender.streaming.rootencoder.RootEncoderStreamEngine

class MobileWebcamApplication : Application() {
    lateinit var sessionController: StreamSessionController
        private set

    lateinit var connectionCoordinator: SenderConnectionCoordinator
        private set

    private lateinit var senderControlServer: SenderControlServer

    lateinit var powerManager: StreamingPowerManager
        private set

    override fun onCreate() {
        super.onCreate()
        powerManager = AndroidStreamingPowerManager(this)
        sessionController = StreamSessionControllerImpl(
            receiver = HttpReceiverControlClient(),
            capabilityProbe = MediaCodecCapabilityProbe(),
            negotiator = CodecNegotiator(),
            streamEngine = RootEncoderStreamEngine(this),
            foreground = AndroidForegroundStreamingController(this, powerManager),
        )
        val pairings = PairingStore(this)
        connectionCoordinator = SenderConnectionCoordinator(this, sessionController, pairings)
        senderControlServer = SenderControlServer(
            coordinator = connectionCoordinator,
            senderId = pairings.senderId,
            displayName = Build.MODEL.takeIf { it.isNotBlank() } ?: "Android phone",
        )
        senderControlServer.start()
    }

    override fun onTerminate() {
        senderControlServer.stop()
        super.onTerminate()
    }
}
