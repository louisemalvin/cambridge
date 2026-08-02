package dev.mobilewebcam.sender.feature.webcam.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.mobilewebcam.sender.R
import dev.mobilewebcam.sender.app.model.ConnectionUiState
import dev.mobilewebcam.sender.app.model.value

@Composable
fun ConnectionStatus(state: ConnectionUiState) {
    Text(
        text = when (state) {
            ConnectionUiState.Waiting -> androidx.compose.ui.res.stringResource(
                R.string.waiting_for_connection,
            )
            is ConnectionUiState.Connecting -> state.status.value()
            is ConnectionUiState.Streaming -> state.codec.value()
            ConnectionUiState.Stopping -> androidx.compose.ui.res.stringResource(
                R.string.stopping_stream,
            )
            is ConnectionUiState.Failed -> state.message.value()
        },
    )
}
