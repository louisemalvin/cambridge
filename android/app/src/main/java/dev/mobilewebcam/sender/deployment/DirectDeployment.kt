package dev.mobilewebcam.sender.deployment

import dev.mobilewebcam.sender.BuildConfig
import dev.mobilewebcam.sender.connection.control.direct.DirectStreamContract
import dev.mobilewebcam.sender.model.ReceiverEndpoint

/** Build-time deployment values keep transport addresses out of connection and UI code. */
object DirectDeployment {
    val endpoint: ReceiverEndpoint = ReceiverEndpoint(
        host = BuildConfig.DIRECT_COMPUTER_ADDRESS,
        controlPort = DirectStreamContract.DEFAULT_CONTROL_PORT,
        displayName = BuildConfig.DIRECT_COMPUTER_DISPLAY_NAME,
    )
}
