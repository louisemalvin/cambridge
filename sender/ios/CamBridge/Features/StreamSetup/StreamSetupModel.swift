import Foundation
import Observation
import CamBridgeCore

@MainActor
@Observable
public final class StreamSetupModel {
    public enum ReceiverStatus: Equatable, Sendable {
        case checking
        case selectionRequired
        case ready
        case unavailable(String)
    }

    public final class ReceiverChoice: Identifiable {
        public let id: String
        public let displayName: String
        public let endpoint: ReceiverEndpoint
        public let target: ReceiverControlTarget
        public let capabilities: ReceiverCapabilities
        public let mediaHosts: [String]
        public let discoveredServiceName: String?

        init(id: String, displayName: String, endpoint: ReceiverEndpoint, target: ReceiverControlTarget, capabilities: ReceiverCapabilities, mediaHosts: [String], discoveredServiceName: String? = nil) {
            self.id = id
            self.displayName = displayName
            self.endpoint = endpoint
            self.target = target
            self.capabilities = capabilities
            self.mediaHosts = mediaHosts
            self.discoveredServiceName = discoveredServiceName
        }
    }

    public private(set) var receiverStatus: ReceiverStatus = .checking
    public private(set) var receivers: [ReceiverChoice] = []
    public var selectedReceiverID: String?
    public var manualHost = ""
    public private(set) var selectedReceiver: ReceiverChoice?
    public private(set) var cameraDevices: [CameraDeviceDescriptor] = []
    public var selectedCameraID: String?
    public private(set) var modeCapabilities: [CameraModeCapability] = []
    public var selectedModeID: String = VideoMode.defaultMode.id
    public var selectedBitrateBps: Int = VideoMode.defaultMode.defaultBitrateBps
    public var selectedOrientation: StreamRotation = .zero
    public var selectedStabilization: CameraStabilizationPreference = .auto
    public private(set) var stabilizationOptions: [CameraStabilizationPreference] = CameraState.initial.supportedStabilization
    public private(set) var cameraAuthorization: CameraAuthorizationState = .notDetermined
    public private(set) var isStarting = false
    public private(set) var isStreamActive = false
    public private(set) var statusMessage = "Checking for OBS receivers…"
    public var failure: StreamFailure?

    public var selectedMode: VideoMode? {
        VideoMode.productModes.first(where: { $0.id == selectedModeID })
    }

    public var bitrateOptions: [Int] {
        guard let selectedMode,
              let capability = selectedModeCapability,
              let minimum = capability.encoderMinimumBitrateBps,
              let maximum = capability.encoderMaximumBitrateBps else {
            return []
        }
        return selectedMode.steppedBitrates(encoderRange: minimum...maximum)
    }

    public var canStart: Bool {
        !isStarting && !isStreamActive && cameraAuthorization == .authorized && receiverStatus == .ready && selectedReceiver != nil && selectedModeCapability?.supported == true && selectedCameraID != nil && bitrateOptions.contains(selectedBitrateBps)
    }

    private let settingsStore: any SenderSettingsStoring
    private let browser: any ReceiverBrowsing
    private let probe: any ReceiverProbing
    private let capture: any CameraSetupServicing
    private let encoderProbe: any EncoderCapabilityProbing
    private let sessionCoordinator: any StreamSessionStarting
    private let logger: CamBridgeLogger
    private let preferredReceiverID: String?
    @ObservationIgnored private var discoveryTask: Task<Void, Never>?
    @ObservationIgnored private var capabilityTask: Task<Void, Never>?
    private var cameraRefreshID: UUID?
    private var capabilityRefreshID: UUID?
    private var hasExplicitReceiverSelection = false

    public init(
        settingsStore: any SenderSettingsStoring,
        browser: any ReceiverBrowsing,
        probe: any ReceiverProbing,
        capture: any CameraSetupServicing,
        encoderProbe: any EncoderCapabilityProbing,
        sessionCoordinator: any StreamSessionStarting,
        logger: CamBridgeLogger
    ) {
        self.settingsStore = settingsStore
        self.browser = browser
        self.probe = probe
        self.capture = capture
        self.encoderProbe = encoderProbe
        self.sessionCoordinator = sessionCoordinator
        self.logger = logger
        let preferences = settingsStore.load()
        preferredReceiverID = preferences.receiverId
        selectedModeID = preferences.modeId
        selectedBitrateBps = preferences.bitrateBps
        selectedOrientation = preferences.orientation
        selectedStabilization = CameraStabilizationPreference(rawValue: preferences.stabilizationPreference) ?? .auto
        manualHost = preferences.receiverHost ?? ""
        selectedReceiverID = preferences.receiverId
    }

