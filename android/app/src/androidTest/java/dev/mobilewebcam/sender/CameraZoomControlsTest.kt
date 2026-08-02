package dev.mobilewebcam.sender

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import dev.mobilewebcam.sender.camera.CameraInteractionState
import dev.mobilewebcam.sender.ui.components.CameraZoomControls
import org.junit.Rule
import org.junit.Test

class CameraZoomControlsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun zoomControlShowsMaterialSliderAndResetAction() {
        composeRule.setContent {
            MaterialTheme {
                CameraZoomControls(
                    state = CameraInteractionState()
                        .withCameraBounds(minimum = 1.0f, maximum = 4.0f, current = 2.0f),
                    onZoomRatioChanged = {},
                    onResetZoom = {},
                )
            }
        }

        composeRule.onNodeWithText("2.0x").assertIsDisplayed()
        composeRule.onNodeWithText("Reset").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Camera zoom level").assertIsDisplayed()
    }
}
