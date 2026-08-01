package dev.mobilewebcam.sender.control.http

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val CONTROL_PROTOCOL_VERSION: Int = 1

@Serializable
enum class ControlCodec {
    @SerialName("h264")
    H264,

    @SerialName("h265")
    H265,
}

@Serializable
enum class ControlTransport {
    @SerialName("mpegts-udp")
    MPEGTS_UDP,
}

@Serializable
enum class ControlPixelFormat {
    @SerialName("yuy2")
    YUY2,

    @SerialName("nv12")
    NV12,

    @SerialName("i420")
    I420,
}

@Serializable
enum class ControlDecoderAcceleration {
    @SerialName("hardware")
    HARDWARE,

    @SerialName("software")
    SOFTWARE,

    @SerialName("unknown")
    UNKNOWN,
}

@Serializable
data class HealthResponseDto(
    val status: String,
    @SerialName("protocolVersion") val protocolVersion: Int,
)

@Serializable
data class MediaCapabilitiesDto(
    val transport: ControlTransport,
    @SerialName("defaultPort") val defaultPort: Int,
)

@Serializable
data class VideoCodecCapabilityDto(
    val codec: ControlCodec,
    val supported: Boolean,
    @SerialName("decoderAcceleration") val decoderAcceleration: ControlDecoderAcceleration,
)

@Serializable
data class OutputCapabilitiesDto(
    val device: String,
    @SerialName("pixelFormats") val pixelFormats: List<ControlPixelFormat>,
)

@Serializable
data class SessionCapabilitiesDto(
    @SerialName("maximumConcurrentSessions") val maximumConcurrentSessions: Int,
    val active: Boolean,
)

@Serializable
data class CapabilitiesResponseDto(
    @SerialName("protocolVersion") val protocolVersion: Int,
    val media: MediaCapabilitiesDto,
    @SerialName("videoCodecs") val videoCodecs: List<VideoCodecCapabilityDto>,
    val output: OutputCapabilitiesDto,
    val session: SessionCapabilitiesDto,
)

@Serializable
data class VideoProfileDto(
    val width: Int,
    val height: Int,
    val fps: Int,
)

@Serializable
data class BitrateByCodecDto(
    val h264: Int,
    val h265: Int,
)

@Serializable
data class PrepareSessionRequestDto(
    @SerialName("protocolVersion") val protocolVersion: Int,
    @SerialName("preferredCodecs") val preferredCodecs: List<ControlCodec>,
    val profile: VideoProfileDto,
    @SerialName("bitrateByCodec") val bitrateByCodec: BitrateByCodecDto,
)

@Serializable
data class MediaResponseDto(
    val transport: ControlTransport,
    val port: Int,
)

@Serializable
data class NegotiatedProfileDto(
    val width: Int,
    val height: Int,
    val fps: Int,
    @SerialName("bitrateBps") val bitrateBps: Int,
)

@Serializable
data class OutputResponseDto(
    @SerialName("pixelFormat") val pixelFormat: ControlPixelFormat,
)

@Serializable
data class PrepareSessionResponseDto(
    @SerialName("sessionId") val sessionId: String,
    @SerialName("selectedCodec") val selectedCodec: ControlCodec,
    val media: MediaResponseDto,
    val profile: NegotiatedProfileDto,
    val output: OutputResponseDto,
    val warnings: List<String>,
)

@Serializable
enum class ControlSessionState {
    @SerialName("idle")
    IDLE,

    @SerialName("prepared")
    PREPARED,

    @SerialName("waiting_for_stream")
    WAITING_FOR_STREAM,

    @SerialName("receiving")
    RECEIVING,

    @SerialName("timed_out")
    TIMED_OUT,

    @SerialName("stopping")
    STOPPING,

    @SerialName("failed")
    FAILED,
}

@Serializable
data class SessionStateResponseDto(
    @SerialName("sessionId") val sessionId: String,
    val state: ControlSessionState,
    @SerialName("selectedCodec") val selectedCodec: ControlCodec,
    val decoder: String? = null,
    @SerialName("receivedBitrateBps") val receivedBitrateBps: Int,
    @SerialName("timeoutCount") val timeoutCount: Long,
)
