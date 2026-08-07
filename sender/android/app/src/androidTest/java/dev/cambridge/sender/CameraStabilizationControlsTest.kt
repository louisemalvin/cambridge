package dev.cambridge.sender

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.cambridge.sender.feature.webcam.components.CameraStabilizationControls
import dev.cambridge.sender.app.model.StabilizationUiState
import org.junit.Rule
import org.junit.Test

class CameraStabilizationControlsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsStabilizationSwitchWhenSupported() {
        composeRule.setContent {
            MaterialTheme {
                CameraStabilizationControls(
                    state = StabilizationUiState(isSupported = true),
                    onStabilizationEnabledChanged = {},
                )
            }
        }

        composeRule.onNodeWithText("Stabilization").assertIsDisplayed()
        composeRule.onNodeWithText("Off").assertIsDisplayed()
    }
}
