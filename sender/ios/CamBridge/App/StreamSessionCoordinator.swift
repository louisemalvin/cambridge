import Foundation
import UIKit
import CamBridgeCore

public protocol StreamCaptureControlling: Sendable {
    func prepare(
        configuration: StreamConfiguration,
        position: CameraPosition,
        onAccessUnit: @escaping @Sendable (Result<EncodedAccessUnit, VideoToolboxEncoderError>) -> Void
    ) async throws
    func start() async throws
    func stop() async
    func cameraState() async -> CameraState
    func encoderMetrics() async -> VideoToolboxEncoderMetrics?
}

extension CaptureService: StreamCaptureControlling {}

private enum StreamStartOperationError: Error {
    case invalidated
}

private enum StreamStartStage: String, Sendable {
    case preparingCapture = "preparing_capture_and_encoder"
    case connectingControl = "connecting_control"
    case sendingHello = "sending_hello"
    case awaitingAcceptance = "awaiting_receiver_acceptance"
    case connectingMedia = "connecting_media_transport"
    case startingCapture = "starting_capture"
    case streaming
}

public protocol RTPDatagramSenderFactory: Sendable {
    func make(host: String, port: Int) throws -> any RTPDatagramSending
}

public protocol StreamSessionStarting: Sendable {
    func start(
        endpoint: ReceiverEndpoint,
        controlTarget: ReceiverControlTarget,
        receiver: ReceiverCapabilities,
        configuration: StreamConfiguration,
        cameraPosition: CameraPosition,
        mediaHosts: [String]
    ) async -> Result<Void, StreamFailure>
    func stop() async -> Result<Void, Never>
}

public struct LiveRTPDatagramSenderFactory: RTPDatagramSenderFactory {
    public init() {}

    public func make(host: String, port: Int) throws -> any RTPDatagramSending {
        try RTPDatagramSender(host: host, port: port)
    }
}

public struct StreamSessionSnapshot: Equatable, Sendable {
    public let state: StreamState
    public let runId: String?
    public let identity: SessionIdentity?

    public init(state: StreamState, runId: String?, identity: SessionIdentity?) {
        self.state = state
        self.runId = runId
        self.identity = identity
    }
}

