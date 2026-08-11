import Foundation

public enum StreamState: Equatable, Sendable {
    case idle
    case connecting(identity: SessionIdentity, configuration: StreamConfiguration)
    case streaming(identity: SessionIdentity, configuration: StreamConfiguration, mediaPort: Int)
    case stopping(identity: SessionIdentity)
    case failed(StreamFailure)

    public var diagnosticLabel: String {
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
