package dev.mobilewebcam.sender.connection.discovery

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal const val SENDER_CONTROL_PROTOCOL_VERSION: Int = 2
internal const val SENDER_CONTROL_PORT: Int = 53_555
internal const val MIN_VALID_NETWORK_PORT: Int = 1
internal const val MAX_VALID_NETWORK_PORT: Int = 65_535

@Serializable
enum class SenderControlActionDto {
    @SerialName("describe")
    DESCRIBE,

    @SerialName("describe_result")
    DESCRIBE_RESULT,

    @SerialName("start")
    START,

    @SerialName("start_result")
    START_RESULT,

    @SerialName("stop")
    STOP,

    @SerialName("stop_result")
    STOP_RESULT,
}

@Serializable
data class DescribeSenderRequestDto(
    @SerialName("protocolVersion") val protocolVersion: Int,
    val action: SenderControlActionDto,
)

@Serializable
data class SenderAdvertisementDto(
    @SerialName("protocolVersion") val protocolVersion: Int,
    val action: SenderControlActionDto,
    @SerialName("senderId") val senderId: String,
    @SerialName("displayName") val displayName: String,
    @SerialName("controlPort") val controlPort: Int,
    val availability: SenderAvailabilityDto,
)

@Serializable
enum class SenderAvailabilityDto {
    @SerialName("standby")
    STANDBY,

    @SerialName("streaming")
    STREAMING,

    @SerialName("busy")
    BUSY,
}

@Serializable
data class StartStreamRequestDto(
    @SerialName("protocolVersion") val protocolVersion: Int,
    val action: SenderControlActionDto,
    @SerialName("streamId") val streamId: String,
    @SerialName("receiverId") val receiverId: String,
    @SerialName("receiverName") val receiverName: String,
    @SerialName("receiverControlPort") val receiverControlPort: Int,
    @SerialName("pairingToken") val pairingToken: String? = null,
)

@Serializable
enum class StartStreamStatusDto {
    @SerialName("accepted")
    ACCEPTED,

    @SerialName("approval_required")
    APPROVAL_REQUIRED,

    @SerialName("rejected")
    REJECTED,

    @SerialName("busy")
    BUSY,

    @SerialName("camera_permission_required")
    CAMERA_PERMISSION_REQUIRED,

    @SerialName("invalid_request")
    INVALID_REQUEST,
}

@Serializable
data class StartStreamResponseDto(
    @SerialName("protocolVersion") val protocolVersion: Int,
    val action: SenderControlActionDto,
    @SerialName("streamId") val streamId: String,
    @SerialName("senderId") val senderId: String,
    val status: StartStreamStatusDto,
    @SerialName("pairingToken") val pairingToken: String? = null,
    val message: String? = null,
)

@Serializable
data class StopStreamRequestDto(
    @SerialName("protocolVersion") val protocolVersion: Int,
    val action: SenderControlActionDto,
    @SerialName("streamId") val streamId: String,
    @SerialName("receiverId") val receiverId: String,
    @SerialName("pairingToken") val pairingToken: String,
)

@Serializable
enum class StopStreamStatusDto {
    @SerialName("stopped")
    STOPPED,

    @SerialName("already_stopped")
    ALREADY_STOPPED,

    @SerialName("stale_stream")
    STALE_STREAM,

    @SerialName("rejected")
    REJECTED,

    @SerialName("invalid_request")
    INVALID_REQUEST,
}

@Serializable
data class StopStreamResponseDto(
    @SerialName("protocolVersion") val protocolVersion: Int,
    val action: SenderControlActionDto,
    @SerialName("streamId") val streamId: String,
    @SerialName("senderId") val senderId: String,
    val status: StopStreamStatusDto,
    val message: String? = null,
)

internal fun String.isValidStreamId(): Boolean = runCatching {
    java.util.UUID.fromString(this).let { uuid ->
        uuid.mostSignificantBits != 0L || uuid.leastSignificantBits != 0L
    }
}.getOrDefault(false)
