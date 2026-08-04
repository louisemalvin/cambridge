import Foundation

enum IOSV2SRTMode: String, Codable, Equatable {
    case caller
    case listener
}

enum IOSV2SRTTransportKind: String, Codable, Equatable {
    case srt
}

enum IOSV2PixelFormat: String, Codable, Equatable {
    case yuy2
    case nv12
    case i420
}

struct IOSV2SRTEndpoint: Codable, Equatable {
    let kind: IOSV2SRTTransportKind
    let mode: IOSV2SRTMode
    let host: String
    let port: UInt16
    let streamId: String
    let latencyMs: UInt32
    let keyLengthBytes: UInt16
    let passphrase: String

    func validate() throws {
        guard !host.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              port != IOSMediaConfigurationLimits.unassignedPort,
              !streamId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              latencyMs > IOSV2ContractLimits.minimumLatencyMs,
              keyLengthBytes == IOSV2ContractLimits.srtKeyLengthBytes,
              passphrase.count >= IOSV2ContractLimits.minimumPassphraseLength,
              passphrase.count <= IOSV2ContractLimits.maximumPassphraseLength else {
            throw IOSMediaConfigurationError.invalidDestination
        }
    }
}

struct IOSV2SRTTransportCapabilities: Codable, Equatable {
    let kind: IOSV2SRTTransportKind
    let modes: [IOSV2SRTMode]
    let keyLengthBytes: UInt16
}

struct IOSV2VideoProfile: Codable, Equatable {
    let width: UInt32
    let height: UInt32
    let fps: UInt32
}

struct IOSV2BitrateByCodec: Codable, Equatable {
    let h264: UInt32
    let h265: UInt32
}

enum IOSV2Container: String, Codable, Equatable {
    case mpegts
}

struct IOSV2VideoConfiguration: Codable, Equatable {
    let codec: IOSVideoCodec
    let container: IOSV2Container
    let width: UInt32
    let height: UInt32
    let fps: UInt32
    let bitrateBps: UInt32
}

struct IOSV2OutputConfiguration: Codable, Equatable {
    let pixelFormat: IOSV2PixelFormat
}

struct IOSV2HealthResponse: Codable, Equatable {
    let status: String
    let protocolVersion: UInt32
}

struct IOSV2ReceiverCapabilities: Codable, Equatable {
    let protocolVersion: UInt32
    let transport: IOSV2SRTTransportCapabilities
    let videoCodecs: [IOSVideoCodec]
    let outputProfile: IOSV2VideoProfile
    let output: IOSV2OutputConfiguration
    let maximumConcurrentSessions: UInt8
    let active: Bool
}

struct IOSV2CreateSessionRequest: Codable, Equatable {
    let protocolVersion: UInt32
    let preferredCodecs: [IOSVideoCodec]
    let profile: IOSV2VideoProfile
    let bitrateByCodec: IOSV2BitrateByCodec
}

struct IOSV2CreateSessionResponse: Codable, Equatable {
    let protocolVersion: UInt32
    let sessionId: String
    let connectDeadlineMs: UInt64
    let reconnectGraceMs: UInt64
    let video: IOSV2VideoConfiguration
    let transport: IOSV2SRTEndpoint
    let output: IOSV2OutputConfiguration
}

enum IOSV2SessionState: String, Codable, Equatable {
    case idle
    case allocating
    case listening
    case connected
    case receiving
    case reconnecting
    case stopping
    case failed
    case expired
}

struct IOSV2SessionMetrics: Codable, Equatable {
    let bytesReceived: UInt64?
    let packetsReceived: UInt64?
    let packetsLost: UInt64?
    let packetsRetransmitted: UInt64?
    let packetsDropped: UInt64?
    let rttMs: UInt32?
    let decodedFrames: UInt64?
    let outputFps: UInt32?
    let outputQueueDepth: UInt32?
    let reconnectCount: UInt32?
}

struct IOSV2SessionStatus: Codable, Equatable {
    let protocolVersion: UInt32
    let sessionId: String
    let state: IOSV2SessionState
    let decoder: String?
    let metrics: IOSV2SessionMetrics
}

enum IOSV2ContractLimits {
    static let protocolVersion: UInt32 = 2
    static let srtKeyLengthBytes: UInt16 = 32
    static let minimumLatencyMs: UInt32 = 0
    static let minimumPassphraseLength = 10
    static let maximumPassphraseLength = 79
}

struct IOSReceiverEndpoint: Equatable {
    let host: String
    let controlPort: UInt16
    let bearerToken: String?
    let receiverId: String?
    let authenticationRequired: Bool

    init(
        host: String,
        controlPort: UInt16,
        bearerToken: String? = nil,
        receiverId: String? = nil,
        authenticationRequired: Bool = false
    ) {
        self.host = host
        self.controlPort = controlPort
        self.bearerToken = bearerToken
        self.receiverId = receiverId
        self.authenticationRequired = authenticationRequired
    }

    var controlBaseURL: URL? {
        var components = URLComponents()
        components.scheme = "http"
        components.host = host
        components.port = Int(controlPort)
        return components.url
    }
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
    case invalidEndpoint
    case invalidResponse
    case invalidSession
    case httpStatus(Int)
    case unsupportedProtocol(UInt32)
    case transport(String)
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

final class URLSessionIOSReceiverControlClient: IOSReceiverControlClient {
    private let session: URLSession
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder

    init(session: URLSession = .shared) {
        self.session = session
        self.encoder = JSONEncoder()
        self.decoder = JSONDecoder()
    }

