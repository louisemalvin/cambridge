package dev.mobilewebcam.sender.ui.streaming

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mobilewebcam.sender.model.StreamState
import dev.mobilewebcam.sender.ui.SenderUiState
import dev.mobilewebcam.sender.camera.PhysicalLensOption
import dev.mobilewebcam.sender.ui.components.CameraLensControls
import dev.mobilewebcam.sender.ui.components.CameraStabilizationControls
import dev.mobilewebcam.sender.ui.components.CameraZoomControls
import dev.mobilewebcam.sender.ui.components.ConnectionStatus
import kotlinx.coroutines.delay

private const val STREAMING_CONTENT_PADDING_DP = 16
private const val STREAMING_ITEM_SPACING_DP = 10
private const val DURATION_UPDATE_INTERVAL_MILLIS = 1_000L
private const val MILLIS_PER_SECOND = 1_000L
private const val BITS_PER_MEGABIT = 1_000_000.0

@Composable
fun StreamingScreen(
    state: SenderUiState,
    onStop: () -> Unit,
    onZoomRatioChanged: (Float) -> Unit,
    onResetZoom: () -> Unit,
    onStabilizationEnabledChanged: (Boolean) -> Unit,
    onPhysicalLensSelected: (PhysicalLensOption) -> Unit,
) {
    val streaming = state.streamState as? StreamState.Streaming
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(streaming?.startedAtMillis) {
        while (true) {
            now = System.currentTimeMillis()
            delay(DURATION_UPDATE_INTERVAL_MILLIS)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(STREAMING_CONTENT_PADDING_DP.dp),
        verticalArrangement = Arrangement.spacedBy(STREAMING_ITEM_SPACING_DP.dp),
    ) {
        ConnectionStatus(state.streamState)
        CameraLensControls(
            state = state.cameraInteraction,
            onLensSelected = onPhysicalLensSelected,
        )
        CameraStabilizationControls(
            state = state.cameraInteraction,
            onStabilizationEnabledChanged = onStabilizationEnabledChanged,
        )
        CameraZoomControls(
            state = state.cameraInteraction,
            onZoomRatioChanged = onZoomRatioChanged,
            onResetZoom = onResetZoom,
        )
        streaming?.let { session ->
            Text("Codec: ${session.session.selectedCodec.protocolId}")
            Text(
                "Profile: ${session.session.profile.width} x " +
                    "${session.session.profile.height} @ ${session.session.profile.fps} FPS",
            )
            Text("Bitrate: ${session.session.bitrateBps / BITS_PER_MEGABIT} Mbps")
            Text("Receiver: ${state.activeReceiverName ?: session.session.endpoint.host}")
            Text(
                "Duration: ${(now - session.startedAtMillis).coerceAtLeast(0) / MILLIS_PER_SECOND} seconds",
            )
        }
        Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
            Text("Stop")
        }
    }
}
