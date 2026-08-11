import Foundation
import Observation
import UIKit
import CamBridgeCore

@MainActor
@Observable
public final class AppModel {
    public enum Route: String, CaseIterable, Sendable {
        case setup
        case webcam
        case settings
    }

    public var route: Route = .setup
    public private(set) var streamSnapshot: StreamSessionSnapshot
    public private(set) var lastFailure: StreamFailure?

    public let setupModel: StreamSetupModel
    public let webcamModel: WebcamModel
    public let settingsModel: SettingsModel

    private let sessionCoordinator: StreamSessionCoordinator
    @ObservationIgnored private var snapshotTask: Task<Void, Never>?

    public init(
        settingsStore: SenderSettingsStore,
        browser: BonjourReceiverBrowser,
        probe: CamBridgeReceiverProbe,
        capture: CaptureService,
        encoderProbe: EncoderCapabilityProbe,
        sessionCoordinator: StreamSessionCoordinator,
        logger: CamBridgeLogger
    ) {
        self.sessionCoordinator = sessionCoordinator
        streamSnapshot = StreamSessionSnapshot(state: .idle, runId: nil, identity: nil)
        setupModel = StreamSetupModel(settingsStore: settingsStore, browser: browser, probe: probe, capture: capture, encoderProbe: encoderProbe, sessionCoordinator: sessionCoordinator, logger: logger)
        webcamModel = WebcamModel(capture: capture, sessionCoordinator: sessionCoordinator, logger: logger)
        settingsModel = SettingsModel(settingsStore: settingsStore, logger: logger)
        snapshotTask = Task { [weak self] in
            guard let self else { return }
            let stream = await sessionCoordinator.snapshots()
            for await snapshot in stream {
                guard !Task.isCancelled else { return }
                self.streamSnapshot = snapshot
                let active: Bool
                switch snapshot.state {
                case .connecting, .streaming, .stopping:
                    active = true
                case .idle, .failed:
                    active = false
                }
                self.settingsModel.setStreamActive(active)
                self.setupModel.setStreamActive(active)
                if case let .failed(failure) = snapshot.state {
                    self.lastFailure = failure
                    self.setupModel.failure = failure
                    self.route = .setup
                }
                if case .idle = snapshot.state, let diagnostics = await sessionCoordinator.diagnostics() {
                    self.settingsModel.setDiagnostics(diagnostics)
                }
                if case .failed = snapshot.state, let diagnostics = await sessionCoordinator.diagnostics() {
                    self.settingsModel.setDiagnostics(diagnostics)
                }
                if case .streaming = snapshot.state {
                    self.settingsModel.reloadPreferences()
                    self.route = .webcam
                }
                if case .idle = snapshot.state, self.route == .webcam {
                    self.route = .setup
                }
            }
        }
    }

    deinit {
        snapshotTask?.cancel()
    }

    public func copyCapabilityReport() async {
        let report = await setupModel.generateCapabilityReport()
        settingsModel.setCapabilityReport(report)
        UIPasteboard.general.string = report.copyableText()
    }
}
