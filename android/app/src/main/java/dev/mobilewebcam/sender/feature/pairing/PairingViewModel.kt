package dev.mobilewebcam.sender.feature.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.mobilewebcam.sender.connection.discovery.SenderConnectionCoordinator
import dev.mobilewebcam.sender.model.StreamState
import kotlinx.coroutines.flow.MutableSharedFlow
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
class PairingViewModel @Inject constructor(
    private val coordinator: SenderConnectionCoordinator,
) : ViewModel() {
    private val effectFlow = MutableSharedFlow<PairingUiEffect>(
        extraBufferCapacity = EFFECT_BUFFER_CAPACITY,
    )

    val uiState: StateFlow<PairingUiState> = combine(
        coordinator.pendingApproval,
        coordinator.streamState,
        coordinator.activeReceiverName,
    ) { pending, streamState, receiverName ->
        PairingUiStateMapper.map(
            PairingDomainSnapshot(
                pendingApprovalName = pending?.receiverName,
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

    fun approvePending() {
        viewModelScope.launch { coordinator.approvePending() }
    }

    fun rejectPending() {
        viewModelScope.launch { coordinator.rejectPending() }
    }

    private companion object {
        const val EFFECT_BUFFER_CAPACITY = 1
        const val FIRST_STATE_TO_SKIP = 1
    }
}
