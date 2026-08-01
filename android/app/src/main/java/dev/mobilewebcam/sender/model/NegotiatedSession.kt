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
)

data class StreamConfiguration(
    val codec: VideoCodec,
    val profile: VideoProfile,
    val bitrateBps: Int,
    val keyframeIntervalSeconds: Int,
)
