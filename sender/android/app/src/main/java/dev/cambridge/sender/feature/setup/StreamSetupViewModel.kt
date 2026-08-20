package dev.cambridge.sender.feature.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.cambridge.sender.R
import dev.cambridge.sender.app.model.SelectOptionUi
import dev.cambridge.sender.app.model.CameraControlsUiStateMapper
import dev.cambridge.sender.app.model.StreamPresentationMapper
import dev.cambridge.sender.app.model.StreamPresentationSnapshot
import dev.cambridge.sender.app.model.UiText
import dev.cambridge.sender.app.model.SenderScreenAction
import dev.cambridge.sender.connection.SenderConnectionCoordinator
import dev.cambridge.sender.media.camera.CameraController
import dev.cambridge.sender.media.camera.CameraPermissionRequiredException
import dev.cambridge.sender.media.camera.AntiFlickerMode
import dev.cambridge.sender.model.StreamOrientation
import dev.cambridge.sender.model.StreamFailure
import dev.cambridge.sender.model.StreamFailureException
import dev.cambridge.sender.model.StreamState
import dev.cambridge.sender.model.SenderSettingsRepository
import dev.cambridge.sender.model.SenderSettings
import dev.cambridge.sender.model.ReceiverProbeState
import dev.cambridge.sender.model.ReceiverCandidate
import dev.cambridge.sender.model.VideoProfile
import dev.cambridge.sender.session.VideoProfiles
import dev.cambridge.sender.media.camera.CameraInteractionState
import dev.cambridge.sender.media.camera.CameraStabilizationMode
import dev.cambridge.sender.media.capabilities.EncoderCapabilityProbe
import dev.cambridge.sender.logging.AppLogger
import dev.cambridge.sender.session.PhoneVideoCapabilities
import dev.cambridge.sender.session.PhoneVideoModeCapability
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

