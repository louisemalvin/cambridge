import Testing
import CamBridgeCore

@Test("generated contract constants match the v6 boundary")
func generatedContractConstants() {
    #expect(CamBridgeContract.name == "cambridge-stream")
    #expect(CamBridgeContract.protocolVersion == 6)
    #expect(CamBridgeContract.Control.maximumMessageBytes == 8192)
    #expect(CamBridgeContract.Media.mtuBytes == 1200)
    #expect(CamBridgeContract.Media.maxInFlightAccessUnits == 2)
    #expect(CamBridgeContract.Validation.maximumIdentifierLength == 128)
}

@Test("generated mode catalog preserves product and test-only modes")
func generatedModeCatalog() {
    #expect(VideoMode.allModes.map(\.id) == ["720p30", "1080p30", "1080p60", "2k30", "2k60"])
    #expect(VideoMode.productModes.map(\.id) == ["1080p30", "1080p60", "2k30", "2k60"])
    #expect(VideoMode.defaultMode.id == "2k30")
    #expect(VideoMode.mode2k60.maximumBitrateBps == 72_000_000)
}

@Test("mode bitrate choices are the bounded product and encoder intersection")
func steppedBitrateIntersection() {
    let encoderRange = CamBridgeTestFixtures.recordedEncoderBitrateRange
    #expect(VideoMode.mode1080p30.steppedBitrates(encoderRange: encoderRange) == [6_000_000, 7_000_000, 8_000_000, 9_000_000, 10_000_000, 11_000_000, 12_000_000])
    #expect(VideoMode.mode1080p30.steppedBitrates(encoderRange: 17_000_000...18_000_000).isEmpty)
}
