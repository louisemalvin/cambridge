package dev.mobilewebcam.sender.feature.settings

import dev.mobilewebcam.sender.app.model.CameraControlsUiStateMapper
import dev.mobilewebcam.sender.app.model.SelectOptionUi
import dev.mobilewebcam.sender.app.model.StreamPresentationMapper
import dev.mobilewebcam.sender.app.model.StreamPresentationSnapshot
import dev.mobilewebcam.sender.app.model.UiText
import dev.mobilewebcam.sender.app.model.buildFailureDiagnostics
import dev.mobilewebcam.sender.model.CodecPreference
import dev.mobilewebcam.sender.model.StreamState
import dev.mobilewebcam.sender.session.VideoProfiles

object SettingsUiStateMapper {
    fun map(
        snapshot: StreamPresentationSnapshot,
        hasConfiguredReceiver: Boolean,
    ): SettingsUiState {
        return SettingsUiState(
            connection = StreamPresentationMapper.connection(snapshot),
            codecOptions = CodecPreference.entries.map { preference ->
                SelectOptionUi(
                    key = preference.name,
                    label = StreamPresentationMapper.codecPreferenceLabel(preference),
                    isSelected = preference == snapshot.codecPreference,
                )
            },
            profileOptions = VideoProfiles.all.map { profile ->
                SelectOptionUi(
                    key = profile.id,
                    label = StreamPresentationMapper.videoProfileLabel(profile),
                    isSelected = profile.id == snapshot.profile.id,
                )
            },
            receiverName = snapshot.activeReceiverName?.let(UiText::Plain),
            connectionStatus = StreamPresentationMapper.connectionStatus(snapshot.streamState),
            hasConfiguredReceiver = hasConfiguredReceiver,
            camera = CameraControlsUiStateMapper.map(snapshot.cameraInteraction),
            validationMessage = snapshot.validationMessage?.let(UiText::Plain),
            failureDiagnostics = (snapshot.streamState as? StreamState.Failed)?.let { failed ->
                buildFailureDiagnostics(
                    receiverName = snapshot.activeReceiverName,
                    profile = snapshot.profile,
                    codecPreference = snapshot.codecPreference,
                    failure = failed.failure,
                    cause = StreamPresentationMapper.causeOrNull(failed.failure),
                )
            },
            isStreaming = snapshot.streamState is StreamState.Streaming ||
                snapshot.streamState is StreamState.Preparing ||
                snapshot.streamState is StreamState.Starting,
        )
    }
}
