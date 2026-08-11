import XCTest
import CamBridgeCore
@testable import CamBridge

final class CoordinatorLifecycleTests: XCTestCase {
    func testStartPublishesStreamingAndStopReturnsToIdleExactlyOnce() async throws {
        let capture = FakeCaptureService()
        let control = FakeSessionControl()
        let datagram = FakeDatagramSender()
        let coordinator = try StreamSessionCoordinator(
            capture: capture,
            controlFactory: FakeSessionControlFactory(connection: control),
            datagramFactory: FakeDatagramFactory(sender: datagram),
            firstGeneration: CamBridgeContract.Validation.minimumGeneration
        )
        let endpoint = try ReceiverEndpoint(host: "127.0.0.1")
        let receiver = try ReceiverCapabilities(
            receiverId: "test-receiver",
            displayName: "Test Receiver",
            maxLongEdge: CamBridgeContract.Geometry.maximumLongEdge,
            maxShortEdge: CamBridgeContract.Geometry.maximumShortEdge
        )
        let configuration = try StreamConfiguration(
            mode: VideoMode.mode1080p30,
            bitrateBps: VideoMode.mode1080p30.defaultBitrateBps,
            orientation: .zero
        )

        let startResult = await coordinator.start(
            endpoint: endpoint,
            controlTarget: .manual(endpoint),
            receiver: receiver,
            configuration: configuration,
            cameraDeviceID: "camera-test",
            stabilization: .off
        )

        guard case .success = startResult else {
            return XCTFail("expected fake receiver to accept the stream")
        }
        let streamingSnapshot = await coordinator.snapshotStream()
        guard case let .streaming(identity, activeConfiguration, mediaPort) = streamingSnapshot.state else {
            return XCTFail("expected a streaming snapshot")
        }
        XCTAssertEqual(identity.generation, CamBridgeContract.Validation.minimumGeneration)
        XCTAssertEqual(activeConfiguration, configuration)
        XCTAssertEqual(mediaPort, CamBridgeContract.Defaults.controlPort + CamBridgeContract.Defaults.mediaPortOffset)
        let startCount = await capture.startCount()
        let stabilization = await capture.stabilization()
        XCTAssertEqual(startCount, 1)
        XCTAssertEqual(stabilization, .off)

        _ = await coordinator.stop()
        _ = await coordinator.stop()

        let stoppedSnapshot = await coordinator.snapshotStream()
        let stopCount = await capture.stopCount()
        let closeCount = await datagram.closeCount()
        let controlClosed = await control.didClose()
        let diagnostics = await coordinator.diagnostics()
        XCTAssertEqual(stoppedSnapshot.state, .idle)
        XCTAssertEqual(stopCount, 1)
        XCTAssertEqual(closeCount, 1)
        XCTAssertTrue(controlClosed)
        XCTAssertNotNil(diagnostics)
        XCTAssertEqual(diagnostics?.sessionId, identity.sessionId)
        XCTAssertEqual(diagnostics?.generation, identity.generation)

        let secondStartResult = await coordinator.start(
            endpoint: endpoint,
            controlTarget: .manual(endpoint),
            receiver: receiver,
            configuration: configuration,
            cameraDeviceID: "camera-test",
            stabilization: .off
        )
        guard case .success = secondStartResult else {
            return XCTFail("expected a stopped coordinator to accept a new start")
        }
        _ = await coordinator.stop()

        let secondStartCount = await capture.startCount()
        let secondStopCount = await capture.stopCount()
        let secondDatagramCloseCount = await datagram.closeCount()
        let secondControlCloseCount = await control.closeCount()
        XCTAssertEqual(secondStartCount, 2)
        XCTAssertEqual(secondStopCount, 2)
        XCTAssertEqual(secondDatagramCloseCount, 2)
        XCTAssertEqual(secondControlCloseCount, 2)
    }

