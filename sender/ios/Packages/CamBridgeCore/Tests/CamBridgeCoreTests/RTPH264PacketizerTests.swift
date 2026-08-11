import Foundation
import Testing
import CamBridgeCore

@Test("one NAL uses one packet and final marker")
func singleNALPacketization() throws {
    var packetizer = try RTPH264Packetizer(ssrc: 0x1020_3040, initialSequence: 4000)
    let packets = try packetizer.packetize(CamBridgeTestFixtures.annexB([CamBridgeTestFixtures.idrNAL]), presentationTimeMicroseconds: 1_000_000)
    #expect(packets.count == 1)
    #expect(packets[0][1] & 0x80 == 0x80)
    #expect(packets[0][2] == 0x0f)
    #expect(packets[0][3] == 0xa0)
    #expect(packets[0].count <= CamBridgeContract.Media.mtuBytes)
}

@Test("large NAL uses FU-A start, middle, end and sequence wrap")
func fragmentedNALPacketization() throws {
    let largeNAL = Data([0x65]) + Data(repeating: 0x55, count: 2500)
    var packetizer = try RTPH264Packetizer(ssrc: 7, initialSequence: UInt16.max)
    let packets = try packetizer.packetize(CamBridgeTestFixtures.annexB([largeNAL]), presentationTimeMicroseconds: .zero)
    #expect(packets.count == 3)
    #expect(packets[0][2] == 0xff)
    #expect(packets[0][3] == 0xff)
    #expect(packets[1][2] == .zero)
    #expect(packets[1][3] == .zero)
    #expect(packets[2][2] == .zero)
    #expect(packets[2][3] == 1)
    #expect(packets.allSatisfy { $0.count <= CamBridgeContract.Media.mtuBytes })
    #expect(packets[0][13] & 0x80 == 0x80)
    #expect(packets[2][13] & 0x40 == 0x40)
    #expect(packets[0][1] & 0x80 == .zero)
    #expect(packets[2][1] & 0x80 == 0x80)
}

@Test("marker only appears on the final packet of a multi-NAL unit")
func markerPlacement() throws {
    var packetizer = try RTPH264Packetizer(ssrc: 7, initialSequence: 1)
    let packets = try packetizer.packetize(CamBridgeTestFixtures.annexB([Data([0x41, 1]), CamBridgeTestFixtures.idrNAL]), presentationTimeMicroseconds: 0)
    #expect(packets.count == 2)
    #expect(packets[0][1] & 0x80 == .zero)
    #expect(packets[1][1] & 0x80 == 0x80)
}

@Test("RTP timestamp conversion wraps at the 32-bit clock")
func timestampConversion() throws {
    var packetizer = try RTPH264Packetizer(ssrc: 7, initialSequence: 1)
    let packets = try packetizer.packetize(CamBridgeTestFixtures.annexB([Data([0x41, 1])]), presentationTimeMicroseconds: 2_000_000)
    #expect(Array(packets[0][4..<8]) == [0, 2, 0xbf, 0x20])
}

@Test("RTP packetizer rejects malformed access units and invalid MTUs")
func malformedPacketizerInput() throws {
    var packetizer = try RTPH264Packetizer(ssrc: 7, initialSequence: 1)
    #expect(throws: Error.self) {
        try packetizer.packetize(Data([0x65, 1]), presentationTimeMicroseconds: .zero)
    }

    let oversizedAccessUnit = Data(repeating: .zero, count: CamBridgeContract.Media.maxAccessUnitBytes + 1)
    #expect(throws: Error.self) {
        try packetizer.packetize(oversizedAccessUnit, presentationTimeMicroseconds: .zero)
    }
    #expect(throws: Error.self) {
        try RTPH264Packetizer(mtu: CamBridgeContract.Media.rtpHeaderBytes)
    }
}
