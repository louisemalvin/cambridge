package dev.mobilewebcam.sender.app.model

import dev.mobilewebcam.sender.media.camera.CameraInteractionState
import dev.mobilewebcam.sender.model.CodecPreference
import dev.mobilewebcam.sender.model.StreamState
import dev.mobilewebcam.sender.model.VideoProfile

/** Infrastructure values shared by destination-specific presentation mappers. */
data class StreamPresentationSnapshot(
    val codecPreference: CodecPreference,
    val profile: VideoProfile,
    val cameraInteraction: CameraInteractionState,
    val streamState: StreamState,
    val activeReceiverName: String?,
    val validationMessage: String?,
)
