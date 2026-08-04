package dev.mobilewebcam.sender.connection.control.http

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val CONTROL_V2_PROTOCOL_VERSION: Int = 2
const val SRT_KEY_LENGTH_BYTES: Int = 32
const val SRT_PASSPHRASE_MIN_LENGTH: Int = 10
const val SRT_PASSPHRASE_MAX_LENGTH: Int = 79

@Serializable
enum class ControlCodec {
    @SerialName("h264")
    H264,

    @SerialName("h265")
    H265,
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
enum class SrtModeDto {
    @SerialName("caller")
    CALLER,

    @SerialName("listener")
    LISTENER,
}

@Serializable
enum class SrtTransportKindDto {
    @SerialName("srt")
    SRT,
}

@Serializable
data class SrtEndpointDto(
    val kind: SrtTransportKindDto,
    val mode: SrtModeDto,
    val host: String,
    val port: Int,
    @SerialName("streamId") val streamId: String,
    @SerialName("latencyMs") val latencyMs: Int,
    @SerialName("keyLengthBytes") val keyLengthBytes: Int,
    val passphrase: String,
) {
    fun validate() {
        require(host.isNotBlank()) { "SRT host is required" }
        require(port in MINIMUM_PORT..MAXIMUM_PORT) { "SRT port is invalid" }
        require(streamId.isNotBlank()) { "SRT stream ID is required" }
        require(latencyMs > 0) { "SRT latency must be positive" }
        require(keyLengthBytes == SRT_KEY_LENGTH_BYTES) { "SRT key length must be AES-256" }
        require(passphrase.length in SRT_PASSPHRASE_MIN_LENGTH..SRT_PASSPHRASE_MAX_LENGTH) {
            "SRT passphrase length is invalid"
        }
    }

    private companion object {
        const val MINIMUM_PORT = 1
        const val MAXIMUM_PORT = 65_535
    }
}

@Serializable
data class SrtTransportCapabilitiesDto(
    val kind: SrtTransportKindDto,
    val modes: List<SrtModeDto>,
    @SerialName("keyLengthBytes") val keyLengthBytes: Int,
)

@Serializable
data class V2VideoProfileDto(
    val width: Int,
    val height: Int,
    val fps: Int,
)

@Serializable
data class V2BitrateByCodecDto(
    val h264: Int,
    val h265: Int,
)

@Serializable
enum class V2ContainerDto {
    @SerialName("mpegts")
    MPEGTS,
}

@Serializable
data class V2VideoConfigurationDto(
    val codec: ControlCodec,
    val container: V2ContainerDto,
    val width: Int,
    val height: Int,
    val fps: Int,
    @SerialName("bitrateBps") val bitrateBps: Int,
)

@Serializable
data class V2OutputConfigurationDto(
    @SerialName("pixelFormat") val pixelFormat: ControlPixelFormat,
)

@Serializable
data class HealthResponseV2Dto(
    val status: String,
    @SerialName("protocolVersion") val protocolVersion: Int,
)

@Serializable
data class ReceiverCapabilitiesV2Dto(
    @SerialName("protocolVersion") val protocolVersion: Int,
    val transport: SrtTransportCapabilitiesDto,
    @SerialName("videoCodecs") val videoCodecs: List<ControlCodec>,
    @SerialName("outputProfile") val outputProfile: V2VideoProfileDto,
    val output: V2OutputConfigurationDto,
    @SerialName("maximumConcurrentSessions") val maximumConcurrentSessions: Int,
    val active: Boolean,
)

@Serializable
data class CreateSessionRequestV2Dto(
    @SerialName("protocolVersion") val protocolVersion: Int,
    @SerialName("preferredCodecs") val preferredCodecs: List<ControlCodec>,
    val profile: V2VideoProfileDto,
    @SerialName("bitrateByCodec") val bitrateByCodec: V2BitrateByCodecDto,
)

@Serializable
data class CreateSessionResponseV2Dto(
    @SerialName("protocolVersion") val protocolVersion: Int,
    @SerialName("sessionId") val sessionId: String,
    @SerialName("connectDeadlineMs") val connectDeadlineMs: Long,
    @SerialName("reconnectGraceMs") val reconnectGraceMs: Long,
    val video: V2VideoConfigurationDto,
    val transport: SrtEndpointDto,
    val output: V2OutputConfigurationDto,
)

@Serializable
enum class SessionStateV2Dto {
    @SerialName("idle")
    IDLE,

    @SerialName("allocating")
    ALLOCATING,

    @SerialName("listening")
    LISTENING,

    @SerialName("connected")
    CONNECTED,

    @SerialName("receiving")
    RECEIVING,

    @SerialName("reconnecting")
    RECONNECTING,

    @SerialName("stopping")
    STOPPING,

    @SerialName("failed")
    FAILED,

    @SerialName("expired")
    EXPIRED,
}

@Serializable
data class SessionMetricsV2Dto(
    @SerialName("bytesReceived") val bytesReceived: Long? = null,
    @SerialName("packetsReceived") val packetsReceived: Long? = null,
    @SerialName("packetsLost") val packetsLost: Long? = null,
    @SerialName("packetsRetransmitted") val packetsRetransmitted: Long? = null,
    @SerialName("packetsDropped") val packetsDropped: Long? = null,
    @SerialName("rttMs") val rttMs: Int? = null,
    @SerialName("decodedFrames") val decodedFrames: Long? = null,
    @SerialName("outputFps") val outputFps: Int? = null,
    @SerialName("outputQueueDepth") val outputQueueDepth: Int? = null,
    @SerialName("reconnectCount") val reconnectCount: Int? = null,
)

@Serializable
data class SessionStatusResponseV2Dto(
    @SerialName("protocolVersion") val protocolVersion: Int,
    @SerialName("sessionId") val sessionId: String,
    val state: SessionStateV2Dto,
    val decoder: String? = null,
    val metrics: SessionMetricsV2Dto,
)

internal fun ControlCodec.toDomain() = when (this) {
    ControlCodec.H264 -> dev.mobilewebcam.sender.model.VideoCodec.H264
    ControlCodec.H265 -> dev.mobilewebcam.sender.model.VideoCodec.H265
}

internal fun dev.mobilewebcam.sender.model.VideoCodec.toDto() = when (this) {
    dev.mobilewebcam.sender.model.VideoCodec.H264 -> ControlCodec.H264
    dev.mobilewebcam.sender.model.VideoCodec.H265 -> ControlCodec.H265
}

internal fun ControlPixelFormat.toDomain() = when (this) {
    ControlPixelFormat.YUY2 -> dev.mobilewebcam.sender.model.OutputPixelFormat.YUY2
    ControlPixelFormat.NV12 -> dev.mobilewebcam.sender.model.OutputPixelFormat.NV12
    ControlPixelFormat.I420 -> dev.mobilewebcam.sender.model.OutputPixelFormat.I420
}
