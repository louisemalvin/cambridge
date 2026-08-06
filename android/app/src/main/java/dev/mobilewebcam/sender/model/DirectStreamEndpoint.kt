package dev.mobilewebcam.sender.model

/** Endpoint and generation selected by the direct RTP control handshake. */
data class DirectStreamEndpoint(
    val host: String,
    val controlPort: Int,
    val mediaPort: Int,
    val sessionId: String,
    val generation: Long,
)
