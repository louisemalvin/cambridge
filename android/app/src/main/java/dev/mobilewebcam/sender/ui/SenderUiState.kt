package dev.mobilewebcam.sender.ui

import dev.mobilewebcam.sender.config.VideoProfiles
import dev.mobilewebcam.sender.discovery.PendingApproval
import dev.mobilewebcam.sender.model.CodecPreference
import dev.mobilewebcam.sender.model.StreamState
import dev.mobilewebcam.sender.model.VideoProfile

data class SenderUiState(
    val codecPreference: CodecPreference = CodecPreference.AUTO_PREFER_H265,
    val profile: VideoProfile = VideoProfiles.default,
    val streamState: StreamState = StreamState.Idle,
    val cameraPermissionGranted: Boolean = false,
    val pendingApproval: PendingApproval? = null,
    val activeReceiverName: String? = null,
    val validationMessage: String? = null,
    val failureDetails: String? = null,
)
