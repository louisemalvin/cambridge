package dev.mobilewebcam.sender

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import dev.mobilewebcam.sender.feature.webcam.components.CameraZoomControls
import dev.mobilewebcam.sender.app.model.ZoomUiState
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
                    state = ZoomUiState(
                        ratio = 2.0f,
                        minimumRatio = 1.0f,
                        maximumRatio = 4.0f,
                        isCameraActive = true,
                    ),
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