    public func startDiscovery() {
        discoveryTask?.cancel()
        capabilityTask?.cancel()
        cameraRefreshID = nil
        capabilityRefreshID = nil
        logger.event("discovery_started", category: .discovery)
        discoveryTask = Task { [weak self] in
            guard let self else { return }
            let events = await browser.events()
            for await event in events {
                guard !Task.isCancelled else { return }
                await self.handleDiscovery(event)
            }
        }
        capabilityTask = Task { [weak self] in
            guard let self else { return }
            await self.refreshCameraAndModes()
        }
    }

    public func stopDiscovery() {
        discoveryTask?.cancel()
        discoveryTask = nil
        capabilityTask?.cancel()
        capabilityTask = nil
        cameraRefreshID = nil
        capabilityRefreshID = nil
        logger.event("discovery_stopped", category: .discovery)
        Task { await browser.stop() }
    }

    public func refreshCameraAndModes() async {
        let refreshID = UUID()
        cameraRefreshID = refreshID
        cameraAuthorization = await capture.authorizationState()
        guard cameraRefreshID == refreshID else { return }
        cameraDevices = await capture.availableCameras()
        guard cameraRefreshID == refreshID else { return }
        if selectedCameraID == nil || !cameraDevices.contains(where: { $0.id == selectedCameraID }) {
            selectedCameraID = cameraDevices.first?.id
        }
        if let selectedCameraID {
            _ = await capture.selectCamera(withID: selectedCameraID)
            guard cameraRefreshID == refreshID else { return }
        }
        stabilizationOptions = await capture.cameraState().supportedStabilization
        guard cameraRefreshID == refreshID else { return }
        if !stabilizationOptions.contains(selectedStabilization) {
            selectedStabilization = stabilizationOptions.first ?? .off
        }
        await refreshModeCapabilities()
    }

    public func requestCameraAccess() async {
        cameraAuthorization = await capture.requestAuthorization()
        await refreshCameraAndModes()
    }

    public func refreshModeCapabilities() async {
        let refreshID = UUID()
        capabilityRefreshID = refreshID
        guard selectedCameraID != nil else {
            modeCapabilities = VideoMode.productModes.map { CameraModeCapability(mode: $0, supported: false, reason: "No rear camera", formatID: nil) }
            return
        }
        let cameraCapabilities = await capture.modeCapabilities(
            modes: VideoMode.productModes,
            receiver: selectedReceiver?.capabilities,
            orientation: selectedOrientation
        )
        guard capabilityRefreshID == refreshID else { return }
        modeCapabilities = cameraCapabilities.map { cameraCapability in
            guard cameraCapability.supported else { return cameraCapability }
            let encoderCapability = encoderProbe.probe(mode: cameraCapability.mode, bitrateBps: cameraCapability.mode.defaultBitrateBps)
            guard encoderCapability.supported else {
                return CameraModeCapability(mode: cameraCapability.mode, supported: false, reason: cameraCapability.reason ?? encoderCapability.reason, formatID: cameraCapability.formatID, supportedStabilization: cameraCapability.supportedStabilization)
            }
            return CameraModeCapability(
                mode: cameraCapability.mode,
                supported: true,
                reason: nil,
                formatID: cameraCapability.formatID,
                supportedStabilization: cameraCapability.supportedStabilization,
                encoderMinimumBitrateBps: encoderCapability.minimumBitrateBps,
                encoderMaximumBitrateBps: encoderCapability.maximumBitrateBps
            )
        }
        if selectedModeCapability?.supported != true {
            selectedModeID = modeCapabilities.first(where: { $0.supported })?.mode.id ?? selectedModeID
        }
        if let selectedModeCapability, selectedModeCapability.supported {
            stabilizationOptions = selectedModeCapability.supportedStabilization
            if !stabilizationOptions.contains(selectedStabilization) {
                selectedStabilization = stabilizationOptions.first ?? .off
            }
        } else {
            stabilizationOptions = [.off]
            selectedStabilization = .off
        }
        if let firstBitrate = bitrateOptions.first, !bitrateOptions.contains(selectedBitrateBps) {
            selectedBitrateBps = min(max(selectedBitrateBps, firstBitrate), bitrateOptions.last ?? firstBitrate)
        }
    }

