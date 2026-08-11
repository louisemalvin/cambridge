import Foundation

public struct ControlFrameDecoder: Sendable {
    private var header: [UInt8] = []
    private var expectedPayloadLength: Int?
    private var payload = Data()

    public init() {}

    public var hasPendingFrame: Bool {
        !header.isEmpty || expectedPayloadLength != nil || !payload.isEmpty
    }

    public mutating func append(_ data: Data) throws -> [Data] {
        var frames: [Data] = []
        var cursor = data.startIndex
        while cursor < data.endIndex {
            if expectedPayloadLength == nil {
                while header.count < MemoryLayout<UInt32>.size, cursor < data.endIndex {
                    header.append(data[cursor])
                    cursor = data.index(after: cursor)
                }
                guard header.count == MemoryLayout<UInt32>.size else { continue }
                let length = header.reduce(UInt64.zero) { partial, byte in
                    (partial << Self.byteShift) | UInt64(byte)
                }
                header.removeAll(keepingCapacity: true)
                guard length > .zero else {
                    reset()
                    throw ControlFrameError.emptyPayload
                }
                guard length <= UInt64(CamBridgeContract.Control.maximumMessageBytes) else {
                    reset()
                    throw ControlFrameError.oversizedPayload(Int(min(length, UInt64(Int.max))))
                }
                expectedPayloadLength = Int(length)
                payload.removeAll(keepingCapacity: true)
                payload.reserveCapacity(Int(length))
            }

            guard let expectedPayloadLength else { continue }
            let remaining = expectedPayloadLength - payload.count
            let available = data.distance(from: cursor, to: data.endIndex)
            let amount = min(remaining, available)
            let end = data.index(cursor, offsetBy: amount)
            payload.append(data[cursor..<end])
            cursor = end
            if payload.count == expectedPayloadLength {
                frames.append(payload)
                self.expectedPayloadLength = nil
                payload.removeAll(keepingCapacity: true)
            }
        }
        return frames
    }

    public mutating func reset() {
        header.removeAll(keepingCapacity: true)
        expectedPayloadLength = nil
        payload.removeAll(keepingCapacity: true)
    }

    private static let byteShift = 8
}
