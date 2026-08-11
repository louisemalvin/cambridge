#if DEBUG
import Observation
import SwiftUI
import CamBridgeCore

public enum CamBridgeUITestLaunchConfiguration {
    public static let fixtureArgument = "--cambridge-ui-fixture"
}

@MainActor
@Observable
private final class UITestFixtureModel {
    enum CameraPermission: String, CaseIterable {
        case notDetermined
        case denied
        case restricted
        case authorized

        var displayName: String {
            switch self {
            case .notDetermined:
                "Not determined"
            case .denied:
                "Denied"
            case .restricted:
                "Restricted"
            case .authorized:
                "Authorized"
            }
        }
    }

    enum StreamPhase {
        case idle
        case starting
        case streaming
        case stopping
        case failed
    }

    static let cameraID = "fixture-camera-wide"
    static let supportedModeID = "1080p30"
    static let unsupportedModeID = "2k60"
    static let validManualHost = "192.0.2.10"
    static let manualReceiverID = "fixture-manual-receiver"
    static let firstReceiverID = "fixture-receiver-one"
    static let secondReceiverID = "fixture-receiver-two"

    var route: AppModel.Route = .setup
    var permission: CameraPermission = .notDetermined
    var selectedCameraID = Self.cameraID
    var selectedReceiverID: String?
    var manualHost = ""
    var manualProbeMessage = "Manual receiver not probed"
    var selectedModeID = Self.unsupportedModeID
    var selectedOrientation = StreamRotation.zero
    var selectedStabilization = CameraStabilizationPreference.auto
    var streamPhase = StreamPhase.idle
    var failureMessage: String?
    var isStopConfirmationPresented = false
    var capabilityReportCopied = false

    var isStreamActive: Bool {
        switch streamPhase {
        case .starting, .streaming, .stopping:
            true
        case .idle, .failed:
            false
        }
    }

    var canStart: Bool {
        permission == .authorized &&
            selectedCameraID == Self.cameraID &&
            selectedReceiverID != nil &&
            selectedModeID == Self.supportedModeID &&
            !isStreamActive
    }

    var receiverStatus: String {
        if selectedReceiverID == nil {
            "Select an OBS receiver"
        } else {
            "Ready: " + selectedReceiverName
        }
    }

    var selectedReceiverName: String {
        switch selectedReceiverID {
        case Self.firstReceiverID:
            "Fixture OBS One"
        case Self.secondReceiverID:
            "Fixture OBS Two"
        case Self.manualReceiverID:
            "Manual Fixture OBS"
        default:
            "No receiver"
        }
    }

    var selectedModeDescription: String {
        selectedModeID == Self.supportedModeID
            ? "Supported by camera, receiver, and encoder"
            : "Unavailable: exact 2K60 camera or encoder capability is not offered"
    }

    func setPermission(_ rawValue: String) {
        permission = CameraPermission(rawValue: rawValue) ?? .notDetermined
    }

    func setReceiver(_ receiverID: String) {
        selectedReceiverID = receiverID
        manualProbeMessage = "Manual receiver not probed"
    }

    func setMode(_ modeID: String) {
        guard modeID == Self.supportedModeID || modeID == Self.unsupportedModeID else { return }
        selectedModeID = modeID
    }

    func probeManualReceiver() {
        guard manualHost == Self.validManualHost else {
            selectedReceiverID = nil
            manualProbeMessage = "Probe failed: enter a valid receiver host."
            return
        }
        selectedReceiverID = Self.manualReceiverID
        manualProbeMessage = "Ready: Manual Fixture OBS"
    }

    func requestCameraAccess() {
        permission = .authorized
    }

    func startStream() {
        guard canStart else { return }
        streamPhase = .starting
        failureMessage = nil
    }

    func completeStart() {
        guard streamPhase == .starting else { return }
        streamPhase = .streaming
        route = .webcam
    }

    func requestStop() {
        isStopConfirmationPresented = true
    }

    func cancelStop() {
        isStopConfirmationPresented = false
    }

    func confirmStop() {
        isStopConfirmationPresented = false
        streamPhase = .idle
        route = .setup
    }

    func simulateTerminalFailure() {
        streamPhase = .failed
        failureMessage = "Simulated terminal media failure; retry is required."
        route = .setup
    }

    func retry() {
        streamPhase = .idle
        failureMessage = nil
    }

