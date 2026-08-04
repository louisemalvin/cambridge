package dev.mobilewebcam.sender.model

data class NegotiatedSession(
    val sessionId: String,
    val endpoint: ReceiverEndpoint,
    val selectedCodec: VideoCodec,
    val profile: VideoProfile,
    val bitrateBps: Int,
    val mediaPort: Int,
    val outputPixelFormat: OutputPixelFormat,
    val warnings: List<String>,
    val srtEndpoint: SrtTransportEndpoint? = null,
    val connectDeadlineMs: Long? = null,
    val reconnectGraceMs: Long? = null,
)

data class StreamConfiguration(
    val codec: VideoCodec,
    val profile: VideoProfile,
    val bitrateBps: Int,
    val keyframeIntervalSeconds: Int,
    val runId: String? = null,
    val sessionId: String? = null,
)
