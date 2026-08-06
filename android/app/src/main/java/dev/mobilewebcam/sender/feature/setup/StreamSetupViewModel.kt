package dev.mobilewebcam.sender.feature.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.mobilewebcam.sender.R
import dev.mobilewebcam.sender.app.model.SelectOptionUi
import dev.mobilewebcam.sender.app.model.StreamPresentationMapper
import dev.mobilewebcam.sender.app.model.StreamPresentationSnapshot
import dev.mobilewebcam.sender.app.model.UiText
import dev.mobilewebcam.sender.app.model.SenderScreenAction
import dev.mobilewebcam.sender.connection.SenderConnectionCoordinator
import dev.mobilewebcam.sender.model.StreamOrientation
import dev.mobilewebcam.sender.model.StreamState
import dev.mobilewebcam.sender.model.SenderSettingsRepository
import dev.mobilewebcam.sender.model.ReceiverProbeState
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
import javax.inject.Inject

@HiltViewModel
class StreamSetupViewModel @Inject constructor(
    private val coordinator: SenderConnectionCoordinator,
    private val settings: SenderSettingsRepository,
) : ViewModel() {
    private val validationMessage = MutableStateFlow<String?>(null)
    private val effectFlow = MutableSharedFlow<StreamSetupUiEffect>(extraBufferCapacity = EFFECT_BUFFER_CAPACITY)

    val uiState: StateFlow<StreamSetupUiState> = combine(
        coordinator.streamState,
        coordinator.activeReceiverName,
        settings.state,
        validationMessage,
        coordinator.receiverProbeState,
    ) { streamState, receiverName, configuredSettings, validation, receiverProbeState ->
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
            profileOptions = VideoProfiles.normal.map { profile ->
                SelectOptionUi(
                    key = profile.id,
                    label = StreamPresentationMapper.videoProfileLabel(profile),
                    isSelected = profile.id == configuredSettings.profile.id,
                )
            },
            orientationOptions = StreamOrientation.entries.map { orientation ->
                SelectOptionUi(
                    key = orientation.name,
                    label = UiText.Resource(
                        if (orientation.isPortrait) R.string.portrait else R.string.landscape,
                    ),
                    isSelected = orientation == configuredSettings.streamOrientation,
                )
            },
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
            is SenderScreenAction.StreamOrientationSelected -> settings.updateStreamOrientation(action.orientation)
            SenderScreenAction.CheckReceiver -> checkReceiver()
            SenderScreenAction.StartStream -> startStream()
            else -> Unit
        }
    }

    private fun selectProfile(profileId: String) {
        VideoProfiles.normal.firstOrNull { profile -> profile.id == profileId }
            ?.let(settings::updateProfile)
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

    private companion object {
        const val EFFECT_BUFFER_CAPACITY = 1
        const val FIRST_STATE_TO_SKIP = 1
    }
}

sealed interface StreamSetupUiEffect {
    data class NavigateToWebcam(val orientation: StreamOrientation) : StreamSetupUiEffect
}
