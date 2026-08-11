import XCTest
import Foundation
import CamBridgeCore
@testable import CamBridge

final class CamBridgeTests: XCTestCase {
    @MainActor
    func testApplicationDependenciesConstructWithoutStartingNetworkOrCamera() {
        let environment = AppEnvironment()

        XCTAssertEqual(environment.appModel.route, .setup)
        XCTAssertEqual(environment.appModel.streamSnapshot.state, .idle)
    }

    @MainActor
    func testApplicationRouteContainsRequiredFeatureAreas() {
        XCTAssertEqual(AppModel.Route.allCases, [.setup, .webcam, .settings])
    }

    func testRecordedCameraFormatRequiresExactRequestedFrameRate() {
        let format = CameraFormatDescriptor(
            formatID: "format-test",
            width: VideoMode.mode1080p30.codedWidth,
            height: VideoMode.mode1080p30.codedHeight,
            minimumFrameRate: 24,
            maximumFrameRate: 30
        )

        XCTAssertTrue(format.supports(fps: VideoMode.mode1080p30.fps))
        XCTAssertFalse(format.supports(fps: VideoMode.mode1080p60.fps))
    }

    func testRecordedCameraFormatsSelectExactDimensionsAndFrameRate() {
        let formats = [
            CameraFormatDescriptor(
                formatID: "wrong-dimensions",
                width: VideoMode.mode1080p30.codedWidth,
                height: VideoMode.mode1080p60.codedHeight,
                minimumFrameRate: 30,
                maximumFrameRate: 60
            ),
            CameraFormatDescriptor(
                formatID: "exact-1080p30",
                width: VideoMode.mode1080p30.codedWidth,
                height: VideoMode.mode1080p30.codedHeight,
                minimumFrameRate: 24,
                maximumFrameRate: 30
            ),
            CameraFormatDescriptor(
                formatID: "too-slow",
                width: VideoMode.mode1080p60.codedWidth,
                height: VideoMode.mode1080p60.codedHeight,
                minimumFrameRate: 24,
                maximumFrameRate: 30
            )
        ]
        let probe = CameraCapabilityProbe()

        XCTAssertEqual(probe.exactFormat(for: VideoMode.mode1080p30, in: formats)?.formatID, "exact-1080p30")
        XCTAssertNil(probe.exactFormat(for: VideoMode.mode1080p60, in: formats))
    }

    func testRecordedCameraFormatRequiresOneSupportedRangeToContainFPS() {
        let format = CameraFormatDescriptor(
            formatID: "disjoint-ranges",
            width: VideoMode.mode1080p30.codedWidth,
            height: VideoMode.mode1080p30.codedHeight,
            supportedFrameRateRanges: [24.0...30.0, 60.0...60.0]
        )

        XCTAssertTrue(format.supports(fps: 30))
        XCTAssertFalse(format.supports(fps: 45))
        XCTAssertTrue(format.supports(fps: 60))
    }

    @MainActor
    func testCapabilityReportCanBeGeneratedBeforeStreamDiagnosticsExist() async throws {
        let settings = FakeSetupSettingsStore()
        let camera = FakeSetupCamera()
        let probeReceiver = try ReceiverCapabilities(
            receiverId: "fixture-receiver",
            displayName: "Fixture receiver",
            maxLongEdge: CamBridgeContract.Geometry.maximumLongEdge,
            maxShortEdge: CamBridgeContract.Geometry.maximumShortEdge
        )
        let builder = CapabilityReportBuilder(clock: { Date(timeIntervalSince1970: 1_700_000_000) })
        let model = StreamSetupModel(
            settingsStore: settings,
            browser: FakeSetupBrowser(),
            probe: FakeSetupProbe(capabilities: probeReceiver),
            capture: camera,
            encoderProbe: FakeSetupEncoderProbe(),
            sessionCoordinator: FakeSetupSession(),
            logger: CamBridgeLogger(),
            capabilityReportBuilder: builder,
            appVersion: "1.2.3",
            buildVersion: "42",
            operatingSystem: "Test OS",
            deviceModel: "Test iPhone"
        )

        let report = await model.generateCapabilityReport()

        XCTAssertEqual(report.schemaVersion, CapabilityReport.currentSchemaVersion)
        XCTAssertEqual(report.generatedAt, Date(timeIntervalSince1970: 1_700_000_000))
        XCTAssertEqual(report.appVersion, "1.2.3")
        XCTAssertEqual(report.cameras.count, 1)
        XCTAssertNil(report.receiver)
        XCTAssertNil(report.selectedReceiverDisplayName)
        XCTAssertNotNil(model.capabilityReport)
    }