@HiltViewModel
class StreamSetupViewModel @Inject constructor(
    private val coordinator: SenderConnectionCoordinator,
    private val cameraController: CameraController,
    private val settings: SenderSettingsRepository,
    private val encoderCapabilityProbe: EncoderCapabilityProbe,
    private val logger: AppLogger,
) : ViewModel() {
    private val validationMessage = MutableStateFlow<String?>(null)
    private val manualReceiver = MutableStateFlow(
        ManualReceiverInput(host = settings.state.value.receiverEndpoint?.host.orEmpty()),
    )
    private val videoCapabilityState = MutableStateFlow(VideoCapabilityState())
    private val effectFlow = MutableSharedFlow<StreamSetupUiEffect>(extraBufferCapacity = EFFECT_BUFFER_CAPACITY)

    private val setupInputs = combine(
        coordinator.streamState,
        coordinator.activeReceiverName,
        settings.state,
        validationMessage,
        coordinator.receiverProbeState,
    ) { streamState, receiverName, configuredSettings, validation, receiverProbeState ->
        SetupInputs(
            streamState = streamState,
            receiverName = receiverName,
            configuredSettings = configuredSettings,
            validation = validation,
            receiverProbeState = receiverProbeState,
        )
    }

    val uiState: StateFlow<StreamSetupUiState> = combine(
        setupInputs,
        cameraController.state,
        videoCapabilityState,
        manualReceiver,
        coordinator.receiverCandidates,
    ) { inputs, cameraInteraction, capabilityState, configuredManualReceiver, receiverCandidates ->
        val capabilities = capabilityState.capabilities
        val capabilityError = capabilityState.error?.let(UiText::Plain)
            ?: capabilityState.takeIf { it.isReady && it.capabilities.isEmpty() }
                ?.let { UiText.Resource(R.string.camera_capabilities_unavailable) }
        val streamState = inputs.streamState
        val receiverName = inputs.receiverName
        val configuredSettings = inputs.configuredSettings
        val validation = inputs.validation
        val receiverProbeState = inputs.receiverProbeState
        val selectedCapability = capabilities.firstOrNull { it.mode.id == configuredSettings.profile.id }
        val selectedProfileSupported = selectedCapability?.isSupported == true &&
            configuredSettings.bitrateBps in selectedCapability.bitrateRange
        val cameraControls = CameraControlsUiStateMapper.map(cameraInteraction)
        StreamSetupUiState(
            connection = StreamPresentationMapper.connection(
                StreamPresentationSnapshot(
                    profile = configuredSettings.profile,
                    cameraInteraction = CameraInteractionState.inactive(),
                    streamState = streamState,
                    activeReceiverName = receiverName,
                    validationMessage = validation,
                ),
            ),
            receiverReadiness = receiverReadiness(receiverProbeState),
            manualReceiverHost = configuredManualReceiver.host,
            manualReceiverHostError = configuredManualReceiver.error,
            receiverOptions = receiverOptions(receiverCandidates, receiverProbeState),
            isManualReceiverInputVisible = configuredManualReceiver.isVisible ||
                receiverProbeState is ReceiverProbeState.Unavailable,
            resolutionOptions = resolutionOptions(configuredSettings.profile, capabilities),
            frameRateOptions = frameRateOptions(configuredSettings.profile, capabilities),
            orientationOptions = StreamOrientation.entries.map { orientation ->
                SelectOptionUi(
                    key = orientation.name,
                    label = StreamPresentationMapper.orientationLabel(orientation),
                    isSelected = orientation == configuredSettings.streamOrientation,
                )
            },
            stabilization = cameraControls.stabilization,
            antiFlicker = cameraControls.antiFlicker,
            selectedProfile = configuredSettings.profile,
            selectedOrientation = configuredSettings.streamOrientation,
            selectedProfileSupported = selectedProfileSupported,
            videoCapabilitiesReady = capabilityState.isReady,
            bitrate = bitrateUiState(configuredSettings.bitrateBps, selectedCapability),
            validationMessage = validation?.let(UiText::Plain)
                ?: capabilityError
                ?: selectedCapability?.takeIf { !it.isSupported || !selectedProfileSupported }
                    ?.let { capability ->
                        capability.reason?.let(UiText::Plain)
                            ?: UiText.Resource(R.string.video_mode_unavailable)
                    },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = StreamSetupUiState(
            selectedProfile = VideoProfiles.default,
            selectedOrientation = StreamOrientation.LANDSCAPE,
        ),
    )

    val effects = effectFlow.asSharedFlow()

    fun prepareCamera() {
        videoCapabilityState.value = VideoCapabilityState()
        viewModelScope.launch(Dispatchers.Default) {
            runCatching {
                cameraController.prepareCamera()
                cameraController.setStabilizationMode(settings.state.value.stabilizationMode)
                val modes = VideoProfiles.all
                val cameraSupported = cameraController.supportedVideoModes(modes)
                val encoderCapabilities = encoderCapabilityProbe.getCapabilities(modes)
                PhoneVideoCapabilities.resolve(
                    modes = modes,
                    cameraSupportedModeIds = cameraSupported,
                    encoderCapabilities = encoderCapabilities,
                )
            }.onSuccess { capabilities ->
                logger.event(
                    "video_capabilities_resolved",
                    mapOf(
                        "modes" to capabilities.joinToString(separator = ",") { capability ->
                            listOf(
                                capability.mode.id,
                                capability.isSupported,
                                capability.reason.orEmpty(),
                            ).joinToString(separator = ":")
                        },
                    ),
                )
                videoCapabilityState.value = VideoCapabilityState(
                    capabilities = capabilities,
                    isReady = true,
                )
            }.onFailure { failure ->
                logger.warn("video capability probe failed", failure)
                if (failure.hasCameraPermissionCause()) {
                    effectFlow.tryEmit(StreamSetupUiEffect.CameraPermissionRequired)
                } else {
                    videoCapabilityState.value = VideoCapabilityState(
                        isReady = true,
                        error = failure.message?.takeIf(String::isNotBlank),
                    )
                }
            }
        }
    }

    fun clearCameraCapabilities() {
        videoCapabilityState.value = VideoCapabilityState()
    }

    init {
        coordinator.startReceiverDiscovery()
        viewModelScope.launch {
            var previousState = coordinator.streamState.value
            coordinator.streamState.drop(FIRST_STATE_TO_SKIP).collect { currentState ->
                if (currentState is StreamState.Streaming && previousState !is StreamState.Streaming) {
                    effectFlow.emit(
                        StreamSetupUiEffect.NavigateToWebcam(
                            orientation = settings.state.value.streamOrientation,
                        ),
                    )
                }
                previousState = currentState
            }
        }
    }

    override fun onCleared() {
        coordinator.stopReceiverDiscovery()
        super.onCleared()
    }

    fun onAction(action: SenderScreenAction) {
        when (action) {
            is SenderScreenAction.ProfileSelected -> selectProfile(action.profileId)
            is SenderScreenAction.FrameRateSelected -> selectFrameRate(action.fps)
            is SenderScreenAction.BitrateSelected -> selectBitrate(action.bitrateBps)
            is SenderScreenAction.StabilizationModeChanged -> setStabilizationMode(action.mode)
            is SenderScreenAction.AntiFlickerChanged -> setAntiFlickerMode(action.mode)
            is SenderScreenAction.StreamOrientationSelected -> settings.updateStreamOrientation(action.orientation)
            is SenderScreenAction.ReceiverHostChanged -> {
                manualReceiver.value = manualReceiver.value.copy(host = action.host, error = null)
                validationMessage.value = null
            }
            is SenderScreenAction.ReceiverSelected -> selectReceiver(action.receiverId)
            SenderScreenAction.ShowManualReceiverInput -> {
                manualReceiver.value = manualReceiver.value.copy(isVisible = true, error = null)
            }
            SenderScreenAction.HideManualReceiverInput -> {
                manualReceiver.value = manualReceiver.value.copy(isVisible = false, error = null)
            }
            SenderScreenAction.UseManualReceiverHost -> useManualReceiverHost()
            SenderScreenAction.CheckReceiver -> checkReceiver()
            SenderScreenAction.StartStream -> startStream()
            else -> Unit
        }
    }

    private fun selectProfile(profileId: String) {
        val currentProfile = settings.state.value.profile
        val selectedQuality = VideoProfiles.all.firstOrNull { profile -> profile.id == profileId }
            ?: return
        val selectedProfile = VideoProfiles.profileForResolution(
            width = selectedQuality.width,
            height = selectedQuality.height,
            fps = currentProfile.fps,
        ) ?: selectedQuality
        settings.updateProfile(selectedProfile)
    }

    private fun selectFrameRate(fps: Int) {
        val currentProfile = settings.state.value.profile
        VideoProfiles.profileForResolution(
            width = currentProfile.width,
            height = currentProfile.height,
            fps = fps,
        )?.let(settings::updateProfile)
    }

    private fun selectBitrate(bitrateBps: Int) {
        val current = settings.state.value
        val capability = videoCapabilityState.value.capabilities
            .firstOrNull { it.mode.id == current.profile.id }
            ?: return
        current.profile.clampToStep(
            valueBps = bitrateBps,
            encoderMinimumBps = capability.encoderMinimumBitrateBps,
            encoderMaximumBps = capability.encoderMaximumBitrateBps,
        )?.let(settings::updateBitrate)
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

    private fun startStream() {
        validationMessage.value = null
        viewModelScope.launch {
            coordinator.startStream().onFailure { failure ->
                if ((failure as? StreamFailureException)?.failure == StreamFailure.CameraPermissionDenied) {
                    effectFlow.tryEmit(StreamSetupUiEffect.CameraPermissionRequired)
                } else {
                    validationMessage.value = failure.message
                }
            }
        }
    }

    private fun checkReceiver() {
        validationMessage.value = null
        manualReceiver.value = manualReceiver.value.copy(error = null)
        viewModelScope.launch {
            coordinator.probeReceiver()
        }
    }

    private fun selectReceiver(receiverId: String) {
        validationMessage.value = null
        manualReceiver.value = manualReceiver.value.copy(isVisible = false, error = null)
        viewModelScope.launch {
            coordinator.selectReceiver(receiverId).onFailure { failure ->
                validationMessage.value = failure.message
            }
        }
    }

    private fun useManualReceiverHost() {
        val host = manualReceiver.value.host.trim()
        manualReceiver.value = manualReceiver.value.copy(host = host, error = null, isVisible = true)
        validationMessage.value = null
        viewModelScope.launch {
            coordinator.configureReceiverHost(host)
                .onSuccess {
                    manualReceiver.value = manualReceiver.value.copy(isVisible = false, error = null)
                }
                .onFailure { failure ->
                    manualReceiver.value = manualReceiver.value.copy(
                        isVisible = true,
                        error = UiText.Plain(failure.message.orEmpty()),
                    )
                }
        }
    }

    private fun receiverReadiness(state: ReceiverProbeState): ReceiverReadinessUiState = when (state) {
        ReceiverProbeState.Idle,
        ReceiverProbeState.Checking,
        -> ReceiverReadinessUiState.Checking
        ReceiverProbeState.SelectionRequired -> ReceiverReadinessUiState.SelectionRequired
        is ReceiverProbeState.Available -> ReceiverReadinessUiState.Ready(
            receiverName = UiText.Plain(state.capabilities.displayName),
            address = UiText.Plain(state.endpoint.host),
        )
        is ReceiverProbeState.Unavailable -> ReceiverReadinessUiState.Unavailable(
            message = UiText.Resource(R.string.receiver_not_found_support),
        )
    }

    private fun receiverOptions(
        candidates: List<ReceiverCandidate>,
        state: ReceiverProbeState,
    ): List<SelectOptionUi> {
        val selectedReceiverId = (state as? ReceiverProbeState.Available)?.capabilities?.receiverId
        return candidates.map { candidate ->
            SelectOptionUi(
                key = candidate.capabilities.receiverId,
                label = UiText.Plain(
                    candidate.capabilities.displayName + " · " + candidate.endpoint.host,
                ),
                isSelected = candidate.capabilities.receiverId == selectedReceiverId,
            )
        }
    }

    private fun resolutionOptions(
        selectedProfile: VideoProfile,
        capabilities: List<PhoneVideoModeCapability>,
    ): List<SelectOptionUi> = StreamSetupOptionResolver.resolutionOptions(selectedProfile, capabilities)

    private fun frameRateOptions(
        selectedProfile: VideoProfile,
        capabilities: List<PhoneVideoModeCapability>,
    ): List<SelectOptionUi> = StreamSetupOptionResolver.frameRateOptions(selectedProfile, capabilities)

    private fun bitrateUiState(
        selectedBitrateBps: Int,
        capability: PhoneVideoModeCapability?,
    ): BitrateUiState {
        val range = capability?.bitrateRange ?: IntRange.EMPTY
        if (capability == null || !capability.isSupported || range.isEmpty()) return BitrateUiState()
        val selected = capability.mode.clampToStep(
            valueBps = selectedBitrateBps,
            encoderMinimumBps = capability.encoderMinimumBitrateBps,
            encoderMaximumBps = capability.encoderMaximumBitrateBps,
        ) ?: range.first
        return BitrateUiState(
            isAvailable = true,
            selectedBps = selected,
            minimumBps = range.first,
            maximumBps = range.last,
            stepBps = capability.mode.bitrateStepBps,
        )
    }

    private companion object {
        const val EFFECT_BUFFER_CAPACITY = 1
        const val FIRST_STATE_TO_SKIP = 1
    }

    private data class SetupInputs(
        val streamState: StreamState,
        val receiverName: String?,
        val configuredSettings: SenderSettings,
        val validation: String?,
        val receiverProbeState: ReceiverProbeState,
    )

    private data class ManualReceiverInput(
        val host: String,
        val error: UiText? = null,
        val isVisible: Boolean = false,
    )

    private data class VideoCapabilityState(
        val capabilities: List<PhoneVideoModeCapability> = emptyList(),
        val isReady: Boolean = false,
        val error: String? = null,
    )
}

sealed interface StreamSetupUiEffect {
    data class NavigateToWebcam(val orientation: StreamOrientation) : StreamSetupUiEffect

    data object CameraPermissionRequired : StreamSetupUiEffect
}

private fun Throwable.hasCameraPermissionCause(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is CameraPermissionRequiredException) return true
        current = current.cause
    }
    return false
}
