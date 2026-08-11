import Foundation

public struct StreamConfiguration: Codable, Equatable, Hashable, Sendable {
    public let mode: VideoMode
    public let bitrateBps: Int
    public let orientation: StreamRotation
    public let geometry: VideoGeometry

    public init(mode: VideoMode, bitrateBps: Int, orientation: StreamRotation) throws {
        try mode.validate()
        guard let geometry = mode.geometry else { throw StreamConfigurationError.invalidMode }
        guard mode.steppedBitrates(encoderRange: CamBridgeContract.Bitrate.minimumBps...CamBridgeContract.Bitrate.maximumBps).contains(bitrateBps) else {
            throw StreamConfigurationError.invalidBitrate(bitrateBps)
        }
        self.mode = mode
        self.bitrateBps = bitrateBps
        self.orientation = orientation
        self.geometry = geometry
    }

    public func validate(receiver: ReceiverCapabilities) throws {
        guard receiver.supports(geometry, rotation: orientation) else {
            throw StreamConfigurationError.receiverGeometryUnsupported
        }
    }
}

public enum StreamConfigurationError: Error, Equatable, Sendable {
    case invalidMode
    case invalidBitrate(Int)
    case receiverGeometryUnsupported
}
