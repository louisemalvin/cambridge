import SwiftUI
import Foundation
import CamBridgeCore

struct SettingsScreen: View {
    @Bindable var model: SettingsModel
    let onOpenStreamSetup: () -> Void

    init(
        model: SettingsModel,
        onOpenStreamSetup: @escaping () -> Void = {}
    ) {
        self.model = model
        self.onOpenStreamSetup = onOpenStreamSetup
    }

    var body: some View {
        Form {
            Section("Streaming") {
                Button(action: onOpenStreamSetup) {
                    Label("Streaming setup", systemImage: "video.badge.waveform")
                }
                .disabled(model.isStreamActive)
                .accessibilityIdentifier("open-stream-setup")
            }
            Section("Receiver") {
                Text(model.preferences.receiverDisplayName ?? "Not selected")
                Text(model.preferences.receiverHost ?? "Manual receiver not configured")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Section("Diagnostics") {
                Text(model.diagnosticsText)
                    .font(.system(.footnote, design: .monospaced))
                    .textSelection(.enabled)
                Button("Copy diagnostics") { model.copyDiagnostics() }
                    .accessibilityIdentifier("copy-diagnostics")
                Text("Local diagnostic reports may include camera identifiers; receiver hosts are redacted.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Section("Build") {
                LabeledContent("App version", value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "unknown")
                LabeledContent("Build", value: Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "unknown")
                LabeledContent("Protocol", value: String(CamBridgeContract.protocolVersion))
            }
        }
        .navigationTitle("Settings")
    }
}
