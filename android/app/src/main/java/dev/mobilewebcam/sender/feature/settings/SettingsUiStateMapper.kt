package dev.mobilewebcam.sender.feature.settings

import dev.mobilewebcam.sender.app.model.CameraControlsUiStateMapper
import dev.mobilewebcam.sender.app.model.StreamPresentationMapper
import dev.mobilewebcam.sender.app.model.StreamPresentationSnapshot
import dev.mobilewebcam.sender.app.model.UiText
import dev.mobilewebcam.sender.app.model.buildFailureDiagnostics
import dev.mobilewebcam.sender.model.StreamState

object SettingsUiStateMapper {
    fun map(
        snapshot: StreamPresentationSnapshot,
        hasConfiguredReceiver: Boolean,
    ): SettingsUiState {
        return SettingsUiState(
            connection = StreamPresentationMapper.connection(snapshot),
            sessionOrientation = StreamPresentationMapper.sessionOrientation(snapshot.streamState),
            receiverName = snapshot.activeReceiverName?.let(UiText::Plain),
            connectionStatus = StreamPresentationMapper.connectionStatus(
                snapshot.streamState,
                snapshot.activeReceiverName,
            ),
            hasConfiguredReceiver = hasConfiguredReceiver,
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
            isStreaming = snapshot.streamState is StreamState.Streaming ||
                snapshot.streamState == StreamState.Connecting ||
                snapshot.streamState == StreamState.Reconnecting,
        )
    }
}
