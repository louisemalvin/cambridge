import Foundation
import Testing
import CamBridgeCore

@Test("AVCC samples become Annex-B and keyframes get SPS/PPS")
func avccNormalization() throws {
    let sample = CamBridgeTestFixtures.avcc([CamBridgeTestFixtures.idrNAL])
    let normalized = try H264AccessUnitNormalizer().normalize(
        sample: sample,
        nalLengthBytes: 4,
        parameterSets: [CamBridgeTestFixtures.sps, CamBridgeTestFixtures.pps],
        isKeyframe: true
    )
    #expect(normalized == CamBridgeTestFixtures.annexB([CamBridgeTestFixtures.sps, CamBridgeTestFixtures.pps, CamBridgeTestFixtures.idrNAL]))
}

@Test("all supported AVCC length widths are parsed")
func avccLengthWidths() throws {
    for lengthBytes in 1...4 {
        let sample = CamBridgeTestFixtures.avcc([CamBridgeTestFixtures.idrNAL], lengthBytes: lengthBytes)
        let normalized = try H264AccessUnitNormalizer().normalize(
            sample: sample,
            nalLengthBytes: lengthBytes,
            parameterSets: [],
            isKeyframe: false
        )
        #expect(normalized == CamBridgeTestFixtures.annexB([CamBridgeTestFixtures.idrNAL]))
    }
}

@Test("truncated and oversized access units are rejected")
func malformedAccessUnits() throws {
    #expect(throws: Error.self) {
        try H264AccessUnitNormalizer().normalize(
            sample: Data([0, 0, 0, 5, 0x65]),
            nalLengthBytes: 4,
            parameterSets: [],
            isKeyframe: false
        )
    }
    #expect(throws: Error.self) {
        try H264AccessUnitNormalizer().normalize(
            sample: Data(repeating: 0, count: CamBridgeContract.Media.maxAccessUnitBytes + 1),
            nalLengthBytes: 4,
            parameterSets: [],
            isKeyframe: false
        )
    }
}
