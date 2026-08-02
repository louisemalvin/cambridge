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

private const val CONNECT_CONTENT_PADDING_DP = 20
private const val CONNECT_ITEM_SPACING_DP = 12
private const val CONNECT_SECTION_SPACER_DP = 4

@Composable
fun ConnectScreen(
    state: SenderUiState,
    onCodecPreferenceChanged: (CodecPreference) -> Unit,
    onProfileChanged: (VideoProfile) -> Unit,
    onRequestCameraPermission: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onCopyError: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(CONNECT_CONTENT_PADDING_DP.dp),
        verticalArrangement = Arrangement.spacedBy(CONNECT_ITEM_SPACING_DP.dp),
    ) {
        Text("Mobile Webcam")
        Text("Available to desktop receivers on this local network")
        state.pendingApproval?.let { approval ->
            Text("${approval.receiverName} wants to use this phone as a webcam.")
            Button(onClick = onApprove, modifier = Modifier.fillMaxWidth()) {
                Text("Approve this computer")
            }
            OutlinedButton(onClick = onReject, modifier = Modifier.fillMaxWidth()) {
                Text("Reject")
            }
        }
        CodecSelector(state.codecPreference, onCodecPreferenceChanged)
        VideoProfileSelector(state.profile, onProfileChanged)
        if (state.profile.id == "4k30") {
            Text("4K UHD is experimental and depends on both devices and the virtual camera consumer.")
        }
        if (!state.cameraPermissionGranted) {
            OutlinedButton(onClick = onRequestCameraPermission) {
                Text("Allow camera access")
            }
        }
        state.validationMessage?.let { Text(it) }
        ConnectionStatus(state.streamState)
        if (state.streamState is dev.mobilewebcam.sender.model.StreamState.Failed) {
            Text("Copy the technical details if you need help troubleshooting.")
            OutlinedButton(
                onClick = onCopyError,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Copy error details")
            }
        }
        Spacer(Modifier.height(CONNECT_SECTION_SPACER_DP.dp))
        if (state.pendingApproval == null) {
            Text("Waiting for the desktop app")
        }
    }
}
