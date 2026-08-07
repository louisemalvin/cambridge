package dev.mobilewebcam.sender

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import dev.mobilewebcam.sender.app.model.CameraControlsUiState
import dev.mobilewebcam.sender.feature.settings.SettingsUiState
import dev.mobilewebcam.sender.feature.settings.SettingsScreen
import dev.mobilewebcam.sender.feature.webcam.WebcamScreen
import dev.mobilewebcam.sender.feature.webcam.WebcamUiState
import dev.mobilewebcam.sender.feature.webcam.overlays.EndStreamConfirmationDialog
import org.junit.Rule
import org.junit.Test

class SenderScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun waitingScreenKeepsPreviewActionsMinimal() {
        composeRule.setContent {
            MaterialTheme {
                WebcamScreen(
                    state = WebcamUiState(),
                    onAction = {},
                    onSurfaceChanged = {},
                )
            }
        }

        composeRule.onAllNodesWithText("Waiting for webcam use").get(0).assertIsDisplayed()
        composeRule.onNodeWithText("Allow camera access").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Dim screen").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }

    @Test
    fun dimmedScreenExposesBrightenAction() {
        composeRule.setContent {
            MaterialTheme {
                WebcamScreen(
                    state = WebcamUiState(isScreenDimmed = true),
                    onAction = {},
                    onSurfaceChanged = {},
                )
            }
        }

        composeRule.onNodeWithContentDescription("Brighten screen").assertIsDisplayed()
    }

    @Test
    fun settingsArePresentedAsAStandardSettingsScreen() {
        composeRule.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = SettingsUiState(
                        camera = CameraControlsUiState(),
                        hasConfiguredReceiver = true,
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
        composeRule.onNodeWithText("Connection").assertIsDisplayed()
        composeRule.onNodeWithText("Stream status").assertIsDisplayed()
        composeRule.onNodeWithText("Forget receiver").assertIsDisplayed()
    }

    @Test
    fun endStreamConfirmationExplainsTheEffectAndOffersKeepStreaming() {
        composeRule.setContent {
            MaterialTheme {
                EndStreamConfirmationDialog(
                    onDismissRequest = {},
                    onConfirm = {},
                )
            }
        }

        composeRule.onNodeWithText("Stop streaming?").assertIsDisplayed()
        composeRule.onNodeWithText("The camera will stop sending video to the computer.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Keep streaming").assertIsDisplayed()
        composeRule.onNodeWithText("Stop stream").assertIsDisplayed()
    }
}
