package dev.mobilewebcam.sender.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mobilewebcam.sender.camera.CameraController
import dev.mobilewebcam.sender.camera.CameraPreviewSurface
import dev.mobilewebcam.sender.camera.PhysicalLensOption
import dev.mobilewebcam.sender.discovery.SenderConnectionCoordinator
import dev.mobilewebcam.sender.model.CodecPreference
import dev.mobilewebcam.sender.model.VideoProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SenderViewModel(
    private val coordinator: SenderConnectionCoordinator,
    private val cameraController: CameraController,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SenderUiState())

    val uiState: StateFlow<SenderUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            coordinator.streamState.collectLatest { streamState ->
                mutableState.update {
                    it.copy(
                        streamState = streamState,
                        validationMessage = null,
                        failureDetails = null,
                    )
                }
            }
        }
        viewModelScope.launch {
            coordinator.pendingApproval.collectLatest { pendingApproval ->
                mutableState.update { it.copy(pendingApproval = pendingApproval) }
            }
        }
        viewModelScope.launch {
            coordinator.activeReceiverName.collectLatest { receiverName ->
                mutableState.update { it.copy(activeReceiverName = receiverName) }
            }
        }
        viewModelScope.launch {
            cameraController.state.collectLatest { cameraState ->
                mutableState.update { it.copy(cameraInteraction = cameraState) }
            }
        }
    }

    fun updateCodecPreference(value: CodecPreference) {
        mutableState.update {
            it.copy(codecPreference = value, validationMessage = null, failureDetails = null)
        }
        coordinator.updateConfiguration(value, uiState.value.profile)
    }

    fun updateProfile(value: VideoProfile) {
        mutableState.update {
            it.copy(profile = value, validationMessage = null, failureDetails = null)
        }
        coordinator.updateConfiguration(uiState.value.codecPreference, value)
    }

    fun setCameraPermissionGranted(granted: Boolean) {
        mutableState.update { it.copy(cameraPermissionGranted = granted) }
    }

    fun setPreviewSurface(surface: CameraPreviewSurface?) {
        viewModelScope.launch(Dispatchers.Default) { cameraController.setPreviewSurface(surface) }
    }

    fun setZoomRatio(zoomRatio: Float) {
        viewModelScope.launch(Dispatchers.Default) { cameraController.setZoomRatio(zoomRatio) }
    }

    fun resetZoom() {
        viewModelScope.launch(Dispatchers.Default) { cameraController.resetZoom() }
    }

    fun setStabilizationEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.Default) {
            cameraController.setStabilizationEnabled(enabled)
        }
    }

    fun selectPhysicalLens(lens: PhysicalLensOption) {
        viewModelScope.launch(Dispatchers.Default) { cameraController.selectPhysicalLens(lens) }
    }

    fun stop() {
        viewModelScope.launch {
            coordinator.stop()
        }
    }

    fun approvePending() {
        viewModelScope.launch { coordinator.approvePending() }
    }

    fun rejectPending() {
        viewModelScope.launch { coordinator.rejectPending() }
    }

}
