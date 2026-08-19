import Foundation
@preconcurrency import Network
import CamBridgeCore

public protocol CamBridgeControlConnectionProtocol: Sendable {
    func connect() async throws
    func send(_ message: ControlMessage) async throws
    func receive() async throws -> ControlMessage?
    func messages() async -> AsyncThrowingStream<ControlMessage, Error>
    func close() async
}

public protocol CamBridgeControlConnectionFactory: Sendable {
    func make(target: ReceiverControlTarget) -> any CamBridgeControlConnectionProtocol
}

public enum CamBridgeControlConnectionError: Error, Equatable, Sendable {
    case notConnected
    case connectTimedOut
    case connectionFailed(String)
    case connectionClosed
    case sendFailed(String)
    case receiveFailed(String)
    case invalidMessage
}

public actor CamBridgeControlConnection: CamBridgeControlConnectionProtocol {
    private let target: ReceiverControlTarget
    private let callbackQueue = DispatchQueue(label: "dev.cambridge.sender.control")
    private var connection: NWConnection?
    private var connectionEnded = false
    private var frameDecoder = ControlFrameDecoder()
    private var bufferedPayloads: [Data] = []
    private var connectContinuation: CheckedContinuation<Void, Error>?
    private var receiveContinuation: CheckedContinuation<Data?, Error>?
    private var sendContinuation: CheckedContinuation<Void, Error>?

    public init(target: ReceiverControlTarget) {
        self.target = target
    }

    public func connect() async throws {
        if connection?.state == .ready, !connectionEnded { return }
        guard connection == nil else {
            throw CamBridgeControlConnectionError.connectionFailed("control connection is already starting")
        }
        let nwEndpoint: NWEndpoint
        do {
            nwEndpoint = try target.nwEndpoint()
        } catch {
            throw CamBridgeControlConnectionError.connectionFailed(String(describing: error))
        }
        let connection = NWConnection(to: nwEndpoint, using: .tcp)
        self.connection = connection
        connectionEnded = false
        frameDecoder.reset()
        bufferedPayloads.removeAll(keepingCapacity: true)
        connection.stateUpdateHandler = { [weak self] state in
            Task { await self?.handle(state: state) }
        }
        connection.start(queue: callbackQueue)

        do {
            try await withThrowingTaskGroup(of: Void.self) { group in
                group.addTask { [weak self] in
                    guard let self else { throw CamBridgeControlConnectionError.connectionClosed }
                    try await self.waitUntilReady()
                }
                group.addTask {
                    try await Task.sleep(nanoseconds: Self.connectTimeoutNanoseconds)
                    throw CamBridgeControlConnectionError.connectTimedOut
                }
                guard let result = try await group.next() else {
                    throw CamBridgeControlConnectionError.connectionFailed("connection readiness produced no result")
                }
                group.cancelAll()
                return result
            }
        } catch {
            cancelConnection(resumeError: error)
            throw error
        }
    }

    public func send(_ message: ControlMessage) async throws {
        guard let connection, connection.state == .ready else {
            throw CamBridgeControlConnectionError.notConnected
        }
        let payload: Data
        do {
            payload = try ControlMessageCodec.encode(message)
        } catch {
            throw CamBridgeControlConnectionError.sendFailed(String(describing: error))
        }
        let frame: Data
        do {
            frame = try ControlFrameEncoder.frame(payload)
        } catch {
            throw CamBridgeControlConnectionError.sendFailed(String(describing: error))
        }
        try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                guard !Task.isCancelled else {
                    continuation.resume(throwing: CancellationError())
                    return
                }
                guard sendContinuation == nil else {
                    continuation.resume(throwing: CamBridgeControlConnectionError.sendFailed("a control send is already in progress"))
                    return
                }
                sendContinuation = continuation
                connection.send(content: frame, completion: .contentProcessed { [weak self] error in
                    let errorMessage = error.map { String(describing: $0) }
                    Task {
                        await self?.handleSendCompletion(errorMessage: errorMessage)
                    }
                })
            }
        }, onCancel: {
            Task { await self.cancelPendingSend() }
        })
    }

    public func receive() async throws -> ControlMessage? {
        let payload: Data?
        if bufferedPayloads.isEmpty {
            if connectionEnded {
                if frameDecoder.hasPendingFrame {
                    throw CamBridgeControlConnectionError.receiveFailed("control connection ended with a truncated frame")
                }
                return nil
            }
            payload = try await receivePayload()
        } else {
            payload = bufferedPayloads.removeFirst()
        }
        guard let payload else { return nil }
        do {
            return try ControlMessageCodec.decode(payload)
        } catch {
            throw CamBridgeControlConnectionError.receiveFailed(String(describing: error))
        }
    }

    public func messages() -> AsyncThrowingStream<ControlMessage, Error> {
        AsyncThrowingStream(bufferingPolicy: .bufferingOldest(Self.messageStreamBufferCapacity)) { continuation in
            Task {
                do {
                    while let message = try await self.receive() {
                        if case .dropped = continuation.yield(message) {
                            continuation.finish(throwing: CamBridgeControlConnectionError.receiveFailed("control message consumer exceeded the bounded buffer"))
                            return
                        }
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in
                Task { await self.close() }
            }
        }
    }

    public func close() {
        cancelConnection(resumeError: CamBridgeControlConnectionError.connectionClosed)
        frameDecoder.reset()
        bufferedPayloads.removeAll(keepingCapacity: true)
    }

    private func waitUntilReady() async throws {
        guard let connection else { throw CamBridgeControlConnectionError.notConnected }
        if connection.state == .ready { return }
        try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                guard !Task.isCancelled else {
                    continuation.resume(throwing: CancellationError())
                    return
                }
                connectContinuation = continuation
                switch connection.state {
                case .ready:
                    connectContinuation = nil
                    continuation.resume()
                case let .failed(error):
                    connectContinuation = nil
                    continuation.resume(throwing: CamBridgeControlConnectionError.connectionFailed(String(describing: error)))
                case .cancelled:
                    connectContinuation = nil
                    continuation.resume(throwing: CamBridgeControlConnectionError.connectionClosed)
                default:
                    break
                }
            }
        }, onCancel: {
            Task { await self.cancelPendingConnect() }
        })
    }

    private func receivePayload() async throws -> Data? {
        if connectionEnded {
            if frameDecoder.hasPendingFrame {
                throw CamBridgeControlConnectionError.receiveFailed("control connection ended with a truncated frame")
            }
            return nil
        }
        guard connection?.state == .ready else { throw CamBridgeControlConnectionError.notConnected }
        return try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Data?, Error>) in
                guard !Task.isCancelled else {
                    continuation.resume(throwing: CancellationError())
                    return
                }
                receiveContinuation = continuation
                scheduleReceive()
            }
        }, onCancel: {
            Task { await self.cancelPendingReceive() }
        })
    }

    private func scheduleReceive() {
        guard !connectionEnded else { return }
        guard let connection else { return }
        connection.receive(
            minimumIncompleteLength: Self.minimumReceiveLength,
            maximumLength: CamBridgeContract.Control.maximumMessageBytes + MemoryLayout<UInt32>.size
        ) { [weak self] data, _, isComplete, error in
            let errorMessage = error.map { String(describing: $0) }
            Task { await self?.handleReceive(data: data, isComplete: isComplete, errorMessage: errorMessage) }
        }
    }

    private func handle(state: NWConnection.State) {
        switch state {
        case .ready:
            let continuation = connectContinuation
            connectContinuation = nil
            continuation?.resume()
        case let .failed(error):
            cancelConnection(resumeError: CamBridgeControlConnectionError.connectionFailed(String(describing: error)))
        case .cancelled:
            cancelConnection(resumeError: CamBridgeControlConnectionError.connectionClosed)
        default:
            break
        }
    }

    private func handleReceive(data: Data?, isComplete: Bool, errorMessage: String?) {
        guard receiveContinuation != nil else { return }
        if let errorMessage {
            connectionEnded = true
            let continuation = receiveContinuation
            receiveContinuation = nil
            continuation?.resume(throwing: CamBridgeControlConnectionError.receiveFailed(errorMessage))
            return
        }
        if isComplete {
            connectionEnded = true
        }
        if let data, !data.isEmpty {
            do {
                let frames = try frameDecoder.append(data)
                if let first = frames.first {
                    let additionalFrameCount = frames.count - Self.firstFrameCount
                    guard bufferedPayloads.count + additionalFrameCount <= Self.bufferedPayloadCapacity else {
                        let continuation = receiveContinuation
                        receiveContinuation = nil
                        continuation?.resume(throwing: CamBridgeControlConnectionError.receiveFailed("control read-ahead exceeded the bounded buffer"))
                        return
                    }
                    bufferedPayloads.append(contentsOf: frames.dropFirst())
                    let continuation = receiveContinuation
                    receiveContinuation = nil
                    continuation?.resume(returning: first)
                    return
                }
            } catch {
                let continuation = receiveContinuation
                receiveContinuation = nil
                continuation?.resume(throwing: CamBridgeControlConnectionError.receiveFailed(String(describing: error)))
                return
            }
        }
        if isComplete {
            let continuation = receiveContinuation
            receiveContinuation = nil
            if frameDecoder.hasPendingFrame {
                continuation?.resume(throwing: CamBridgeControlConnectionError.receiveFailed("control connection ended with a truncated frame"))
            } else {
                continuation?.resume(returning: nil)
            }
            return
        }
        scheduleReceive()
    }

    private func handleSendCompletion(errorMessage: String?) {
        let continuation = sendContinuation
        sendContinuation = nil
        if let errorMessage {
            continuation?.resume(throwing: CamBridgeControlConnectionError.sendFailed(errorMessage))
        } else {
            continuation?.resume()
        }
    }

    private func cancelConnection(resumeError: Error) {
        connectionEnded = true
        connection?.cancel()
        connection = nil
        let connectContinuation = self.connectContinuation
        self.connectContinuation = nil
        connectContinuation?.resume(throwing: resumeError)
        let receiveContinuation = self.receiveContinuation
        self.receiveContinuation = nil
        receiveContinuation?.resume(throwing: resumeError)
        let sendContinuation = self.sendContinuation
        self.sendContinuation = nil
        sendContinuation?.resume(throwing: resumeError)
    }

    private func cancelPendingConnect() {
        connectionEnded = true
        let continuation = connectContinuation
        connectContinuation = nil
        continuation?.resume(throwing: CancellationError())
        connection?.cancel()
        connection = nil
    }

    private func cancelPendingReceive() {
        connectionEnded = true
        let continuation = receiveContinuation
        receiveContinuation = nil
        continuation?.resume(throwing: CancellationError())
        connection?.cancel()
        connection = nil
    }

    private func cancelPendingSend() {
        connectionEnded = true
        let continuation = sendContinuation
        sendContinuation = nil
        continuation?.resume(throwing: CancellationError())
        connection?.cancel()
        connection = nil
    }

    private static let minimumReceiveLength = MemoryLayout<UInt8>.size
    private static let firstFrameCount = 1
    private static let bufferedPayloadCapacity = 1
    // Session control messages are sparse and ordered. A single pending
    // message is enough; exceeding it is a terminal consumer failure instead
    // of permitting an unbounded read-ahead queue.
    private static let messageStreamBufferCapacity = 1
    private static let nanosecondsPerMillisecond: UInt64 = 1_000_000
    private static let connectTimeoutNanoseconds = UInt64(CamBridgeContract.Control.connectTimeoutMilliseconds) * nanosecondsPerMillisecond
}

public struct LiveCamBridgeControlConnectionFactory: CamBridgeControlConnectionFactory {
    public init() {}

    public func make(target: ReceiverControlTarget) -> any CamBridgeControlConnectionProtocol {
        CamBridgeControlConnection(target: target)
    }
}
