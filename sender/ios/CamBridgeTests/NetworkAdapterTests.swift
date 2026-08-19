import XCTest
import UIKit
@preconcurrency import Network
import CamBridgeCore
@testable import CamBridge

final class NetworkAdapterTests: XCTestCase {
    func testLiveControlConnectionExchangesFramedMessagesAndHandlesRemoteEOF() async throws {
        let listener = try NWListener(using: .tcp, on: .any)
        let listenerQueue = DispatchQueue(label: "dev.cambridge.sender.tests.control-listener")
        let (incomingConnections, incomingContinuation) = AsyncStream.makeStream(
            of: SendableLoopbackConnection.self
        )
        listener.newConnectionHandler = { connection in
            incomingContinuation.yield(SendableLoopbackConnection(connection))
        }
        defer {
            incomingContinuation.finish()
            listener.cancel()
        }
        let listenerPort = try await start(listener: listener, queue: listenerQueue)
        let expectedHello = ControlMessage.hello(
            sessionId: "loopback-session",
            generation: UInt64(CamBridgeContract.Validation.minimumGeneration),
            profileId: SenderVideoCatalog.profileID,
            codedWidth: SenderVideoCatalog.fullHd.codedWidth,
            codedHeight: SenderVideoCatalog.fullHd.codedHeight,
            rotation: .ninety,
            fps: 60,
            bitrateBps: SenderVideoCatalog.minimumBitrateMbps * SenderVideoCatalog.bitrateUnitBps
        )
        let expectedAccepted = ControlMessage.accepted(
            sessionId: "loopback-session",
            generation: UInt64(CamBridgeContract.Validation.minimumGeneration),
            profileId: SenderVideoCatalog.profileID,
            mediaPort: CamBridgeContract.Defaults.controlPort + CamBridgeContract.Defaults.mediaPortOffset,
            maxLongEdge: CamBridgeContract.Geometry.maximumLongEdge,
            maxShortEdge: CamBridgeContract.Geometry.maximumShortEdge
        )
        let serverTask = Task {
            let serverConnection = try await firstConnection(from: incomingConnections)
            defer { serverConnection.cancel() }
            try await start(connection: serverConnection, queue: listenerQueue)
            let hello = try await receiveControlMessage(from: serverConnection)
            let responsePayload = try ControlMessageCodec.encode(expectedAccepted)
            let responseFrame = try ControlFrameEncoder.frame(responsePayload)
            let splitIndex = responseFrame.index(
                responseFrame.startIndex,
                offsetBy: responseFrame.count / Self.responseFragmentDivisor
            )
            try await send(Data(responseFrame[..<splitIndex]), on: serverConnection)
            try await Task.sleep(nanoseconds: Self.responseFragmentDelayNanoseconds)
            try await send(Data(responseFrame[splitIndex...]), on: serverConnection)
            try await finishWriting(on: serverConnection)
            return hello
        }
        defer { serverTask.cancel() }

        let endpoint = try ReceiverEndpoint(
            host: "127.0.0.1",
            controlPort: Int(listenerPort.rawValue)
        )
        let control = CamBridgeControlConnection(target: .manual(endpoint))
        try await control.connect()
        try await control.send(expectedHello)
        let accepted = try await control.receive()
        let eof = try await control.receive()
        let receivedHello = try await serverTask.value
        await control.close()

        XCTAssertEqual(receivedHello, expectedHello)
        XCTAssertEqual(accepted, expectedAccepted)
        XCTAssertNil(eof)
    }

    func testProbeValidatesCapabilitiesAndClosesConnection() async throws {
        let endpoint = try ReceiverEndpoint(host: "127.0.0.1")
        let connection = FakeControlConnection()
        let factory = FakeControlConnectionFactory(connection: connection)
        let probe = CamBridgeReceiverProbe(factory: factory)

        let result = await probe.probe(target: ReceiverControlTarget.manual(endpoint))

        guard case let .success(capabilities) = result else {
            return XCTFail("expected a valid capabilities response")
        }
        XCTAssertEqual(capabilities.receiverId, "test-receiver")
        let connectCount = await connection.connectCount()
        let probeCount = await connection.probeCount()
        let didClose = await connection.didClose()
        XCTAssertEqual(connectCount, 1)
        XCTAssertEqual(probeCount, 1)
        XCTAssertTrue(didClose)
    }

