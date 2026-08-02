package dev.mobilewebcam.sender.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mobilewebcam.sender.camera.CameraController
import dev.mobilewebcam.sender.config.VideoProfiles
import dev.mobilewebcam.sender.discovery.SenderConnectionCoordinator
import dev.mobilewebcam.sender.model.CodecPreference
import dev.mobilewebcam.sender.model.StreamState
import dev.mobilewebcam.sender.model.VideoProfile
import dev.mobilewebcam.sender.ui.SenderDomainSnapshot
import dev.mobilewebcam.sender.ui.SenderScreenStateMapper
import dev.mobilewebcam.sender.ui.model.SenderScreenAction
import dev.mobilewebcam.sender.ui.model.SenderUiEffect
import dev.mobilewebcam.sender.ui.model.SettingsUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val coordinator: SenderConnectionCoordinator,
    private val cameraController: CameraController,
) : ViewModel() {
    private val localState = MutableStateFlow(LocalSettingsState())
    private val effectFlow = MutableSharedFlow<SenderUiEffect>(
        extraBufferCapacity = EFFECT_BUFFER_CAPACITY,
    )

    val uiState: StateFlow<SettingsUiState> = combine(
        coordinator.streamState,
        coordinator.activeReceiverName,
        cameraController.state,
        localState,
    ) { streamState, receiverName, cameraInteraction, local ->
        val fullState = SenderScreenStateMapper.map(
            SenderDomainSnapshot(
                codecPreference = local.codecPreference,
                profile = local.profile,
                cameraInteraction = cameraInteraction,
                streamState = streamState,
                cameraPermissionGranted = true,
                pendingApproval = null,
                activeReceiverName = receiverName,
                validationMessage = local.validationMessage,
                isScreenDimmed = false,
                isZoomTrayOpen = false,
                isPermissionDialogOpen = false,
            ),
        )
        SettingsUiState(
            codecOptions = fullState.settings.codecOptions,
            profileOptions = fullState.settings.profileOptions,
            receiverName = fullState.settings.receiverName,
            connectionStatus = fullState.settings.connectionStatus,
            camera = fullState.camera,
            validationMessage = fullState.validationMessage,
            failureDiagnostics = fullState.failureDiagnostics,
            isStreaming = streamState is StreamState.Streaming || streamState is StreamState.Preparing || streamState is StreamState.Starting,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = SettingsUiState(),
    )

    val effects = effectFlow.asSharedFlow()

    fun onAction(action: SenderScreenAction) {
        when (action) {
            is SenderScreenAction.CodecSelected -> updateCodecPreference(action.key)
            is SenderScreenAction.ProfileSelected -> updateProfile(action.key)
            is SenderScreenAction.ZoomChanged -> setZoomRatio(action.ratio)
            SenderScreenAction.ResetZoom -> resetZoom()
            is SenderScreenAction.LensSelected -> selectPhysicalLens(action.key)
            is SenderScreenAction.StabilizationChanged -> setStabilizationEnabled(action.enabled)
            SenderScreenAction.StopStream -> stop()
            SenderScreenAction.CopyDiagnostics -> copyDiagnostics()
            else -> Unit
        }
    }

    private fun updateCodecPreference(key: String) {
        val preference = CodecPreference.entries.firstOrNull { it.name == key } ?: return
        val profile = localState.value.profile
        localState.update {
            it.copy(codecPreference = preference, validationMessage = null)
        }
        coordinator.updateConfiguration(preference, profile)
    }

    private fun updateProfile(key: String) {
        val profile = VideoProfiles.all.firstOrNull { it.id == key } ?: return
        val preference = localState.value.codecPreference
        localState.update {
            it.copy(profile = profile, validationMessage = null)
        }
        coordinator.updateConfiguration(preference, profile)
    }

    private fun setZoomRatio(zoomRatio: Float) {
        viewModelScope.launch(Dispatchers.Default) {
            cameraController.setZoomRatio(zoomRatio)
        }
    }

    private fun resetZoom() {
        viewModelScope.launch(Dispatchers.Default) {
            cameraController.resetZoom()
        }
    }

    private fun setStabilizationEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.Default) {
            cameraController.setStabilizationEnabled(enabled)
        }
    }

    private fun selectPhysicalLens(key: String) {
        val lens = cameraController.state.value.physicalLensOptions
            .firstOrNull { it.label == key }
            ?: return
        viewModelScope.launch(Dispatchers.Default) {
            cameraController.selectPhysicalLens(lens)
        }
    }

    private fun stop() {
        viewModelScope.launch { coordinator.stop() }
    }

    private fun copyDiagnostics() {
        val details = uiState.value.failureDiagnostics ?: return
        effectFlow.tryEmit(SenderUiEffect.CopyDiagnostics(details))
    }

    private data class LocalSettingsState(
        val codecPreference: CodecPreference = CodecPreference.AUTO_PREFER_H265,
        val profile: VideoProfile = VideoProfiles.default,
        val validationMessage: String? = null,
    )

    private companion object {
        const val EFFECT_BUFFER_CAPACITY = 4
    }
}
