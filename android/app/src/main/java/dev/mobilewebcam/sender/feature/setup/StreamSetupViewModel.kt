package dev.mobilewebcam.sender.feature.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.mobilewebcam.sender.R
import dev.mobilewebcam.sender.app.model.SelectOptionUi
import dev.mobilewebcam.sender.app.model.CameraControlsUiStateMapper
import dev.mobilewebcam.sender.app.model.StreamPresentationMapper
import dev.mobilewebcam.sender.app.model.StreamPresentationSnapshot
import dev.mobilewebcam.sender.app.model.UiText
import dev.mobilewebcam.sender.app.model.SenderScreenAction
import dev.mobilewebcam.sender.connection.SenderConnectionCoordinator
import dev.mobilewebcam.sender.media.camera.CameraController
import dev.mobilewebcam.sender.model.StreamOrientation
import dev.mobilewebcam.sender.model.StreamState
import dev.mobilewebcam.sender.model.ReceiverCapabilities
import dev.mobilewebcam.sender.model.SenderSettingsRepository
import dev.mobilewebcam.sender.model.SenderSettings
import dev.mobilewebcam.sender.model.ReceiverProbeState
import dev.mobilewebcam.sender.model.VideoProfile
import dev.mobilewebcam.sender.session.VideoProfiles
import dev.mobilewebcam.sender.media.camera.CameraInteractionState
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
) : ViewModel() {
    private val validationMessage = MutableStateFlow<String?>(null)
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
    ) { inputs, cameraInteraction ->
        val streamState = inputs.streamState
        val receiverName = inputs.receiverName
        val configuredSettings = inputs.configuredSettings
        val validation = inputs.validation
        val receiverProbeState = inputs.receiverProbeState
        val receiverCapabilities = (receiverProbeState as? ReceiverProbeState.Available)?.capabilities
        val selectedProfileSupported = (receiverProbeState as? ReceiverProbeState.Available)
            ?.capabilities
            ?.supports(configuredSettings.profile)
            ?: false
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
            receiverName = receiverName?.let(UiText::Plain),
            profileOptions = qualityOptions(configuredSettings.profile, receiverCapabilities),
            frameRateOptions = frameRateOptions(configuredSettings.profile, receiverCapabilities),
            orientationOptions = StreamOrientation.entries.map { orientation ->
                SelectOptionUi(
                    key = orientation.name,
                    label = StreamPresentationMapper.orientationLabel(orientation),
                    isSelected = orientation == configuredSettings.streamOrientation,
                )
            },
            stabilization = CameraControlsUiStateMapper.map(cameraInteraction).stabilization,
            selectedProfile = configuredSettings.profile,
            selectedOrientation = configuredSettings.streamOrientation,
            selectedProfileSupported = selectedProfileSupported,
            validationMessage = validation?.let(UiText::Plain),
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
        viewModelScope.launch(Dispatchers.Default) {
            runCatching { cameraController.prepareCamera() }
        }
    }

    init {
        viewModelScope.launch {
            coordinator.probeReceiver()
        }
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

    fun onAction(action: SenderScreenAction) {
        when (action) {
            is SenderScreenAction.ProfileSelected -> selectProfile(action.profileId)
            is SenderScreenAction.FrameRateSelected -> selectFrameRate(action.fps)
            is SenderScreenAction.StabilizationChanged -> setStabilizationEnabled(action.enabled)
            is SenderScreenAction.StreamOrientationSelected -> settings.updateStreamOrientation(action.orientation)
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

    private fun setStabilizationEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.Default) {
            cameraController.setStabilizationEnabled(enabled)
        }
    }

    private fun startStream() {
        validationMessage.value = null
        viewModelScope.launch {
            coordinator.startStream().onFailure { failure ->
                validationMessage.value = failure.message
            }
        }
    }

    private fun checkReceiver() {
        validationMessage.value = null
        viewModelScope.launch {
            coordinator.probeReceiver()
        }
    }

    private fun receiverReadiness(state: ReceiverProbeState): ReceiverReadinessUiState = when (state) {
        ReceiverProbeState.Idle,
        ReceiverProbeState.Checking,
        -> ReceiverReadinessUiState.Checking
        is ReceiverProbeState.Available -> ReceiverReadinessUiState.Ready(
            receiverName = UiText.Plain(state.capabilities.displayName),
            address = UiText.Plain(state.endpoint.host),
        )
        is ReceiverProbeState.Unavailable -> ReceiverReadinessUiState.Unavailable(
            message = UiText.Resource(R.string.receiver_not_found_support),
        )
    }

    private fun qualityOptions(
        selectedProfile: VideoProfile,
        receiverCapabilities: ReceiverCapabilities?,
    ): List<SelectOptionUi> {
        val profiles = if (VideoProfiles.qualityProfiles.any { profile ->
                sameResolution(profile, selectedProfile)
            }) {
            VideoProfiles.qualityProfiles
        } else {
            listOf(selectedProfile) + VideoProfiles.qualityProfiles
        }
        return profiles.map { profile ->
            SelectOptionUi(
                key = profile.id,
                label = StreamPresentationMapper.videoProfileLabel(profile),
                isSelected = sameResolution(profile, selectedProfile),
                isEnabled = receiverCapabilities?.let {
                    VideoProfiles.profilesForResolution(profile).any { candidate ->
                        it.supports(candidate)
                    }
                } ?: true,
            )
        }
    }

    private fun frameRateOptions(
        selectedProfile: VideoProfile,
        receiverCapabilities: ReceiverCapabilities?,
    ): List<SelectOptionUi> = VideoProfiles.profilesForResolution(selectedProfile)
        .distinctBy { profile -> profile.fps }
        .sortedBy { profile -> profile.fps }
        .map { profile ->
            SelectOptionUi(
                key = profile.fps.toString(),
                label = UiText.Resource(R.string.frame_rate_option, listOf(profile.fps)),
                isSelected = profile.fps == selectedProfile.fps,
                isEnabled = receiverCapabilities?.supports(profile) ?: true,
            )
        }

    private fun sameResolution(
        left: VideoProfile,
        right: VideoProfile,
    ): Boolean = left.width == right.width && left.height == right.height

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
}

sealed interface StreamSetupUiEffect {
    data class NavigateToWebcam(val orientation: StreamOrientation) : StreamSetupUiEffect
}
