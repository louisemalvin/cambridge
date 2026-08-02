package dev.mobilewebcam.sender

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.mobilewebcam.sender.camera.CameraInteractionState
import dev.mobilewebcam.sender.camera.physicalLensOptionsFor
import dev.mobilewebcam.sender.ui.components.CameraLensControls
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
                    state = CameraInteractionState()
                        .withPhysicalLensOptions(physicalLensOptionsFor(listOf("2", "3", "4"))),
                    onLensSelected = {},
                )
            }
        }

        composeRule.onNodeWithText("Auto").assertIsDisplayed()
        composeRule.onNodeWithText("Lens 2").assertIsDisplayed()
        composeRule.onNodeWithText("Lens 4").assertIsDisplayed()
    }
}
