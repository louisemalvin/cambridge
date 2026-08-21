package dev.cambridge.sender.model

/** Endpoint and generation selected by the CamBridge control handshake. */
data class CamBridgeStreamEndpoint(
    val host: String,
    val controlPort: Int,
    val mediaRtpPort: Int,
    val mediaRtcpPort: Int,
    val sessionId: String,
    val generation: Long,
)
