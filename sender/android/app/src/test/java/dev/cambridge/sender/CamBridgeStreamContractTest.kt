package dev.cambridge.sender

import dev.cambridge.sender.connection.control.cambridge.CamBridgeStreamContract
import dev.cambridge.sender.connection.control.cambridge.CamBridgeStreamContract.intField
import dev.cambridge.sender.connection.control.cambridge.CamBridgeStreamContract.requireProtocolVersion
import dev.cambridge.sender.connection.control.cambridge.CamBridgeStreamContract.stringField
import dev.cambridge.sender.connection.control.cambridge.CamBridgeStreamContract.requireCapabilities
import dev.cambridge.sender.model.ReceiverCapabilities
import dev.cambridge.sender.session.VideoProfiles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CamBridgeStreamContractTest {
    @Test
    fun helloUsesV6PhoneAuthoredValuesAndResolvedRotation() {
        val hello = CamBridgeStreamContract.hello(
            sessionId = "test-session",
            generation = 1,
            profileId = VideoProfiles.PROFILE_2K30.id,
            codedWidth = 2_560,
            codedHeight = 1_440,
            rotationDegrees = 90,
            fps = VideoProfiles.PROFILE_2K30.fps,
            bitrateBps = 18_000_000,
        )

        assertEquals(CamBridgeStreamContract.PROTOCOL_VERSION, hello.requireProtocolVersion())
        assertEquals("hello", hello.stringField("type"))
        assertEquals(VideoProfiles.PROFILE_2K30.id, hello.stringField("profileId"))
        assertEquals(2_560, hello.intField("codedWidth"))
        assertEquals(1_440, hello.intField("codedHeight"))
        assertEquals(90, hello.intField("rotationDegrees"))
        assertEquals(VideoProfiles.PROFILE_2K30.fps, hello.intField("fps"))
    }

    @Test
    fun probeAndCapabilitiesUseMatchingRequestId() {
        val probe = CamBridgeStreamContract.probe("probe-1")
        assertEquals("probe", probe.stringField("type"))
        assertEquals("probe-1", probe.stringField("requestId"))

        val response = Json.parseToJsonElement(
            """
            {
              "protocolVersion": 6,
              "type": "capabilities",
              "requestId": "probe-1",
              "receiverId": "cambridge-obs-source",
              "displayName": "OBS receiver",
              "maxLongEdge": 3840,
              "maxShortEdge": 2160
            }
            """.trimIndent(),
        ).jsonObject
        val capabilities: ReceiverCapabilities = response.requireCapabilities("probe-1")

        assertEquals("OBS receiver", capabilities.displayName)
        assertTrue(capabilities.supportsGeometry(1_920, 1_080))
    }

    @Test
    fun qualityAndFrameRateChoicesResolveToExactProfiles() {
        assertEquals(
            listOf("1080p30", "2k30"),
            VideoProfiles.qualityProfiles.map { profile -> profile.id },
        )
        assertEquals(
            listOf(30, 60),
            VideoProfiles.profilesForResolution(VideoProfiles.PROFILE_1080P30)
                .map { profile -> profile.fps }
                .distinct()
                .sorted(),
        )
        assertEquals(
            VideoProfiles.PROFILE_1080P60,
            VideoProfiles.profileForResolution(width = 1_920, height = 1_080, fps = 60),
        )
        assertEquals(
            VideoProfiles.PROFILE_2K60,
            VideoProfiles.profileForResolution(width = 2_560, height = 1_440, fps = 60),
        )
    }
}
