package dev.mobilewebcam.sender

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.mobilewebcam.sender.feature.webcam.components.CameraLensControls
import dev.mobilewebcam.sender.app.model.LensOptionUi
import dev.mobilewebcam.sender.app.model.UiText
import org.junit.Rule
import org.junit.Test

class CameraLensControlsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsAutomaticAndRuntimeLensOptions() {
        composeRule.setContent {
            MaterialTheme {
                CameraLensControls(
                    options = listOf(
                        LensOptionUi("auto", UiText.Plain("Auto"), isSelected = true),
                        LensOptionUi("2", UiText.Plain("Lens 2"), isSelected = false),
                        LensOptionUi("4", UiText.Plain("Lens 4"), isSelected = false),
                    ),
                    onLensSelected = {},
                )
            }
        }

        composeRule.onNodeWithText("Physical lens").performClick()
        composeRule.onNodeWithText("Auto").assertIsDisplayed()
        composeRule.onNodeWithText("Lens 2").assertIsDisplayed()
        composeRule.onNodeWithText("Lens 4").assertIsDisplayed()
    }
}