    func testCapabilityReportIncludesCameraModesEncoderIdentityAndRedactsReceiverHost() {
        let supportedCapability = CameraModeCapability(
            mode: .mode1080p30,
            supported: true,
            reason: nil,
            formatID: "format-1080p30",
            formatWidth: 1920,
            formatHeight: 1080,
            supportedFrameRateRanges: [24...30],
            supportedStabilization: [.off, .standard]
        )
        let unsupportedCapability = CameraModeCapability(
            mode: .mode2k60,
            supported: false,
            reason: "Receiver geometry limit",
            formatID: "format-2k60",
            formatWidth: 2560,
            formatHeight: 1440,
            supportedFrameRateRanges: [60...60]
        )
        let camera = CameraCapabilitySnapshot(
            device: CameraDeviceDescriptor(id: "camera-rear-wide", name: "Wide Camera", position: .back, isVirtual: false),
            minimumZoomRatio: 1,
            maximumZoomRatio: 8,
            supportedStabilization: [.off, .standard],
            modeCapabilities: [supportedCapability, unsupportedCapability]
        )
        let report = CapabilityReportBuilder(clock: { Date(timeIntervalSince1970: 1_700_000_001) }).build(
            CapabilityReportInput(
                appVersion: "1.2.3",
                buildVersion: "42",
                operatingSystem: "Test OS",
                deviceModel: "Test iPhone",
                cameraAuthorization: "authorized",
                cameras: [CapabilityReportCamera(
                    snapshot: camera,
                    modes: [
                        CapabilityReportMode(
                            capability: supportedCapability,
                            encoder: EncoderCapability(
                                modeId: "1080p30",
                                supported: true,
                                minimumBitrateBps: 4_000_000,
                                maximumBitrateBps: 16_000_000,
                                encoderIdentity: "com.apple.test.h264",
                                encoderIdentityUnavailableReason: nil,
                                encoderUsesHardwareAccelerated: true,
                                encoderHardwareAvailabilityReason: nil,
                                reason: nil
                            )
                        ),
                        CapabilityReportMode(capability: unsupportedCapability, encoder: nil)
                    ]
                )],
                selectedCameraID: "camera-rear-wide",
                receiver: CapabilityReportReceiver(
                    receiverId: "receiver-1",
                    displayName: "OBS",
                    maxLongEdge: 2560,
                    maxShortEdge: 1440,
                    host: "[redacted]"
                ),
                selectedModeID: "1080p30",
                selectedBitrateBps: 8_000_000,
                selectedOrientationDegrees: 90,
                selectedStabilization: CameraStabilizationPreference.standard.rawValue,
                selectedReceiverDisplayName: "OBS"
            )
        )

        let modes = report.cameras[0].modes
        XCTAssertEqual(report.cameras[0].minimumZoomRatio, 1)
        XCTAssertEqual(report.cameras[0].maximumZoomRatio, 8)
        XCTAssertEqual(report.cameras[0].supportedStabilization, ["off", "standard"])
        XCTAssertEqual(modes[0].encoderIdentity, "com.apple.test.h264")
        XCTAssertTrue(modes[0].encoderUsesHardwareAccelerated == true)
        XCTAssertEqual(modes[0].frameRateRanges, [CapabilityReportFrameRateRange(minimum: 24, maximum: 30)])
        XCTAssertFalse(modes[1].offered)
        XCTAssertEqual(modes[1].reason, "Receiver geometry limit")
        XCTAssertEqual(report.receiver?.host, "[redacted]")
        XCTAssertEqual(report.copyableText(), report.copyableText())
        XCTAssertFalse(report.copyableText().contains("127.0.0.1"))

        let unavailableIdentity = CapabilityReportMode(
            capability: supportedCapability,
            encoder: EncoderCapability(
                modeId: "1080p30",
                supported: true,
                minimumBitrateBps: 4_000_000,
                maximumBitrateBps: 16_000_000,
                encoderIdentity: nil,
                encoderIdentityUnavailableReason: "VideoToolbox returned no encoder identifier",
                encoderUsesHardwareAccelerated: nil,
                encoderHardwareAvailabilityReason: "VideoToolbox hardware-use property unavailable",
                reason: nil
            )
        )
        XCTAssertNil(unavailableIdentity.encoderIdentity)
        XCTAssertEqual(
            unavailableIdentity.encoderIdentityUnavailableReason,
            "VideoToolbox returned no encoder identifier"
        )
    }

