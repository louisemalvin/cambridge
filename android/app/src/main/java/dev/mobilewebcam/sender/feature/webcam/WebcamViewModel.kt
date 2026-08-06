package dev.mobilewebcam.sender.feature.webcam

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.mobilewebcam.sender.app.model.SenderScreenAction
import dev.mobilewebcam.sender.app.model.SenderUiEffect
import dev.mobilewebcam.sender.app.model.StreamPresentationSnapshot
import dev.mobilewebcam.sender.connection.SenderConnectionCoordinator
import dev.mobilewebcam.sender.media.camera.CameraController
import dev.mobilewebcam.sender.media.camera.CameraPreviewSurface
import dev.mobilewebcam.sender.model.SenderSettingsRepository
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
import javax.inject.Inject

@HiltViewModel
class WebcamViewModel @Inject constructor(
    private val coordinator: SenderConnectionCoordinator,
    private val cameraController: CameraController,
    private val settings: SenderSettingsRepository,
) : ViewModel() {
    private val localState = MutableStateFlow(LocalWebcamState())
    private val effectFlow = MutableSharedFlow<SenderUiEffect>(
        extraBufferCapacity = EFFECT_BUFFER_CAPACITY,
    )

    val uiState: StateFlow<WebcamUiState> = combine(
        coordinator.streamState,
        coordinator.activeReceiverName,
        cameraController.state,
        settings.state,
        localState,
    ) { streamState, receiverName, cameraInteraction, configuredSettings, local ->
        WebcamUiStateMapper.map(
            snapshot = StreamPresentationSnapshot(
                profile = configuredSettings.profile,
                cameraInteraction = cameraInteraction,
                streamState = streamState,
                activeReceiverName = receiverName,
                validationMessage = null,
            ),
            cameraPermissionGranted = local.cameraPermissionGranted,
            isScreenDimmed = local.isScreenDimmed,
            isZoomTrayOpen = local.isZoomTrayOpen,
            isPermissionDialogOpen = local.isPermissionDialogOpen,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = WebcamUiState(),
    )

    val effects = effectFlow.asSharedFlow()

    fun onAction(action: SenderScreenAction) {
        when (action) {
            SenderScreenAction.ToggleScreenDimmed -> localState.update {
                it.copy(isScreenDimmed = !it.isScreenDimmed)
            }
            SenderScreenAction.ToggleZoomTray -> localState.update {
                it.copy(isZoomTrayOpen = !it.isZoomTrayOpen)
            }
            SenderScreenAction.CloseZoomTray -> localState.update {
                it.copy(isZoomTrayOpen = false)
            }
            is SenderScreenAction.ZoomChanged -> setZoomRatio(action.ratio)
            SenderScreenAction.ResetZoom -> resetZoom()
            is SenderScreenAction.LensSelected -> selectPhysicalLens(action.key)
            is SenderScreenAction.StabilizationChanged -> setStabilizationEnabled(action.enabled)
            SenderScreenAction.OpenPermissionDialog -> localState.update {
                it.copy(isPermissionDialogOpen = true)
            }
            SenderScreenAction.DismissPermissionDialog -> localState.update {
                it.copy(isPermissionDialogOpen = false)
            }
            SenderScreenAction.RequestCameraPermission -> requestCameraPermission()
            SenderScreenAction.StartStream -> start()
            SenderScreenAction.StopStream -> stop()
            SenderScreenAction.CopyDiagnostics -> copyDiagnostics()
            else -> Unit
        }
    }

    fun setCameraPermissionGranted(granted: Boolean) {
        localState.update {
            it.copy(
                cameraPermissionGranted = granted,
                isPermissionDialogOpen = false,
            )
        }
    }

    fun setPreviewSurface(surface: CameraPreviewSurface?) {
        viewModelScope.launch(Dispatchers.Default) {
            cameraController.setPreviewSurface(surface)
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

    private fun requestCameraPermission() {
        localState.update { it.copy(isPermissionDialogOpen = false) }
        effectFlow.tryEmit(SenderUiEffect.RequestCameraPermission)
    }

    private fun stop() {
        viewModelScope.launch { coordinator.stop() }
    }

    private fun start() {
        viewModelScope.launch { coordinator.startStream() }
    }

    private fun copyDiagnostics() {
        val details = uiState.value.failureDiagnostics ?: return
        effectFlow.tryEmit(SenderUiEffect.CopyDiagnostics(details))
    }

    private data class LocalWebcamState(
        val cameraPermissionGranted: Boolean = false,
        val isScreenDimmed: Boolean = false,
        val isZoomTrayOpen: Boolean = false,
        val isPermissionDialogOpen: Boolean = false,
    )

    private companion object {
        const val EFFECT_BUFFER_CAPACITY = 4
    }
}
