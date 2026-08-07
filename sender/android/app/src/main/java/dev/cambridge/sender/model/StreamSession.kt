package dev.cambridge.sender.model

import dev.cambridge.sender.connection.control.cambridge.CamBridgeStreamContract
import dev.cambridge.sender.media.camera.SessionTransform

data class StreamSession(
    val sessionId: String,
    val endpoint: ReceiverEndpoint,
    val selectedCodec: VideoCodec,
    val profile: VideoProfile,
    val bitrateBps: Int,
    val mediaPort: Int,
    val outputPixelFormat: OutputPixelFormat,
    val warnings: List<String>,
    val streamGeneration: Long = CamBridgeStreamContract.FIRST_STREAM_GENERATION,
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
