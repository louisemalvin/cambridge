import XCTest
import Foundation
import CamBridgeCore
@testable import CamBridge

final class CamBridgeTests: XCTestCase {
    @MainActor
    func testApplicationDependenciesConstructWithoutStartingNetworkOrCamera() {
        let environment = AppEnvironment()

        XCTAssertEqual(environment.appModel.route, .settings)
        XCTAssertEqual(environment.appModel.streamSnapshot.state, .idle)
    }

    @MainActor
    func testApplicationRouteContainsRequiredFeatureAreas() {
        XCTAssertEqual(AppModel.Route.allCases, [.setup, .webcam, .settings])
    }

    func testCameraFormatRequiresOneSupportedRangeToContainFPS() {
        let format = CameraFormatDescriptor(
            formatID: "disjoint-ranges",
            width: SenderVideoCatalog.fullHd.codedWidth,
            height: SenderVideoCatalog.fullHd.codedHeight,
            supportedFrameRateRanges: [24.0...30.0, 60.0...60.0]
        )

        XCTAssertTrue(format.supports(fps: 30))
        XCTAssertFalse(format.supports(fps: 45))
        XCTAssertTrue(format.supports(fps: 60))
    }

    func testCameraFormatSelectionUsesSmallestCompatibleSameAspectSource() {
        let formats = [
            CameraFormatDescriptor(
                formatID: "smaller",
                width: 1280,
                height: 720,
                minimumFrameRate: 30,
                maximumFrameRate: 60
            ),
            CameraFormatDescriptor(
                formatID: "different-aspect",
                width: 2048,
                height: 1536,
                minimumFrameRate: 30,
                maximumFrameRate: 60
            ),
            CameraFormatDescriptor(
                formatID: "four-k",
                width: 3840,
                height: 2160,
                minimumFrameRate: 30,
                maximumFrameRate: 60
            ),
            CameraFormatDescriptor(
                formatID: "exact-two-k",
                width: 2560,
                height: 1440,
                minimumFrameRate: 30,
                maximumFrameRate: 30
            ),
        ]
        let probe = CameraCapabilityProbe()

        XCTAssertEqual(
            probe.compatibleFormatDescriptor(
                for: SenderVideoCatalog.resolution2k,
                fps: 30,
                in: formats
            )?.formatID,
            "exact-two-k"
        )
        XCTAssertEqual(
            probe.compatibleFormatDescriptor(
                for: SenderVideoCatalog.resolution2k,
                fps: 60,
                in: formats
            )?.formatID,
            "four-k"
        )
    }

    func testEncoderInputMustMatchSelectedCodedDimensions() throws {
        let configuration = try StreamConfiguration(
            resolution: SenderVideoCatalog.resolution2k,
            fps: 60,
            bitrateBps: 18_000_000,
            orientation: .zero
        )

        XCTAssertNoThrow(try VideoToolboxEncoder.validateInputDimensions(
            width: SenderVideoCatalog.resolution2k.codedWidth,
            height: SenderVideoCatalog.resolution2k.codedHeight,
            configuration: configuration
        ))
        XCTAssertThrowsError(try VideoToolboxEncoder.validateInputDimensions(
            width: SenderVideoCatalog.fullHd.codedWidth,
            height: SenderVideoCatalog.fullHd.codedHeight,
            configuration: configuration
        )) { error in
            XCTAssertEqual(
                error as? VideoToolboxEncoderError,
                .inputDimensionsMismatch(
                    expectedWidth: SenderVideoCatalog.resolution2k.codedWidth,
                    expectedHeight: SenderVideoCatalog.resolution2k.codedHeight,
                    actualWidth: SenderVideoCatalog.fullHd.codedWidth,
                    actualHeight: SenderVideoCatalog.fullHd.codedHeight
                )
            )
        }
    }

