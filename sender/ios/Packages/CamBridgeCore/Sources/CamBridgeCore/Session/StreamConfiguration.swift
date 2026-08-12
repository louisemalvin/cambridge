import Foundation

public struct StreamConfiguration: Codable, Equatable, Hashable, Sendable {
    public let resolution: VideoResolution
    public let fps: Int
    public let bitrateBps: Int
    public let orientation: StreamRotation
    public let geometry: VideoGeometry

    public init(resolution: VideoResolution, fps: Int, bitrateBps: Int, orientation: StreamRotation) throws {
        try resolution.validate()
        guard SenderVideoCatalog.resolutions.contains(resolution),
              let geometry = resolution.geometry else {
            throw StreamConfigurationError.invalidResolution
        }
        guard SenderVideoCatalog.frameRates.contains(fps) else {
            throw StreamConfigurationError.invalidFrameRate(fps)
        }
        guard BitrateInput.wholeMegabits(fromBitsPerSecond: bitrateBps) != nil,
              bitrateBps >= CamBridgeContract.Bitrate.minimumBps,
              bitrateBps <= CamBridgeContract.Bitrate.maximumBps else {
            throw StreamConfigurationError.invalidBitrate(bitrateBps)
        }
        self.resolution = resolution
        self.fps = fps
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
    case invalidResolution
    case invalidFrameRate(Int)
    case invalidBitrate(Int)
    case receiverGeometryUnsupported
}
