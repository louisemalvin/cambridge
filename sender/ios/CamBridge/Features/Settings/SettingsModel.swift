import Foundation
import Observation
import UIKit
import CamBridgeCore

@MainActor
@Observable
public final class SettingsModel {
    public private(set) var preferences: SenderPreferences
    public private(set) var isStreamActive = false
    public private(set) var diagnosticsText = "No stream diagnostics yet."
    public private(set) var capabilityReportText = "No capability report generated yet."

    private let settingsStore: any SenderSettingsStoring
    private let logger: CamBridgeLogger

    public init(settingsStore: any SenderSettingsStoring, logger: CamBridgeLogger) {
        self.settingsStore = settingsStore
        self.logger = logger
        preferences = settingsStore.load()
    }

    public func setStreamActive(_ active: Bool) {
        isStreamActive = active
    }

    public func reloadPreferences() {
        guard !isStreamActive else { return }
        preferences = settingsStore.load()
    }

    public func updatePreferences(_ preferences: SenderPreferences) {
        guard !isStreamActive else { return }
        self.preferences = preferences
        settingsStore.save(preferences)
    }

    public func copyDiagnostics() {
        UIPasteboard.general.string = diagnosticsText
        logger.event("diagnostics_copied", category: .app)
    }

    public func setDiagnostics(_ report: DiagnosticsReport) {
        diagnosticsText = report.copyableText()
    }

    public func setCapabilityReport(_ report: CapabilityReport) {
        capabilityReportText = report.copyableText()
    }
}
