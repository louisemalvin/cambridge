import SwiftUI
import CamBridgeCore

struct StreamSetupScreen: View {
    @Bindable var model: StreamSetupModel

    var body: some View {
        NavigationStack {
            Form {
                ReceiverSelectionView(model: model)
                Section("Camera") {
                    Picker("Rear camera", selection: Binding(
                        get: { model.selectedCameraID ?? "" },
                        set: { model.selectCamera($0) }
                    )) {
                        ForEach(model.cameraDevices) { device in
                            Text(device.name).tag(device.id)
                        }
                    }
                    .disabled(model.isStreamActive)
                    .accessibilityIdentifier("camera-picker")
                    if model.cameraAuthorization != .authorized {
                        Button("Allow camera access") {
                            Task { await model.requestCameraAccess() }
                        }
                        .accessibilityIdentifier("request-camera-access")
                    }
                }
                VideoModeSelectionView(model: model)
                    .disabled(model.isStreamActive)
                Section {
                    Button {
                        Task { await model.startStream() }
                    } label: {
                        HStack {
                            Spacer()
                            if model.isStarting { ProgressView() }
                            Text("Start stream")
                                .fontWeight(.semibold)
                            Spacer()
                        }
                    }
                    .disabled(!model.canStart)
                    .accessibilityIdentifier("start-stream")
                }
                Section("Diagnostics") {
                    Text("Local diagnostic report: camera identifiers are included; receiver hosts are redacted. It can be copied before Start.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                    Button("Copy capability report") {
                        Task { await model.copyCapabilityReport() }
                    }
                    .accessibilityIdentifier("copy-capability-report")
                }
                if !model.statusMessage.isEmpty {
                    Section {
                        Text(model.statusMessage)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle("CamBridge")
            .task { model.startDiscovery() }
            .onDisappear { model.stopDiscovery() }
            .alert("Stream unavailable", isPresented: Binding(
                get: { model.failure != nil },
                set: { if !$0 { model.failure = nil } }
            )) {
                Button("Retry") { Task { await model.retry() } }
                Button("Dismiss", role: .cancel) { model.failure = nil }
            } message: {
                Text(model.failure?.recoverySummary ?? "Try again.")
            }
        }
    }
}