    func testProbeRejectsMismatchedRequestIDAndStillClosesConnection() async throws {
        let endpoint = try ReceiverEndpoint(host: "127.0.0.1")
        let connection = FakeControlConnection(response: .capabilities(
            requestId: "wrong-request",
            receiverId: "test-receiver",
            displayName: "Test Receiver",
            maxLongEdge: CamBridgeContract.Geometry.maximumLongEdge,
            maxShortEdge: CamBridgeContract.Geometry.maximumShortEdge
        ), respondToProbe: false)
        let probe = CamBridgeReceiverProbe(factory: FakeControlConnectionFactory(connection: connection))

        let result = await probe.probe(target: ReceiverControlTarget.manual(endpoint))

        let expectedFailure: Result<ReceiverCapabilities, StreamFailure> = .failure(.incompatibleProtocol)
        XCTAssertEqual(result, expectedFailure)
        let didClose = await connection.didClose()
        XCTAssertTrue(didClose)
    }

    func testProbeRejectsUnexpectedControlMessageAndStillClosesConnection() async throws {
        let endpoint = try ReceiverEndpoint(host: "127.0.0.1")
        let connection = FakeControlConnection(response: .hello(
            sessionId: "unexpected-session",
            generation: UInt64(CamBridgeContract.Validation.minimumGeneration),
            profileId: SenderVideoCatalog.profileID,
            codedWidth: SenderVideoCatalog.fullHd.codedWidth,
            codedHeight: SenderVideoCatalog.fullHd.codedHeight,
            rotation: .zero,
            fps: SenderVideoCatalog.defaultFrameRate,
            bitrateBps: 5_000_000
        ), respondToProbe: false)
        let probe = CamBridgeReceiverProbe(factory: FakeControlConnectionFactory(connection: connection))

        let result = await probe.probe(target: ReceiverControlTarget.manual(endpoint))

        let expectedFailure: Result<ReceiverCapabilities, StreamFailure> = .failure(.incompatibleProtocol)
        XCTAssertEqual(result, expectedFailure)
        let didClose = await connection.didClose()
        XCTAssertTrue(didClose)
    }

    func testBonjourMetadataValidatesUnicastCandidates() throws {
        let keys = CamBridgeContract.Discovery.txtKeys
        let metadata = try BonjourReceiverMetadata(dictionary: [
            keys[0]: "test-receiver",
            keys[1]: "Test Receiver",
            keys[2]: String(CamBridgeContract.protocolVersion),
            keys[3]: CamBridgeContract.Codec.h264,
            keys[4]: String(CamBridgeContract.Discovery.version),
            "address0": "192.168.1.10",
            "address1": "10.0.0.12",
        ])

        XCTAssertEqual(metadata.ipv4Addresses, ["192.168.1.10", "10.0.0.12"])
        XCTAssertThrowsError(try BonjourReceiverMetadata(dictionary: [
            keys[0]: "test-receiver",
            keys[1]: "Test Receiver",
            keys[2]: String(CamBridgeContract.protocolVersion),
            keys[3]: CamBridgeContract.Codec.h264,
            keys[4]: String(CamBridgeContract.Discovery.version),
            "address0": "127.0.0.1",
        ]))
        XCTAssertThrowsError(try BonjourReceiverMetadata(dictionary: [
            keys[0]: "test-receiver",
            keys[1]: "Test Receiver",
            keys[2]: String(CamBridgeContract.protocolVersion),
            keys[3]: CamBridgeContract.Codec.h264,
            keys[4]: String(CamBridgeContract.Discovery.version),
            "address16": "192.168.1.10",
        ]))
        XCTAssertThrowsError(try BonjourReceiverMetadata(dictionary: [
            keys[0]: "test-receiver",
            keys[1]: "Test Receiver",
            keys[2]: String(CamBridgeContract.protocolVersion),
            keys[3]: CamBridgeContract.Codec.h264,
            keys[4]: String(CamBridgeContract.Discovery.version),
            "address01": "192.168.1.10",
        ]))
    }