    func testStopInvalidatesStartWhileCapturePreparationIsSuspended() async throws {
        let capture = BlockingCaptureService()
        let control = FakeSessionControl()
        let datagram = FakeDatagramSender()
        let coordinator = try StreamSessionCoordinator(
            capture: capture,
            controlFactory: FakeSessionControlFactory(connection: control),
            datagramFactory: FakeDatagramFactory(sender: datagram),
            firstGeneration: CamBridgeContract.Validation.minimumGeneration
        )
        let endpoint = try ReceiverEndpoint(host: "127.0.0.1")
        let receiver = try ReceiverCapabilities(
            receiverId: "test-receiver",
            displayName: "Test Receiver",
            maxLongEdge: CamBridgeContract.Geometry.maximumLongEdge,
            maxShortEdge: CamBridgeContract.Geometry.maximumShortEdge
        )
        let configuration = try StreamConfiguration(
            mode: VideoMode.mode1080p30,
            bitrateBps: VideoMode.mode1080p30.defaultBitrateBps,
            orientation: .zero
        )

        let startTask = Task {
            await coordinator.start(
                endpoint: endpoint,
                controlTarget: .manual(endpoint),
                receiver: receiver,
                configuration: configuration,
                cameraDeviceID: "camera-test",
                stabilization: .off
            )
        }
        await capture.waitUntilPrepareEntered()

        _ = await coordinator.stop()
        await capture.releasePrepare()

        let startResult = await startTask.value
        guard case let .failure(failure) = startResult else {
            return XCTFail("a Stop during preparation must cancel the suspended Start")
        }
        XCTAssertEqual(failure, .cancelled)
        let snapshot = await coordinator.snapshotStream()
        XCTAssertEqual(snapshot.state, .idle)
        let stopCount = await capture.stopCount()
        let controlCloseCount = await control.closeCount()
        let datagramCloseCount = await datagram.closeCount()
        XCTAssertEqual(stopCount, 1)
        XCTAssertEqual(controlCloseCount, 1)
        XCTAssertEqual(datagramCloseCount, 0)
    }

    func testTransportFailureTerminatesStreamingAndCleansResources() async throws {
        let capture = FakeCaptureService()
        let control = FakeSessionControl()
        let datagram = FailingDatagramSender()
        let coordinator = try StreamSessionCoordinator(
            capture: capture,
            controlFactory: FakeSessionControlFactory(connection: control),
            datagramFactory: FailingDatagramFactory(sender: datagram),
            firstGeneration: CamBridgeContract.Validation.minimumGeneration
        )
        let endpoint = try ReceiverEndpoint(host: "127.0.0.1")
        let receiver = try ReceiverCapabilities(
            receiverId: "test-receiver",
            displayName: "Test Receiver",
            maxLongEdge: CamBridgeContract.Geometry.maximumLongEdge,
            maxShortEdge: CamBridgeContract.Geometry.maximumShortEdge
        )
        let configuration = try StreamConfiguration(
            mode: VideoMode.mode1080p30,
            bitrateBps: VideoMode.mode1080p30.defaultBitrateBps,
            orientation: .zero
        )
        let snapshots = await coordinator.snapshots()
        let failureTask = Task { () -> StreamSessionSnapshot? in
            for await snapshot in snapshots {
                if case .failed = snapshot.state { return snapshot }
            }
            return nil
        }

        let startResult = await coordinator.start(
            endpoint: endpoint,
            controlTarget: .manual(endpoint),
            receiver: receiver,
            configuration: configuration,
            cameraDeviceID: "camera-test",
            stabilization: .off
        )
        guard case .success = startResult else {
            return XCTFail("expected fake receiver to accept the stream")
        }
        await capture.emit(.success(EncodedAccessUnit(
            data: Data([0x00, 0x00, 0x00, 0x01, 0x65, 0x01]),
            presentationTimeMicroseconds: .zero,
            isKeyframe: true
        )))

        let failedSnapshot = await failureTask.value
        guard case let .failed(failure)? = failedSnapshot?.state else {
            return XCTFail("expected a terminal transport failure")
        }
        let expectedFailure = StreamFailure.transportFailed(
            String(describing: RTPDatagramSenderError.sendFailed("synthetic transport failure"))
        )
        let datagramCloseCount = await datagram.closeCount()
        let controlDidClose = await control.didClose()
        let captureStopCount = await capture.stopCount()
        let diagnostics = await coordinator.diagnostics()
        XCTAssertEqual(failure, expectedFailure)
        XCTAssertEqual(datagramCloseCount, 1)
        XCTAssertTrue(controlDidClose)
        XCTAssertEqual(captureStopCount, 1)
        XCTAssertEqual(diagnostics?.receiverHost, "[redacted]")
        XCTAssertFalse(diagnostics?.copyableText().contains("synthetic transport failure") == true)
    }

