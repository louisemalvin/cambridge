package dev.mobilewebcam.sender

import dev.mobilewebcam.sender.media.camera.CameraLensFacing
import dev.mobilewebcam.sender.media.camera.DisplayOrientation
import dev.mobilewebcam.sender.media.camera.SessionTransform
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTransformTest {
    @Test
    fun backCameraCoversAllDisplayRotations() {
        val expected = listOf(90, 0, 270, 180)
        DisplayOrientation.entries.forEachIndexed { index, orientation ->
            val transform = SessionTransform.calculate(
                displayOrientation = orientation,
                sensorOrientationDegrees = 90,
                lensFacing = CameraLensFacing.BACK,
                codedWidth = 2_560,
                codedHeight = 1_440,
            )
            assertEquals(expected[index], transform.rotationDegrees)
        }
    }

    @Test
    fun frontCameraCoversReverseRotations() {
        val expected = listOf(90, 180, 270, 0)
        DisplayOrientation.entries.forEachIndexed { index, orientation ->
            val transform = SessionTransform.calculate(
                displayOrientation = orientation,
                sensorOrientationDegrees = 90,
                lensFacing = CameraLensFacing.FRONT,
                codedWidth = 2_560,
                codedHeight = 1_440,
            )
            assertEquals(expected[index], transform.rotationDegrees)
        }
    }

    @Test
    fun portraitDisplayGeometrySwapsCodedEdges() {
        val transform = SessionTransform.calculate(
            displayOrientation = DisplayOrientation.REVERSE_PORTRAIT,
            sensorOrientationDegrees = 90,
            lensFacing = CameraLensFacing.BACK,
            codedWidth = 2_560,
            codedHeight = 1_440,
        )

        assertEquals(1_440, transform.displayWidth)
        assertEquals(2_560, transform.displayHeight)
        assertEquals(270, transform.rotationDegrees)
    }
}
