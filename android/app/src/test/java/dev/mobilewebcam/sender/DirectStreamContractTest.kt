package dev.mobilewebcam.sender

import dev.mobilewebcam.sender.connection.control.direct.DirectStreamContract
import dev.mobilewebcam.sender.connection.control.direct.DirectStreamContract.intField
import dev.mobilewebcam.sender.connection.control.direct.DirectStreamContract.requireProtocolVersion
import dev.mobilewebcam.sender.connection.control.direct.DirectStreamContract.stringField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DirectStreamContractTest {
    @Test
    fun helloUsesV2CodedAndDisplayGeometry() {
        val hello = DirectStreamContract.hello(
            sessionId = "test-session",
            generation = 1,
            codedWidth = 2_560,
            codedHeight = 1_440,
            displayWidth = 1_440,
            displayHeight = 2_560,
            rotationDegrees = 90,
            fps = DirectStreamContract.SUPPORTED_FPS,
            bitrateBps = 18_000_000,
        )

        assertEquals(DirectStreamContract.PROTOCOL_VERSION, hello.requireProtocolVersion())
        assertEquals("hello", hello.stringField("type"))
        assertEquals(2_560, hello.intField("codedWidth"))
        assertEquals(1_440, hello.intField("codedHeight"))
        assertEquals(1_440, hello.intField("displayWidth"))
        assertEquals(2_560, hello.intField("displayHeight"))
        assertEquals(90, hello.intField("rotationDegrees"))
        assertFalse("width" in hello)
        assertFalse("height" in hello)
    }
}
