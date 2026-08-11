import Foundation
@preconcurrency import Network
import CamBridgeCore

public struct RTPDatagramMetrics: Equatable, Sendable {
    public let packetsSent: Int
    public let bytesSent: Int
    public let sendFailures: Int
    public let maximumSendDurationNanoseconds: UInt64
}

public protocol RTPDatagramSending: Sendable {
    func connect() async throws
    func send(_ datagram: Data) async throws
    func close() async
    func metrics() async -> RTPDatagramMetrics
}

public enum RTPDatagramSenderError: Error, Equatable, Sendable {
    case invalidPort(Int)
    case invalidDatagramSize(Int)
    case notConnected
    case connectionFailed(String)
    case connectionTimedOut
    case sendFailed(String)
}

public actor RTPDatagramSender: RTPDatagramSending {
    private let host: String
    private let port: Int
    private let callbackQueue = DispatchQueue(label: "dev.cambridge.sender.rtp")
    private var connection: NWConnection?
    private var packetsSent = Int.zero
    private var bytesSent = Int.zero
    private var sendFailures = Int.zero
    private var maximumSendDurationNanoseconds: UInt64 = .zero
    private var sendContinuation: CheckedContinuation<Void, Error>?

    public init(host: String, port: Int) throws {
        guard port >= CamBridgeContract.Validation.minimumPort,
              port <= CamBridgeContract.Validation.maximumPort else {
            throw RTPDatagramSenderError.invalidPort(port)
        }
        self.host = host
        self.port = port
    }

    public func connect() async throws {
        if connection?.state == .ready { return }
        if connection != nil {
            throw RTPDatagramSenderError.connectionFailed("sender is already connecting")
        }
        guard let nwPort = NWEndpoint.Port(rawValue: UInt16(port)) else {
            throw RTPDatagramSenderError.invalidPort(port)
        }
        let connection = NWConnection(
            host: .name(host),
            port: nwPort,
            using: .udp
        )
        self.connection = connection
        connection.stateUpdateHandler = { [weak self] state in
            Task { await self?.handle(state: state) }
        }
        connection.start(queue: callbackQueue)
        do {
            try await withThrowingTaskGroup(of: Void.self) { group in
                group.addTask { [weak self] in
                    guard let self else { throw RTPDatagramSenderError.connectionFailed("sender deallocated") }
                    try await self.waitUntilReady()
                }
                group.addTask {
                    try await Task.sleep(nanoseconds: Self.requestTimeoutNanoseconds)
                    throw RTPDatagramSenderError.connectionTimedOut
                }
                _ = try await group.next()
                group.cancelAll()
            }
        } catch {
            cancelConnection(resumeError: error)
            throw error
        }
    }

    public func send(_ datagram: Data) async throws {
        guard datagram.count <= CamBridgeContract.Media.mtuBytes else {
            throw RTPDatagramSenderError.invalidDatagramSize(datagram.count)
        }
        guard let connection, connection.state == .ready else {
            throw RTPDatagramSenderError.notConnected
        }
        let started = DispatchTime.now().uptimeNanoseconds
        defer {
            let duration = DispatchTime.now().uptimeNanoseconds - started
            maximumSendDurationNanoseconds = max(maximumSendDurationNanoseconds, duration)
        }
        do {
            try await withTaskCancellationHandler(operation: {
                try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                    guard !Task.isCancelled else {
                        continuation.resume(throwing: CancellationError())
                        return
                    }
                    guard sendContinuation == nil else {
                        continuation.resume(throwing: RTPDatagramSenderError.sendFailed("a media send is already in progress"))
                        return
                    }
                    sendContinuation = continuation
                    connection.send(content: datagram, completion: .contentProcessed { [weak self] error in
                        let errorMessage = error.map { String(describing: $0) }
                        Task {
                            await self?.handleSendCompletion(errorMessage: errorMessage)
                        }
                    })
                }
            }, onCancel: {
                Task { await self.cancelPendingSend() }
            })
            packetsSent += Self.counterIncrement
            bytesSent += datagram.count
        } catch {
            sendFailures += Self.counterIncrement
            throw error
        }
    }

    public func close() {
        cancelConnection(resumeError: RTPDatagramSenderError.connectionFailed("connection closed"))
    }

    public func metrics() -> RTPDatagramMetrics {
        RTPDatagramMetrics(
            packetsSent: packetsSent,
            bytesSent: bytesSent,
            sendFailures: sendFailures,
            maximumSendDurationNanoseconds: maximumSendDurationNanoseconds
        )
    }

    private func waitUntilReady() async throws {
        guard let connection else { throw RTPDatagramSenderError.notConnected }
        if connection.state == .ready { return }
        try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                guard !Task.isCancelled else {
                    continuation.resume(throwing: CancellationError())
                    return
                }
                readyContinuation = continuation
                switch connection.state {
                case .ready:
                    readyContinuation = nil
                    continuation.resume()
                case let .failed(error):
                    readyContinuation = nil
                    continuation.resume(throwing: RTPDatagramSenderError.connectionFailed(String(describing: error)))
                case .cancelled:
                    readyContinuation = nil
                    continuation.resume(throwing: RTPDatagramSenderError.connectionFailed("connection cancelled"))
                default:
                    break
                }
            }
        }, onCancel: {
            Task { await self.cancelReadyWait() }
        })
    }

    private func cancelReadyWait() {
        let continuation = readyContinuation
        readyContinuation = nil
        continuation?.resume(throwing: CancellationError())
        connection?.cancel()
        connection = nil
    }

    private func cancelConnection(resumeError: Error) {
        connection?.cancel()
        connection = nil
        let continuation = readyContinuation
        readyContinuation = nil
        continuation?.resume(throwing: resumeError)
        let sendContinuation = self.sendContinuation
        self.sendContinuation = nil
        sendContinuation?.resume(throwing: resumeError)
    }

    private func handleSendCompletion(errorMessage: String?) {
        let continuation = sendContinuation
        sendContinuation = nil
        if let errorMessage {
            continuation?.resume(throwing: RTPDatagramSenderError.sendFailed(errorMessage))
        } else {
            continuation?.resume()
        }
    }

    private func cancelPendingSend() {
        let continuation = sendContinuation
        sendContinuation = nil
        continuation?.resume(throwing: CancellationError())
        connection?.cancel()
        connection = nil
    }

    private var readyContinuation: CheckedContinuation<Void, Error>?

    private func handle(state: NWConnection.State) {
        switch state {
        case .ready:
            let continuation = readyContinuation
            readyContinuation = nil
            continuation?.resume()
        case let .failed(error):
            cancelConnection(resumeError: RTPDatagramSenderError.connectionFailed(String(describing: error)))
        case .cancelled:
            cancelConnection(resumeError: RTPDatagramSenderError.connectionFailed("connection cancelled"))
        default:
            break
        }
    }

    private static let nanosecondsPerMillisecond: UInt64 = 1_000_000
    private static let requestTimeoutNanoseconds = UInt64(CamBridgeContract.Control.connectTimeoutMilliseconds) * nanosecondsPerMillisecond
    private static let counterIncrement = 1
}
