package dev.cambridge.sender.deployment

import dev.cambridge.sender.BuildConfig
import dev.cambridge.sender.connection.control.cambridge.CamBridgeStreamContract
import dev.cambridge.sender.model.ReceiverEndpoint

/** Build-time deployment values keep transport addresses out of connection and UI code. */
object CamBridgeDeployment {
    val endpoint: ReceiverEndpoint = ReceiverEndpoint(
        host = BuildConfig.CAMBRIDGE_COMPUTER_ADDRESS,
        controlPort = CamBridgeStreamContract.DEFAULT_CONTROL_PORT,
        displayName = BuildConfig.CAMBRIDGE_COMPUTER_DISPLAY_NAME,
    )
}
