package dev.mobilewebcam.sender.feature.pairing

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.mobilewebcam.sender.R
import dev.mobilewebcam.sender.app.model.SenderScreenAction
import dev.mobilewebcam.sender.connection.SenderConnectionCoordinator
import dev.mobilewebcam.sender.connection.discovery.ReceiverDiscovery
import dev.mobilewebcam.sender.connection.discovery.ReceiverDiscoveryState
import dev.mobilewebcam.sender.model.SenderSettingsRepository
import dev.mobilewebcam.sender.model.StreamState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PairingViewModel @Inject constructor(
    private val application: Application,
    private val coordinator: SenderConnectionCoordinator,
    private val settings: SenderSettingsRepository,
    private val discovery: ReceiverDiscovery,
) : ViewModel() {
    private val effectFlow = MutableSharedFlow<PairingUiEffect>(
        extraBufferCapacity = EFFECT_BUFFER_CAPACITY,
    )
    private val originFlow = MutableStateFlow(
        ReceiverOriginDraft.from(settings.state.value.receiverEndpoint),
    )
    private val originErrorFlow = MutableStateFlow<String?>(null)

    val receiverOrigin: StateFlow<ReceiverOriginDraft> = originFlow
    val receiverOriginError: StateFlow<String?> = originErrorFlow
    val discoveryState: StateFlow<ReceiverDiscoveryState> = discovery.state

    val uiState: StateFlow<PairingUiState> = kotlinx.coroutines.flow.combine(
        coordinator.streamState,
        coordinator.activeReceiverName,
    ) { streamState, receiverName ->
        PairingUiStateMapper.map(
            PairingDomainSnapshot(
                streamState = streamState,
                activeReceiverName = receiverName,
            ),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = PairingUiState.Idle,
    )

    val effects = effectFlow.asSharedFlow()

    init {
        discovery.start()
        viewModelScope.launch {
            var previousState = coordinator.streamState.value
            coordinator.streamState.drop(FIRST_STATE_TO_SKIP).collect { currentState ->
                PairingUiEffectMapper.map(previousState, currentState)?.let { effect ->
                    effectFlow.emit(effect)
                }
                previousState = currentState
            }
        }
    }

    fun onAction(action: SenderScreenAction) {
        when (action) {
            is SenderScreenAction.ReceiverNameChanged -> {
                originFlow.update { it.copy(name = action.name) }
            }
            is SenderScreenAction.ReceiverHostChanged -> {
                originFlow.update { it.copy(host = action.host) }
            }
            is SenderScreenAction.ReceiverControlPortChanged -> {
                originFlow.update { it.copy(controlPort = action.port) }
            }
            is SenderScreenAction.ReceiverTokenChanged -> {
                originFlow.update { it.copy(token = action.token) }
            }
            is SenderScreenAction.DiscoveredReceiverSelected -> {
                originFlow.value = ReceiverOriginDraft.from(action.endpoint)
                originErrorFlow.value = null
            }
            SenderScreenAction.ConnectReceiver -> connect()
            else -> Unit
        }
    }

    override fun onCleared() {
        discovery.stop()
        super.onCleared()
    }

    private fun connect() {
        val endpoint = originFlow.value.endpointOrNull()
        if (endpoint == null) {
            originErrorFlow.value = application.getString(R.string.receiver_origin_required)
            return
        }
        originErrorFlow.value = null
        viewModelScope.launch {
            coordinator.connectToReceiver(endpoint).onFailure { error ->
                originErrorFlow.value = error.message
                    ?: application.getString(R.string.receiver_connection_failed)
            }
        }
    }

    private companion object {
        const val EFFECT_BUFFER_CAPACITY = 1
        const val FIRST_STATE_TO_SKIP = 1
    }
}
