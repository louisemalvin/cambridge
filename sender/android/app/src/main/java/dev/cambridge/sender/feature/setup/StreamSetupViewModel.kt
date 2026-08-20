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
import dev.cambridge.sender.model.StreamVideoConfiguration
import dev.cambridge.sender.model.SenderSettingsRepository
import dev.cambridge.sender.model.SenderSettings
import dev.cambridge.sender.model.ReceiverProbeState
import dev.cambridge.sender.model.ReceiverCandidate
import dev.cambridge.sender.model.VideoProfile
import dev.cambridge.sender.model.EncoderCapability
import dev.cambridge.sender.session.VideoProfiles
import dev.cambridge.sender.media.camera.CameraInteractionState
import dev.cambridge.sender.media.camera.CameraStabilizationMode
import dev.cambridge.sender.media.capabilities.EncoderCapabilityProbe
import dev.cambridge.sender.logging.AppLogger
import dev.cambridge.sender.session.PhoneVideoModeCapability
import dev.cambridge.sender.session.EncoderCatalog
import dev.cambridge.sender.session.VideoConfigurationResolver
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
        val selectedEncoderName = configuredSettings.videoConfiguration.encoderName
        val validation = inputs.validation
        val receiverProbeState = inputs.receiverProbeState
        val selectedCapability = capabilities.firstOrNull { it.mode.id == configuredSettings.profile.id }
        val selectedProfileSupported = selectedCapability?.let { capability ->
            val minimum = capability.encoderMinimumBitrateBps
            val maximum = capability.encoderMaximumBitrateBps
            capability.isSupported &&
                minimum != null &&
                maximum != null &&
                configuredSettings.profile.clampToStep(
                    configuredSettings.bitrateBps,
                    minimum,
                    maximum,
                ) == configuredSettings.bitrateBps
        } == true
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
            encoderOptions = encoderOptions(
                capabilityState.eligibleEncoders,
                selectedEncoderName,
            ),
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
            selectedEncoderName = selectedEncoderName,
            selectedProfileSupported = selectedProfileSupported,
            videoCapabilitiesReady = capabilityState.isReady,
            bitrate = bitrateUiState(configuredSettings.bitrateBps, selectedCapability),
            validationMessage = validation?.let(UiText::Plain)
                ?: capabilityError
                ?: selectedCapability?.takeIf { !it.isSupported || !selectedProfileSupported }
                    ?.let { UiText.Resource(R.string.video_mode_unavailable) },
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
                VideoConfigurationResolver.resolve(
                    current = settings.state.value.videoConfiguration,
                    modes = modes,
                    cameraSupportedModeIds = cameraSupported,
                    encoders = encoderCapabilities,
                ) ?: throw IllegalStateException("No complete H.264 encoder mode is available")
            }.onSuccess { resolved ->
                if (settings.state.value.videoConfiguration != resolved.configuration) {
                    settings.updateVideoConfiguration(resolved.configuration)
                }
                logger.event(
                    "video_capabilities_resolved",
                    mapOf(
                        "selectedEncoder" to resolved.selectedEncoder.implementationName,
                        "eligibleEncoders" to resolved.eligibleEncoders.joinToString { it.implementationName },
                        "modes" to resolved.capabilities.joinToString(separator = ",") { capability ->
                            listOf(capability.mode.id, capability.isSupported, capability.reason.orEmpty())
                                .joinToString(separator = ":")
                        },
                        "encoderEvidence" to resolved.eligibleEncoders.joinToString(separator = ",") { encoder ->
                            listOf(
                                encoder.implementationName,
                                encoder.acceleration,
                                encoder.surfaceInputSupported,
                                encoder.cbrSupported,
                                encoder.modes.joinToString(separator = ";") { mode ->
                                    listOf(
                                        mode.modeId,
                                        mode.sizeAndRateSupported,
                                        mode.minimumBitrateBps,
                                        mode.maximumBitrateBps,
                                    ).joinToString(separator = ":")
                                },
                            ).joinToString(separator = ":")
                        },
                    ),
                )
                videoCapabilityState.value = VideoCapabilityState(
                    capabilities = resolved.capabilities,
                    eligibleEncoders = resolved.eligibleEncoders,
                    selectedEncoder = resolved.selectedEncoder,
                    cameraSupportedModeIds = resolved.capabilities
                        .filter(PhoneVideoModeCapability::cameraSupported)
                        .mapTo(mutableSetOf()) { it.mode.id },
                    probedEncoders = resolved.eligibleEncoders,
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
            is SenderScreenAction.EncoderSelected -> selectEncoder(action.implementationName)
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
        val selectedQuality = VideoProfiles.all.firstOrNull { profile -> profile.id == profileId }
            ?: return
        val current = settings.state.value.videoConfiguration
        val selectedProfile = VideoProfiles.profilesForResolution(selectedQuality)
            .firstOrNull { profile ->
                profile.fps == current.profile.fps && isSupported(profile.id)
            }
            ?: VideoProfiles.profilesForResolution(selectedQuality)
                .firstOrNull { profile -> isSupported(profile.id) }
            ?: return
        applyVideoConfiguration(current.copy(profile = selectedProfile))
    }

    private fun selectFrameRate(fps: Int) {
        val current = settings.state.value.videoConfiguration
        val selectedProfile = VideoProfiles.profileForResolution(
            width = current.profile.width,
            height = current.profile.height,
            fps = fps,
        )?.takeIf { isSupported(it.id) } ?: return
        applyVideoConfiguration(current.copy(profile = selectedProfile))
    }

    private fun selectEncoder(implementationName: String) {
        if (videoCapabilityState.value.eligibleEncoders.none {
                it.implementationName == implementationName
            }
        ) return
        applyVideoConfiguration(
            settings.state.value.videoConfiguration.copy(encoderName = implementationName),
        )
    }

    private fun selectBitrate(bitrateBps: Int) {
        val current = settings.state.value.videoConfiguration
        val capability = videoCapabilityState.value.capabilities
            .firstOrNull { it.mode.id == current.profile.id }
            ?: return
        val normalized = capability.encoderMinimumBitrateBps?.let { minimum ->
            capability.encoderMaximumBitrateBps?.let { maximum ->
                current.profile.clampToStep(
                    valueBps = bitrateBps,
                    encoderMinimumBps = minimum,
                    encoderMaximumBps = maximum,
                )
            }
        } ?: return
        applyVideoConfiguration(current.copy(bitrateBps = normalized))
    }

    private fun applyVideoConfiguration(configuration: StreamVideoConfiguration) {
        val capabilityState = videoCapabilityState.value
        val resolved = VideoConfigurationResolver.resolve(
            current = configuration,
            modes = VideoProfiles.all,
            cameraSupportedModeIds = capabilityState.cameraSupportedModeIds,
            encoders = capabilityState.probedEncoders,
        ) ?: return
        settings.updateVideoConfiguration(resolved.configuration)
        videoCapabilityState.value = capabilityState.copy(
            capabilities = resolved.capabilities,
            eligibleEncoders = resolved.eligibleEncoders,
            selectedEncoder = resolved.selectedEncoder,
        )
    }

    private fun isSupported(modeId: String): Boolean = videoCapabilityState.value.capabilities
        .firstOrNull { it.mode.id == modeId }
        ?.isSupported == true

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

    private fun encoderOptions(
        encoders: List<EncoderCapability>,
        selectedEncoderName: String?,
    ): List<SelectOptionUi> {
        val defaultEncoderName = EncoderCatalog.default(encoders)?.implementationName
        return encoders.map { encoder ->
            val sameAcceleration = encoders.filter { it.acceleration == encoder.acceleration }
            val ordinal = sameAcceleration.indexOf(encoder) + 1
            val isDefault = encoder.implementationName == defaultEncoderName
            val labelResource = when (encoder.acceleration) {
                dev.cambridge.sender.model.EncoderAcceleration.HARDWARE -> when {
                    ordinal == FIRST_ENCODER_ORDINAL && isDefault -> R.string.encoder_hardware_default
                    ordinal == FIRST_ENCODER_ORDINAL -> R.string.encoder_hardware
                    isDefault -> R.string.encoder_hardware_number_default
                    else -> R.string.encoder_hardware_number
                }
                dev.cambridge.sender.model.EncoderAcceleration.SOFTWARE -> when {
                    ordinal == FIRST_ENCODER_ORDINAL && isDefault -> R.string.encoder_software_default
                    ordinal == FIRST_ENCODER_ORDINAL -> R.string.encoder_software
                    isDefault -> R.string.encoder_software_number_default
                    else -> R.string.encoder_software_number
                }
                dev.cambridge.sender.model.EncoderAcceleration.UNKNOWN -> R.string.encoder_unknown
            }
            val arguments = if (ordinal == FIRST_ENCODER_ORDINAL) emptyList() else listOf(ordinal)
            SelectOptionUi(
                key = encoder.implementationName,
                label = UiText.Resource(labelResource, arguments),
                isSelected = encoder.implementationName == selectedEncoderName,
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
        val minimumBitrate = capability?.encoderMinimumBitrateBps
        val maximumBitrate = capability?.encoderMaximumBitrateBps
        if (
            capability == null ||
            !capability.isSupported ||
            range.isEmpty() ||
            minimumBitrate == null ||
            maximumBitrate == null
        ) return BitrateUiState()
        val selected = capability.mode.clampToStep(
            valueBps = selectedBitrateBps,
            encoderMinimumBps = minimumBitrate,
            encoderMaximumBps = maximumBitrate,
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
        val eligibleEncoders: List<EncoderCapability> = emptyList(),
        val selectedEncoder: EncoderCapability? = null,
        val cameraSupportedModeIds: Set<String> = emptySet(),
        val probedEncoders: List<EncoderCapability> = emptyList(),
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

private const val FIRST_ENCODER_ORDINAL = 1
