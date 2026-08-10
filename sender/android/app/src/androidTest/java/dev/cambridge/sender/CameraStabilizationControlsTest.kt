package dev.cambridge.sender

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.cambridge.sender.feature.webcam.components.CameraStabilizationControls
import dev.cambridge.sender.app.model.StabilizationUiState
import dev.cambridge.sender.media.camera.CameraStabilizationApplyStatus
import dev.cambridge.sender.media.camera.CameraStabilizationMode
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class CameraStabilizationControlsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun showsExplicitStabilizationModesWhenAdvertised() {
        composeRule.setContent {
            MaterialTheme {
                CameraStabilizationControls(
                    state = StabilizationUiState(
                        supportedModes = listOf(
                            CameraStabilizationMode.OFF,
                            CameraStabilizationMode.OPTICAL,
                            CameraStabilizationMode.ELECTRONIC,
                        ),
                        applyStatus = CameraStabilizationApplyStatus.APPLIED,
                    ),
                    onStabilizationModeChanged = {},
                )
            }
        }

        composeRule.onNodeWithText("Stabilization").assertIsDisplayed()
        composeRule.onNodeWithText("Off").assertIsDisplayed()
        composeRule.onNodeWithText("Optical").assertIsDisplayed()
        composeRule.onNodeWithText("Electronic").assertIsDisplayed()
    }

    @Test
    fun unavailableStateShowsOnlyOffAndNamesTheRequestedMode() {
        composeRule.setContent {
            MaterialTheme {
                CameraStabilizationControls(
                    state = StabilizationUiState(
                        requestedMode = CameraStabilizationMode.ELECTRONIC,
                        selectedMode = CameraStabilizationMode.OFF,
                        applyStatus = CameraStabilizationApplyStatus.UNAVAILABLE_FOR_STREAM,
                    ),
                )
            }
        }

        composeRule.onNodeWithText("Off").assertIsDisplayed()
        composeRule.onNodeWithText("Electronic unavailable for this stream").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("Electronic").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun selectingAnAdvertisedModeReportsTheExplicitCallback() {
        var selectedMode: CameraStabilizationMode? = null
        composeRule.setContent {
            MaterialTheme {
                CameraStabilizationControls(
                    state = StabilizationUiState(
                        supportedModes = listOf(
                            CameraStabilizationMode.OFF,
                            CameraStabilizationMode.ELECTRONIC,
                        ),
                    ),
                    onStabilizationModeChanged = { selectedMode = it },
                )
            }
        }

        composeRule.onNodeWithText("Electronic").performClick()

        assertEquals(CameraStabilizationMode.ELECTRONIC, selectedMode)
    }
}
