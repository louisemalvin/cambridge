import Foundation

public struct RTPH264Packetizer: Sendable {
    public let ssrc: UInt32
    public private(set) var nextSequence: UInt16
    private let mtu: Int

    public init(ssrc: UInt32 = UInt32.random(in: UInt32.min...UInt32.max), initialSequence: UInt16 = UInt16.random(in: UInt16.min...UInt16.max), mtu: Int = CamBridgeContract.Media.mtuBytes) throws {
        guard mtu >= CamBridgeContract.Media.rtpHeaderBytes + Self.minimumFUAPayloadBytes,
              mtu <= CamBridgeContract.Media.maxDatagramBytes else {
            throw RTPH264PacketizerError.invalidMTU(mtu)
        }
        self.ssrc = ssrc
        self.nextSequence = initialSequence
        self.mtu = mtu
    }

    public mutating func packetize(_ annexBAccessUnit: Data, presentationTimeMicroseconds: Int64) throws -> [Data] {
        guard !annexBAccessUnit.isEmpty else { throw RTPH264PacketizerError.emptyAccessUnit }
        guard annexBAccessUnit.count <= CamBridgeContract.Media.maxAccessUnitBytes else {
            throw RTPH264PacketizerError.oversizedAccessUnit(annexBAccessUnit.count)
        }
        guard presentationTimeMicroseconds >= .zero else {
            throw RTPH264PacketizerError.negativePresentationTime
        }
        let nals = try nalRanges(in: annexBAccessUnit)
        guard !nals.isEmpty else { throw RTPH264PacketizerError.noNALUnits }
        let timestamp = Self.rtpTimestamp(for: presentationTimeMicroseconds)
        var packets: [Data] = []
        for (index, range) in nals.enumerated() {
            let nal = Data(annexBAccessUnit[range])
            let isLastNAL = index == nals.index(before: nals.endIndex)
            try appendPackets(for: nal, timestamp: timestamp, isLastNAL: isLastNAL, to: &packets)
        }
        return packets
    }

    public mutating func send(
        _ annexBAccessUnit: Data,
        presentationTimeMicroseconds: Int64,
        using sendPacket: (Data) throws -> Void
    ) throws {
        let packets = try packetize(annexBAccessUnit, presentationTimeMicroseconds: presentationTimeMicroseconds)
        for packet in packets {
            try sendPacket(packet)
        }
    }

    private mutating func appendPackets(for nal: Data, timestamp: UInt32, isLastNAL: Bool, to packets: inout [Data]) throws {
        guard !nal.isEmpty else { throw RTPH264PacketizerError.emptyNAL }
        let maximumPayload = mtu - CamBridgeContract.Media.rtpHeaderBytes
        if nal.count <= maximumPayload {
            let packet = try makePacket(marker: isLastNAL, timestamp: timestamp, payload: nal)
            packets.append(packet)
            return
        }

        let maximumFragmentPayload = maximumPayload - Self.fuaHeaderBytes
        guard maximumFragmentPayload >= Self.minimumFUAPayloadBytes else {
            throw RTPH264PacketizerError.invalidMTU(mtu)
        }
        let nalHeader = nal[nal.startIndex]
        let indicator = (nalHeader & Self.nalReferenceMask) | Self.fuaType
        let nalType = nalHeader & Self.nalTypeMask
        var offset = nal.index(after: nal.startIndex)
        var isFirst = true
        while offset < nal.endIndex {
            let remaining = nal.distance(from: offset, to: nal.endIndex)
            let fragmentLength = min(maximumFragmentPayload, remaining)
            let fragmentEnd = nal.index(offset, offsetBy: fragmentLength)
            let isEnd = fragmentEnd == nal.endIndex
            let flags = (isFirst ? Self.fuaStartMask : .zero) | (isEnd ? Self.fuaEndMask : .zero) | nalType
            var payload = Data(capacity: Self.fuaHeaderBytes + fragmentLength)
            payload.append(indicator)
            payload.append(flags)
            payload.append(nal[offset..<fragmentEnd])
            packets.append(try makePacket(marker: isEnd && isLastNAL, timestamp: timestamp, payload: payload))
            offset = fragmentEnd
            isFirst = false
        }
    }