    func testAcceptedStartFailureSendsMatchingStopBeforeCleanup() async throws {
        let capture = FakeCaptureService()
        let control = FakeSessionControl()
        let datagram = FailingConnectDatagramSender()
        let coordinator = try StreamSessionCoordinator(
            capture: capture,
            controlFactory: FakeSessionControlFactory(connection: control),
            datagramFactory: FailingConnectDatagramFactory(sender: datagram),
            firstGeneration: CamBridgeContract.Validation.minimumGeneration
        )
        let endpoint = try ReceiverEndpoint(host: "127.0.0.1")
        let receiver = try ReceiverCapabilities(
            receiverId: "test-receiver",
            displayName: "Test Receiver",
            maxLongEdge: CamBridgeContract.Geometry.maximumLongEdge,
            maxShortEdge: CamBridgeContract.Geometry.maximumShortEdge
        )
        let configuration = try StreamConfiguration(
            mode: VideoMode.mode1080p30,
            bitrateBps: VideoMode.mode1080p30.defaultBitrateBps,
            orientation: .zero
        )

        let result = await coordinator.start(
            endpoint: endpoint,
            controlTarget: .manual(endpoint),
            receiver: receiver,
            configuration: configuration,
            cameraDeviceID: "camera-test",
            stabilization: .off
        )

        guard case let .failure(failure) = result else {
            return XCTFail("a media connection failure must fail the start")
        }
        guard case .transportFailed = failure else {
            return XCTFail("a media connection failure must be reported as transport failure")
        }
        let stopCount = await control.stopCount()
        let datagramCloseCount = await datagram.closeCount()
        let captureStopCount = await capture.stopCount()
        XCTAssertEqual(stopCount, 1)
        XCTAssertEqual(datagramCloseCount, 1)
        XCTAssertEqual(captureStopCount, 1)
    }

    func testReceiverErrorDuringHelloMapsToReceiverRejected() async throws {
        let capture = FakeCaptureService()
        let control = FakeSessionControl(response: .error(message: "unsupported exact mode"))
        let coordinator = try StreamSessionCoordinator(
            capture: capture,
            controlFactory: FakeSessionControlFactory(connection: control),
            datagramFactory: FakeDatagramFactory(sender: FakeDatagramSender()),
            firstGeneration: CamBridgeContract.Validation.minimumGeneration
        )
        let endpoint = try ReceiverEndpoint(host: "127.0.0.1")
        let receiver = try ReceiverCapabilities(
            receiverId: "test-receiver",
            displayName: "Test Receiver",
            maxLongEdge: CamBridgeContract.Geometry.maximumLongEdge,
            maxShortEdge: CamBridgeContract.Geometry.maximumShortEdge
        )
        let configuration = try StreamConfiguration(
            mode: VideoMode.mode1080p30,
            bitrateBps: VideoMode.mode1080p30.defaultBitrateBps,
            orientation: .zero
        )

        let result = await coordinator.start(
            endpoint: endpoint,
            controlTarget: .manual(endpoint),
            receiver: receiver,
            configuration: configuration,
            cameraDeviceID: "camera-test",
            stabilization: .off
        )

        XCTAssertEqual(result, .failure(.receiverRejected("unsupported exact mode")))
        let snapshot = await coordinator.snapshotStream()
        guard case let .failed(failure) = snapshot.state else {
            return XCTFail("receiver rejection must produce a failed stream state")
        }
        XCTAssertEqual(failure, .receiverRejected("unsupported exact mode"))
    }
}

