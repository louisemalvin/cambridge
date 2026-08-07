package dev.cambridge.sender.app.model

import dev.cambridge.sender.media.camera.CameraInteractionState
import dev.cambridge.sender.model.StreamState
import dev.cambridge.sender.model.VideoProfile

/** Infrastructure values shared by destination-specific presentation mappers. */
data class StreamPresentationSnapshot(
    val profile: VideoProfile,
    val cameraInteraction: CameraInteractionState,
    val streamState: StreamState,
    val activeReceiverName: String?,
    val validationMessage: String?,
)
