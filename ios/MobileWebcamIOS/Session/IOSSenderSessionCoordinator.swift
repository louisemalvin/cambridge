import Combine
import Foundation

enum IOSSenderSessionFailure: Equatable {
    case permissionDenied
    case cameraUnavailable
    case encoderUnavailable
    case networkFailure
    case notImplemented
    case invalidConfiguration
    case unknown
}

enum IOSSenderSessionState: Equatable, CustomStringConvertible {
    case idle
    case starting
    case streaming
    case stopped
    case failed(IOSSenderSessionFailure)

    var description: String {
        switch self {
        case .idle:
            return "Idle"
        case .starting:
            return "Starting"
        case .streaming:
            return "Streaming"
        case .stopped:
            return "Stopped"
        case .failed(let failure):
            return "Failed: \(failure)"
        }
    }
}

@MainActor
final class IOSSenderSessionCoordinator: ObservableObject {
    @Published private(set) var state: IOSSenderSessionState = .idle

    private let mediaEngine: IOSMediaEngine
    private let receiverControl: IOSReceiverControlClient
    private var activeSessionID: UUID?

    init(
        mediaEngine: IOSMediaEngine,
        receiverControl: IOSReceiverControlClient
    ) {
        self.mediaEngine = mediaEngine
        self.receiverControl = receiverControl
        mediaEngine.eventHandler = { [weak self] event in
            Task { @MainActor in
                self?.handle(event)
            }
        }
    }

    func start(
        configuration: IOSMediaConfiguration,
        receiver: IOSReceiverEndpoint
    ) async {
        state = .starting

        do {
            let destination = try await receiverControl.prepareSession(
                configuration: configuration,
                endpoint: receiver
            )
            activeSessionID = destination.sessionID
            try await mediaEngine.start(
                configuration: configuration,
                destination: destination
            )
            state = .streaming
        } catch {
            state = .failed(failure(for: error))
        }
    }

    func stop(receiver: IOSReceiverEndpoint) async {
        await mediaEngine.stop()
        if let activeSessionID {
            try? await receiverControl.stopSession(
                sessionID: activeSessionID,
                endpoint: receiver
            )
        }
        self.activeSessionID = nil
        state = .stopped
    }

    func requestKeyframe() {
        mediaEngine.requestKeyframe()
    }

    func updateBitrate(_ bitrateBps: Int) async {
        do {
            try await mediaEngine.updateBitrate(bitrateBps)
        } catch {
            state = .failed(failure(for: error))
        }
    }

    private func handle(_ event: IOSMediaEngineEvent) {
        switch event {
        case .starting:
            state = .starting
        case .streaming:
            state = .streaming
        case .stopped:
            state = .stopped
        case .permissionDenied:
            state = .failed(.permissionDenied)
        case .cameraUnavailable:
            state = .failed(.cameraUnavailable)
        case .encoderUnavailable:
            state = .failed(.encoderUnavailable)
        case .networkFailure:
            state = .failed(.networkFailure)
        }
    }

    private func failure(for error: Error) -> IOSSenderSessionFailure {
        if let mediaError = error as? IOSMediaEngineError {
            switch mediaError {
            case .notImplemented:
                return .notImplemented
            case .invalidConfiguration:
                return .invalidConfiguration
            case .invalidBitrate:
                return .invalidConfiguration
            }
        }
        if let controlError = error as? IOSReceiverControlError {
            switch controlError {
            case .notImplemented:
                return .notImplemented
            case .transport, .httpStatus:
                return .networkFailure
            case .invalidEndpoint, .invalidResponse, .invalidSession, .unsupportedProtocol:
                return .unknown
            }
        }
        return .unknown
    }
}
