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
            case .notDetermined: "Not determined"
            case .denied: "Denied"
            case .restricted: "Restricted"
            case .authorized: "Authorized"
            }
        }
    }

    enum StreamPhase {
        case idle
        case starting
        case streaming
        case failed
    }

    static let validManualHost = "192.0.2.10"
    static let manualReceiverID = "fixture-manual-receiver"
    static let firstReceiverID = "fixture-receiver-one"
    static let secondReceiverID = "fixture-receiver-two"

    var route: AppModel.Route = .settings
    var permission: CameraPermission = .notDetermined
    var selectedReceiverID: String?
    var manualHost = ""
    var manualProbeMessage = "Manual receiver not probed"
    var resolutionID = SenderVideoCatalog.defaultResolution.id
    var fps = SenderVideoCatalog.defaultFrameRate
    var bitrateText = BitrateInput.wholeMegabits(
        fromBitsPerSecond: SenderVideoCatalog.suggestedBitrateBps(
            resolution: SenderVideoCatalog.defaultResolution,
            fps: SenderVideoCatalog.defaultFrameRate
        ) ?? SenderVideoCatalog.minimumBitrateMbps * SenderVideoCatalog.bitrateUnitBps
    ) ?? ""
    var streamPhase = StreamPhase.idle
    var failureMessage: String?
    var isStopConfirmationPresented = false

    var isStreamActive: Bool {
        streamPhase == .starting || streamPhase == .streaming
    }

    var canStart: Bool {
        permission == .authorized
            && selectedReceiverID != nil
            && SenderVideoCatalog.resolution(id: resolutionID) != nil
            && SenderVideoCatalog.frameRates.contains(fps)
            && BitrateInput.bitsPerSecond(fromWholeMegabits: bitrateText) != nil
            && !isStreamActive
    }

    var receiverStatus: String {
        selectedReceiverID == nil ? "Select an OBS receiver" : "Ready: " + selectedReceiverName
    }

    var selectedReceiverName: String {
        switch selectedReceiverID {
        case Self.firstReceiverID: "Fixture OBS One"
        case Self.secondReceiverID: "Fixture OBS Two"
        case Self.manualReceiverID: "Manual Fixture OBS"
        default: "No receiver"
        }
    }

    var resolutionName: String {
        SenderVideoCatalog.resolution(id: resolutionID)?.displayName ?? "Invalid"
    }

    func setPermission(_ rawValue: String) {
        permission = CameraPermission(rawValue: rawValue) ?? .notDetermined
    }

    func setReceiver(_ receiverID: String) {
        selectedReceiverID = receiverID
        manualProbeMessage = "Manual receiver not probed"
    }

    func setResolution(_ resolution: VideoResolution) {
        resolutionID = resolution.id
        applySuggestion()
    }

    func setFrameRate(_ frameRate: Int) {
        guard SenderVideoCatalog.frameRates.contains(frameRate) else { return }
        fps = frameRate
        applySuggestion()
    }

    func setBitrate(_ text: String) {
        bitrateText = text
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

    func confirmStop() {
        isStopConfirmationPresented = false
        streamPhase = .idle
        route = .settings
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

    private func applySuggestion() {
        guard let resolution = SenderVideoCatalog.resolution(id: resolutionID),
              let bitrate = SenderVideoCatalog.suggestedBitrateBps(resolution: resolution, fps: fps),
              let text = BitrateInput.wholeMegabits(fromBitsPerSecond: bitrate) else {
            return
        }
        bitrateText = text
    }
}

struct UITestFixtureRootView: View {
    @State private var model = UITestFixtureModel()
    @State private var path: [AppModel.Route] = []

    var body: some View {
        NavigationStack(path: $path) {
            UITestFixtureSettingsView(model: model) { model.route = .setup }
                .navigationDestination(for: AppModel.Route.self) { route in
                    switch route {
                    case .setup: UITestFixtureSetupView(model: model)
                    case .webcam: UITestFixtureWebcamView(model: model) { path.append(.settings) }
                    case .settings: UITestFixtureSettingsView(model: model) { model.route = .setup }
                    }
                }
        }
        .onAppear { path = navigationPath(for: model.route) }
        .onChange(of: model.route) { _, route in path = navigationPath(for: route) }
        .onChange(of: path) { _, newPath in
            guard newPath.isEmpty, model.route != .settings else { return }
            model.route = .settings
        }
    }

    private func navigationPath(for route: AppModel.Route) -> [AppModel.Route] {
        switch route {
        case .settings: []
        case .setup: [.setup]
        case .webcam: [.setup, .webcam]
        }
    }
}

private struct UITestFixtureSetupView: View {
    @Bindable var model: UITestFixtureModel

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: UITestFixtureLayout.standardSpacing) {
                Text("CamBridge")
                    .font(.title2)
                    .accessibilityIdentifier("setup-screen-title")

                Group {
                    Text("Camera permission: \(model.permission.displayName)")
                        .accessibilityIdentifier("camera-permission-state")
                    ForEach(UITestFixtureModel.CameraPermission.allCases, id: \.rawValue) { permission in
                        Button(permission.displayName) { model.setPermission(permission.rawValue) }
                            .accessibilityIdentifier("permission-\(permission.rawValue)")
                    }
                }

                Group {
                    Text(model.receiverStatus).accessibilityIdentifier("receiver-status")
                    Button("Fixture OBS One") { model.setReceiver(UITestFixtureModel.firstReceiverID) }
                        .accessibilityIdentifier(UITestFixtureModel.firstReceiverID)
                    Button("Fixture OBS Two") { model.setReceiver(UITestFixtureModel.secondReceiverID) }
                        .accessibilityIdentifier(UITestFixtureModel.secondReceiverID)
                    TextField("OBS computer host", text: $model.manualHost)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .accessibilityIdentifier("manual-receiver-host")
                    Button("Probe manual receiver", action: model.probeManualReceiver)
                        .accessibilityIdentifier("probe-manual-receiver")
                    Text(model.manualProbeMessage).accessibilityIdentifier("manual-probe-status")
                }

                Group {
                    Text("Resolution: \(model.resolutionName)")
                        .accessibilityIdentifier("selected-resolution")
                    Button("Full HD") { model.setResolution(SenderVideoCatalog.fullHd) }
                        .accessibilityIdentifier("resolution-full-hd")
                    Button("2K") { model.setResolution(SenderVideoCatalog.resolution2k) }
                        .accessibilityIdentifier("resolution-2k")
                    Text("Frame rate: \(model.fps) fps")
                        .accessibilityIdentifier("selected-frame-rate")
                    ForEach(SenderVideoCatalog.frameRates, id: \.self) { frameRate in
                        Button("\(frameRate) fps") { model.setFrameRate(frameRate) }
                            .accessibilityIdentifier("frame-rate-\(frameRate)")
                    }
                    Text("Bitrate: \(model.bitrateText) Mbps")
                        .accessibilityIdentifier("selected-bitrate")
                    Button("Use 1 Mbps") { model.setBitrate("1") }
                        .accessibilityIdentifier("bitrate-1")
                    Button("Use invalid bitrate") { model.setBitrate("1.5") }
                        .accessibilityIdentifier("bitrate-invalid")
                }
                .disabled(model.isStreamActive)

                Button(model.streamPhase == .starting ? "Starting…" : "Start stream") {
                    model.startStream()
                }
                .disabled(!model.canStart)
                .accessibilityIdentifier("start-stream")
                if model.streamPhase == .starting {
                    Button("Complete simulated start", action: model.completeStart)
                        .accessibilityIdentifier("complete-start")
                }
                if let failureMessage = model.failureMessage {
                    Text(failureMessage).accessibilityIdentifier("stream-failure")
                    Button("Retry", action: model.retry).accessibilityIdentifier("retry-stream")
                }
            }
            .padding()
        }
        .navigationTitle("Stream setup")
    }
}

