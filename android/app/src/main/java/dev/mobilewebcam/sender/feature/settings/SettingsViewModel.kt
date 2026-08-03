package dev.mobilewebcam.sender.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.mobilewebcam.sender.app.model.SenderScreenAction
import dev.mobilewebcam.sender.app.model.SenderUiEffect
import dev.mobilewebcam.sender.app.model.SettingsUiState
import dev.mobilewebcam.sender.connection.discovery.SenderConnectionCoordinator
import dev.mobilewebcam.sender.feature.webcam.SenderDomainSnapshot
import dev.mobilewebcam.sender.feature.webcam.SenderScreenStateMapper
import dev.mobilewebcam.sender.media.camera.CameraController
import dev.mobilewebcam.sender.session.VideoProfiles
import dev.mobilewebcam.sender.model.CodecPreference
import dev.mobilewebcam.sender.model.SenderSettingsRepository
import dev.mobilewebcam.sender.model.StreamState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val coordinator: SenderConnectionCoordinator,
    private val cameraController: CameraController,
    private val settings: SenderSettingsRepository,
) : ViewModel() {
    private val validationMessage = MutableStateFlow<String?>(null)
    private val effectFlow = MutableSharedFlow<SenderUiEffect>(
        extraBufferCapacity = EFFECT_BUFFER_CAPACITY,
    )

    private val baseUiState = combine(
        coordinator.streamState,
        coordinator.activeReceiverName,
        cameraController.state,
        settings.state,
        validationMessage,
    ) { streamState, receiverName, cameraInteraction, configuredSettings, validation ->
        val fullState = SenderScreenStateMapper.map(
            SenderDomainSnapshot(
                codecPreference = configuredSettings.codecPreference,
                profile = configuredSettings.profile,
                cameraInteraction = cameraInteraction,
                streamState = streamState,
                cameraPermissionGranted = true,
                activeReceiverName = receiverName,
                validationMessage = validation,
                isScreenDimmed = false,
                isZoomTrayOpen = false,
                isPermissionDialogOpen = false,
            ),
        )
        SettingsUiState(
            connection = fullState.connection,
            codecOptions = fullState.settings.codecOptions,
            profileOptions = fullState.settings.profileOptions,
            receiverName = fullState.settings.receiverName,
            connectionStatus = fullState.settings.connectionStatus,
            camera = fullState.camera,
            validationMessage = fullState.validationMessage,
            failureDiagnostics = fullState.failureDiagnostics,
            isStreaming = streamState is StreamState.Streaming || streamState is StreamState.Preparing || streamState is StreamState.Starting,
        )
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        baseUiState,
        coordinator.hasApprovedReceiver,
    ) { state, hasApprovedReceiver ->
        state.copy(hasApprovedReceiver = hasApprovedReceiver)
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
            SenderScreenAction.ForgetPairing -> forgetPairing()
            SenderScreenAction.CopyDiagnostics -> copyDiagnostics()
            else -> Unit
        }
    }

    private fun updateCodecPreference(key: String) {
        val preference = CodecPreference.entries.firstOrNull { it.name == key } ?: return
        settings.updateCodecPreference(preference)
        validationMessage.value = null
    }

    private fun updateProfile(key: String) {
        val profile = VideoProfiles.all.firstOrNull { it.id == key } ?: return
        settings.updateProfile(profile)
        validationMessage.value = null
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

    private fun forgetPairing() {
        viewModelScope.launch {
            val result = coordinator.forgetPairing()
            if (result.isSuccess) {
                effectFlow.tryEmit(SenderUiEffect.NavigateToPairing)
            } else {
                validationMessage.value = result.exceptionOrNull()?.message
                    ?: "Could not forget the receiver pairing"
            }
        }
    }

    private fun copyDiagnostics() {
        val details = uiState.value.failureDiagnostics ?: return
        effectFlow.tryEmit(SenderUiEffect.CopyDiagnostics(details))
    }

    private companion object {
        const val EFFECT_BUFFER_CAPACITY = 4
    }
}
