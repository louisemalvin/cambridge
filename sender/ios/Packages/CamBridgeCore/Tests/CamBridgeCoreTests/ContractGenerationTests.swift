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

@Test("generated sender settings expose independent product choices and fresh defaults")
func generatedSenderSettings() {
    #expect(SenderVideoCatalog.resolutions.map(\.id) == ["full-hd", "2k"])
    #expect(SenderVideoCatalog.frameRates == [30, 60])
    #expect(SenderVideoCatalog.defaultResolution == SenderVideoCatalog.fullHd)
    #expect(SenderVideoCatalog.defaultFrameRate == 30)
    #expect(SenderVideoCatalog.profileID == "sender")
    #expect(SenderVideoCatalog.suggestedBitrateBps(resolution: SenderVideoCatalog.fullHd, fps: 30) == 5_000_000)
    #expect(SenderVideoCatalog.suggestedBitrateBps(resolution: SenderVideoCatalog.fullHd, fps: 60) == 10_000_000)
    #expect(SenderVideoCatalog.suggestedBitrateBps(resolution: SenderVideoCatalog.resolution2k, fps: 30) == 9_000_000)
    #expect(SenderVideoCatalog.suggestedBitrateBps(resolution: SenderVideoCatalog.resolution2k, fps: 60) == 18_000_000)
}

@Test("bitrate input accepts only whole product Mbps without clamping")
func bitrateInputGrammar() {
    #expect(BitrateInput.bitsPerSecond(fromWholeMegabits: "1") == 1_000_000)
    #expect(BitrateInput.bitsPerSecond(fromWholeMegabits: "100") == 100_000_000)
    #expect(BitrateInput.bitsPerSecond(fromWholeMegabits: "0") == nil)
    #expect(BitrateInput.bitsPerSecond(fromWholeMegabits: "101") == nil)
    #expect(BitrateInput.bitsPerSecond(fromWholeMegabits: "1.5") == nil)
    #expect(BitrateInput.bitsPerSecond(fromWholeMegabits: "1e1") == nil)
    #expect(BitrateInput.bitsPerSecond(fromWholeMegabits: "1,000") == nil)
    #expect(BitrateInput.bitsPerSecond(fromWholeMegabits: " 1") == nil)
}

@Test("a one Mbps override forms the exact Full HD 60 request")
func manualBitrateOverride() throws {
    let configuration = try StreamConfiguration(
        resolution: SenderVideoCatalog.fullHd,
        fps: 60,
        bitrateBps: 1_000_000,
        orientation: .zero
    )
    #expect(configuration.geometry.codedWidth == 1920)
    #expect(configuration.geometry.codedHeight == 1080)
    #expect(configuration.fps == 60)
    #expect(configuration.bitrateBps == 1_000_000)
}