    func health(at endpoint: IOSReceiverEndpoint) async throws -> IOSReceiverHealth {
        let response: IOSV2HealthResponse = try await get(
            path: "/v2/health",
            endpoint: endpoint,
            requiresAuthorization: false,
        )
        try validateProtocol(response.protocolVersion)
        return IOSReceiverHealth(
            status: response.status,
            protocolVersion: Int(response.protocolVersion),
        )
    }

    func capabilities(at endpoint: IOSReceiverEndpoint) async throws -> IOSReceiverCapabilities {
        let response: IOSV2ReceiverCapabilities = try await get(
            path: "/v2/capabilities",
            endpoint: endpoint,
        )
        try validateProtocol(response.protocolVersion)
        return IOSReceiverCapabilities(supportedCodecs: response.videoCodecs)
    }

    func prepareSession(
        configuration: IOSMediaConfiguration,
        endpoint: IOSReceiverEndpoint
    ) async throws -> IOSMediaDestination {
        try configuration.validate()
        let request = try makeCreateRequest(configuration: configuration)
        let response: IOSV2CreateSessionResponse = try await post(
            path: "/v2/sessions",
            endpoint: endpoint,
            body: request,
        )
        try validateProtocol(response.protocolVersion)
        guard let sessionID = UUID(uuidString: response.sessionId) else {
            throw IOSReceiverControlError.invalidSession
        }
        return IOSMediaDestination(sessionID: sessionID, srtEndpoint: response.transport)
    }

    func stopSession(
        sessionID: UUID,
        endpoint: IOSReceiverEndpoint
    ) async throws {
        try await delete(
            path: "/v2/sessions/\(sessionID.uuidString)",
            endpoint: endpoint,
            acceptsNotFound: true,
        )
    }

    private func makeCreateRequest(
        configuration: IOSMediaConfiguration
    ) throws -> IOSV2CreateSessionRequest {
        guard let width = UInt32(exactly: configuration.profile.width),
              let height = UInt32(exactly: configuration.profile.height),
              let fps = UInt32(exactly: configuration.profile.framesPerSecond),
              let bitrate = UInt32(exactly: configuration.bitrateBps) else {
            throw IOSReceiverControlError.invalidResponse
        }
        return IOSV2CreateSessionRequest(
            protocolVersion: IOSV2ContractLimits.protocolVersion,
            preferredCodecs: [configuration.codec],
            profile: IOSV2VideoProfile(width: width, height: height, fps: fps),
            bitrateByCodec: IOSV2BitrateByCodec(h264: bitrate, h265: bitrate),
        )
    }

    private func validateProtocol(_ version: UInt32) throws {
        guard version == IOSV2ContractLimits.protocolVersion else {
            throw IOSReceiverControlError.unsupportedProtocol(version)
        }
    }

    private func get<Response: Decodable>(
        path: String,
        endpoint: IOSReceiverEndpoint,
        requiresAuthorization: Bool = true,
    ) async throws -> Response {
        var request = try makeRequest(path: path, endpoint: endpoint)
        request.httpMethod = "GET"
        if requiresAuthorization {
            addAuthorization(to: &request, endpoint: endpoint)
        }
        return try await send(request)
    }

    private func post<Response: Decodable, Body: Encodable>(
        path: String,
        endpoint: IOSReceiverEndpoint,
        body: Body,
    ) async throws -> Response {
        var request = try makeRequest(path: path, endpoint: endpoint)
        request.httpMethod = "POST"
        addAuthorization(to: &request, endpoint: endpoint)
        request.httpBody = try encoder.encode(body)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        return try await send(request)
    }

    private func delete(
        path: String,
        endpoint: IOSReceiverEndpoint,
        acceptsNotFound: Bool,
    ) async throws {
        var request = try makeRequest(path: path, endpoint: endpoint)
        request.httpMethod = "DELETE"
        addAuthorization(to: &request, endpoint: endpoint)
        let (_, response) = try await data(for: request)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw IOSReceiverControlError.invalidResponse
        }
        let statusCode = httpResponse.statusCode
        guard (200..<300).contains(statusCode) || (acceptsNotFound && statusCode == 404) else {
            throw IOSReceiverControlError.httpStatus(statusCode)
        }
    }

    private func makeRequest(
        path: String,
        endpoint: IOSReceiverEndpoint,
    ) throws -> URLRequest {
        guard var url = endpoint.controlBaseURL else {
            throw IOSReceiverControlError.invalidEndpoint
        }
        url.appendPathComponent(
            path.trimmingCharacters(in: CharacterSet(charactersIn: "/")),
        )
        return URLRequest(url: url)
    }

    private func addAuthorization(to request: inout URLRequest, endpoint: IOSReceiverEndpoint) {
        guard let token = endpoint.bearerToken?.trimmingCharacters(in: .whitespacesAndNewlines),
              !token.isEmpty else {
            return
        }
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
    }

    private func send<Response: Decodable>(_ request: URLRequest) async throws -> Response {
        let (data, response) = try await data(for: request)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw IOSReceiverControlError.invalidResponse
        }
        guard (200..<300).contains(httpResponse.statusCode) else {
            throw IOSReceiverControlError.httpStatus(httpResponse.statusCode)
        }
        do {
            return try decoder.decode(Response.self, from: data)
        } catch {
            throw IOSReceiverControlError.invalidResponse
        }
    }

    private func data(for request: URLRequest) async throws -> (Data, URLResponse) {
        do {
            return try await session.data(for: request)
        } catch {
            throw IOSReceiverControlError.transport(error.localizedDescription)
        }
    }
}
