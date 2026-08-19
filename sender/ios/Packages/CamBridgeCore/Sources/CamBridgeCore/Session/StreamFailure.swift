import Foundation

public enum StreamFailure: Error, Codable, Equatable, Sendable {
    case permissionDenied
    case receiverUnavailable
    case incompatibleProtocol
    case invalidConfiguration(String)
    case cameraUnavailable(String)
    case encoderUnavailable(String)
    case controlConnectionFailed(String)
    case receiverRejected(String)
    case transportFailed(String)
    case interrupted(String)
    case backgrounded
    case cancelled
    case unexpected(String)

    public var recoverySummary: String {
        switch self {
        case .permissionDenied:
            "Allow camera and local-network access in Settings."
        case .receiverUnavailable:
            "Select or enter a reachable OBS receiver."
        case .incompatibleProtocol:
            "The receiver does not support CamBridge protocol v6."
        case .invalidConfiguration:
            "Choose a supported video mode and bitrate."
        case .cameraUnavailable:
            "The camera rejected the selected resolution or frame rate."
        case .encoderUnavailable:
            "Hardware H.264 rejected the selected stream settings."
        case .controlConnectionFailed, .transportFailed:
            "Check the local network and try Start again."
        case .receiverRejected:
            "The receiver rejected the exact selected stream configuration."
        case .interrupted:
            "Camera or system resources interrupted the stream."
        case .backgrounded:
            "Streaming ended because CamBridge entered the background."
        case .cancelled:
            "The operation was cancelled."
        case .unexpected:
            "Stop the stream and try again."
        }
    }
}
