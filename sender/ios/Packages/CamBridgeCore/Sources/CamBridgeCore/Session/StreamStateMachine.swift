import Foundation

public enum StreamStateMachineError: Error, Equatable, Sendable {
    case illegalTransition(from: String, operation: String)
    case identityMismatch
    case profileMismatch
    case invalidAcceptedPort
}

public struct StreamStateMachine: Sendable {
    public private(set) var state: StreamState = .idle

    public init() {}

    public mutating func beginStart(identity: SessionIdentity, configuration: StreamConfiguration) throws {
        guard case .idle = state else {
            throw StreamStateMachineError.illegalTransition(from: state.name, operation: "beginStart")
        }
        state = .connecting(identity: identity, configuration: configuration)
    }

    public mutating func accept(_ message: ControlMessage) throws {
        guard case let .connecting(identity, configuration) = state else {
            throw StreamStateMachineError.illegalTransition(from: state.name, operation: "accept")
        }
        try validateAccepted(message, identity: identity, configuration: configuration)
        guard case let .accepted(_, _, _, mediaPort, _, _) = message else { return }
        state = .streaming(identity: identity, configuration: configuration, mediaPort: mediaPort)
    }

    public func validateAccepted(_ message: ControlMessage) throws {
        guard case let .connecting(identity, configuration) = state else {
            throw StreamStateMachineError.illegalTransition(from: state.name, operation: "validateAccepted")
        }
        try validateAccepted(message, identity: identity, configuration: configuration)
    }

    private func validateAccepted(_ message: ControlMessage, identity: SessionIdentity, configuration: StreamConfiguration) throws {
        guard case let .accepted(sessionId, generation, profileId, mediaPort, maxLongEdge, maxShortEdge) = message else {
            throw StreamStateMachineError.illegalTransition(from: state.name, operation: "accept")
        }
        guard identity.matches(sessionId: sessionId, generation: generation) else {
            throw StreamStateMachineError.identityMismatch
        }
        guard SenderVideoCatalog.profileID == profileId else {
            throw StreamStateMachineError.profileMismatch
        }
        guard mediaPort >= CamBridgeContract.Validation.minimumPort,
              mediaPort <= CamBridgeContract.Validation.maximumPort else {
            throw StreamStateMachineError.invalidAcceptedPort
        }
        guard configuration.geometry.fits(
            receiverMaxLongEdge: maxLongEdge,
            receiverMaxShortEdge: maxShortEdge,
            rotation: configuration.orientation
        ) else {
            throw StreamConfigurationError.receiverGeometryUnsupported
        }
    }

    @discardableResult
    public mutating func beginStop() -> Bool {
        switch state {
        case .idle:
            return false
        case let .connecting(identity, _), let .streaming(identity, _, _):
            state = .stopping(identity: identity)
            return true
        case .stopping:
            return false
        case .failed:
            return true
        }
    }

    public mutating func finishStop() {
        state = .idle
    }

    public mutating func fail(_ failure: StreamFailure) {
        state = .failed(failure)
    }

}

private extension StreamState {
    var name: String {
        switch self {
        case .idle:
            "idle"
        case .connecting:
            "connecting"
        case .streaming:
            "streaming"
        case .stopping:
            "stopping"
        case .failed:
            "failed"
        }
    }
}
