import XCTest
import CamBridgeCore
@testable import CamBridge

final class NetworkAdapterTests: XCTestCase {
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