    @MainActor
    func testInvalidPersistedReceiverFallsBackWithoutCrashing() {
        let suiteName = "cambridge-settings-test-\(UUID().uuidString)"
        guard let defaults = UserDefaults(suiteName: suiteName) else {
            return XCTFail("the test suite should be available")
        }
        defer { defaults.removePersistentDomain(forName: suiteName) }
        defaults.set(Data("not-json".utf8), forKey: "cambridge.sender.preferences.v1")

        let store = SenderSettingsStore(defaults: defaults)

        XCTAssertEqual(store.load(), .default)
    }

    @MainActor
    func testWebcamStopConfirmationAndDimmedPresentationReducers() {
        let environment = AppEnvironment()
        let model = environment.appModel.webcamModel

        model.requestStop()
        XCTAssertTrue(model.isStopConfirmationPresented)
        model.cancelStopRequest()
        XCTAssertFalse(model.isStopConfirmationPresented)

        model.toggleDimmedPresentation()
        XCTAssertTrue(model.isDimmed)
        model.toggleDimmedPresentation()
        XCTAssertFalse(model.isDimmed)
    }

    func testSyntheticEncoderAssemblyPrependsParameterSetsToKeyframes() throws {
        let sps = Data([0x67, 0x64, 0x00, 0x1F])
        let pps = Data([0x68, 0xEB, 0xEC, 0xB2])
        let idr = Data([0x65, 0x88, 0x84])
        let sample = Data([0x00, 0x00, 0x00, 0x03]) + idr

        let accessUnit = try VideoToolboxEncoder.assembleAccessUnit(
            sampleData: sample,
            nalLengthBytes: MemoryLayout<UInt32>.size,
            parameterSets: [sps, pps],
            isKeyframe: true,
            presentationTimeMicroseconds: 1_000
        )

        let startCode = Data([0x00, 0x00, 0x00, 0x01])
        XCTAssertEqual(accessUnit.data, startCode + sps + startCode + pps + startCode + idr)
        XCTAssertTrue(accessUnit.isKeyframe)
        XCTAssertEqual(accessUnit.presentationTimeMicroseconds, 1_000)
    }

    func testVideoToolboxDataRateLimitsUseFlatByteCountAndWindowArray() throws {
        let limits = try VideoToolboxEncoder.dataRateLimits(
            bitrateBps: 8_000_000,
            windowSeconds: 2
        )

        XCTAssertEqual(limits.map(\.intValue), [2_000_000, 2])
        XCTAssertEqual(limits.count, 2)
    }

    func testVideoToolboxDataRateLimitsRoundNonByteAlignedBitrateUp() throws {
        let limits = try VideoToolboxEncoder.dataRateLimits(
            bitrateBps: 9,
            windowSeconds: 1
        )

        XCTAssertEqual(limits.map(\.intValue), [2, 1])
    }

    func testVideoToolboxDataRateLimitsRejectOverflow() {
        XCTAssertThrowsError(
            try VideoToolboxEncoder.dataRateLimits(
                bitrateBps: Int.max,
                windowSeconds: 2
            )
        ) { error in
            XCTAssertEqual(error as? VideoToolboxEncoderError, .dataRateLimitOverflow)
        }
    }

    func testEncodedAccessUnitQueueRetainsOnlyNewestCompleteWork() async throws {
        let queue = try EncodedAccessUnitQueue(capacity: CamBridgeContract.Media.maxInFlightAccessUnits)
        let first = EncodedAccessUnit(data: Data([0x01]), presentationTimeMicroseconds: 1, isKeyframe: false)
        let second = EncodedAccessUnit(data: Data([0x02]), presentationTimeMicroseconds: 2, isKeyframe: false)
        let newest = EncodedAccessUnit(data: Data([0x03]), presentationTimeMicroseconds: 3, isKeyframe: true)

        _ = await queue.insert(first)
        _ = await queue.insert(second)
        let telemetry = await queue.insert(newest)

        XCTAssertEqual(telemetry.occupancy, 1)
        XCTAssertEqual(telemetry.drops, CamBridgeContract.Media.maxInFlightAccessUnits)
        let removed = await queue.removeNewest()
        XCTAssertEqual(removed, newest)
        await queue.finish()
        let afterFinish = await queue.removeNewest()
        XCTAssertNil(afterFinish)
    }

