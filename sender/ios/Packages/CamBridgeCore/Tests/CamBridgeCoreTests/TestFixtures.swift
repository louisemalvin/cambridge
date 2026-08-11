import Foundation
import CamBridgeCore

enum CamBridgeTestFixtures {
    static let requestId = "request-test"
    static let sessionId = "session-test"
    static let generation: UInt64 = 7
    static let profileId = "2k30"
    static let sps = Data([0x67, 0x42, 0x00, 0x1f])
    static let pps = Data([0x68, 0xce, 0x06, 0xe2])
    static let idrNAL = Data([0x65, 0x01, 0x02, 0x03])
    static let recordedEncoderBitrateRange = 5_500_000...12_500_000

    static func hello(rotation: StreamRotation = .ninety) -> ControlMessage {
        .hello(
            sessionId: sessionId,
            generation: generation,
            profileId: profileId,
            codedWidth: 2560,
            codedHeight: 1440,
            rotation: rotation,
            fps: 30,
            bitrateBps: 18_000_000
        )
    }

    static func accepted() -> ControlMessage {
        .accepted(
            sessionId: sessionId,
            generation: generation,
            profileId: profileId,
            mediaPort: 55_032,
            maxLongEdge: CamBridgeContract.Geometry.maximumLongEdge,
            maxShortEdge: CamBridgeContract.Geometry.maximumShortEdge
        )
    }

    static func avcc(_ nals: [Data], lengthBytes: Int = 4) -> Data {
        var data = Data()
        for nal in nals {
            var length = nal.count
            var prefix = Array(repeating: UInt8.zero, count: lengthBytes)
            for index in prefix.indices.reversed() {
                prefix[index] = UInt8(truncatingIfNeeded: length)
                length >>= 8
            }
            data.append(contentsOf: prefix)
            data.append(nal)
        }
        return data
    }

    static func annexB(_ nals: [Data]) -> Data {
        nals.reduce(into: Data()) { result, nal in
            result.append(contentsOf: [0, 0, 0, 1])
            result.append(nal)
        }
    }
}
