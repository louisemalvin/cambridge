package dev.cambridge.sender

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import dev.cambridge.sender.app.model.CameraControlsUiState
import dev.cambridge.sender.app.model.ConnectionUiState
import dev.cambridge.sender.app.model.AntiFlickerUiState
import dev.cambridge.sender.app.model.SelectOptionUi
import dev.cambridge.sender.app.model.StabilizationUiState
import dev.cambridge.sender.feature.settings.SettingsUiState
import dev.cambridge.sender.feature.settings.SettingsScreen
import dev.cambridge.sender.feature.setup.BitrateUiState
import dev.cambridge.sender.feature.setup.CameraPermissionUiState
import dev.cambridge.sender.feature.setup.ReceiverReadinessUiState
import dev.cambridge.sender.feature.setup.StreamSetupScreen
import dev.cambridge.sender.feature.setup.StreamSetupUiState
import dev.cambridge.sender.feature.webcam.WebcamScreen
import dev.cambridge.sender.feature.webcam.WebcamUiState
import dev.cambridge.sender.feature.webcam.overlays.EndStreamConfirmationDialog
import dev.cambridge.sender.app.model.UiText
import dev.cambridge.sender.media.camera.CameraStabilizationMode
import dev.cambridge.sender.model.StreamOrientation
import dev.cambridge.sender.session.VideoProfiles
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
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
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
        composeRule.onNodeWithText("Connection").assertIsDisplayed()
        composeRule.onNodeWithText("Stream status").assertIsDisplayed()
    }

    @Test
    fun setupShowsPhoneVideoControlsWithoutRootBackAction() {
        val selectedProfile = VideoProfiles.PROFILE_1080P30
        composeRule.setContent {
            MaterialTheme {
                StreamSetupScreen(
                    state = StreamSetupUiState(
                        connection = ConnectionUiState.Waiting,
                        receiverReadiness = ReceiverReadinessUiState.Ready(
                            receiverName = UiText.Plain("OBS Studio"),
                            address = UiText.Plain("192.168.1.20"),
                        ),
                        receiverOptions = listOf(
                            SelectOptionUi(
                                key = "studio-obs",
                                label = UiText.Plain("OBS Studio · 192.168.1.20"),
                                isSelected = true,
                            ),
                        ),
                        resolutionOptions = listOf(
                            SelectOptionUi(
                                key = selectedProfile.id,
                                label = UiText.Plain("1080p"),
                                isSelected = true,
                            ),
                            SelectOptionUi(
                                key = VideoProfiles.PROFILE_2K30.id,
                                label = UiText.Plain("2K"),
                                isSelected = false,
                                isEnabled = false,
                            ),
                        ),
                        frameRateOptions = listOf(
                            SelectOptionUi(
                                key = selectedProfile.fps.toString(),
                                label = UiText.Plain("30 fps"),
                                isSelected = true,
                            ),
                        ),
                        orientationOptions = listOf(
                            SelectOptionUi(
                                key = StreamOrientation.LANDSCAPE.name,
                                label = UiText.Plain("Landscape"),
                                isSelected = true,
                            ),
                        ),
                        bitrate = BitrateUiState(
                            isAvailable = true,
                            selectedBps = selectedProfile.defaultBitrateBps,
                            minimumBps = selectedProfile.minimumBitrateBps,
                            maximumBps = selectedProfile.maximumBitrateBps,
                            stepBps = selectedProfile.bitrateStepBps,
                        ),
                        selectedProfile = selectedProfile,
                        selectedOrientation = StreamOrientation.LANDSCAPE,
                        selectedProfileSupported = true,
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText("CamBridge").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithText("Receiver").assertIsDisplayed()
        composeRule.onNodeWithText("OBS Studio · Ready").assertIsDisplayed()
        composeRule.onNodeWithText("192.168.1.20").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Change receiver").performClick()
        composeRule.onNodeWithText("OBS Studio · 192.168.1.20").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Cancel receiver change").performClick()
        composeRule.onNodeWithText("OBS Studio · Ready").assertIsDisplayed()
        composeRule.onNodeWithText("Stream settings").assertIsDisplayed()
        composeRule.onNodeWithText("Resolution").assertIsDisplayed()
        composeRule.onNodeWithText("Frame rate").assertIsDisplayed()
        composeRule.onNodeWithText("Bitrate").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Video bitrate").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Video bitrate in megabits per second").assertIsDisplayed()
        composeRule.onNodeWithText("2K").assertIsNotEnabled()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Start stream"))
        composeRule.onNodeWithText("Start stream").assertIsEnabled()
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Stream settings"))
        composeRule.onNodeWithText("Stream settings").performClick()
        assertTrue(composeRule.onAllNodesWithText("Resolution").fetchSemanticsNodes().isEmpty())
        composeRule.onNodeWithText("1080p · 30 fps · 8 Mbps").assertIsDisplayed()
    }

    @Test
    fun setupKeepsCameraSettingsCollapsedUntilRequested() {
        composeRule.setContent {
            MaterialTheme {
                StreamSetupScreen(
                    state = StreamSetupUiState(
                        stabilization = StabilizationUiState(
                            supportedModes = listOf(
                                CameraStabilizationMode.OFF,
                                CameraStabilizationMode.OPTICAL,
                            ),
                            requestedMode = CameraStabilizationMode.OPTICAL,
                            selectedMode = CameraStabilizationMode.OPTICAL,
                        ),
                        antiFlicker = AntiFlickerUiState(
                            options = listOf(
                                SelectOptionUi(
                                    key = "AUTO",
                                    label = UiText.Plain("Auto"),
                                    isSelected = true,
                                ),
                            ),
                        ),
                        selectedProfile = VideoProfiles.default,
                        selectedOrientation = StreamOrientation.LANDSCAPE,
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("Camera settings"))
        composeRule.onNodeWithText("Camera settings").assertIsDisplayed()
        composeRule.onNodeWithText("Optical · Auto").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("Stabilization").fetchSemanticsNodes().isEmpty())

        composeRule.onNodeWithText("Camera settings").performClick()
        composeRule.onNodeWithText("Stabilization").assertIsDisplayed()
        composeRule.onNodeWithText("Anti-flicker").assertIsDisplayed()
    }

    @Test
    fun setupMakesMissingCameraPermissionActionable() {
        var permissionRequested = false
        composeRule.setContent {
            MaterialTheme {
                StreamSetupScreen(
                    state = StreamSetupUiState(
                        receiverReadiness = ReceiverReadinessUiState.Ready(
                            receiverName = UiText.Plain("OBS Studio"),
                            address = UiText.Plain("192.168.1.20"),
                        ),
                        selectedProfile = VideoProfiles.default,
                        selectedOrientation = StreamOrientation.LANDSCAPE,
                    ),
                    cameraPermission = CameraPermissionUiState(isGranted = false),
                    onAction = { action ->
                        if (action == dev.cambridge.sender.app.model.SenderScreenAction.RequestCameraPermission) {
                            permissionRequested = true
                        }
                    },
                )
            }
        }

        composeRule.onNodeWithText("Camera access required").assertIsDisplayed()
        composeRule.onNodeWithText("Allow camera access").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertTrue(permissionRequested) }
    }

    @Test
    fun setupExplainsWhyAnUnsupportedFrameRateIsDisabled() {
        val encoderReason = "The phone H.264 encoder does not provide this mode"
        composeRule.setContent {
            MaterialTheme {
                StreamSetupScreen(
                    state = StreamSetupUiState(
                        receiverReadiness = ReceiverReadinessUiState.Ready(
                            receiverName = UiText.Plain("OBS Studio"),
                            address = UiText.Plain("192.168.1.20"),
                        ),
                        frameRateOptions = listOf(
                            SelectOptionUi(
                                key = "30",
                                label = UiText.Plain("30 fps"),
                                isSelected = true,
                            ),
                            SelectOptionUi(
                                key = "60",
                                label = UiText.Plain("60 fps"),
                                isSelected = false,
                                isEnabled = false,
                                disabledReason = UiText.Plain(encoderReason),
                            ),
                        ),
                        bitrate = BitrateUiState(
                            isAvailable = true,
                            selectedBps = VideoProfiles.default.defaultBitrateBps,
                            minimumBps = VideoProfiles.default.minimumBitrateBps,
                            maximumBps = VideoProfiles.default.maximumBitrateBps,
                            stepBps = VideoProfiles.default.bitrateStepBps,
                        ),
                        selectedProfile = VideoProfiles.default,
                        selectedOrientation = StreamOrientation.LANDSCAPE,
                        selectedProfileSupported = true,
                        videoCapabilitiesReady = true,
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText("60 fps unavailable: $encoderReason").assertIsDisplayed()
        composeRule.onNodeWithText("30 fps").performClick()
        composeRule.onNodeWithText(encoderReason).assertIsDisplayed()
    }

    @Test
    fun setupOffersManualReceiverAddressWhenDiscoveryFails() {
        composeRule.setContent {
            MaterialTheme {
                StreamSetupScreen(
                    state = StreamSetupUiState(
                        receiverReadiness = ReceiverReadinessUiState.Unavailable(
                            message = UiText.Plain("Discovery did not find OBS"),
                        ),
                        selectedProfile = VideoProfiles.default,
                        selectedOrientation = StreamOrientation.LANDSCAPE,
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText("IP address or hostname").assertIsDisplayed()
        composeRule.onNodeWithText("Use address").assertIsNotEnabled()
    }

    @Test
    fun setupRequiresAReceiverChoiceWhenMultipleReceiversAreAvailable() {
        composeRule.setContent {
            MaterialTheme {
                StreamSetupScreen(
                    state = StreamSetupUiState(
                        receiverReadiness = ReceiverReadinessUiState.SelectionRequired,
                        receiverOptions = listOf(
                            SelectOptionUi(
                                key = "office-obs",
                                label = UiText.Plain("Office OBS · 192.168.1.20"),
                                isSelected = false,
                            ),
                            SelectOptionUi(
                                key = "studio-obs",
                                label = UiText.Plain("Studio OBS · 192.168.1.21"),
                                isSelected = false,
                            ),
                        ),
                        selectedProfile = VideoProfiles.default,
                        selectedOrientation = StreamOrientation.LANDSCAPE,
                    ),
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText("Choose receiver").performClick()
        composeRule.onNodeWithText("Office OBS · 192.168.1.20").assertIsDisplayed()
        composeRule.onNodeWithText("Studio OBS · 192.168.1.21").assertIsDisplayed()
        composeRule.onNodeWithText("Start stream").assertIsNotEnabled()
    }

    @Test
    fun setupAcceptsAnExactBitrateFromTheNumericField() {
        val selectedProfile = VideoProfiles.PROFILE_1080P30
        var selectedBitrateBps: Int? = null
        composeRule.setContent {
            MaterialTheme {
                StreamSetupScreen(
                    state = StreamSetupUiState(
                        bitrate = BitrateUiState(
                            isAvailable = true,
                            selectedBps = selectedProfile.defaultBitrateBps,
                            minimumBps = selectedProfile.minimumBitrateBps,
                            maximumBps = selectedProfile.maximumBitrateBps,
                            stepBps = selectedProfile.bitrateStepBps,
                        ),
                        selectedProfile = selectedProfile,
                        selectedOrientation = StreamOrientation.LANDSCAPE,
                    ),
                    onAction = { action ->
                        if (action is dev.cambridge.sender.app.model.SenderScreenAction.BitrateSelected) {
                            selectedBitrateBps = action.bitrateBps
                        }
                    },
                )
            }
        }

        val bitrateInput = composeRule.onNodeWithContentDescription(
            "Video bitrate in megabits per second",
        )
        bitrateInput.performTextReplacement("12")
        bitrateInput.performImeAction()

        composeRule.runOnIdle {
            assertEquals(12 * VideoProfiles.MEGABIT, selectedBitrateBps)
        }
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
        composeRule.onNodeWithText("The camera will stop sending video to the receiver.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Keep streaming").assertIsDisplayed()
        composeRule.onNodeWithText("Stop stream").assertIsDisplayed()
    }
}
