import SwiftUI
import Foundation
import CamBridgeCore

struct SettingsScreen: View {
    @Bindable var model: SettingsModel
    let onCopyCapabilityReport: () -> Void

    init(model: SettingsModel, onCopyCapabilityReport: @escaping () -> Void = {}) {
        self.model = model
        self.onCopyCapabilityReport = onCopyCapabilityReport
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Stream preferences") {
                    Picker("Mode", selection: Binding(
                        get: { model.preferences.modeId },
                        set: { modeID in
                            guard let mode = VideoMode.productModes.first(where: { $0.id == modeID }) else { return }
                            var preferences = model.preferences
                            preferences.modeId = mode.id
                            preferences.bitrateBps = mode.defaultBitrateBps
                            model.updatePreferences(preferences)
                        }
                    )) {
                        ForEach(VideoMode.productModes, id: \.id) { mode in
                            Text(mode.id).tag(mode.id)
                        }
                    }
                    .disabled(model.isStreamActive)
                    Picker("Orientation", selection: Binding(
                        get: { model.preferences.orientation },
                        set: { orientation in
                            var preferences = model.preferences
                            preferences.orientation = orientation
                            model.updatePreferences(preferences)
                        }
                    )) {
                        ForEach(StreamRotation.allCases, id: \.self) { orientation in
                            Text("\(orientation.degrees)°").tag(orientation)
                        }
                    }
                    .disabled(model.isStreamActive)
                    Picker("Stabilization", selection: Binding(
                        get: { model.preferences.stabilizationPreference },
                        set: { stabilization in
                            guard CameraStabilizationPreference(rawValue: stabilization) != nil else { return }
                            var preferences = model.preferences
                            preferences.stabilizationPreference = stabilization
                            model.updatePreferences(preferences)
                        }
                    )) {
                        ForEach(CameraStabilizationPreference.allCases.filter { $0.avFoundationMode != nil }, id: \.rawValue) { preference in
                            Text(preference.displayName).tag(preference.rawValue)
                        }
                    }
                    .disabled(model.isStreamActive)
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
                    Text(model.capabilityReportText)
                        .font(.system(.footnote, design: .monospaced))
                        .textSelection(.enabled)
                    Button("Copy capability report", action: onCopyCapabilityReport)
                        .accessibilityIdentifier("copy-capability-report-settings")
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
}
