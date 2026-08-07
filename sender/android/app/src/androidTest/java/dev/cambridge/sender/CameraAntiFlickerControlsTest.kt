package dev.cambridge.sender

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.cambridge.sender.app.model.AntiFlickerUiState
import dev.cambridge.sender.app.model.SelectOptionUi
import dev.cambridge.sender.app.model.UiText
import dev.cambridge.sender.feature.webcam.components.CameraAntiFlickerControls
import org.junit.Rule
import org.junit.Test

class CameraAntiFlickerControlsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsCameraAntiFlickerChoices() {
        composeRule.setContent {
            MaterialTheme {
                CameraAntiFlickerControls(
                    state = AntiFlickerUiState(
                        options = listOf(
                            SelectOptionUi("AUTO", UiText.Plain("Auto"), true),
                            SelectOptionUi("HZ_50", UiText.Plain("50 Hz"), false),
                            SelectOptionUi("HZ_60", UiText.Plain("60 Hz"), false),
                        ),
                    ),
                    onModeSelected = {},
                )
            }
        }

        composeRule.onNodeWithText("Anti-flicker").assertIsDisplayed()
        composeRule.onNodeWithText("Auto").assertIsDisplayed()
        composeRule.onNodeWithText("50 Hz").assertIsDisplayed()
        composeRule.onNodeWithText("60 Hz").assertIsDisplayed()
    }
}
