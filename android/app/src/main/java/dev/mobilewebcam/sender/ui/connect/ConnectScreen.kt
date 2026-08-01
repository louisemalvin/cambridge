package dev.mobilewebcam.sender.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mobilewebcam.sender.model.CodecPreference
import dev.mobilewebcam.sender.model.VideoProfile
import dev.mobilewebcam.sender.ui.SenderUiState
import dev.mobilewebcam.sender.ui.components.CodecSelector
import dev.mobilewebcam.sender.ui.components.ConnectionStatus
import dev.mobilewebcam.sender.ui.components.VideoProfileSelector

@Composable
fun ConnectScreen(
    state: SenderUiState,
    onReceiverHostChanged: (String) -> Unit,
    onControlPortChanged: (String) -> Unit,
    onCodecPreferenceChanged: (CodecPreference) -> Unit,
    onProfileChanged: (VideoProfile) -> Unit,
    onRequestCameraPermission: () -> Unit,
    onStart: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Mobile Webcam")
        Text("Connect to a receiver on the local network")
        OutlinedTextField(
            value = state.receiverHost,
            onValueChange = onReceiverHostChanged,
            label = { Text("Receiver IP address") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.controlPort,
            onValueChange = onControlPortChanged,
            label = { Text("Control port") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        CodecSelector(state.codecPreference, onCodecPreferenceChanged)
        VideoProfileSelector(state.profile, onProfileChanged)
        if (state.profile.id == "4k30") {
            Text("4K UHD is experimental and depends on both devices and the virtual camera consumer.")
        }
        if (state.networkInformation.isNotEmpty()) {
            Text("Local addresses")
            state.networkInformation.forEach { information ->
                Text("${information.transport}: ${information.addresses.joinToString()}")
            }
        }
        if (!state.cameraPermissionGranted) {
            OutlinedButton(onClick = onRequestCameraPermission) {
                Text("Allow camera access")
            }
        }
        state.validationMessage?.let { Text(it) }
        ConnectionStatus(state.streamState)
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = onStart,
            enabled = state.cameraPermissionGranted && state.receiverHost.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Connect")
        }
    }
}