private struct UITestFixtureWebcamView: View {
    @Bindable var model: UITestFixtureModel
    let onShowSettings: () -> Void

    var body: some View {
        VStack(spacing: UITestFixtureLayout.standardSpacing) {
            Text("Streaming to " + model.selectedReceiverName).accessibilityIdentifier("webcam-status")
            Button("Settings", action: onShowSettings).accessibilityIdentifier("webcam-settings")
            Button("Stop stream") { model.isStopConfirmationPresented = true }
                .accessibilityIdentifier("stop-stream")
            Button("Simulate terminal failure", action: model.simulateTerminalFailure)
                .accessibilityIdentifier("simulate-terminal-failure")
        }
        .padding()
        .navigationTitle("Webcam")
        .toolbar(.hidden, for: .navigationBar)
        .alert("Stop stream?", isPresented: $model.isStopConfirmationPresented) {
            Button("Cancel", role: .cancel) { model.isStopConfirmationPresented = false }
                .accessibilityIdentifier("cancel-stop")
            Button("Stop", role: .destructive, action: model.confirmStop)
                .accessibilityIdentifier("confirm-stop")
        }
    }
}

private struct UITestFixtureSettingsView: View {
    @Bindable var model: UITestFixtureModel
    let onOpenStreamSetup: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: UITestFixtureLayout.standardSpacing) {
            Text("Settings").font(.title2).accessibilityIdentifier("settings-screen-title")
            Button("Streaming setup", action: onOpenStreamSetup)
                .disabled(model.isStreamActive)
                .accessibilityIdentifier("open-stream-setup")
            Text(model.selectedReceiverName)
            Text("Diagnostics available after a stream")
        }
        .padding()
        .navigationTitle("Settings")
    }
}

private enum UITestFixtureLayout {
    static let standardSpacing: CGFloat = 16
}
#endif