public actor StreamSessionCoordinator {
    private let capture: any StreamCaptureControlling
    private let controlFactory: any CamBridgeControlConnectionFactory
    private let datagramFactory: any RTPDatagramSenderFactory
    private let logger: CamBridgeLogger
    private var generationAllocator: SessionIdentityAllocator
    private var stateMachine = StreamStateMachine()
    private var runId: String?
    private var controlConnection: (any CamBridgeControlConnectionProtocol)?
    private var datagramSender: (any RTPDatagramSending)?
    private var encodedQueue: EncodedAccessUnitQueue?
    private var mediaTask: Task<Void, Never>?
    private var controlTask: Task<Void, Never>?
    private var cameraTask: Task<Void, Never>?
    private var activeEndpoint: ReceiverEndpoint?
    private var activeConfiguration: StreamConfiguration?
    private var activeIdentity: SessionIdentity?
    private var receiverAccepted = false
    private var lastDiagnostics: DiagnosticsReport?
    private var stateTransitions: [String] = []
    private var activeStartOperationID: UUID?
    private var invalidatedStartFailure: StreamFailure?
    private var activeStartStage: StreamStartStage?
    private var cleaning = false
    private var stateContinuation: AsyncStream<StreamSessionSnapshot>.Continuation?

    public init(
        capture: any StreamCaptureControlling,
        controlFactory: any CamBridgeControlConnectionFactory = LiveCamBridgeControlConnectionFactory(),
        datagramFactory: any RTPDatagramSenderFactory = LiveRTPDatagramSenderFactory(),
        logger: CamBridgeLogger = CamBridgeLogger()
    ) {
        self.capture = capture
        self.controlFactory = controlFactory
        self.datagramFactory = datagramFactory
        self.logger = logger
        generationAllocator = SessionIdentityAllocator()
    }

    public init(
        capture: any StreamCaptureControlling,
        controlFactory: any CamBridgeControlConnectionFactory,
        datagramFactory: any RTPDatagramSenderFactory,
        firstGeneration: UInt64,
        logger: CamBridgeLogger = CamBridgeLogger()
    ) throws {
        self.capture = capture
        self.controlFactory = controlFactory
        self.datagramFactory = datagramFactory
        self.logger = logger
        generationAllocator = try SessionIdentityAllocator(firstGeneration: firstGeneration)
    }

    public func snapshotStream() -> StreamSessionSnapshot {
        StreamSessionSnapshot(state: stateMachine.state, runId: runId, identity: currentIdentity)
    }

    public func diagnostics() -> DiagnosticsReport? {
        lastDiagnostics
    }

    public func snapshots() -> AsyncStream<StreamSessionSnapshot> {
        AsyncStream(bufferingPolicy: .bufferingNewest(Self.snapshotStreamBufferCapacity)) { continuation in
            stateContinuation = continuation
            continuation.yield(snapshotStream())
            continuation.onTermination = { _ in
                Task { await self.clearStateContinuation() }
            }
        }
    }

    public func start(
        endpoint: ReceiverEndpoint,
        controlTarget: ReceiverControlTarget,
        receiver: ReceiverCapabilities,
        configuration: StreamConfiguration,
        cameraPosition: CameraPosition = .back,
        mediaHosts: [String] = []
    ) async -> Result<Void, StreamFailure> {
        guard case .idle = stateMachine.state else {
            return .failure(.invalidConfiguration("A stream is already active; stop it before starting again."))
        }
        let operationID = UUID()
        var didBeginStart = false
        do {
            try configuration.validate(receiver: receiver)
            let identity = try generationAllocator.allocate(sessionId: "session-\(UUID().uuidString)")
            try stateMachine.beginStart(identity: identity, configuration: configuration)
            didBeginStart = true
            runId = "run-\(UUID().uuidString)"
            activeIdentity = identity
            activeEndpoint = endpoint
            activeConfiguration = configuration
            receiverAccepted = false
            stateTransitions.removeAll(keepingCapacity: true)
            activeStartOperationID = operationID
            invalidatedStartFailure = nil
            activeStartStage = nil
            publishSnapshot()
            logger.event("stream_start_requested", category: .session)

            let queue = try EncodedAccessUnitQueue()
            encodedQueue = queue
            let control = controlFactory.make(target: controlTarget)
            controlConnection = control
            enterStartStage(.preparingCapture)
            try await capture.prepare(configuration: configuration, position: cameraPosition) { [weak self, weak queue] result in
                guard let queue else { return }
                switch result {
                case let .success(accessUnit):
                    queue.offer(accessUnit)
                case let .failure(error):
                    Task { await self?.handleEncoderFailure(error) }
                }
            }
            try ensureStartOperationIsActive(operationID)
            enterStartStage(.connectingControl)
            try await control.connect()
            try ensureStartOperationIsActive(operationID)
            enterStartStage(.sendingHello)
            try await sendControlMessageWithTimeout(control, message: .hello(
                sessionId: identity.sessionId,
                generation: identity.generation,
                profileId: SenderVideoCatalog.profileID,
                codedWidth: configuration.geometry.codedWidth,
                codedHeight: configuration.geometry.codedHeight,
                rotation: configuration.orientation,
                fps: configuration.fps,
                bitrateBps: configuration.bitrateBps
            ))
            try ensureStartOperationIsActive(operationID)
            enterStartStage(.awaitingAcceptance)
            guard let accepted = try await receiveWithTimeout(control) else {
                throw StreamFailure.receiverUnavailable
            }
            try ensureStartOperationIsActive(operationID)
            switch accepted {
            case let .error(message):
                throw StreamFailure.receiverRejected(message)
            case .accepted:
                break
            default:
                throw StreamFailure.incompatibleProtocol
            }
            try stateMachine.validateAccepted(accepted)
            receiverAccepted = true
            guard case let .accepted(_, _, _, mediaPort, _, _) = accepted else {
                throw StreamFailure.incompatibleProtocol
            }
            enterStartStage(.connectingMedia)
            let candidateHosts = mediaHosts.isEmpty ? [endpoint.host] : mediaHosts
            var sender: (any RTPDatagramSending)?
            var lastTransportError: Error?
            for host in candidateHosts {
                try ensureStartOperationIsActive(operationID)
                do {
                    let candidateSender = try datagramFactory.make(host: host, port: mediaPort)
                    do {
                        try await candidateSender.connect()
                        try ensureStartOperationIsActive(operationID)
                        sender = candidateSender
                        break
                    } catch {
                        await candidateSender.close()
                        try ensureStartOperationIsActive(operationID)
                        lastTransportError = error
                    }
                } catch {
                    lastTransportError = error
                }
            }
            guard let sender else {
                throw lastTransportError ?? StreamFailure.transportFailed("no discovered media address connected")
            }
            datagramSender = sender
            enterStartStage(.startingCapture)
            try await capture.start()
            try ensureStartOperationIsActive(operationID)
            try stateMachine.accept(accepted)
            startMediaTask(queue: queue, sender: sender)
            startControlTask(control: control)
            startCameraStateTask()
            enterStartStage(.streaming)
            publishSnapshot()
            activeStartOperationID = nil
            return .success(())
        } catch let failure as StreamFailure {
            guard didBeginStart else { return .failure(failure) }
            guard activeStartOperationID == operationID else {
                return .failure(invalidatedStartFailure ?? .cancelled)
            }
            await failStart(failure)
            return .failure(failure)
        } catch {
            let failure = mapFailure(error)
            guard didBeginStart else { return .failure(failure) }
            guard activeStartOperationID == operationID else {
                return .failure(invalidatedStartFailure ?? .cancelled)
            }
            await failStart(failure)
            return .failure(failure)
        }
    }

    public func stop() async -> Result<Void, Never> {
        guard !cleaning else { return .success(()) }
        guard case .idle = stateMachine.state else {
            activeStartOperationID = nil
            invalidatedStartFailure = nil
            _ = stateMachine.beginStop()
            publishSnapshot()
            await cleanupResources(sendStop: true)
            stateMachine.finishStop()
            publishSnapshot()
            return .success(())
        }
        return .success(())
    }

    public func endForBackground() async {
        guard !cleaning else { return }
        switch stateMachine.state {
        case .connecting, .streaming, .stopping:
            activeStartOperationID = nil
            invalidatedStartFailure = .backgrounded
            stateMachine.fail(.backgrounded)
            recordStateTransition()
            await cleanupResources(sendStop: true)
            publishSnapshot()
        case .idle, .failed:
            break
        }
    }

    private func startMediaTask(queue: EncodedAccessUnitQueue, sender: any RTPDatagramSending) {
        mediaTask = Task { [weak self] in
            guard let self else { return }
            do {
                var packetizer = try RTPH264Packetizer()
                while !Task.isCancelled, let accessUnit = await queue.next() {
                    let datagrams = try packetizer.packetize(
                        accessUnit.data,
                        presentationTimeMicroseconds: accessUnit.presentationTimeMicroseconds
                    )
                    for datagram in datagrams {
                        try await sendRTPDatagramWithTimeout(sender, datagram: datagram)
                    }
                }
            } catch {
                guard !Task.isCancelled else { return }
                await self.handleTransportFailure(error)
            }
        }
    }

    private func startControlTask(control: any CamBridgeControlConnectionProtocol) {
        controlTask = Task { [weak self] in
            guard let self else { return }
            do {
                for try await message in await control.messages() {
                    switch message {
                    case let .error(message):
                        await self.handleTerminalFailure(.receiverRejected(message))
                    default:
                        await self.handleTerminalFailure(.incompatibleProtocol)
                }
                return
            }
                guard !Task.isCancelled else { return }
                await self.handleTerminalFailure(.controlConnectionFailed("receiver closed the control lease"))
            } catch {
                guard !Task.isCancelled else { return }
                await self.handleTerminalFailure(.controlConnectionFailed(String(describing: error)))
            }
        }
    }

    private func startCameraStateTask() {
        cameraTask = Task { [weak self, capture] in
            guard let self else { return }
            while !Task.isCancelled {
                let cameraState = await capture.cameraState()
                if let interruption = cameraState.interruption {
                    await self.handleTerminalFailure(.interrupted(String(describing: interruption)))
                    return
                }
                if let runtimeError = cameraState.runtimeError {
                    await self.handleTerminalFailure(.interrupted(runtimeError))
                    return
                }
                if let pressure = cameraState.systemPressureLevel, pressure.requiresTerminalCleanup {
                    await self.handleTerminalFailure(.interrupted("system pressure: \(pressure.rawValue)"))
                    return
                }
                try? await Task.sleep(nanoseconds: StreamSessionTimeouts.cameraStatePollNanoseconds)
            }
        }
    }

    private func receiveWithTimeout(_ connection: any CamBridgeControlConnectionProtocol) async throws -> ControlMessage? {
    try await withThrowingTaskGroup(of: ControlMessage?.self) { group in
        group.addTask { try await connection.receive() }
        group.addTask {
            try await Task.sleep(nanoseconds: StreamSessionTimeouts.requestTimeoutNanoseconds)
            throw CamBridgeControlConnectionError.receiveFailed("hello acceptance timed out")
        }
        do {
            guard let result = try await group.next() else { return nil }
            group.cancelAll()
            return result
        } catch {
            group.cancelAll()
            await connection.close()
            throw error
        }
    }
}

    private func handleEncoderFailure(_ error: VideoToolboxEncoderError) async {
        await handleTerminalFailure(.encoderUnavailable(String(describing: error)))
    }

    private func handleTransportFailure(_ error: Error) async {
        await handleTerminalFailure(.transportFailed(String(describing: error)))
    }

    private func handleTerminalFailure(_ failure: StreamFailure) async {
        guard !cleaning else { return }
        switch stateMachine.state {
        case .connecting, .streaming:
            break
        case .idle, .stopping, .failed:
            return
        }
        activeStartOperationID = nil
        invalidatedStartFailure = failure
        stateMachine.fail(failure)
        recordStateTransition()
        await cleanupResources(sendStop: true)
        publishSnapshot()
    }

    private func failStart(_ failure: StreamFailure) async {
        guard activeStartOperationID != nil else { return }
        logger.error(
            "stream start failed at \(activeStartStage?.rawValue ?? "before_resource_setup"): \(failure.recoverySummary)",
            category: .session
        )
        activeStartOperationID = nil
        stateMachine.fail(failure)
        recordStateTransition()
        await cleanupResources(sendStop: receiverAccepted)
        publishSnapshot()
    }

    private func cleanupResources(sendStop: Bool) async {
        guard !cleaning else { return }
        cleaning = true
        activeStartOperationID = nil
        defer {
            cleaning = false
        }
        let identity = activeIdentity
        let terminalFailure: StreamFailure?
        if case let .failed(failure) = stateMachine.state {
            terminalFailure = failure
        } else {
            terminalFailure = nil
        }
        let cameraState = await capture.cameraState()
        let encoderMetrics = await capture.encoderMetrics()
        let queueTelemetry: EncodedAccessUnitQueueTelemetry
        if let encodedQueue {
            queueTelemetry = await encodedQueue.telemetry()
        } else {
            queueTelemetry = EncodedAccessUnitQueueTelemetry(occupancy: .zero, maximumOccupancy: .zero, drops: .zero)
        }
        let rtpMetrics: RTPDatagramMetrics
        if let datagramSender {
            rtpMetrics = await datagramSender.metrics()
        } else {
            rtpMetrics = RTPDatagramMetrics(packetsSent: .zero, bytesSent: .zero, sendFailures: .zero, maximumSendDurationNanoseconds: .zero)
        }
        if let runId {
            let appVersion = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "unknown"
            let buildVersion = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "unknown"
            let receiver = activeEndpoint
            let configuration = activeConfiguration
            let requestedStabilizationValue = CameraStabilizationPreference.auto.rawValue
            let stateTransitionsSnapshot = stateTransitions
            let deviceModel = await MainActor.run { UIDevice.current.localizedModel }
            lastDiagnostics = DiagnosticsReport(
                runId: runId,
                appVersion: appVersion,
                buildVersion: buildVersion,
                deviceModel: deviceModel,
                identity: identity,
                receiver: receiver,
                cameraState: cameraState,
                configuration: configuration,
                requestedStabilization: requestedStabilizationValue,
                activeStabilization: cameraState.activeStabilization.rawValue,
                startStage: activeStartStage?.rawValue,
                encoderIdentity: encoderMetrics?.encoderIdentity,
                encoderIdentityUnavailableReason: encoderMetrics?.encoderIdentityUnavailableReason,
                encoderUsesHardwareAccelerated: encoderMetrics?.encoderUsesHardwareAccelerated,
                encoderHardwareAvailabilityReason: encoderMetrics?.encoderHardwareAvailabilityReason,
                encoderAdvisoryPropertyFailures: encoderMetrics?.advisoryPropertyFailures ?? [],
                encodedAccessUnits: encoderMetrics?.encodedAccessUnits ?? .zero,
                encodedKeyframes: encoderMetrics?.encodedKeyframes ?? .zero,
                encodedBytes: encoderMetrics?.encodedBytes ?? .zero,
                queueOccupancy: queueTelemetry.occupancy,
                queueMaximumOccupancy: queueTelemetry.maximumOccupancy,
                queueDrops: queueTelemetry.drops,
                rtpPacketsSent: rtpMetrics.packetsSent,
                rtpBytesSent: rtpMetrics.bytesSent,
                udpFailures: rtpMetrics.sendFailures,
                maximumSendDurationNanoseconds: rtpMetrics.maximumSendDurationNanoseconds,
                encoderMetricsFirstPresentationTime: encoderMetrics?.firstPresentationTimeMicroseconds,
                encoderMetricsLastPresentationTime: encoderMetrics?.lastPresentationTimeMicroseconds,
                terminalFailure: terminalFailure,
                stateTransitions: stateTransitionsSnapshot
            )
        }
        await capture.stop()
        if let encodedQueue { await encodedQueue.finish() }
        mediaTask?.cancel()
        cameraTask?.cancel()
        if sendStop, let identity, let controlConnection {
            try? await sendControlMessageWithTimeout(
                controlConnection,
                message: .stop(sessionId: identity.sessionId, generation: identity.generation)
            )
        }
        controlTask?.cancel()
        if let datagramSender { await datagramSender.close() }
        if let controlConnection { await controlConnection.close() }
        mediaTask = nil
        controlTask = nil
        cameraTask = nil
        datagramSender = nil
        controlConnection = nil
        encodedQueue = nil
        activeEndpoint = nil
        activeConfiguration = nil
        activeIdentity = nil
        activeStartStage = nil
        receiverAccepted = false
        runId = nil
    }

    private func mapFailure(_ error: Error) -> StreamFailure {
        if error is CancellationError { return .cancelled }
        if error is StreamStartOperationError { return .cancelled }
        if let failure = error as? StreamFailure { return failure }
        if let captureError = error as? CaptureServiceError {
            if captureError == .permissionDenied { return .permissionDenied }
            return .cameraUnavailable(String(describing: captureError))
        }
        if let encoderError = error as? VideoToolboxEncoderError {
            return .encoderUnavailable(String(describing: encoderError))
        }
        if error is CamBridgeControlConnectionError {
            return .controlConnectionFailed(String(describing: error))
        }
        if error is RTPDatagramSenderError {
            return .transportFailed(String(describing: error))
        }
        return .unexpected(String(describing: error))
    }

    private func enterStartStage(_ stage: StreamStartStage) {
        activeStartStage = stage
        logger.info("stream start stage: \(stage.rawValue)", category: .session)
    }

    private func ensureStartOperationIsActive(_ operationID: UUID) throws {
        guard !Task.isCancelled,
              activeStartOperationID == operationID,
              case .connecting = stateMachine.state else {
            throw StreamStartOperationError.invalidated
        }
    }

    private var currentIdentity: SessionIdentity? {
        switch stateMachine.state {
        case let .connecting(identity, _), let .streaming(identity, _, _), let .stopping(identity):
            identity
        case .idle, .failed:
            activeIdentity
        }
    }

    private func publishSnapshot() {
        recordStateTransition()
        stateContinuation?.yield(snapshotStream())
    }

    private func recordStateTransition() {
        let snapshot = snapshotStream()
        if stateTransitions.last != snapshot.state.diagnosticLabel {
            stateTransitions.append(snapshot.state.diagnosticLabel)
        }
    }

    private func clearStateContinuation() {
        stateContinuation = nil
    }

    // The UI only needs the current lifecycle state; a short bounded history
    // protects a temporarily stalled observer without retaining a session's
    // entire transition history.
    private static let snapshotStreamBufferCapacity = 8
}

