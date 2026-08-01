package dev.mobilewebcam.sender.ui

import dev.mobilewebcam.sender.config.AppDefaults
import dev.mobilewebcam.sender.config.VideoProfiles
import dev.mobilewebcam.sender.model.CodecPreference
import dev.mobilewebcam.sender.model.StreamState
import dev.mobilewebcam.sender.model.VideoProfile
import dev.mobilewebcam.sender.platform.NetworkInformation

data class SenderUiState(
    val receiverHost: String = "",
    val controlPort: String = AppDefaults.controlPort.toString(),
    val codecPreference: CodecPreference = CodecPreference.AUTO_PREFER_H265,
    val profile: VideoProfile = VideoProfiles.default,
    val streamState: StreamState = StreamState.Idle,
    val cameraPermissionGranted: Boolean = false,
    val networkInformation: List<NetworkInformation> = emptyList(),
    val validationMessage: String? = null,
)