    public func selectReceiver(_ receiverID: String) {
        hasExplicitReceiverSelection = true
        selectedReceiverID = receiverID
        selectedReceiver = receivers.first(where: { $0.id == receiverID })
        receiverStatus = selectedReceiver == nil ? .selectionRequired : .ready
        scheduleModeCapabilityRefresh()
    }

    public func selectMode(_ modeID: String) {
        guard VideoMode.productModes.contains(where: { $0.id == modeID }) else { return }
        selectedModeID = modeID
        if let defaultBitrate = selectedMode?.defaultBitrateBps { selectedBitrateBps = defaultBitrate }
        scheduleModeCapabilityRefresh()
    }

    public func selectOrientation(_ orientation: StreamRotation) {
        selectedOrientation = orientation
        scheduleModeCapabilityRefresh()
    }

    public func selectCamera(_ cameraID: String) {
        guard cameraDevices.contains(where: { $0.id == cameraID }) else { return }
        selectedCameraID = cameraID
        capabilityTask?.cancel()
        capabilityTask = Task { [weak self] in
            guard let self else { return }
            _ = await self.capture.selectCamera(withID: cameraID)
            await self.refreshModeCapabilities()
        }
    }

    public func setManualHost(_ host: String) {
        manualHost = host
    }

    public func setStreamActive(_ active: Bool) {
        isStreamActive = active
    }

    public func probeManualReceiver() async {
        let host = manualHost.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let endpoint = try? ReceiverEndpoint(host: host) else {
            receiverStatus = .unavailable("Enter a valid receiver host.")
            return
        }
        receiverStatus = .checking
        let result = await probe.probe(target: .manual(endpoint))
        switch result {
        case let .success(capabilities):
            logger.event("manual_receiver_probe_succeeded", category: .discovery, fields: ["receiverId": capabilities.receiverId])
            let choice = ReceiverChoice(id: capabilities.receiverId, displayName: capabilities.displayName, endpoint: endpoint, target: .manual(endpoint), capabilities: capabilities, mediaHosts: [endpoint.host])
            receivers = [choice]
            hasExplicitReceiverSelection = true
            selectedReceiverID = choice.id
            selectedReceiver = choice
            receiverStatus = .ready
            statusMessage = "Ready: \(choice.displayName)"
            await refreshModeCapabilities()
        case let .failure(failure):
            logger.event("manual_receiver_probe_failed", category: .discovery, fields: ["failure": failure.recoverySummary])
            receiverStatus = .unavailable(failure.recoverySummary)
            self.failure = failure
        }
    }

    public func startStream() async {
        guard canStart,
              let receiver = selectedReceiver,
              let mode = selectedMode,
              let cameraID = selectedCameraID,
              let configuration = try? StreamConfiguration(mode: mode, bitrateBps: selectedBitrateBps, orientation: selectedOrientation) else {
            failure = .invalidConfiguration("Choose a supported receiver, camera, mode, orientation, and bitrate.")
            return
        }
        isStarting = true
        failure = nil
        statusMessage = "Starting stream…"
        let authorization = await capture.authorizationState()
        let effectiveAuthorization: CameraAuthorizationState
        if authorization == .authorized {
            effectiveAuthorization = authorization
        } else {
            effectiveAuthorization = await capture.requestAuthorization()
        }
        guard effectiveAuthorization == .authorized else {
            cameraAuthorization = effectiveAuthorization
            isStarting = false
            failure = .permissionDenied
            return
        }
        let result = await sessionCoordinator.start(endpoint: receiver.endpoint, controlTarget: receiver.target, receiver: receiver.capabilities, configuration: configuration, cameraDeviceID: cameraID, stabilization: selectedStabilization, mediaHosts: receiver.mediaHosts)
        isStarting = false
        switch result {
        case .success:
            logger.event("stream_start_succeeded", category: .session, fields: ["mode": mode.id])
            settingsStore.save(SenderPreferences(modeId: mode.id, bitrateBps: selectedBitrateBps, orientation: selectedOrientation, stabilizationPreference: selectedStabilization.rawValue, receiverId: receiver.id, receiverDisplayName: receiver.displayName, receiverHost: receiver.endpoint.host, receiverControlPort: receiver.endpoint.controlPort))
            statusMessage = "Streaming to \(receiver.displayName)"
        case let .failure(failure):
            logger.event("stream_start_failed", category: .session, fields: ["failure": failure.recoverySummary])
            self.failure = failure
            statusMessage = failure.recoverySummary
        }
    }

