import Foundation
import Testing
import CamBridgeCore

@Test("RTP packet encodes RFC 3550 header fields")
func rtpHeaderEncoding() throws {
    let packet = try RTPPacket(
        marker: true,
        sequence: 0x1234,
        timestamp: 90_000,
        ssrc: 0x1020_3040,
        payload: Data([0x65, 1, 2, 3])
    )
    #expect(Array(packet.encoded()) == [0x80, 0xe0, 0x12, 0x34, 0, 1, 0x5f, 0x90, 0x10, 0x20, 0x30, 0x40, 0x65, 1, 2, 3])
}

@Test("RTP packet rejects payload types outside the seven-bit field")
func invalidRTPPayloadType() {
    #expect(throws: Error.self) {
        try RTPPacket(
            marker: false,
            payloadType: 128,
            sequence: .zero,
            timestamp: .zero,
            ssrc: .zero,
            payload: Data([0x65])
        )
    }
}
