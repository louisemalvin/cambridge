package dev.mobilewebcam.sender.ui

import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.mobilewebcam.sender.model.CodecPreference
import dev.mobilewebcam.sender.model.StreamFailure
import dev.mobilewebcam.sender.model.StreamFailureException
import dev.mobilewebcam.sender.model.StreamState
import dev.mobilewebcam.sender.model.VideoProfile
import dev.mobilewebcam.sender.platform.NetworkInformationProvider
import dev.mobilewebcam.sender.session.StreamSessionController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SenderViewModel(
    private val controller: StreamSessionController,
    private val networkInformationProvider: NetworkInformationProvider,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SenderUiState())
    private var previewSurface: Surface? = null

    val uiState: StateFlow<SenderUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            controller.state.collectLatest { streamState ->
                mutableState.update { it.copy(streamState = streamState, validationMessage = null) }
            }
        }
        refreshNetworkInformation()
    }

    fun updateReceiverHost(value: String) {
        mutableState.update { it.copy(receiverHost = value, validationMessage = null) }
    }

    fun updateControlPort(value: String) {
        mutableState.update { it.copy(controlPort = value.filter(Char::isDigit), validationMessage = null) }
    }

    fun updateCodecPreference(value: CodecPreference) {
        mutableState.update { it.copy(codecPreference = value, validationMessage = null) }
    }

    fun updateProfile(value: VideoProfile) {
        mutableState.update { it.copy(profile = value, validationMessage = null) }
    }

    fun setCameraPermissionGranted(granted: Boolean) {
        mutableState.update { it.copy(cameraPermissionGranted = granted) }
    }

    fun setPreviewSurface(surface: Surface?) {
        previewSurface = surface
    }

    fun start() {
        val snapshot = uiState.value
        if (!snapshot.cameraPermissionGranted) {
            showFailure(StreamFailure.CameraPermissionDenied)
            return
        }
        val port = snapshot.controlPort.toIntOrNull()
        if (port == null) {
            mutableState.update { it.copy(validationMessage = "Enter a valid control port") }
            return
        }
        viewModelScope.launch {
            controller.start(
                host = snapshot.receiverHost,
                controlPort = port,
                preference = snapshot.codecPreference,
                profile = snapshot.profile,
                previewSurface = previewSurface,
            ).onFailure { error ->
                val failure = (error as? StreamFailureException)?.failure
                    ?: StreamFailure.Unexpected(error)
                showFailure(failure)
            }
        }
    }

    fun stop() {
        viewModelScope.launch {
            controller.stop()
        }
    }

    fun refreshNetworkInformation() {
        mutableState.update { it.copy(networkInformation = networkInformationProvider.current()) }
    }

    private fun showFailure(failure: StreamFailure) {
        mutableState.update { it.copy(streamState = StreamState.Failed(failure)) }
    }
}