    func testEncodedAccessUnitQueueWakesOneWaitingConsumer() async throws {
        let queue = try EncodedAccessUnitQueue(capacity: CamBridgeContract.Media.maxInFlightAccessUnits)
        let accessUnit = EncodedAccessUnit(data: Data([0x04]), presentationTimeMicroseconds: 4, isKeyframe: true)
        let consumer = Task { await queue.next() }

        queue.offer(accessUnit)

        let received = await consumer.value
        XCTAssertEqual(received, accessUnit)
        await queue.finish()
    }

    func testEncodedAccessUnitCallbackMailboxRetainsNewestAndStaysWithinBound() async throws {
        let queue = try EncodedAccessUnitQueue(capacity: CamBridgeContract.Media.maxInFlightAccessUnits)
        let first = EncodedAccessUnit(data: Data([0x05]), presentationTimeMicroseconds: 5, isKeyframe: false)
        let second = EncodedAccessUnit(data: Data([0x06]), presentationTimeMicroseconds: 6, isKeyframe: false)
        let newest = EncodedAccessUnit(data: Data([0x07]), presentationTimeMicroseconds: 7, isKeyframe: true)

        queue.offer(first)
        queue.offer(second)
        queue.offer(newest)

        let telemetry = await queue.telemetry()
        XCTAssertLessThanOrEqual(telemetry.occupancy, CamBridgeContract.Media.maxInFlightAccessUnits)
        XCTAssertEqual(telemetry.drops, 2)
        let received = await queue.next()
        XCTAssertEqual(received, newest)
        await queue.finish()
    }

    @MainActor
    func testStreamSetupUsesInjectedCapabilitiesAndStartsOnce() async throws {
        let settings = FakeSetupSettingsStore()
        let browser = FakeSetupBrowser()
        let session = FakeSetupSession()
        let receiver = try ReceiverCapabilities(
            receiverId: "fixture-receiver",
            displayName: "Fixture Receiver",
            maxLongEdge: CamBridgeContract.Geometry.maximumLongEdge,
            maxShortEdge: CamBridgeContract.Geometry.maximumShortEdge
        )
        let model = StreamSetupModel(
            settingsStore: settings,
            browser: browser,
            probe: FakeSetupProbe(capabilities: receiver),
            capture: FakeSetupCamera(),
            encoderProbe: FakeSetupEncoderProbe(),
            sessionCoordinator: session,
            logger: CamBridgeLogger()
        )

        await model.refreshCameraAndModes()
        model.manualHost = "192.168.1.10"
        await model.probeManualReceiver()

        XCTAssertEqual(model.receiverStatus, .ready)
        XCTAssertTrue(model.canStart)
        await model.startStream()
        XCTAssertFalse(model.isStarting)
        XCTAssertNil(model.failure)
        XCTAssertEqual(settings.load().receiverId, "fixture-receiver")
        let startCount = await session.startCount()
        XCTAssertEqual(startCount, 1)
    }

    @MainActor
    func testBackgroundLifecycleRequestsTerminalSessionCleanup() async {
        let session = FakeBackgroundSession()
        let controller = AppLifecycleController(sessionCoordinator: session, logger: CamBridgeLogger())

        controller.streamActivityChanged(isActive: true)
        controller.scenePhaseChanged(.background)
        await session.waitUntilEnded()

        let endCount = await session.endCount()
        XCTAssertEqual(endCount, 1)
    }
}

@MainActor
private final class FakeSetupSettingsStore: SenderSettingsStoring {
    private var preferences = SenderPreferences.default

    func load() -> SenderPreferences {
        preferences
    }

    func save(_ preferences: SenderPreferences) {
        self.preferences = preferences
    }
}

private actor FakeSetupBrowser: ReceiverBrowsing {
    private var stopCalls = 0

    func events() async -> AsyncStream<BonjourReceiverBrowserEvent> {
        AsyncStream(bufferingPolicy: .bufferingNewest(CamBridgeContract.Media.mailboxCapacity)) { continuation in
            continuation.finish()
        }
    }

    func stop() async {
        stopCalls += 1
    }
}

