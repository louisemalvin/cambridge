package dev.mobilewebcam.sender.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mobilewebcam.sender.camera.CameraController
import dev.mobilewebcam.sender.camera.CameraPreviewSurface
import dev.mobilewebcam.sender.config.VideoProfiles
import dev.mobilewebcam.sender.discovery.SenderConnectionCoordinator
import dev.mobilewebcam.sender.model.CodecPreference
import dev.mobilewebcam.sender.model.VideoProfile
import dev.mobilewebcam.sender.ui.model.SenderScreenAction
import dev.mobilewebcam.sender.ui.model.SenderScreenState
import dev.mobilewebcam.sender.ui.model.SenderUiEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SenderViewModel(
    private val coordinator: SenderConnectionCoordinator,
    private val cameraController: CameraController,
) : ViewModel() {
    private val localState = MutableStateFlow(LocalSenderState())
    private val effectFlow = MutableSharedFlow<SenderUiEffect>(
        extraBufferCapacity = EFFECT_BUFFER_CAPACITY,
    )

    val uiState: StateFlow<SenderScreenState> = combine(
        coordinator.streamState,
        coordinator.pendingApproval,
        coordinator.activeReceiverName,
        cameraController.state,
        localState,
    ) { streamState, pendingApproval, receiverName, cameraInteraction, local ->
        SenderScreenStateMapper.map(
            SenderDomainSnapshot(
                codecPreference = local.codecPreference,
                profile = local.profile,
                cameraInteraction = cameraInteraction,
                streamState = streamState,
                cameraPermissionGranted = local.cameraPermissionGranted,
                pendingApproval = pendingApproval,
                activeReceiverName = receiverName,
                validationMessage = local.validationMessage,
                isScreenDimmed = local.isScreenDimmed,
                isSettingsSheetOpen = local.isSettingsSheetOpen,
                isZoomTrayOpen = local.isZoomTrayOpen,
                isPermissionDialogOpen = local.isPermissionDialogOpen,
            ),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = SenderScreenState(),
    )

    val effects = effectFlow.asSharedFlow()

    fun onAction(action: SenderScreenAction) {
        when (action) {
            SenderScreenAction.ToggleScreenDimmed -> localState.update {
                it.copy(isScreenDimmed = !it.isScreenDimmed)
            }
            SenderScreenAction.OpenSettings -> localState.update {
                it.copy(isSettingsSheetOpen = true, isZoomTrayOpen = false)
            }
            SenderScreenAction.CloseSettings -> localState.update {
                it.copy(isSettingsSheetOpen = false)
            }
            SenderScreenAction.ToggleZoomTray -> localState.update {
                it.copy(isZoomTrayOpen = !it.isZoomTrayOpen, isSettingsSheetOpen = false)
            }
            SenderScreenAction.CloseZoomTray -> localState.update {
                it.copy(isZoomTrayOpen = false)
            }
            is SenderScreenAction.ZoomChanged -> setZoomRatio(action.ratio)
            SenderScreenAction.ResetZoom -> resetZoom()
            is SenderScreenAction.LensSelected -> selectPhysicalLens(action.key)
            is SenderScreenAction.StabilizationChanged -> setStabilizationEnabled(action.enabled)
            is SenderScreenAction.CodecSelected -> updateCodecPreference(action.key)
            is SenderScreenAction.ProfileSelected -> updateProfile(action.key)
            SenderScreenAction.OpenPermissionDialog -> localState.update {
                it.copy(isPermissionDialogOpen = true)
            }
            SenderScreenAction.DismissPermissionDialog -> localState.update {
                it.copy(isPermissionDialogOpen = false)
            }
            SenderScreenAction.RequestCameraPermission -> requestCameraPermission()
            SenderScreenAction.ApprovePending -> approvePending()
            SenderScreenAction.RejectPending -> rejectPending()
            SenderScreenAction.StopStream -> stop()
            SenderScreenAction.CopyDiagnostics -> copyDiagnostics()
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

    private fun requestCameraPermission() {
        localState.update { it.copy(isPermissionDialogOpen = false) }
        effectFlow.tryEmit(SenderUiEffect.RequestCameraPermission)
    }

    private fun approvePending() {
        viewModelScope.launch { coordinator.approvePending() }
    }

    private fun rejectPending() {
        viewModelScope.launch { coordinator.rejectPending() }
    }

    private fun stop() {
        viewModelScope.launch { coordinator.stop() }
    }

    private fun copyDiagnostics() {
        val details = uiState.value.failureDiagnostics ?: return
        effectFlow.tryEmit(SenderUiEffect.CopyDiagnostics(details))
    }

    private data class LocalSenderState(
        val codecPreference: CodecPreference = CodecPreference.AUTO_PREFER_H265,
        val profile: VideoProfile = VideoProfiles.default,
        val cameraPermissionGranted: Boolean = false,
        val validationMessage: String? = null,
        val isScreenDimmed: Boolean = false,
        val isSettingsSheetOpen: Boolean = false,
        val isZoomTrayOpen: Boolean = false,
        val isPermissionDialogOpen: Boolean = false,
    )

    private companion object {
        const val EFFECT_BUFFER_CAPACITY = 4
    }
}