private actor FakeCaptureService: StreamCaptureControlling {
    private var starts = 0
    private var stops = 0
    private var selectedStabilization: CameraStabilizationPreference = .auto
    private var callback: (@Sendable (Result<EncodedAccessUnit, VideoToolboxEncoderError>) -> Void)?

    func prepare(
        configuration: StreamConfiguration,
        deviceID: String,
        onAccessUnit: @escaping @Sendable (Result<EncodedAccessUnit, VideoToolboxEncoderError>) -> Void
    ) async throws {
        callback = onAccessUnit
    }

    func start() async throws {
        starts += 1
    }

    func setStabilization(_ preference: CameraStabilizationPreference) async throws {
        selectedStabilization = preference
    }

    func stop() async {
        stops += 1
    }

    func cameraState() async -> CameraState {
        .initial
    }

    func encoderMetrics() async -> VideoToolboxEncoderMetrics? {
        nil
    }

    func startCount() -> Int { starts }
    func stopCount() -> Int { stops }
    func stabilization() -> CameraStabilizationPreference { selectedStabilization }

    func emit(_ result: Result<EncodedAccessUnit, VideoToolboxEncoderError>) {
        callback?(result)
    }
}

private actor BlockingCaptureService: StreamCaptureControlling {
    private var prepareEntered = false
    private var prepareEnteredWaiter: CheckedContinuation<Void, Never>?
    private var prepareRelease: CheckedContinuation<Void, Never>?
    private var starts = 0
    private var selectedStabilization: CameraStabilizationPreference = .auto
    private var stops = 0

    func prepare(
        configuration: StreamConfiguration,
        deviceID: String,
        onAccessUnit: @escaping @Sendable (Result<EncodedAccessUnit, VideoToolboxEncoderError>) -> Void
    ) async throws {
        prepareEntered = true
        prepareEnteredWaiter?.resume()
        prepareEnteredWaiter = nil
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            prepareRelease = continuation
        }
    }

    func start() async throws {
        starts += 1
    }

    func setStabilization(_ preference: CameraStabilizationPreference) async throws {
        selectedStabilization = preference
    }

    func stop() async {
        stops += 1
    }

    func cameraState() async -> CameraState {
        .initial
    }

    func encoderMetrics() async -> VideoToolboxEncoderMetrics? {
        nil
    }

    func waitUntilPrepareEntered() async {
        if prepareEntered { return }
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            prepareEnteredWaiter = continuation
        }
    }

    func releasePrepare() {
        prepareRelease?.resume()
        prepareRelease = nil
    }

    func stopCount() -> Int { stops }
}

