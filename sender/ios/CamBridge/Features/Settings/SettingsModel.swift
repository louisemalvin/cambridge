import Foundation
import Observation
import UIKit
import CamBridgeCore

@MainActor
@Observable
public final class SettingsModel {
    public var preferences: SenderPreferences { preferencesState.preferences }
    public var isStreamActive: Bool { preferencesState.isStreamActive }
    public private(set) var diagnosticsText = "No stream diagnostics yet."

    private let preferencesState: SenderPreferencesState
    private let logger: CamBridgeLogger

    public init(preferencesState: SenderPreferencesState, logger: CamBridgeLogger) {
        self.preferencesState = preferencesState
        self.logger = logger
    }

    public init(settingsStore: any SenderSettingsStoring, logger: CamBridgeLogger) {
        self.preferencesState = SenderPreferencesState(settingsStore: settingsStore)
        self.logger = logger
    }

    public func updatePreferences(_ preferences: SenderPreferences) {
        _ = preferencesState.update(preferences)
    }

    public func copyDiagnostics() {
        UIPasteboard.general.string = diagnosticsText
        logger.event("diagnostics_copied", category: .app)
    }

    public func setDiagnostics(_ report: DiagnosticsReport) {
        diagnosticsText = report.copyableText()
    }

}
