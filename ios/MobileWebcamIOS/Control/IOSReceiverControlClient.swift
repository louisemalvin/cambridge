import Foundation

struct IOSReceiverEndpoint: Equatable {
    let host: String
    let controlPort: UInt16
}

struct IOSReceiverHealth: Equatable {
    let status: String
    let protocolVersion: Int
}

struct IOSReceiverCapabilities: Equatable {
    let supportedCodecs: [IOSVideoCodec]
}

enum IOSReceiverControlError: Error, Equatable {
    case notImplemented
}

protocol IOSReceiverControlClient: AnyObject {
    func health(at endpoint: IOSReceiverEndpoint) async throws -> IOSReceiverHealth

    func capabilities(at endpoint: IOSReceiverEndpoint) async throws -> IOSReceiverCapabilities

    func prepareSession(
        configuration: IOSMediaConfiguration,
        endpoint: IOSReceiverEndpoint
    ) async throws -> IOSMediaDestination

    func stopSession(
        sessionID: UUID,
        endpoint: IOSReceiverEndpoint
    ) async throws
}

final class StubIOSReceiverControlClient: IOSReceiverControlClient {
    func health(at endpoint: IOSReceiverEndpoint) async throws -> IOSReceiverHealth {
        throw IOSReceiverControlError.notImplemented
    }

    func capabilities(at endpoint: IOSReceiverEndpoint) async throws -> IOSReceiverCapabilities {
        throw IOSReceiverControlError.notImplemented
    }

    func prepareSession(
        configuration: IOSMediaConfiguration,
        endpoint: IOSReceiverEndpoint
    ) async throws -> IOSMediaDestination {
        throw IOSReceiverControlError.notImplemented
    }

    func stopSession(
        sessionID: UUID,
        endpoint: IOSReceiverEndpoint
    ) async throws {
        throw IOSReceiverControlError.notImplemented
    }
}
