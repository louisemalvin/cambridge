package dev.cambridge.sender.model

/** Endpoint and generation selected by the CamBridge RTP control handshake. */
data class CamBridgeStreamEndpoint(
    val host: String,
    val controlPort: Int,
    val mediaPort: Int,
    val sessionId: String,
    val generation: Long,
)
