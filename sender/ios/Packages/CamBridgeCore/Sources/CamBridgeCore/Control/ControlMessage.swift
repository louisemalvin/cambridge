import Foundation

public enum ControlMessage: Equatable, Sendable {
    case probe(requestId: String)
    case capabilities(
        requestId: String,
        receiverId: String,
        displayName: String,
        maxLongEdge: Int,
        maxShortEdge: Int
    )
    case hello(
        sessionId: String,
        generation: UInt64,
        profileId: String,
        codedWidth: Int,
        codedHeight: Int,
        rotation: StreamRotation,
        fps: Int,
        bitrateBps: Int
    )
    case accepted(
        sessionId: String,
        generation: UInt64,
        profileId: String,
        mediaPort: Int,
        maxLongEdge: Int,
        maxShortEdge: Int
    )
    case stop(sessionId: String, generation: UInt64)
    case error(message: String)
}

public extension ControlMessage {
    var type: String {
        switch self {
        case .probe:
            CamBridgeContract.MessageType.probe
        case .capabilities:
            CamBridgeContract.MessageType.capabilities
        case .hello:
            CamBridgeContract.MessageType.hello
        case .accepted:
            CamBridgeContract.MessageType.accepted
        case .stop:
            CamBridgeContract.MessageType.stop
        case .error:
            CamBridgeContract.MessageType.error
        }
    }
}
