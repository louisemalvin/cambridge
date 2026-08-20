package dev.cambridge.sender.feature.webcam

import dev.cambridge.sender.app.model.CameraControlsUiStateMapper
import dev.cambridge.sender.app.model.PreviewUiState
import dev.cambridge.sender.app.model.StreamPresentationMapper
import dev.cambridge.sender.app.model.StreamPresentationSnapshot
import dev.cambridge.sender.app.model.UiText
import dev.cambridge.sender.app.model.buildFailureDiagnostics
import dev.cambridge.sender.model.StreamState

object WebcamUiStateMapper {
    fun map(
        snapshot: StreamPresentationSnapshot,
        isScreenDimmed: Boolean,
        isZoomTrayOpen: Boolean,
    ): WebcamUiState {
        val connection = StreamPresentationMapper.connection(snapshot)
        return WebcamUiState(
            preview = PreviewUiState(
                landscapeAspectRatio = snapshot.profile.width.toFloat() / snapshot.profile.height,
                isLive = snapshot.streamState is StreamState.Streaming ||
                    snapshot.streamState == StreamState.Stopping,
            ),
            connection = connection,
            sessionOrientation = StreamPresentationMapper.sessionOrientation(snapshot.streamState),
            camera = CameraControlsUiStateMapper.map(snapshot.cameraInteraction),
            validationMessage = snapshot.validationMessage?.let(UiText::Plain),
            failureDiagnostics = (snapshot.streamState as? StreamState.Failed)?.let { failed ->
                buildFailureDiagnostics(
                    receiverName = snapshot.activeReceiverName,
                    profile = snapshot.profile,
                    failure = failed.failure,
                    cause = StreamPresentationMapper.causeOrNull(failed.failure),
                )
            },
            isScreenDimmed = isScreenDimmed,
            isZoomTrayOpen = isZoomTrayOpen,
        )
    }
}