    func copyCapabilityReport() {
        capabilityReportCopied = true
    }
}

struct UITestFixtureRootView: View {
    @State private var model = UITestFixtureModel()

    var body: some View {
        UITestFixtureTabs(model: model)
    }
}

private struct UITestFixtureTabs: View {
    @Bindable var model: UITestFixtureModel

    var body: some View {
        TabView(selection: $model.route) {
            UITestFixtureSetupView(model: model)
                .tabItem {
                    Label("Setup", systemImage: "antenna.radiowaves.left.and.right")
                        .accessibilityIdentifier("setup-tab")
                }
                .tag(AppModel.Route.setup)
            UITestFixtureWebcamView(model: model)
                .tabItem {
                    Label("Webcam", systemImage: "video")
                        .accessibilityIdentifier("webcam-tab")
                }
                .tag(AppModel.Route.webcam)
            UITestFixtureSettingsView(model: model)
                .tabItem {
                    Label("Settings", systemImage: "gear")
                        .accessibilityIdentifier("settings-tab")
                }
                .tag(AppModel.Route.settings)
        }
    }
}

private struct UITestFixtureSetupView: View {
    @Bindable var model: UITestFixtureModel

    var body: some View {
        NavigationStack {
            Form {
                Section("Camera") {
                    Text("Camera permission: \(model.permission.displayName)")
                        .accessibilityIdentifier("camera-permission-state")
                    ForEach(UITestFixtureModel.CameraPermission.allCases, id: \.rawValue) { permission in
                        Button(permission.displayName) {
                            model.setPermission(permission.rawValue)
                        }
                        .accessibilityIdentifier("permission-\(permission.rawValue)")
                    }
                    Picker("Rear camera", selection: $model.selectedCameraID) {
                        Text("Fixture wide camera").tag(UITestFixtureModel.cameraID)
                    }
                    .disabled(model.isStreamActive)
                    .accessibilityIdentifier("camera-picker")
                    if model.permission != .authorized {
                        Button("Allow camera access", action: model.requestCameraAccess)
                            .accessibilityIdentifier("request-camera-access")
                    }
                }

                Section("Receiver") {
                    Text(model.receiverStatus)
                        .accessibilityIdentifier("receiver-status")
                    ForEach([UITestFixtureModel.firstReceiverID, UITestFixtureModel.secondReceiverID], id: \.self) { receiverID in
                        Button {
                            model.setReceiver(receiverID)
                        } label: {
                            HStack {
                                Image(systemName: model.selectedReceiverID == receiverID ? "checkmark.circle.fill" : "circle")
                                Text(receiverID == UITestFixtureModel.firstReceiverID ? "Fixture OBS One" : "Fixture OBS Two")
                            }
                        }
                        .accessibilityIdentifier(receiverID)
                    }
                    TextField("OBS computer host", text: $model.manualHost)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .accessibilityIdentifier("manual-receiver-host")
                    Button("Probe manual receiver", action: model.probeManualReceiver)
                        .accessibilityIdentifier("probe-manual-receiver")
                    Text(model.manualProbeMessage)
                        .font(.caption)
                        .accessibilityIdentifier("manual-probe-status")
                }

                Section("Video") {
                    Button("1080p30") {
                        model.setMode(UITestFixtureModel.supportedModeID)
                    }
                    .disabled(model.isStreamActive)
                    .accessibilityIdentifier("mode-1080p30")
                    Button("2K60") {
                        model.setMode(UITestFixtureModel.unsupportedModeID)
                    }
                    .accessibilityIdentifier("mode-2k60")
                    .disabled(model.isStreamActive)
                    Text(model.selectedModeDescription)
                        .font(.caption)
                        .accessibilityIdentifier("mode-capability-reason")
                    Button("Landscape") {
                        model.selectedOrientation = .zero
                    }
                    .disabled(model.isStreamActive)
                    .accessibilityIdentifier("orientation-landscape")
                    Button("Portrait clockwise") {
                        model.selectedOrientation = .ninety
                    }
                    .disabled(model.isStreamActive)
                    .accessibilityIdentifier("orientation-portrait")
                    Button("Auto") {
                        model.selectedStabilization = .auto
                    }
                    .disabled(model.isStreamActive)
                    .accessibilityIdentifier("stabilization-auto")
                    Button("Off") {
                        model.selectedStabilization = .off
                    }
                    .disabled(model.isStreamActive)
                    .accessibilityIdentifier("stabilization-off")
                }

                Section {
                    Button {
                        model.startStream()
                    } label: {
                        Text(model.streamPhase == .starting ? "Starting…" : "Start stream")
                            .frame(maxWidth: .infinity)
                    }
                    .disabled(!model.canStart)
                    .accessibilityIdentifier("start-stream")
                    if model.streamPhase == .starting {
                        Button("Complete simulated start", action: model.completeStart)
                            .accessibilityIdentifier("complete-start")
                    }
                }

                if let failureMessage = model.failureMessage {
                    Section("Recovery") {
                        Text(failureMessage)
                            .accessibilityIdentifier("stream-failure")
                        Button("Retry", action: model.retry)
                            .accessibilityIdentifier("retry-stream")
                    }
                }

                Section("Diagnostics") {
                    Text("Local diagnostic report: camera identifiers are included; receiver hosts are redacted.")
                        .font(.caption)
                    Button("Copy capability report", action: model.copyCapabilityReport)
                        .accessibilityIdentifier("copy-capability-report")
                    Text(model.capabilityReportCopied ? "Capability report copied" : "No capability report copied")
                        .font(.caption)
                        .accessibilityIdentifier("capability-report-status")
                }
            }
            .navigationTitle("CamBridge")
        }
    }
}

