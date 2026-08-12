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
        public let mediaHosts: [String]
        public let discoveredServiceName: String?

        init(
            id: String,
            displayName: String,
            endpoint: ReceiverEndpoint,
            target: ReceiverControlTarget,
            mediaHosts: [String],
            discoveredServiceName: String? = nil
        ) {
            self.id = id
            self.displayName = displayName
            self.endpoint = endpoint
            self.target = target
            self.mediaHosts = mediaHosts
            self.discoveredServiceName = discoveredServiceName
        }
    }

    public private(set) var receiverStatus: ReceiverStatus = .checking
    public private(set) var receivers: [ReceiverChoice] = []
    public var selectedReceiverID: String?
    public var manualHost = ""
    public private(set) var selectedReceiver: ReceiverChoice?
    public private(set) var cameraAuthorization: CameraAuthorizationState = .notDetermined
    public private(set) var isStarting = false
    public private(set) var statusMessage = "Checking for OBS receivers…"
    public var failure: StreamFailure?
    public var bitrateText: String

    public var selectedResolutionID: String {
        get { preferencesState.preferences.resolutionId }
        set { selectResolution(newValue) }
    }

    public var selectedFPS: Int {
        get { preferencesState.preferences.fps }
        set { selectFrameRate(newValue) }
    }

    public var selectedResolution: VideoResolution? {
        SenderVideoCatalog.resolution(id: selectedResolutionID)
    }

    public var selectedBitrateBps: Int? {
        BitrateInput.bitsPerSecond(fromWholeMegabits: bitrateText)
    }

    public var isStreamActive: Bool { preferencesState.isStreamActive }

    public var canStart: Bool {
        !isStarting
            && !isStreamActive
            && cameraAuthorization == .authorized
            && receiverStatus == .ready
            && selectedReceiver != nil
            && selectedResolution != nil
            && SenderVideoCatalog.frameRates.contains(selectedFPS)
            && selectedBitrateBps != nil
    }

    private let preferencesState: SenderPreferencesState
    private let browser: any ReceiverBrowsing
    private let probe: any ReceiverProbing
    private let capture: any CameraSetupServicing
    private let sessionCoordinator: any StreamSessionStarting
    private let logger: CamBridgeLogger
    private let orientationProvider: any StreamOrientationProviding
    private let preferredReceiverID: String?
    @ObservationIgnored private var discoveryTask: Task<Void, Never>?
    @ObservationIgnored private var cameraTask: Task<Void, Never>?
    @ObservationIgnored private var preferenceTask: Task<Void, Never>?
    private var cameraRefreshID: UUID?
    private var hasExplicitReceiverSelection = false

    public init(
        settingsStore: any SenderSettingsStoring,
        browser: any ReceiverBrowsing,
        probe: any ReceiverProbing,
        capture: any CameraSetupServicing,
        sessionCoordinator: any StreamSessionStarting,
        logger: CamBridgeLogger,
        orientationProvider: any StreamOrientationProviding = InterfaceOrientationProvider()
    ) {
        let preferencesState = SenderPreferencesState(settingsStore: settingsStore)
        self.preferencesState = preferencesState
        self.browser = browser
        self.probe = probe
        self.capture = capture
        self.sessionCoordinator = sessionCoordinator
        self.logger = logger
        self.orientationProvider = orientationProvider
        preferredReceiverID = preferencesState.preferences.receiverId
        manualHost = preferencesState.preferences.receiverHost ?? ""
        selectedReceiverID = preferencesState.preferences.receiverId
        bitrateText = BitrateInput.wholeMegabits(
            fromBitsPerSecond: preferencesState.preferences.bitrateBps
        ) ?? ""
    }

    public init(
        preferencesState: SenderPreferencesState,
        browser: any ReceiverBrowsing,
        probe: any ReceiverProbing,
        capture: any CameraSetupServicing,
        sessionCoordinator: any StreamSessionStarting,
        logger: CamBridgeLogger,
        orientationProvider: any StreamOrientationProviding = InterfaceOrientationProvider()
    ) {
        self.preferencesState = preferencesState
        self.browser = browser
        self.probe = probe
        self.capture = capture
        self.sessionCoordinator = sessionCoordinator
        self.logger = logger
        self.orientationProvider = orientationProvider
        preferredReceiverID = preferencesState.preferences.receiverId
        manualHost = preferencesState.preferences.receiverHost ?? ""
        selectedReceiverID = preferencesState.preferences.receiverId
        bitrateText = BitrateInput.wholeMegabits(
            fromBitsPerSecond: preferencesState.preferences.bitrateBps
        ) ?? ""
    }

    public func startDiscovery() {
        discoveryTask?.cancel()
        cameraTask?.cancel()
        preferenceTask?.cancel()
        cameraRefreshID = nil
        logger.event("discovery_started", category: .discovery)
        discoveryTask = Task { [weak self] in
            guard let self else { return }
            let events = await browser.events()
            for await event in events {
                guard !Task.isCancelled else { return }
                await self.handleDiscovery(event)
            }
        }
        cameraTask = Task { [weak self] in
            await self?.refreshCameraAuthorization()
        }
        preferenceTask = Task { [weak self] in
            guard let self else { return }
            let changes = preferencesState.changes()
            for await preferences in changes {
                guard !Task.isCancelled else { return }
                self.syncBitrateText(with: preferences)
            }
        }
    }

    public func stopDiscovery() {
        discoveryTask?.cancel()
        discoveryTask = nil
        cameraTask?.cancel()
        cameraTask = nil
        preferenceTask?.cancel()
        preferenceTask = nil
        cameraRefreshID = nil
        logger.event("discovery_stopped", category: .discovery)
        Task { await browser.stop() }
    }

    public func refreshCameraAuthorization() async {
        let refreshID = UUID()
        cameraRefreshID = refreshID
        cameraAuthorization = await capture.authorizationState()
        guard cameraRefreshID == refreshID else { return }
    }

    public func requestCameraAccess() async {
        cameraAuthorization = await capture.requestAuthorization()
        await refreshCameraAuthorization()
    }

    public func selectReceiver(_ receiverID: String) {
        hasExplicitReceiverSelection = true
        selectedReceiverID = receiverID
        selectedReceiver = receivers.first(where: { $0.id == receiverID })
        receiverStatus = selectedReceiver == nil ? .selectionRequired : .ready
    }

    public func selectResolution(_ resolutionID: String) {
        guard resolutionID != selectedResolutionID else { return }
        guard let resolution = SenderVideoCatalog.resolution(id: resolutionID),
              let suggestedBitrate = SenderVideoCatalog.suggestedBitrateBps(
                resolution: resolution,
                fps: selectedFPS
              ),
              let suggestedText = BitrateInput.wholeMegabits(fromBitsPerSecond: suggestedBitrate) else {
            return
        }
        bitrateText = suggestedText
        _ = updatePreferences { preferences in
            preferences.resolutionId = resolution.id
            preferences.bitrateBps = suggestedBitrate
        }
    }

    public func selectFrameRate(_ fps: Int) {
        guard fps != selectedFPS else { return }
        guard SenderVideoCatalog.frameRates.contains(fps),
              let resolution = selectedResolution,
              let suggestedBitrate = SenderVideoCatalog.suggestedBitrateBps(
                resolution: resolution,
                fps: fps
              ),
              let suggestedText = BitrateInput.wholeMegabits(fromBitsPerSecond: suggestedBitrate) else {
            return
        }
        bitrateText = suggestedText
        _ = updatePreferences { preferences in
            preferences.fps = fps
            preferences.bitrateBps = suggestedBitrate
        }
    }

    public func setBitrateText(_ text: String) {
        bitrateText = text
        guard let bitrateBps = BitrateInput.bitsPerSecond(fromWholeMegabits: text) else { return }
        _ = updatePreferences { $0.bitrateBps = bitrateBps }
    }

    public func setManualHost(_ host: String) {
        manualHost = host
    }

    public func setStreamActive(_ active: Bool) {
        preferencesState.setStreamActive(active)
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
            let choice = ReceiverChoice(
                id: capabilities.receiverId,
                displayName: capabilities.displayName,
                endpoint: endpoint,
                target: .manual(endpoint),
                mediaHosts: [endpoint.host]
            )
            receivers = [choice]
            hasExplicitReceiverSelection = true
            selectedReceiverID = choice.id
            selectedReceiver = choice
            receiverStatus = .ready
            statusMessage = "Ready: \(choice.displayName)"
        case let .failure(failure):
            logger.event("manual_receiver_probe_failed", category: .discovery, fields: ["failure": failure.recoverySummary])
            receiverStatus = .unavailable(failure.recoverySummary)
            self.failure = failure
        }
    }

    public func startStream() async {
        guard canStart,
              let receiver = selectedReceiver,
              let resolution = selectedResolution,
              let bitrateBps = selectedBitrateBps,
              let configuration = try? StreamConfiguration(
                resolution: resolution,
                fps: selectedFPS,
                bitrateBps: bitrateBps,
                orientation: orientationProvider.currentRotation()
              ) else {
            failure = .invalidConfiguration("Choose a receiver and enter valid resolution, FPS, and bitrate settings.")
            return
        }
        isStarting = true
        failure = nil
        statusMessage = "Starting stream…"
        let result = await sessionCoordinator.start(
            endpoint: receiver.endpoint,
            controlTarget: receiver.target,
            configuration: configuration,
            cameraPosition: .back,
            mediaHosts: receiver.mediaHosts
        )
        isStarting = false
        switch result {
        case .success:
            logger.event("stream_start_succeeded", category: .session, fields: [
                "width": String(resolution.codedWidth),
                "height": String(resolution.codedHeight),
                "fps": String(selectedFPS),
                "bitrateBps": String(bitrateBps),
            ])
            preferencesState.commitAccepted(SenderPreferences(
                resolutionId: resolution.id,
                fps: selectedFPS,
                bitrateBps: bitrateBps,
                receiverId: receiver.id,
                receiverDisplayName: receiver.displayName,
                receiverHost: receiver.endpoint.host,
                receiverControlPort: receiver.endpoint.controlPort
            ))
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
        await refreshCameraAuthorization()
    }

    deinit {
        discoveryTask?.cancel()
        cameraTask?.cancel()
        preferenceTask?.cancel()
    }

    @discardableResult
    private func updatePreferences(_ mutate: (inout SenderPreferences) -> Void) -> Bool {
        var preferences = preferencesState.preferences
        mutate(&preferences)
        return preferencesState.update(preferences)
    }

    private func syncBitrateText(with preferences: SenderPreferences) {
        guard let storedText = BitrateInput.wholeMegabits(fromBitsPerSecond: preferences.bitrateBps),
              BitrateInput.bitsPerSecond(fromWholeMegabits: bitrateText) != preferences.bitrateBps else {
            return
        }
        bitrateText = storedText
    }

    private func handleDiscovery(_ event: BonjourReceiverBrowserEvent) async {
        switch event {
        case let .failed(message):
            logger.event("discovery_failed", category: .discovery, fields: ["message": message])
            receiverStatus = .unavailable(message)
            statusMessage = "Bonjour unavailable; enter a receiver manually."
        case let .discovered(candidate):
            guard let host = candidate.metadata.ipv4Addresses.first,
                  let endpoint = try? ReceiverEndpoint(
                    host: host,
                    receiverId: candidate.metadata.receiverId,
                    displayName: candidate.metadata.displayName
                  ) else {
                return
            }
            let result = await probe.probe(target: .bonjour(candidate.serviceEndpoint))
            guard case let .success(capabilities) = result else { return }
            guard capabilities.receiverId == candidate.metadata.receiverId else {
                logger.event("discovery_probe_identity_mismatch", category: .discovery)
                return
            }
            let choice = ReceiverChoice(
                id: capabilities.receiverId,
                displayName: capabilities.displayName,
                endpoint: endpoint,
                target: .bonjour(candidate.serviceEndpoint),
                mediaHosts: candidate.metadata.ipv4Addresses,
                discoveredServiceName: candidate.serviceName
            )
            receivers.removeAll(where: { $0.id == choice.id })
            receivers.append(choice)
            if selectedReceiverID == choice.id {
                selectedReceiver = choice
            }
            reconcileReceiverSelection()
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
        }
    }

    private func reconcileReceiverSelection() {
        guard !hasExplicitReceiverSelection else {
            receiverStatus = selectedReceiver == nil ? .selectionRequired : .ready
            statusMessage = selectedReceiver == nil
                ? "Select an OBS receiver"
                : "Ready: \(selectedReceiver?.displayName ?? "Receiver")"
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
