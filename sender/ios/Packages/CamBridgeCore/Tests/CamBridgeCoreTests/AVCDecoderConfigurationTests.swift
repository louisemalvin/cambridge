import Foundation
import Testing
import CamBridgeCore

@Test("AVC configuration extracts NAL length width and parameter sets")
func avcConfigurationParsing() throws {
    let configuration = Data([1, 0x64, 0, 0x1f, 0xff, 0xe1, 0, 4, 0x67, 0x42, 0, 0x1f, 1, 0, 4, 0x68, 0xce, 6, 0xe2])
    let parsed = try AVCDecoderConfiguration(data: configuration)
    #expect(parsed.nalLengthBytes == 4)
    #expect(parsed.sps == [CamBridgeTestFixtures.sps])
    #expect(parsed.pps == [CamBridgeTestFixtures.pps])
}

@Test("truncated AVC configuration is rejected")
func malformedAvcConfiguration() {
    #expect(throws: Error.self) { try AVCDecoderConfiguration(data: Data([1, 0, 0])) }
}

@Test("AVC configuration rejects parameter sets with the wrong NAL type")
func invalidAvcParameterSetTypes() {
    let invalidSPS = Data([
        1, 0x64, 0, 0x1f, 0xff, 0xe1,
        0, 2, 0x68, 0x01,
        1, 0, 2, 0x68, 0x01,
    ])
    #expect(throws: AVCDecoderConfigurationError.invalidParameterSetType("SPS")) {
        _ = try AVCDecoderConfiguration(data: invalidSPS)
    }
}
