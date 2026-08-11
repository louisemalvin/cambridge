import Foundation

public struct ReceiverCapabilities: Codable, Equatable, Hashable, Sendable {
    public let receiverId: String
    public let displayName: String
    public let maxLongEdge: Int
    public let maxShortEdge: Int

    public init(receiverId: String, displayName: String, maxLongEdge: Int, maxShortEdge: Int) throws {
        guard !receiverId.isEmpty, receiverId.count <= Self.maximumIdentifierLength else {
            throw ReceiverCapabilitiesError.invalidIdentity
        }
        guard !displayName.isEmpty, displayName.count <= Self.maximumIdentifierLength else {
            throw ReceiverCapabilitiesError.invalidIdentity
        }
        guard maxLongEdge >= CamBridgeContract.Geometry.minimumDimension,
              maxLongEdge <= CamBridgeContract.Geometry.maximumLongEdge,
              maxShortEdge >= CamBridgeContract.Geometry.minimumDimension,
              maxShortEdge <= CamBridgeContract.Geometry.maximumShortEdge,
              maxLongEdge >= maxShortEdge else {
            throw ReceiverCapabilitiesError.invalidBounds
        }
        self.receiverId = receiverId
        self.displayName = displayName
        self.maxLongEdge = maxLongEdge
        self.maxShortEdge = maxShortEdge
    }

    public func supports(_ geometry: VideoGeometry, rotation: StreamRotation) -> Bool {
        geometry.fits(receiverMaxLongEdge: maxLongEdge, receiverMaxShortEdge: maxShortEdge, rotation: rotation)
    }

    private static let maximumIdentifierLength = CamBridgeContract.Validation.maximumIdentifierLength
}

public enum ReceiverCapabilitiesError: Error, Equatable, Sendable {
    case invalidIdentity
    case invalidBounds
}
