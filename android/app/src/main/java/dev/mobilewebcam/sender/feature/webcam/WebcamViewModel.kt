package dev.mobilewebcam.sender.feature.webcam

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.mobilewebcam.sender.camera.CameraController
import dev.mobilewebcam.sender.camera.CameraPreviewSurface
import dev.mobilewebcam.sender.config.VideoProfiles
import dev.mobilewebcam.sender.discovery.SenderConnectionCoordinator
import dev.mobilewebcam.sender.model.CodecPreference
import dev.mobilewebcam.sender.model.VideoProfile
import dev.mobilewebcam.sender.ui.SenderDomainSnapshot
import dev.mobilewebcam.sender.ui.SenderScreenStateMapper
import dev.mobilewebcam.sender.ui.model.SenderScreenAction
import dev.mobilewebcam.sender.ui.model.SenderUiEffect
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
) : ViewModel() {
    private val localState = MutableStateFlow(LocalWebcamState())
    private val effectFlow = MutableSharedFlow<SenderUiEffect>(
        extraBufferCapacity = EFFECT_BUFFER_CAPACITY,
    )

    val uiState: StateFlow<WebcamUiState> = combine(
        coordinator.streamState,
        coordinator.pendingApproval,
        coordinator.activeReceiverName,
        cameraController.state,
        localState,
    ) { streamState, pendingApproval, receiverName, cameraInteraction, local ->
        val fullState = SenderScreenStateMapper.map(
            SenderDomainSnapshot(
                codecPreference = local.codecPreference,
                profile = local.profile,
                cameraInteraction = cameraInteraction,
                streamState = streamState,
                cameraPermissionGranted = local.cameraPermissionGranted,
                pendingApproval = pendingApproval,
                activeReceiverName = receiverName,
                validationMessage = null,
                isScreenDimmed = local.isScreenDimmed,
                isZoomTrayOpen = local.isZoomTrayOpen,
                isPermissionDialogOpen = local.isPermissionDialogOpen,
            ),
        )
        WebcamUiState(
            preview = fullState.preview,
            connection = fullState.connection,
            camera = fullState.camera,
            isScreenDimmed = fullState.isScreenDimmed,
            isZoomTrayOpen = fullState.isZoomTrayOpen,
            cameraPermissionGranted = fullState.cameraPermissionGranted,
            isPermissionDialogOpen = local.isPermissionDialogOpen,
            failureDiagnostics = fullState.failureDiagnostics,
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

    private fun copyDiagnostics() {
        val details = uiState.value.failureDiagnostics ?: return
        effectFlow.tryEmit(SenderUiEffect.CopyDiagnostics(details))
    }

    private data class LocalWebcamState(
        val codecPreference: CodecPreference = CodecPreference.AUTO_PREFER_H265,
        val profile: VideoProfile = VideoProfiles.default,
        val cameraPermissionGranted: Boolean = false,
        val isScreenDimmed: Boolean = false,
        val isZoomTrayOpen: Boolean = false,
        val isPermissionDialogOpen: Boolean = false,
    )

    private companion object {
        const val EFFECT_BUFFER_CAPACITY = 4
    }
}
