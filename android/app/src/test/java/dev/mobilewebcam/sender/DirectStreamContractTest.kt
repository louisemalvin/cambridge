package dev.mobilewebcam.sender

import dev.mobilewebcam.sender.connection.control.direct.DirectStreamContract
import dev.mobilewebcam.sender.connection.control.direct.DirectStreamContract.intField
import dev.mobilewebcam.sender.connection.control.direct.DirectStreamContract.requireProtocolVersion
import dev.mobilewebcam.sender.connection.control.direct.DirectStreamContract.stringField
import dev.mobilewebcam.sender.session.VideoProfiles
import org.junit.Assert.assertEquals
import org.junit.Test

class DirectStreamContractTest {
    @Test
    fun helloUsesV4ProfileAndResolvedRotation() {
        val hello = DirectStreamContract.hello(
            sessionId = "test-session",
            generation = 1,
            profileId = VideoProfiles.PROFILE_2K30.id,
            codedWidth = 2_560,
            codedHeight = 1_440,
            rotationDegrees = 90,
            fps = DirectStreamContract.SUPPORTED_FPS,
            bitrateBps = 18_000_000,
        )

        assertEquals(DirectStreamContract.PROTOCOL_VERSION, hello.requireProtocolVersion())
        assertEquals("hello", hello.stringField("type"))
        assertEquals(VideoProfiles.PROFILE_2K30.id, hello.stringField("profileId"))
        assertEquals(2_560, hello.intField("codedWidth"))
        assertEquals(1_440, hello.intField("codedHeight"))
        assertEquals(90, hello.intField("rotationDegrees"))
        assertEquals(DirectStreamContract.SUPPORTED_FPS, hello.intField("fps"))
    }
}
