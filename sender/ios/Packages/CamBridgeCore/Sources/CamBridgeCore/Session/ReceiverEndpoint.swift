import Foundation

public struct ReceiverEndpoint: Codable, Equatable, Hashable, Sendable {
    public let host: String
    public let controlPort: Int
    public let receiverId: String?
    public let displayName: String?

    public init(host: String, controlPort: Int = CamBridgeContract.Defaults.controlPort, receiverId: String? = nil, displayName: String? = nil) throws {
        guard !host.isEmpty, host.count <= Self.maximumHostLength else {
            throw ReceiverEndpointError.invalidHost
        }
        guard controlPort >= CamBridgeContract.Validation.minimumPort, controlPort <= CamBridgeContract.Validation.maximumPort else {
            throw ReceiverEndpointError.invalidPort(controlPort)
        }
        if let receiverId, (receiverId.isEmpty || receiverId.count > Self.maximumIdentifierLength) {
            throw ReceiverEndpointError.invalidReceiverId
        }
        if let displayName, (displayName.isEmpty || displayName.count > Self.maximumIdentifierLength) {
            throw ReceiverEndpointError.invalidDisplayName
        }
        self.host = host
        self.controlPort = controlPort
        self.receiverId = receiverId
        self.displayName = displayName
    }

    private static let maximumHostLength = 253
    private static let maximumIdentifierLength = CamBridgeContract.Validation.maximumIdentifierLength
}

public enum ReceiverEndpointError: Error, Equatable, Sendable {
    case invalidHost
    case invalidPort(Int)
    case invalidReceiverId
    case invalidDisplayName
}