    public func retry() async {
        _ = await sessionCoordinator.stop()
        failure = nil
        await refreshCameraAndModes()
    }

    deinit {
        discoveryTask?.cancel()
        capabilityTask?.cancel()
    }

    private func scheduleModeCapabilityRefresh() {
        capabilityTask?.cancel()
        capabilityTask = Task { [weak self] in
            guard let self else { return }
            await self.refreshModeCapabilities()
        }
    }

    private var selectedModeCapability: CameraModeCapability? {
        modeCapabilities.first(where: { $0.mode.id == selectedModeID })
    }

    private func handleDiscovery(_ event: BonjourReceiverBrowserEvent) async {
        switch event {
        case let .failed(message):
            logger.event("discovery_failed", category: .discovery, fields: ["message": message])
            receiverStatus = .unavailable(message)
            statusMessage = "Bonjour unavailable; enter a receiver manually."
        case let .discovered(candidate):
            guard let host = candidate.metadata.ipv4Addresses.first,
                  let endpoint = try? ReceiverEndpoint(host: host, receiverId: candidate.metadata.receiverId, displayName: candidate.metadata.displayName) else {
                return
            }
            let result = await probe.probe(target: .bonjour(candidate.serviceEndpoint))
            guard case let .success(capabilities) = result else { return }
            guard capabilities.receiverId == candidate.metadata.receiverId else {
                logger.event("discovery_probe_identity_mismatch", category: .discovery)
                return
            }
            let choice = ReceiverChoice(id: capabilities.receiverId, displayName: capabilities.displayName, endpoint: endpoint, target: .bonjour(candidate.serviceEndpoint), capabilities: capabilities, mediaHosts: candidate.metadata.ipv4Addresses, discoveredServiceName: candidate.serviceName)
            receivers.removeAll(where: { $0.id == choice.id })
            receivers.append(choice)
            if selectedReceiverID == choice.id {
                selectedReceiver = choice
            }
            reconcileReceiverSelection()
            await refreshModeCapabilities()
        case let .removed(receiverId):
            receivers.removeAll { choice in
                choice.id == receiverId && choice.discoveredServiceName != nil
            }
            if selectedReceiver?.id == receiverId,
               selectedReceiver?.discoveredServiceName != nil {
                selectedReceiver = nil
                selectedReceiverID = nil
            }
            reconcileReceiverSelection()
            await refreshModeCapabilities()
        }
    }

    private func reconcileReceiverSelection() {
        guard !hasExplicitReceiverSelection else {
            receiverStatus = selectedReceiver == nil ? .selectionRequired : .ready
            statusMessage = selectedReceiver == nil ? "Select an OBS receiver" : "Ready: \(selectedReceiver?.displayName ?? "Receiver")"
            return
        }
        if let preferredReceiverID,
           let preferredChoice = receivers.first(where: { $0.id == preferredReceiverID }) {
            selectedReceiverID = preferredChoice.id
            selectedReceiver = preferredChoice
        } else if receivers.count == Self.singleReceiverCount,
                  let onlyReceiver = receivers.first {
            selectedReceiverID = onlyReceiver.id
            selectedReceiver = onlyReceiver
        } else {
            selectedReceiverID = nil
            selectedReceiver = nil
        }
        if let selectedReceiver {
            receiverStatus = .ready
            statusMessage = "Ready: \(selectedReceiver.displayName)"
        } else if receivers.isEmpty {
            receiverStatus = .checking
            statusMessage = "Checking for OBS receivers…"
        } else {
            receiverStatus = .selectionRequired
            statusMessage = "Select an OBS receiver"
        }
    }

    private static let singleReceiverCount = 1
}
