package dev.mobilewebcam.sender

import android.app.Application
import dev.mobilewebcam.sender.capabilities.mediacodec.MediaCodecCapabilityProbe
import dev.mobilewebcam.sender.control.http.HttpReceiverControlClient
import dev.mobilewebcam.sender.platform.AndroidForegroundStreamingController
import dev.mobilewebcam.sender.platform.AndroidStreamingPowerManager
import dev.mobilewebcam.sender.platform.AndroidNetworkInformationProvider
import dev.mobilewebcam.sender.platform.NetworkInformationProvider
import dev.mobilewebcam.sender.platform.StreamingPowerManager
import dev.mobilewebcam.sender.session.CodecNegotiator
import dev.mobilewebcam.sender.session.StreamSessionController
import dev.mobilewebcam.sender.session.StreamSessionControllerImpl
import dev.mobilewebcam.sender.streaming.rootencoder.RootEncoderStreamEngine

class MobileWebcamApplication : Application() {
    lateinit var sessionController: StreamSessionController
        private set

    lateinit var powerManager: StreamingPowerManager
        private set

    lateinit var networkInformationProvider: NetworkInformationProvider
        private set

    override fun onCreate() {
        super.onCreate()
        powerManager = AndroidStreamingPowerManager(this)
        networkInformationProvider = AndroidNetworkInformationProvider(this)
        sessionController = StreamSessionControllerImpl(
            receiver = HttpReceiverControlClient(),
            capabilityProbe = MediaCodecCapabilityProbe(),
            negotiator = CodecNegotiator(),
            streamEngine = RootEncoderStreamEngine(this),
            foreground = AndroidForegroundStreamingController(this, powerManager),
        )
    }
}