    private mutating func makePacket(marker: Bool, timestamp: UInt32, payload: Data) throws -> Data {
        let packet = try RTPPacket(marker: marker, sequence: nextSequence, timestamp: timestamp, ssrc: ssrc, payload: payload)
        nextSequence = nextSequence &+ Self.sequenceIncrement
        return packet.encoded()
    }

    private func nalRanges(in accessUnit: Data) throws -> [Range<Data.Index>] {
        var ranges: [Range<Data.Index>] = []
        var cursor = accessUnit.startIndex
        guard let first = findStartCode(in: accessUnit, from: cursor), first.offset == accessUnit.startIndex else {
            throw RTPH264PacketizerError.missingStartCode
        }
        while let start = findStartCode(in: accessUnit, from: cursor) {
            let nalStart = accessUnit.index(start.offset, offsetBy: start.length)
            guard nalStart < accessUnit.endIndex else { throw RTPH264PacketizerError.emptyNAL }
            let next = findStartCode(in: accessUnit, from: nalStart)
            var nalEnd = next?.offset ?? accessUnit.endIndex
            while nalEnd > nalStart, accessUnit[accessUnit.index(before: nalEnd)] == .zero {
                nalEnd = accessUnit.index(before: nalEnd)
            }
            guard nalEnd > nalStart else { throw RTPH264PacketizerError.emptyNAL }
            _ = try H264NALUnit(data: Data(accessUnit[nalStart..<nalEnd]))
            ranges.append(nalStart..<nalEnd)
            cursor = next?.offset ?? accessUnit.endIndex
        }
        return ranges
    }

    private func findStartCode(in data: Data, from: Data.Index) -> (offset: Data.Index, length: Int)? {
        var cursor = from
        while data.distance(from: cursor, to: data.endIndex) >= Self.minimumStartCodeBytes {
            let second = data.index(after: cursor)
            let third = data.index(second, offsetBy: Self.oneByte)
            if data[cursor] == .zero, data[second] == .zero {
                if data[third] == Self.startCodeTerminator {
                    return (cursor, Self.threeByteStartCodeBytes)
                }
                if data[third] == .zero,
                   data.distance(from: cursor, to: data.endIndex) >= Self.fourByteStartCodeBytes,
                   data[data.index(cursor, offsetBy: Self.threeBytes)] == Self.startCodeTerminator {
                    return (cursor, Self.fourByteStartCodeBytes)
                }
            }
            cursor = data.index(after: cursor)
        }
        return nil
    }

    private static func rtpTimestamp(for presentationTimeMicroseconds: Int64) -> UInt32 {
        let seconds = presentationTimeMicroseconds / Self.microsecondsPerSecond
        let remainder = presentationTimeMicroseconds % Self.microsecondsPerSecond
        let whole = UInt64(seconds) &* UInt64(CamBridgeContract.Media.clockRateHz)
        let fractional = UInt64(remainder) * UInt64(CamBridgeContract.Media.clockRateHz) / UInt64(Self.microsecondsPerSecond)
        return UInt32(truncatingIfNeeded: whole &+ fractional)
    }

    private static let minimumFUAPayloadBytes = 1
    private static let fuaHeaderBytes = 2
    private static let nalReferenceMask: UInt8 = 224
    private static let fuaType: UInt8 = 28
    private static let nalTypeMask: UInt8 = 31
    private static let fuaStartMask: UInt8 = 128
    private static let fuaEndMask: UInt8 = 64
    private static let sequenceIncrement: UInt16 = 1
    private static let microsecondsPerSecond: Int64 = 1_000_000
    private static let oneByte = 1
    private static let threeBytes = 3
    private static let minimumStartCodeBytes = 3
    private static let threeByteStartCodeBytes = 3
    private static let fourByteStartCodeBytes = 4
    private static let startCodeTerminator: UInt8 = 1
}

public enum RTPH264PacketizerError: Error, Equatable, Sendable {
    case emptyAccessUnit
    case oversizedAccessUnit(Int)
    case negativePresentationTime
    case noNALUnits
    case missingStartCode
    case emptyNAL
    case invalidMTU(Int)
}
