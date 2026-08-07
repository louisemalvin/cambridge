package dev.cambridge.sender.feature.pairing

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import dev.cambridge.sender.R

@Composable
fun PairingRoute(
    onNavigateToStreamSetup: () -> Unit,
) {
    val viewModel: PairingViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsState()

    PairingScreen(
        state = state,
        computerName = stringResource(R.string.computer_name),
        onAction = { onNavigateToStreamSetup() },
    )
}
