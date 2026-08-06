package dev.mobilewebcam.sender.model

import dev.mobilewebcam.sender.connection.control.direct.DirectStreamContract
import dev.mobilewebcam.sender.media.camera.SessionTransform

data class StreamSession(
    val sessionId: String,
    val endpoint: ReceiverEndpoint,
    val selectedCodec: VideoCodec,
    val profile: VideoProfile,
    val bitrateBps: Int,
    val mediaPort: Int,
    val outputPixelFormat: OutputPixelFormat,
    val warnings: List<String>,
    val streamGeneration: Long = DirectStreamContract.FIRST_STREAM_GENERATION,
    val sessionTransform: SessionTransform? = null,
)

enum class OutputPixelFormat {
    NV12,
}

data class StreamConfiguration(
    val codec: VideoCodec,
    val profile: VideoProfile,
    val bitrateBps: Int,
    val keyframeIntervalSeconds: Int,
    val runId: String? = null,
    val sessionId: String? = null,
    val sessionTransform: SessionTransform? = null,
)
