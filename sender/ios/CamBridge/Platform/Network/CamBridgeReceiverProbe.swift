import Foundation
import CamBridgeCore

public protocol ReceiverProbing: Sendable {
    func probe(target: ReceiverControlTarget) async -> Result<ReceiverCapabilities, StreamFailure>
}

public struct CamBridgeReceiverProbe: Sendable {
    private let factory: any CamBridgeControlConnectionFactory

    public init(factory: any CamBridgeControlConnectionFactory = LiveCamBridgeControlConnectionFactory()) {
        self.factory = factory
    }

    public func probe(target: ReceiverControlTarget) async -> Result<ReceiverCapabilities, StreamFailure> {
        let connection = factory.make(target: target)
        do {
            try await connection.connect()
            let requestId = UUID().uuidString
            try await sendWithTimeout(connection, message: .probe(requestId: requestId))
            let response = try await receiveWithTimeout(connection)
            let result: Result<ReceiverCapabilities, StreamFailure>
            guard let response else {
                result = .failure(.receiverUnavailable)
                await connection.close()
                return result
            }
            switch response {
            case let .capabilities(responseRequestId, receiverId, displayName, maxLongEdge, maxShortEdge):
                guard responseRequestId == requestId else {
                    result = .failure(.incompatibleProtocol)
                    await connection.close()
                    return result
                }
                do {
                    let capabilities = try ReceiverCapabilities(
                        receiverId: receiverId,
                        displayName: displayName,
                        maxLongEdge: maxLongEdge,
                        maxShortEdge: maxShortEdge
                    )
                    result = .success(capabilities)
                } catch {
                    result = .failure(.incompatibleProtocol)
                }
            case let .error(message):
                result = .failure(.receiverRejected(message))
            default:
                result = .failure(.incompatibleProtocol)
            }
            await connection.close()
            return result
        } catch let failure as StreamFailure {
            await connection.close()
            return .failure(failure)
        } catch {
            await connection.close()
            return .failure(.controlConnectionFailed(String(describing: error)))
        }
    }

    private func receiveWithTimeout(_ connection: any CamBridgeControlConnectionProtocol) async throws -> ControlMessage? {
        try await withThrowingTaskGroup(of: ControlMessage?.self) { group in
            group.addTask { try await connection.receive() }
            group.addTask {
                try await Task.sleep(nanoseconds: Self.requestTimeoutNanoseconds)
                throw CamBridgeControlConnectionError.receiveFailed("probe request timed out")
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

    private func sendWithTimeout(
        _ connection: any CamBridgeControlConnectionProtocol,
        message: ControlMessage
    ) async throws {
        try await withThrowingTaskGroup(of: Void.self) { group in
            group.addTask {
                try await connection.send(message)
            }
            group.addTask {
                try await Task.sleep(nanoseconds: Self.requestTimeoutNanoseconds)
                throw CamBridgeControlConnectionError.sendFailed("probe send timed out")
            }
            do {
                guard let result = try await group.next() else {
                    throw CamBridgeControlConnectionError.sendFailed("probe send produced no result")
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

    private static let nanosecondsPerMillisecond: UInt64 = 1_000_000
    private static let requestTimeoutNanoseconds = UInt64(CamBridgeContract.Control.requestTimeoutMilliseconds) * nanosecondsPerMillisecond
}

extension CamBridgeReceiverProbe: ReceiverProbing {}
