package dev.mobilewebcam.sender

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import dev.mobilewebcam.sender.app.model.CameraControlsUiState
import dev.mobilewebcam.sender.app.model.SelectOptionUi
import dev.mobilewebcam.sender.feature.settings.SettingsUiState
import dev.mobilewebcam.sender.feature.webcam.WebcamUiState
import dev.mobilewebcam.sender.app.model.UiText
import dev.mobilewebcam.sender.feature.webcam.WebcamScreen
import dev.mobilewebcam.sender.feature.settings.SettingsScreen
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
                        codecOptions = listOf(
                            SelectOptionUi(
                                key = "auto",
                                label = UiText.Plain("Auto"),
                                isSelected = true,
                            ),
                        ),
                        profileOptions = listOf(
                            SelectOptionUi(
                                key = "1080p30",
                                label = UiText.Plain("1080p30"),
                                isSelected = true,
                            ),
                        ),
                        hasConfiguredReceiver = true,
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
        composeRule.onNodeWithText("Stream defaults").assertIsDisplayed()
        composeRule.onNodeWithText("Codec mode").assertIsDisplayed()
        composeRule.onNodeWithText("Video profile").assertIsDisplayed()
        composeRule.onNodeWithText("Forget receiver").assertIsDisplayed()
    }
}
