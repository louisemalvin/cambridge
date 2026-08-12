import Foundation
import Observation
import UIKit
import CamBridgeCore

@MainActor
@Observable
public final class WebcamModel {
    public private(set) var cameraState: CameraState = .initial
    public var isDimmed = false
    public var isStopConfirmationPresented = false
    public private(set) var statusText = "Waiting for stream"
    public private(set) var failure: StreamFailure?

    public let capture: CaptureService
    private let sessionCoordinator: StreamSessionCoordinator
    private let logger: CamBridgeLogger
    @ObservationIgnored private var monitorTask: Task<Void, Never>?

    public init(capture: CaptureService, sessionCoordinator: StreamSessionCoordinator, logger: CamBridgeLogger) {
        self.capture = capture
        self.sessionCoordinator = sessionCoordinator
        self.logger = logger
        monitorTask = Task { [weak self] in
            guard let self else { return }
            while !Task.isCancelled {
                let state = await capture.state
                self.cameraState = state
                let snapshot = await sessionCoordinator.snapshotStream()
                switch snapshot.state {
                case let .connecting(_, configuration):
                    self.failure = nil
                    self.statusText = "Connecting · \(configuration.resolution.displayName)"
                case let .streaming(_, configuration, _):
                    self.failure = nil
                    self.statusText = "Streaming · \(configuration.resolution.displayName) · \(configuration.fps) fps"
                case let .failed(failure):
                    self.failure = failure
                    self.statusText = failure.recoverySummary
                    self.isDimmed = false
                case .stopping:
                    self.statusText = "Stopping…"
                case .idle:
                    self.failure = nil
                    self.statusText = "Waiting for stream"
                    self.isDimmed = false
                }
                try? await Task.sleep(nanoseconds: Self.stateRefreshNanoseconds)
            }
        }
    }

    deinit {
        monitorTask?.cancel()
    }

    public func requestStop() {
        isStopConfirmationPresented = true
    }

    public func cancelStopRequest() {
        isStopConfirmationPresented = false
    }

    public func confirmStop() async {
        isStopConfirmationPresented = false
        _ = await sessionCoordinator.stop()
        logger.event("stream_stopped_by_user", category: .session)
    }

    public func setZoomRatio(_ ratio: Double) {
        Task {
            try? await capture.setZoomRatio(ratio)
        }
    }

    public func toggleDimmedPresentation() {
        isDimmed.toggle()
    }

    public func copyDiagnostics() {
        Task { @MainActor in
            guard let diagnostics = await sessionCoordinator.diagnostics() else { return }
            UIPasteboard.general.string = diagnostics.copyableText()
            logger.event("diagnostics_copied", category: .app)
        }
    }

    private static let nanosecondsPerMillisecond: UInt64 = 1_000_000
    private static let stateRefreshNanoseconds = UInt64(CamBridgeContract.Media.maximumLiveFrameAgeMilliseconds) * nanosecondsPerMillisecond
}
