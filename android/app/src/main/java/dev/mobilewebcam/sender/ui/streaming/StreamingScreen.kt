package dev.mobilewebcam.sender.ui.streaming

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import dev.mobilewebcam.sender.ui.components.ConnectionStatus
import kotlinx.coroutines.delay

@Composable
fun StreamingScreen(
    state: SenderUiState,
    onStop: () -> Unit,
) {
    val streaming = state.streamState as? StreamState.Streaming
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(streaming?.startedAtMillis) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ConnectionStatus(state.streamState)
        streaming?.let { session ->
            Text("Codec: ${session.session.selectedCodec.protocolId}")
            Text(
                "Profile: ${session.session.profile.width} x " +
                    "${session.session.profile.height} @ ${session.session.profile.fps} FPS",
            )
            Text("Bitrate: ${session.session.bitrateBps / 1_000_000.0} Mbps")
            Text("Receiver: ${session.session.endpoint.host}:${session.session.mediaPort}")
            Text("Duration: ${(now - session.startedAtMillis).coerceAtLeast(0) / 1_000} seconds")
        }
        Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) {
            Text("Stop")
        }
    }
}