private struct UITestFixtureWebcamView: View {
    @Bindable var model: UITestFixtureModel

    var body: some View {
        NavigationStack {
            VStack(spacing: UITestFixtureLayout.standardSpacing) {
                Text("Streaming to " + model.selectedReceiverName)
                    .accessibilityIdentifier("webcam-status")
                Button("Stop stream", action: model.requestStop)
                    .accessibilityIdentifier("stop-stream")
                Button("Simulate terminal failure", action: model.simulateTerminalFailure)
                    .accessibilityIdentifier("simulate-terminal-failure")
            }
            .padding()
            .navigationTitle("Webcam")
            .alert("Stop stream?", isPresented: $model.isStopConfirmationPresented) {
                Button("Cancel", role: .cancel, action: model.cancelStop)
                    .accessibilityIdentifier("cancel-stop")
                Button("Stop", role: .destructive, action: model.confirmStop)
                    .accessibilityIdentifier("confirm-stop")
            } message: {
                Text("The stream will end and return to Setup.")
            }
        }
    }
}

private struct UITestFixtureSettingsView: View {
    @Bindable var model: UITestFixtureModel

    var body: some View {
        NavigationStack {
            Form {
                Section("Stream preferences") {
                    Button("1080p30") {
                        model.setMode(UITestFixtureModel.supportedModeID)
                    }
                    .disabled(model.isStreamActive)
                    .accessibilityIdentifier("settings-mode-1080p30")
                    Button("2K60") {
                        model.setMode(UITestFixtureModel.unsupportedModeID)
                    }
                    .disabled(model.isStreamActive)
                    .accessibilityIdentifier("settings-mode-2k60")
                    Button("Landscape") {
                        model.selectedOrientation = .zero
                    }
                    .disabled(model.isStreamActive)
                    .accessibilityIdentifier("settings-orientation-landscape")
                    Button("Portrait clockwise") {
                        model.selectedOrientation = .ninety
                    }
                    .disabled(model.isStreamActive)
                    .accessibilityIdentifier("settings-orientation-portrait")
                    Button("Auto") {
                        model.selectedStabilization = .auto
                    }
                    .disabled(model.isStreamActive)
                    .accessibilityIdentifier("settings-stabilization-auto")
                    Button("Off") {
                        model.selectedStabilization = .off
                    }
                    .disabled(model.isStreamActive)
                    .accessibilityIdentifier("settings-stabilization-off")
                    if model.isStreamActive {
                        Text("Settings locked while the stream is active")
                            .accessibilityIdentifier("settings-locked")
                    }
                }
                Section("Receiver") {
                    Text(model.selectedReceiverName)
                    Text(model.manualHost.isEmpty ? "Receiver host redacted" : "Receiver configured")
                        .font(.caption)
                }
                Section("Diagnostics") {
                    Button("Copy capability report", action: model.copyCapabilityReport)
                        .accessibilityIdentifier("copy-capability-report-settings")
                }
            }
            .navigationTitle("Settings")
        }
    }
}

private enum UITestFixtureLayout {
    static let standardSpacing: CGFloat = 16
}
#endif
