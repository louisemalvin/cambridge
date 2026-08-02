package dev.mobilewebcam.sender

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import dev.mobilewebcam.sender.ui.SenderScreen
import dev.mobilewebcam.sender.ui.model.CameraControlsUiState
import dev.mobilewebcam.sender.ui.model.SelectOptionUi
import dev.mobilewebcam.sender.ui.model.SenderScreenState
import dev.mobilewebcam.sender.ui.model.SettingsUiState
import dev.mobilewebcam.sender.ui.model.UiText
import org.junit.Rule
import org.junit.Test

class SenderScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun waitingScreenKeepsPreviewActionsMinimal() {
        composeRule.setContent {
            MaterialTheme {
                SenderScreen(
                    state = SenderScreenState(),
                    onAction = {},
                    onSurfaceChanged = {},
                )
            }
        }

        composeRule.onNodeWithText("Waiting for connection").assertIsDisplayed()
        composeRule.onNodeWithText("Allow camera access").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Dim screen").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }

    @Test
    fun dimmedScreenExposesBrightenAction() {
        composeRule.setContent {
            MaterialTheme {
                SenderScreen(
                    state = SenderScreenState(isScreenDimmed = true),
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
                SenderScreen(
                    state = SenderScreenState(
                        cameraPermissionGranted = true,
                        isSettingsOpen = true,
                        camera = CameraControlsUiState(),
                        settings = SettingsUiState(
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
                        ),
                    ),
                    onAction = {},
                    onSurfaceChanged = {},
                )
            }
        }

        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
        composeRule.onNodeWithText("Stream defaults").assertIsDisplayed()
        composeRule.onNodeWithText("Codec mode").assertIsDisplayed()
        composeRule.onNodeWithText("Video profile").assertIsDisplayed()
    }
}