    func testOrientationResolverKeepsWireRotationSeparateFromPreviewOrientation() {
        let resolver = SessionOrientationResolver()

        let rearAngles = [
            resolver.resolve(rotation: .zero, cameraPosition: .back).previewRotationAngle,
            resolver.resolve(rotation: .ninety, cameraPosition: .back).previewRotationAngle,
            resolver.resolve(rotation: .oneEighty, cameraPosition: .back).previewRotationAngle,
            resolver.resolve(rotation: .twoSeventy, cameraPosition: .back).previewRotationAngle,
        ]
        let frontAngles = [
            resolver.resolve(rotation: .zero, cameraPosition: .front).previewRotationAngle,
            resolver.resolve(rotation: .ninety, cameraPosition: .front).previewRotationAngle,
            resolver.resolve(rotation: .oneEighty, cameraPosition: .front).previewRotationAngle,
            resolver.resolve(rotation: .twoSeventy, cameraPosition: .front).previewRotationAngle,
        ]

        XCTAssertEqual(rearAngles, [0, 90, 180, 270])
        XCTAssertEqual(frontAngles, [180, 90, 0, 270])
    }

    func testInterfaceOrientationMapsToFrozenWireRotation() {
        let resolver = SessionOrientationResolver()

        XCTAssertEqual(resolver.rotation(interfaceOrientation: .landscapeRight), .zero)
        XCTAssertEqual(resolver.rotation(interfaceOrientation: .portrait), .ninety)
        XCTAssertEqual(resolver.rotation(interfaceOrientation: .landscapeLeft), .oneEighty)
        XCTAssertEqual(resolver.rotation(interfaceOrientation: .portraitUpsideDown), .twoSeventy)
        XCTAssertEqual(resolver.rotation(interfaceOrientation: .unknown), .zero)
    }

    private static let responseFragmentDivisor = 2
    private static let responseFragmentDelayNanoseconds: UInt64 = 20_000_000
}

private enum LoopbackControlServerError: Error {
    case listenerFailed(String)
    case listenerMissingPort
    case connectionFailed(String)
    case connectionClosed
}

private struct SendableLoopbackConnection: @unchecked Sendable {
    let connection: NWConnection

    init(_ connection: NWConnection) {
        self.connection = connection
    }
}

private func start(listener: NWListener, queue: DispatchQueue) async throws -> NWEndpoint.Port {
    try await withCheckedThrowingContinuation { continuation in
        listener.stateUpdateHandler = { state in
            switch state {
            case .ready:
                listener.stateUpdateHandler = nil
                guard let port = listener.port else {
                    continuation.resume(throwing: LoopbackControlServerError.listenerMissingPort)
                    return
                }
                continuation.resume(returning: port)
            case let .failed(error):
                listener.stateUpdateHandler = nil
                continuation.resume(throwing: LoopbackControlServerError.listenerFailed(String(describing: error)))
            case .cancelled:
                listener.stateUpdateHandler = nil
                continuation.resume(throwing: LoopbackControlServerError.connectionClosed)
            default:
                break
            }
        }
        listener.start(queue: queue)
    }
}

private func firstConnection(
    from connections: AsyncStream<SendableLoopbackConnection>
) async throws -> NWConnection {
    for await connection in connections { return connection.connection }
    throw LoopbackControlServerError.connectionClosed
}