private struct FakeSetupProbe: ReceiverProbing {
    let capabilities: ReceiverCapabilities

    func probe(target: ReceiverControlTarget) async -> Result<ReceiverCapabilities, StreamFailure> {
        .success(capabilities)
    }
}

private actor FakeSetupCamera: CameraSetupServicing {
    private let camera = CameraDeviceDescriptor(
        id: "fixture-camera",
        name: "Fixture rear camera",
        position: .back,
        isVirtual: false
    )
    private var selectedCameraID: String?

    func authorizationState() async -> CameraAuthorizationState {
        .authorized
    }

    func requestAuthorization() async -> CameraAuthorizationState {
        .authorized
    }

    func availableCameras() async -> [CameraDeviceDescriptor] {
        [camera]
    }

    func selectCamera(withID deviceID: String) async -> Bool {
        guard deviceID == camera.id else { return false }
        selectedCameraID = deviceID
        return true
    }

    func modeCapabilities(
        modes: [VideoMode],
        receiver: ReceiverCapabilities?,
        orientation: StreamRotation
    ) async -> [CameraModeCapability] {
        guard selectedCameraID != nil else {
            return modes.map { CameraModeCapability(mode: $0, supported: false, reason: "No fixture camera selected", formatID: nil) }
        }
        return modes.map { mode in
            let supportedByReceiver: Bool
            if let geometry = mode.geometry {
                supportedByReceiver = receiver?.supports(geometry, rotation: orientation) ?? true
            } else {
                supportedByReceiver = false
            }
            return CameraModeCapability(
                mode: mode,
                supported: supportedByReceiver,
                reason: supportedByReceiver ? nil : "Fixture receiver geometry limit",
                formatID: "fixture-\(mode.id)",
                supportedStabilization: [.off]
            )
        }
    }

    func capabilitySnapshots(
        modes: [VideoMode],
        receiver: ReceiverCapabilities?,
        orientation: StreamRotation
    ) async -> [CameraCapabilitySnapshot] {
        let capabilities = await modeCapabilities(modes: modes, receiver: receiver, orientation: orientation)
        return [
            CameraCapabilitySnapshot(
                device: camera,
                minimumZoomRatio: 1,
                maximumZoomRatio: 6,
                supportedStabilization: [.off],
                modeCapabilities: capabilities
            )
        ]
    }

    func cameraState() async -> CameraState {
        var state = CameraState.initial
        state.authorization = .authorized
        state.devices = [camera]
        state.selectedDeviceID = selectedCameraID
        state.supportedStabilization = [.off]
        state.activeStabilization = .off
        return state
    }
}

private struct FakeSetupEncoderProbe: EncoderCapabilityProbing {
    func probe(mode: VideoMode, bitrateBps: Int) -> EncoderCapability {
        let supported = mode.steppedBitrates(
            encoderRange: CamBridgeContract.Bitrate.minimumBps...CamBridgeContract.Bitrate.maximumBps
        ).contains(bitrateBps)
        return EncoderCapability(
            modeId: mode.id,
            supported: supported,
            minimumBitrateBps: mode.minimumBitrateBps,
            maximumBitrateBps: mode.maximumBitrateBps,
            encoderIdentity: supported ? "fixture-encoder" : nil,
            reason: supported ? nil : "Fixture bitrate rejected"
        )
    }
}

private actor FakeSetupSession: StreamSessionStarting {
    private var starts = 0
    private var stops = 0

    func start(
        endpoint: ReceiverEndpoint,
        controlTarget: ReceiverControlTarget,
        receiver: ReceiverCapabilities,
        configuration: StreamConfiguration,
        cameraDeviceID: String,
        stabilization: CameraStabilizationPreference,
        mediaHosts: [String]
    ) async -> Result<Void, StreamFailure> {
        starts += 1
        return .success(())
    }

    func stop() async -> Result<Void, Never> {
        stops += 1
        return .success(())
    }

    func startCount() -> Int { starts }
}

private actor FakeBackgroundSession: StreamBackgroundEnding {
    private var ends = 0
    private var waiter: CheckedContinuation<Void, Never>?

    func endForBackground() async {
        ends += 1
        waiter?.resume()
        waiter = nil
    }

    func waitUntilEnded() async {
        if ends > .zero { return }
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            waiter = continuation
        }
    }

    func endCount() -> Int { ends }
}