    func testZoomMappingExposesOnlyProductFacingTargetsAndCap() {
        let multiCamera = CameraZoomMapping(
            rawMinimum: 1,
            rawMaximum: 40,
            displayMultiplier: 0.5
        )
        let singleCamera = CameraZoomMapping(
            rawMinimum: 1,
            rawMaximum: 123.75,
            displayMultiplier: 1
        )

        XCTAssertEqual(multiCamera.targets, [0.5, 1, 2])
        XCTAssertEqual(multiCamera.rawRatio(forUserRatio: 1), 2)
        XCTAssertEqual(multiCamera.maximumUserRatio, CameraZoomPolicy.maximumUserZoomRatio)
        XCTAssertEqual(singleCamera.targets, [1, 2])
        XCTAssertEqual(singleCamera.maximumUserRatio, CameraZoomPolicy.maximumUserZoomRatio)
    }

    @MainActor
    func testFreshSettingsUseFullHD30AtFiveMbps() {
        let store = FakeSetupSettingsStore()
        let preferences = store.load()

        XCTAssertEqual(preferences.resolutionId, SenderVideoCatalog.fullHd.id)
        XCTAssertEqual(preferences.fps, 30)
        XCTAssertEqual(preferences.bitrateBps, 5_000_000)
    }

    @MainActor
    func testLegacySettingsMigrateIndependentValuesAndPreserveManualBitrate() throws {
        let suiteName = "CamBridgeTests.legacy-settings.\(UUID().uuidString)"
        guard let defaults = UserDefaults(suiteName: suiteName) else {
            return XCTFail("failed to create isolated defaults")
        }
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let legacy: [String: Any] = [
            "modeId": "1080p60",
            "bitrateBps": 1_000_000,
            "orientation": 90,
            "stabilizationPreference": "standard",
        ]
        defaults.set(try JSONSerialization.data(withJSONObject: legacy), forKey: "cambridge.sender.preferences.v1")

        let preferences = SenderSettingsStore(defaults: defaults).load()

        XCTAssertEqual(preferences.resolutionId, SenderVideoCatalog.fullHd.id)
        XCTAssertEqual(preferences.fps, 60)
        XCTAssertEqual(preferences.bitrateBps, 1_000_000)
    }

    @MainActor
    func testSetupUsesIndependentDefaultsAndStartsOneExactManualOverride() async throws {
        let settings = FakeSetupSettingsStore()
        let session = FakeSetupSession()
        let receiver = try ReceiverCapabilities(
            receiverId: "fixture-receiver",
            displayName: "Fixture Receiver",
            maxLongEdge: CamBridgeContract.Geometry.maximumLongEdge,
            maxShortEdge: CamBridgeContract.Geometry.maximumShortEdge
        )
        let model = StreamSetupModel(
            settingsStore: settings,
            browser: FakeSetupBrowser(),
            probe: FakeSetupProbe(capabilities: receiver),
            capture: FakeSetupCamera(),
            sessionCoordinator: session,
            logger: CamBridgeLogger(),
            orientationProvider: FakeOrientationProvider(rotation: .ninety)
        )

        XCTAssertEqual(model.selectedResolutionID, SenderVideoCatalog.fullHd.id)
        XCTAssertEqual(model.selectedFPS, 30)
        XCTAssertEqual(model.bitrateText, "5")

        await model.refreshCameraAuthorization()
        model.setManualHost("192.168.1.10")
        await model.probeManualReceiver()
        model.selectFrameRate(60)
        XCTAssertEqual(model.bitrateText, "10")
        model.setBitrateText("1")

        XCTAssertTrue(model.canStart)
        await model.startStream()

        let configuration = await session.lastConfiguration()
        XCTAssertEqual(configuration?.resolution, SenderVideoCatalog.fullHd)
        XCTAssertEqual(configuration?.fps, 60)
        XCTAssertEqual(configuration?.bitrateBps, 1_000_000)
        XCTAssertEqual(configuration?.orientation, .ninety)
        XCTAssertEqual(settings.load().bitrateBps, 1_000_000)
        let startCount = await session.startCount()
        XCTAssertEqual(startCount, 1)
    }

