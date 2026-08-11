import Foundation

public enum ControlFrameError: Error, Equatable, Sendable {
    case emptyPayload
    case oversizedPayload(Int)
    case invalidFrameLength(UInt64)
}

public enum ControlFrameEncoder {
    public static func frame(_ payload: Data) throws -> Data {
        guard !payload.isEmpty else { throw ControlFrameError.emptyPayload }
        guard payload.count <= CamBridgeContract.Control.maximumMessageBytes else {
            throw ControlFrameError.oversizedPayload(payload.count)
        }
        guard payload.count <= Int(UInt32.max) else {
            throw ControlFrameError.invalidFrameLength(UInt64(payload.count))
        }
        var length = UInt32(payload.count).bigEndian
        var framed = Data(capacity: MemoryLayout<UInt32>.size + payload.count)
        withUnsafeBytes(of: &length) { framed.append(contentsOf: $0) }
        framed.append(payload)
        return framed
    }
}