extension StreamSessionCoordinator: StreamSessionStarting {}

private enum StreamSessionTimeouts {
    static let nanosecondsPerMillisecond: UInt64 = 1_000_000
    static let requestTimeoutNanoseconds = UInt64(CamBridgeContract.Control.requestTimeoutMilliseconds) * nanosecondsPerMillisecond
    static let cameraStatePollNanoseconds = UInt64(CamBridgeContract.Media.maximumLiveFrameAgeMilliseconds) * nanosecondsPerMillisecond
}

private func sendControlMessageWithTimeout(
    _ connection: any CamBridgeControlConnectionProtocol,
    message: ControlMessage
) async throws {
    try await withThrowingTaskGroup(of: Void.self) { group in
        group.addTask {
            try await connection.send(message)
        }
        group.addTask {
            try await Task.sleep(nanoseconds: StreamSessionTimeouts.requestTimeoutNanoseconds)
            throw CamBridgeControlConnectionError.sendFailed("control send timed out")
        }
        do {
            guard let result = try await group.next() else {
                throw CamBridgeControlConnectionError.sendFailed("control send produced no result")
            }
            group.cancelAll()
            return result
        } catch {
            group.cancelAll()
            await connection.close()
            throw error
        }
    }
}

private func sendRTPDatagramWithTimeout(
    _ sender: any RTPDatagramSending,
    datagram: Data
) async throws {
    try await withThrowingTaskGroup(of: Void.self) { group in
        group.addTask {
            try await sender.send(datagram)
        }
        group.addTask {
            try await Task.sleep(nanoseconds: StreamSessionTimeouts.requestTimeoutNanoseconds)
            throw RTPDatagramSenderError.sendFailed("media send timed out")
        }
        do {
            guard let result = try await group.next() else {
                throw RTPDatagramSenderError.sendFailed("media send produced no result")
            }
            group.cancelAll()
            return result
        } catch {
            group.cancelAll()
            await sender.close()
            throw error
        }
    }
}