    @MainActor
    func testResolutionOrFrameRateChangeReplacesManualBitrateWithSuggestion() {
        let model = StreamSetupModel(
            settingsStore: FakeSetupSettingsStore(),
            browser: FakeSetupBrowser(),
            probe: FakeSetupProbe.unavailable,
            capture: FakeSetupCamera(),
            sessionCoordinator: FakeSetupSession(),
            logger: CamBridgeLogger()
        )

        model.setBitrateText("1")
        model.selectResolution(SenderVideoCatalog.resolution2k.id)
        XCTAssertEqual(model.bitrateText, "9")
        model.selectFrameRate(60)
        XCTAssertEqual(model.bitrateText, "18")
        model.selectResolution(SenderVideoCatalog.fullHd.id)
        XCTAssertEqual(model.bitrateText, "10")
    }

    @MainActor
    func testInvalidBitrateTextIsNotCommitted() {
        let settings = FakeSetupSettingsStore()
        let model = StreamSetupModel(
            settingsStore: settings,
            browser: FakeSetupBrowser(),
            probe: FakeSetupProbe.unavailable,
            capture: FakeSetupCamera(),
            sessionCoordinator: FakeSetupSession(),
            logger: CamBridgeLogger()
        )

        model.setBitrateText("1")
        model.setBitrateText("1.5")

        XCTAssertNil(model.selectedBitrateBps)
        XCTAssertEqual(settings.load().bitrateBps, 1_000_000)
        XCTAssertFalse(model.canStart)
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

    func load() -> SenderPreferences { preferences }

    func save(_ preferences: SenderPreferences) {
        self.preferences = preferences
    }
}

private actor FakeSetupBrowser: ReceiverBrowsing {
    func events() async -> AsyncStream<BonjourReceiverBrowserEvent> {
        AsyncStream { continuation in continuation.finish() }
    }

    func stop() async {}
}

private struct FakeSetupProbe: ReceiverProbing {
    let result: Result<ReceiverCapabilities, StreamFailure>

    init(capabilities: ReceiverCapabilities) {
        result = .success(capabilities)
    }

    private init(result: Result<ReceiverCapabilities, StreamFailure>) {
        self.result = result
    }

    static let unavailable = FakeSetupProbe(result: .failure(.receiverUnavailable))

    func probe(target: ReceiverControlTarget) async -> Result<ReceiverCapabilities, StreamFailure> {
        result
    }
}

private actor FakeSetupCamera: CameraSetupServicing {
    func authorizationState() async -> CameraAuthorizationState { .authorized }
    func requestAuthorization() async -> CameraAuthorizationState { .authorized }

    func cameraState() async -> CameraState {
        var state = CameraState.initial
        state.authorization = .authorized
        return state
    }
}

private actor FakeSetupSession: StreamSessionStarting {
    private var starts = 0
    private var configuration: StreamConfiguration?

    func start(
        endpoint: ReceiverEndpoint,
        controlTarget: ReceiverControlTarget,
        receiver: ReceiverCapabilities,
        configuration: StreamConfiguration,
        cameraPosition: CameraPosition,
        mediaHosts: [String]
    ) async -> Result<Void, StreamFailure> {
        starts += 1
        self.configuration = configuration
        return .success(())
    }

    func stop() async -> Result<Void, Never> { .success(()) }
    func startCount() -> Int { starts }
    func lastConfiguration() -> StreamConfiguration? { configuration }
}

@MainActor
private struct FakeOrientationProvider: StreamOrientationProviding {
    let rotation: StreamRotation

    func currentRotation() -> StreamRotation { rotation }
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
        await withCheckedContinuation { continuation in
            waiter = continuation
        }
    }

    func endCount() -> Int { ends }
}
