import Foundation

public struct RTPPacket: Equatable, Sendable {
    public let marker: Bool
    public let payloadType: UInt8
    public let sequence: UInt16
    public let timestamp: UInt32
    public let ssrc: UInt32
    public let payload: Data

    public init(
        marker: Bool,
        payloadType: UInt8 = UInt8(CamBridgeContract.Media.payloadType),
        sequence: UInt16,
        timestamp: UInt32,
        ssrc: UInt32,
        payload: Data
    ) throws {
        guard !payload.isEmpty else { throw RTPPacketError.emptyPayload }
        guard payloadType <= Self.maximumPayloadType else {
            throw RTPPacketError.invalidPayloadType(payloadType)
        }
        guard payload.count + Self.headerBytes <= CamBridgeContract.Media.mtuBytes else {
            throw RTPPacketError.exceedsMTU(payload.count + Self.headerBytes)
        }
        self.marker = marker
        self.payloadType = payloadType
        self.sequence = sequence
        self.timestamp = timestamp
        self.ssrc = ssrc
        self.payload = payload
    }

    public func encoded() -> Data {
        var packet = Data(capacity: Self.headerBytes + payload.count)
        packet.append(Self.version << Self.versionShift)
        packet.append((marker ? Self.markerMask : .zero) | payloadType)
        packet.append(UInt8(truncatingIfNeeded: sequence >> Self.byteShift))
        packet.append(UInt8(truncatingIfNeeded: sequence))
        appendBigEndian(timestamp, to: &packet)
        appendBigEndian(ssrc, to: &packet)
        packet.append(payload)
        return packet
    }

    private func appendBigEndian(_ value: UInt32, to data: inout Data) {
        data.append(UInt8(truncatingIfNeeded: value >> (Self.byteShift * Self.wordBytes - Self.byteShift)))
        data.append(UInt8(truncatingIfNeeded: value >> (Self.byteShift * Self.wordBytes - Self.byteShift * Self.twoBytes)))
        data.append(UInt8(truncatingIfNeeded: value >> Self.byteShift))
        data.append(UInt8(truncatingIfNeeded: value))
    }

    private static let headerBytes = CamBridgeContract.Media.rtpHeaderBytes
    private static let maximumPayloadType: UInt8 = 127
    private static let version: UInt8 = 2
    private static let versionShift = 6
    private static let markerMask: UInt8 = 128
    private static let byteShift = 8
    private static let wordBytes = 4
    private static let twoBytes = 2
}

public enum RTPPacketError: Error, Equatable, Sendable {
    case emptyPayload
    case invalidPayloadType(UInt8)
    case exceedsMTU(Int)
}
