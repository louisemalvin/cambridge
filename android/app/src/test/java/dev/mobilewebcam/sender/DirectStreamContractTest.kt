package dev.mobilewebcam.sender

import dev.mobilewebcam.sender.connection.control.direct.DirectStreamContract
import dev.mobilewebcam.sender.connection.control.direct.DirectStreamContract.intField
import dev.mobilewebcam.sender.connection.control.direct.DirectStreamContract.requireProtocolVersion
import dev.mobilewebcam.sender.connection.control.direct.DirectStreamContract.stringField
import dev.mobilewebcam.sender.connection.control.direct.DirectStreamContract.requireCapabilities
import dev.mobilewebcam.sender.connection.control.direct.DirectStreamContract.stringListField
import dev.mobilewebcam.sender.model.ReceiverCapabilities
import dev.mobilewebcam.sender.session.VideoProfiles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
            fps = VideoProfiles.PROFILE_2K30.fps,
            bitrateBps = 18_000_000,
        )

        assertEquals(DirectStreamContract.PROTOCOL_VERSION, hello.requireProtocolVersion())
        assertEquals("hello", hello.stringField("type"))
        assertEquals(VideoProfiles.PROFILE_2K30.id, hello.stringField("profileId"))
        assertEquals(2_560, hello.intField("codedWidth"))
        assertEquals(1_440, hello.intField("codedHeight"))
        assertEquals(90, hello.intField("rotationDegrees"))
        assertEquals(VideoProfiles.PROFILE_2K30.fps, hello.intField("fps"))
    }

    @Test
    fun probeAndCapabilitiesUseMatchingRequestId() {
        val probe = DirectStreamContract.probe("probe-1")
        assertEquals("probe", probe.stringField("type"))
        assertEquals("probe-1", probe.stringField("requestId"))

        val response = Json.parseToJsonElement(
            """
            {
              "protocolVersion": 4,
              "type": "capabilities",
              "requestId": "probe-1",
              "receiverId": "obs-direct-webcam-source",
              "displayName": "OBS receiver",
              "profiles": ["1080p30", "2k30"],
              "maxLongEdge": 3840,
              "maxShortEdge": 2160
            }
            """.trimIndent(),
        ).jsonObject
        val capabilities: ReceiverCapabilities = response.requireCapabilities("probe-1")

        assertEquals("OBS receiver", capabilities.displayName)
        assertEquals(listOf("1080p30", "2k30"), capabilities.profileIds)
        assertTrue(capabilities.supports(VideoProfiles.PROFILE_1080P30))
        assertEquals(listOf("1080p30", "2k30"), response.stringListField("profiles"))
    }

    @Test
    fun qualityAndFrameRateChoicesResolveToExactProfiles() {
        assertEquals(
            listOf("1080p30", "2k30"),
            VideoProfiles.qualityProfiles.map { profile -> profile.id },
        )
        assertEquals(
            listOf(15, 30),
            VideoProfiles.profilesForResolution(VideoProfiles.PROFILE_1080P30)
                .map { profile -> profile.fps }
                .distinct()
                .sorted(),
        )
        assertEquals(
            VideoProfiles.PROFILE_1080P15,
            VideoProfiles.profileForResolution(width = 1_920, height = 1_080, fps = 15),
        )
        assertEquals(
            VideoProfiles.PROFILE_2K15,
            VideoProfiles.profileForResolution(width = 2_560, height = 1_440, fps = 15),
        )
    }
}
