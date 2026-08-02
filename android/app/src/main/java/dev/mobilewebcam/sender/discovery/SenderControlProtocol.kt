package dev.mobilewebcam.sender.discovery

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

internal const val SENDER_CONTROL_PROTOCOL_VERSION: Int = 1
internal const val SENDER_CONTROL_PORT: Int = 53_555
internal const val MIN_VALID_NETWORK_PORT: Int = 1
internal const val MAX_VALID_NETWORK_PORT: Int = 65_535

@Serializable
enum class SenderControlActionDto {
    @SerialName("describe")
    DESCRIBE,
}

@Serializable
data class DescribeSenderRequestDto(
    @SerialName("protocolVersion") val protocolVersion: Int,
    val action: SenderControlActionDto,
)

@Serializable
data class SenderAdvertisementDto(
    @SerialName("protocolVersion") val protocolVersion: Int,
    @SerialName("senderId") val senderId: String,
    @SerialName("displayName") val displayName: String,
    @SerialName("controlPort") val controlPort: Int,
)

@Serializable
data class StartStreamRequestDto(
    @SerialName("protocolVersion") val protocolVersion: Int,
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
    @SerialName("senderId") val senderId: String,
    val status: StartStreamStatusDto,
    @SerialName("pairingToken") val pairingToken: String? = null,
    val message: String? = null,
)
