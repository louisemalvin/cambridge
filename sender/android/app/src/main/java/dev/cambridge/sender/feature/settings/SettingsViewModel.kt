package dev.cambridge.sender.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.cambridge.sender.app.model.SenderScreenAction
import dev.cambridge.sender.app.model.SenderUiEffect
import dev.cambridge.sender.app.model.StreamPresentationSnapshot
import dev.cambridge.sender.connection.SenderConnectionCoordinator
import dev.cambridge.sender.media.camera.CameraController
import dev.cambridge.sender.media.camera.AntiFlickerMode
import dev.cambridge.sender.media.camera.CameraStabilizationMode
import dev.cambridge.sender.model.SenderSettingsRepository
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
        SettingsUiStateMapper.map(
            snapshot = StreamPresentationSnapshot(
                profile = configuredSettings.profile,
                cameraInteraction = cameraInteraction,
                streamState = streamState,
                activeReceiverName = receiverName,
                validationMessage = validation,
            ),
        )
    }

    val uiState: StateFlow<SettingsUiState> = baseUiState.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = SettingsUiState(),
    )

    val effects = effectFlow.asSharedFlow()

    fun onAction(action: SenderScreenAction) {
        when (action) {
            is SenderScreenAction.ZoomChanged -> setZoomRatio(action.ratio)
            SenderScreenAction.ResetZoom -> resetZoom()
            is SenderScreenAction.LensSelected -> selectPhysicalLens(action.key)
            is SenderScreenAction.StabilizationModeChanged -> setStabilizationMode(action.mode)
            is SenderScreenAction.AntiFlickerChanged -> setAntiFlickerMode(action.mode)
            SenderScreenAction.CopyDiagnostics -> copyDiagnostics()
            else -> Unit
        }
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

    private fun setStabilizationMode(mode: CameraStabilizationMode) {
        viewModelScope.launch(Dispatchers.Default) {
            settings.updateStabilizationMode(mode)
            cameraController.setStabilizationMode(mode)
        }
    }

    private fun setAntiFlickerMode(mode: AntiFlickerMode) {
        viewModelScope.launch(Dispatchers.Default) {
            cameraController.setAntiFlickerMode(mode)
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

    private fun copyDiagnostics() {
        val details = uiState.value.failureDiagnostics ?: return
        effectFlow.tryEmit(SenderUiEffect.CopyDiagnostics(details))
    }

    private companion object {
        const val EFFECT_BUFFER_CAPACITY = 4
    }
}
