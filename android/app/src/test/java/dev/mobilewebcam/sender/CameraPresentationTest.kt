package dev.mobilewebcam.sender

import android.view.Surface
import dev.mobilewebcam.sender.camera.DisplayOrientation
import dev.mobilewebcam.sender.camera.VideoPreviewLayout
import dev.mobilewebcam.sender.model.VideoProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraPresentationTest {
    @Test
    fun landscapePreviewUsesTheSelectedProfileDimensions() {
        val layout = VideoPreviewLayout.forProfile(profile, DisplayOrientation.LANDSCAPE)

        assertEquals(profile.width, layout.dimensions.width)
        assertEquals(profile.height, layout.dimensions.height)
        assertEquals(16.0f / 9.0f, layout.aspectRatio, ASPECT_RATIO_TOLERANCE)
    }

    @Test
    fun portraitPreviewSwapsDimensionsWithoutChangingTheProfile() {
        val layout = VideoPreviewLayout.forProfile(profile, DisplayOrientation.PORTRAIT)

        assertEquals(profile.height, layout.dimensions.width)
        assertEquals(profile.width, layout.dimensions.height)
        assertEquals(9.0f / 16.0f, layout.aspectRatio, ASPECT_RATIO_TOLERANCE)
    }

    @Test
    fun displayRotationMapsToTheExpectedOrientation() {
        assertEquals(DisplayOrientation.PORTRAIT, DisplayOrientation.fromRotationDegrees(0))
        assertEquals(DisplayOrientation.LANDSCAPE, DisplayOrientation.fromRotationDegrees(90))
        assertEquals(DisplayOrientation.REVERSE_PORTRAIT, DisplayOrientation.fromRotationDegrees(180))
        assertEquals(DisplayOrientation.REVERSE_LANDSCAPE, DisplayOrientation.fromRotationDegrees(270))
        assertEquals(0, DisplayOrientation.PORTRAIT.cameraRotationDegrees)
        assertEquals(270, DisplayOrientation.LANDSCAPE.cameraRotationDegrees)
        assertEquals(180, DisplayOrientation.REVERSE_PORTRAIT.cameraRotationDegrees)
        assertEquals(90, DisplayOrientation.REVERSE_LANDSCAPE.cameraRotationDegrees)
    }

    @Test
    fun androidSurfaceRotationValuesMapToTheExpectedOrientation() {
        assertEquals(
            DisplayOrientation.PORTRAIT,
            DisplayOrientation.fromSurfaceRotation(Surface.ROTATION_0),
        )
        assertEquals(
            DisplayOrientation.LANDSCAPE,
            DisplayOrientation.fromSurfaceRotation(Surface.ROTATION_90),
        )
        assertEquals(
            DisplayOrientation.REVERSE_PORTRAIT,
            DisplayOrientation.fromSurfaceRotation(Surface.ROTATION_180),
        )
        assertEquals(
            DisplayOrientation.REVERSE_LANDSCAPE,
            DisplayOrientation.fromSurfaceRotation(Surface.ROTATION_270),
        )
    }

    private companion object {
        const val ASPECT_RATIO_TOLERANCE = 0.0001f
        val profile = VideoProfile(
            id = "test-1080p",
            width = 1920,
            height = 1080,
            fps = 30,
            h264BitrateBps = 10_000_000,
            h265BitrateBps = 7_000_000,
            keyframeIntervalSeconds = 1,
        )
    }
}
