package dev.mobilewebcam.sender

import dev.mobilewebcam.sender.ui.model.PreviewViewportCalculator
import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewViewportTest {
    @Test
    fun landscapeContentFitsHeightWhenContainerIsWider() {
        val viewport = PreviewViewportCalculator.fit(
            containerWidth = 2000.0f,
            containerHeight = 1000.0f,
            aspectRatio = 16.0f / 9.0f,
        )

        assertEquals(16.0f / 9.0f * 1000.0f, viewport.width, TOLERANCE)
        assertEquals(1000.0f, viewport.height, TOLERANCE)
    }

    @Test
    fun portraitContentFitsWidthWhenContainerIsTaller() {
        val viewport = PreviewViewportCalculator.fit(
            containerWidth = 1000.0f,
            containerHeight = 1600.0f,
            aspectRatio = 9.0f / 16.0f,
        )

        assertEquals(900.0f, viewport.width, TOLERANCE)
        assertEquals(1600.0f, viewport.height, TOLERANCE)
    }

    @Test
    fun zeroContainerDimensionDoesNotProduceInvalidValues() {
        val viewport = PreviewViewportCalculator.fit(
            containerWidth = 0.0f,
            containerHeight = 1000.0f,
            aspectRatio = 16.0f / 9.0f,
        )

        assertEquals(0.0f, viewport.width, TOLERANCE)
        assertEquals(1000.0f, viewport.height, TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 0.0001f
    }
}