private actor FakeSessionControl: CamBridgeControlConnectionProtocol {
    private var response: ControlMessage?
    private var continuation: AsyncThrowingStream<ControlMessage, Error>.Continuation?
    private var connected = false
    private var closed = false
    private var closes = 0
    private var stops = 0

    init(response: ControlMessage? = nil) {
        self.response = response
    }

    func connect() async throws {
        connected = true
    }

    func send(_ message: ControlMessage) async throws {
        guard connected else { throw CamBridgeControlConnectionError.notConnected }
        switch message {
        case let .hello(sessionId, generation, profileId, _, _, _, _, _):
            if response == nil {
                response = .accepted(
                    sessionId: sessionId,
                    generation: generation,
                    profileId: profileId,
                    mediaPort: CamBridgeContract.Defaults.controlPort + CamBridgeContract.Defaults.mediaPortOffset,
                    maxLongEdge: CamBridgeContract.Geometry.maximumLongEdge,
                    maxShortEdge: CamBridgeContract.Geometry.maximumShortEdge
                )
            }
        case .stop:
            stops += 1
        default:
            break
        }
    }

    func receive() async throws -> ControlMessage? {
        response
    }

    func messages() async -> AsyncThrowingStream<ControlMessage, Error> {
        AsyncThrowingStream { continuation in
            Task { await self.store(continuation: continuation) }
        }
    }

    private func store(continuation: AsyncThrowingStream<ControlMessage, Error>.Continuation) {
        self.continuation = continuation
    }

    func close() async {
        closed = true
        closes += 1
        continuation?.finish()
        continuation = nil
    }

    func didClose() -> Bool { closed }
    func closeCount() -> Int { closes }
    func stopCount() -> Int { stops }
}

private struct FakeSessionControlFactory: CamBridgeControlConnectionFactory {
    let connection: FakeSessionControl

    func make(target: ReceiverControlTarget) -> any CamBridgeControlConnectionProtocol {
        connection
    }
}

private actor FakeDatagramSender: RTPDatagramSending {
    private var connected = false
    private var closes = 0
    private var packets = 0
    private var bytes = 0

    func connect() async throws {
        connected = true
    }

    func send(_ datagram: Data) async throws {
        guard connected else { throw RTPDatagramSenderError.notConnected }
        packets += 1
        bytes += datagram.count
    }

    func close() async {
        connected = false
        closes += 1
    }

    func metrics() async -> RTPDatagramMetrics {
        RTPDatagramMetrics(packetsSent: packets, bytesSent: bytes, sendFailures: .zero, maximumSendDurationNanoseconds: .zero)
    }
    func closeCount() -> Int { closes }
}

private struct FakeDatagramFactory: RTPDatagramSenderFactory {
    let sender: FakeDatagramSender

    func make(host: String, port: Int) throws -> any RTPDatagramSending {
        sender
    }
}

private actor FailingDatagramSender: RTPDatagramSending {
    private var connected = false
    private var closes = 0

    func connect() async throws {
        connected = true
    }

    func send(_ datagram: Data) async throws {
        guard connected else { throw RTPDatagramSenderError.notConnected }
        throw RTPDatagramSenderError.sendFailed("synthetic transport failure")
    }

    func close() async {
        connected = false
        closes += 1
    }

    func metrics() async -> RTPDatagramMetrics {
        RTPDatagramMetrics(packetsSent: .zero, bytesSent: .zero, sendFailures: 1, maximumSendDurationNanoseconds: .zero)
    }

    func closeCount() -> Int { closes }
}

private struct FailingDatagramFactory: RTPDatagramSenderFactory {
    let sender: FailingDatagramSender

    func make(host: String, port: Int) throws -> any RTPDatagramSending {
        sender
    }
}

private actor FailingConnectDatagramSender: RTPDatagramSending {
    private var closes = 0

    func connect() async throws {
        throw RTPDatagramSenderError.connectionFailed("synthetic connection failure")
    }

    func send(_ datagram: Data) async throws {
        throw RTPDatagramSenderError.notConnected
    }

    func close() async {
        closes += 1
    }

    func metrics() async -> RTPDatagramMetrics {
        RTPDatagramMetrics(packetsSent: .zero, bytesSent: .zero, sendFailures: .zero, maximumSendDurationNanoseconds: .zero)
    }

    func closeCount() -> Int { closes }
}

private struct FailingConnectDatagramFactory: RTPDatagramSenderFactory {
    let sender: FailingConnectDatagramSender

    func make(host: String, port: Int) throws -> any RTPDatagramSending {
        sender
    }
}