private func start(connection: NWConnection, queue: DispatchQueue) async throws {
    try await withCheckedThrowingContinuation { continuation in
        connection.stateUpdateHandler = { state in
            switch state {
            case .ready:
                connection.stateUpdateHandler = nil
                continuation.resume()
            case let .failed(error):
                connection.stateUpdateHandler = nil
                continuation.resume(throwing: LoopbackControlServerError.connectionFailed(String(describing: error)))
            case .cancelled:
                connection.stateUpdateHandler = nil
                continuation.resume(throwing: LoopbackControlServerError.connectionClosed)
            default:
                break
            }
        }
        connection.start(queue: queue)
    }
}

private func receiveControlMessage(from connection: NWConnection) async throws -> ControlMessage {
    var decoder = ControlFrameDecoder()
    while true {
        let data = try await receiveData(from: connection)
        if let payload = try decoder.append(data).first {
            return try ControlMessageCodec.decode(payload)
        }
    }
}

private func receiveData(from connection: NWConnection) async throws -> Data {
    try await withCheckedThrowingContinuation { continuation in
        connection.receive(
            minimumIncompleteLength: MemoryLayout<UInt8>.size,
            maximumLength: CamBridgeContract.Control.maximumMessageBytes + MemoryLayout<UInt32>.size
        ) { data, _, isComplete, error in
            if let error {
                continuation.resume(throwing: LoopbackControlServerError.connectionFailed(String(describing: error)))
            } else if let data, !data.isEmpty {
                continuation.resume(returning: data)
            } else if isComplete {
                continuation.resume(throwing: LoopbackControlServerError.connectionClosed)
            } else {
                continuation.resume(throwing: LoopbackControlServerError.connectionFailed("empty receive"))
            }
        }
    }
}

private func send(_ data: Data, on connection: NWConnection) async throws {
    try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
        connection.send(content: data, completion: .contentProcessed { error in
            if let error {
                continuation.resume(throwing: LoopbackControlServerError.connectionFailed(String(describing: error)))
            } else {
                continuation.resume()
            }
        })
    }
}

private func finishWriting(on connection: NWConnection) async throws {
    try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
        connection.send(
            content: nil,
            contentContext: .finalMessage,
            isComplete: true,
            completion: .contentProcessed { error in
                if let error {
                    continuation.resume(throwing: LoopbackControlServerError.connectionFailed(String(describing: error)))
                } else {
                    continuation.resume()
                }
            }
        )
    }
}

private actor FakeControlConnection: CamBridgeControlConnectionProtocol {
    private var response: ControlMessage?
    private let respondToProbe: Bool
    private var connects = 0
    private var probes = 0
    private var closed = false
    private var messageContinuation: AsyncThrowingStream<ControlMessage, Error>.Continuation?

    init(response: ControlMessage? = nil, respondToProbe: Bool = true) {
        self.response = response
        self.respondToProbe = respondToProbe
    }

    func connect() async throws {
        connects += 1
    }

    func send(_ message: ControlMessage) async throws {
        if case let .probe(requestId) = message {
            probes += 1
            if respondToProbe {
                response = .capabilities(
                    requestId: requestId,
                    receiverId: "test-receiver",
                    displayName: "Test Receiver",
                    maxLongEdge: CamBridgeContract.Geometry.maximumLongEdge,
                    maxShortEdge: CamBridgeContract.Geometry.maximumShortEdge
                )
            }
        }
    }

    func receive() async throws -> ControlMessage? {
        response
    }

    func messages() async -> AsyncThrowingStream<ControlMessage, Error> {
        AsyncThrowingStream { continuation in
            messageContinuation = continuation
        }
    }

    func close() async {
        closed = true
        messageContinuation?.finish()
        messageContinuation = nil
    }

    func connectCount() -> Int { connects }
    func probeCount() -> Int { probes }
    func didClose() -> Bool { closed }
}

private struct FakeControlConnectionFactory: CamBridgeControlConnectionFactory {
    let connection: FakeControlConnection

    func make(target: ReceiverControlTarget) -> any CamBridgeControlConnectionProtocol {
        connection
    }
}
