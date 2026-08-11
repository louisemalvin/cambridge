import Foundation
@preconcurrency import Network
import CamBridgeCore

public struct BonjourReceiverMetadata: Equatable, Sendable {
    public let receiverId: String
    public let displayName: String
    public let protocolVersion: Int
    public let codec: String
    public let discoveryVersion: Int
    public let ipv4Addresses: [String]

    public init(dictionary: [String: String]) throws {
        let txtKeys = CamBridgeContract.Discovery.txtKeys
        guard txtKeys.count >= Self.requiredTXTKeyCount else {
            throw BonjourReceiverMetadataError.invalidValue
        }
        let receiverIdKey = txtKeys[Self.receiverIdKeyIndex]
        let displayNameKey = txtKeys[Self.displayNameKeyIndex]
        let protocolVersionKey = txtKeys[Self.protocolVersionKeyIndex]
        let codecKey = txtKeys[Self.codecKeyIndex]
        let discoveryVersionKey = txtKeys[Self.discoveryVersionKeyIndex]
        let requiredKeys = [
            receiverIdKey,
            displayNameKey,
            protocolVersionKey,
            codecKey,
            discoveryVersionKey
        ]
        for key in requiredKeys where dictionary[key]?.isEmpty != false {
            throw BonjourReceiverMetadataError.missingKey(key)
        }
        guard let receiverId = dictionary[receiverIdKey], receiverId.count <= Self.maximumIdentifierLength,
              let displayName = dictionary[displayNameKey], displayName.count <= Self.maximumIdentifierLength,
              let protocolVersion = Int(dictionary[protocolVersionKey] ?? ""),
              let discoveryVersion = Int(dictionary[discoveryVersionKey] ?? "") else {
            throw BonjourReceiverMetadataError.invalidValue
        }
        guard protocolVersion == CamBridgeContract.protocolVersion,
              dictionary[codecKey] == CamBridgeContract.Codec.h264,
              discoveryVersion == CamBridgeContract.Discovery.version else {
            throw BonjourReceiverMetadataError.incompatible
        }

        var addresses: [(Int, String)] = []
        for (key, value) in dictionary where key.hasPrefix(CamBridgeContract.Discovery.addressKeyPrefix) {
            let suffix = String(key.dropFirst(CamBridgeContract.Discovery.addressKeyPrefix.count))
            guard let index = Int(suffix), String(index) == suffix,
                  index >= .zero, index < CamBridgeContract.Discovery.maximumAddressCount,
                  IPv4Address(value) != nil,
                  Self.isUnicastIPv4(value) else {
                throw BonjourReceiverMetadataError.invalidAddressKey(key)
            }
            addresses.append((index, value))
        }
        addresses.sort { $0.0 < $1.0 }
        var uniqueAddresses: [String] = []
        for address in addresses.map(\.1) where !uniqueAddresses.contains(address) {
            uniqueAddresses.append(address)
        }
        self.receiverId = receiverId
        self.displayName = displayName
        self.protocolVersion = protocolVersion
        self.codec = CamBridgeContract.Codec.h264
        self.discoveryVersion = discoveryVersion
        self.ipv4Addresses = uniqueAddresses
    }

    private static func isUnicastIPv4(_ value: String) -> Bool {
        guard let address = IPv4Address(value) else { return false }
        let bytes = address.rawValue
        guard bytes.count == Self.ipv4ByteCount else { return false }
        let first = bytes[bytes.startIndex]
        let second = bytes[bytes.index(after: bytes.startIndex)]
        let isLoopback = first == Self.loopbackFirstOctet
        let isLinkLocal = first == Self.linkLocalFirstOctet && second == Self.linkLocalSecondOctet
        let isReservedZeroNetwork = first == Self.reservedZeroNetworkFirstOctet
        let isMulticast = first >= Self.multicastFirstOctet
        let isUnspecified = bytes.allSatisfy { $0 == .zero }
        return !isLoopback && !isLinkLocal && !isReservedZeroNetwork && !isMulticast && !isUnspecified
    }

    private static let maximumIdentifierLength = CamBridgeContract.Validation.maximumIdentifierLength
    private static let ipv4ByteCount = 4
    private static let loopbackFirstOctet: UInt8 = 127
    private static let linkLocalFirstOctet: UInt8 = 169
    private static let linkLocalSecondOctet: UInt8 = 254
    private static let reservedZeroNetworkFirstOctet: UInt8 = 0
    private static let multicastFirstOctet: UInt8 = 224

    // These positions are the generated contract's stable TXT-key order.
    private static let receiverIdKeyIndex = 0
    private static let displayNameKeyIndex = 1
    private static let protocolVersionKeyIndex = 2
    private static let codecKeyIndex = 3
    private static let discoveryVersionKeyIndex = 4
    private static let requiredTXTKeyCount = 5
}

public enum BonjourReceiverMetadataError: Error, Equatable, Sendable {
    case missingKey(String)
    case invalidValue
    case incompatible
    case invalidAddressKey(String)
}

public struct BonjourReceiverCandidate: Identifiable, Equatable, Sendable {
    public let serviceName: String
    public let serviceEndpoint: NWEndpoint
    public let metadata: BonjourReceiverMetadata

    public var id: String { "\(metadata.receiverId)@\(serviceName)" }

    public static func == (lhs: Self, rhs: Self) -> Bool {
        lhs.serviceName == rhs.serviceName && lhs.metadata == rhs.metadata
    }
}

public enum ReceiverControlTarget: Sendable {
    case bonjour(NWEndpoint)
    case manual(ReceiverEndpoint)

    public func nwEndpoint() throws -> NWEndpoint {
        switch self {
        case let .bonjour(endpoint):
            return endpoint
        case let .manual(endpoint):
            guard let port = NWEndpoint.Port(rawValue: UInt16(endpoint.controlPort)) else {
                throw ReceiverEndpointError.invalidPort(endpoint.controlPort)
            }
            return .hostPort(host: .name(endpoint.host, nil), port: port)
        }
    }
}

public enum BonjourReceiverBrowserEvent: Sendable {
    case discovered(BonjourReceiverCandidate)
    case removed(receiverId: String)
    case failed(String)
}

public protocol ReceiverBrowsing: Sendable {
    func events() async -> AsyncStream<BonjourReceiverBrowserEvent>
    func stop() async
}

public actor BonjourReceiverBrowser {
    private var browser: NWBrowser?
    private var continuation: AsyncStream<BonjourReceiverBrowserEvent>.Continuation?
    private var activeStreamID: UUID?
    private var activeReceiverIDs: Set<String> = []
    private let callbackQueue = DispatchQueue(label: "dev.cambridge.sender.discovery")

    public init() {}

    public func events() -> AsyncStream<BonjourReceiverBrowserEvent> {
        let streamID = UUID()
        activeStreamID = streamID
        return AsyncStream(bufferingPolicy: .bufferingNewest(Self.eventStreamBufferCapacity)) { continuation in
            continuation.onTermination = { _ in
                Task { await self.stop(streamID: streamID) }
            }
            Task { await self.start(continuation: continuation, streamID: streamID) }
        }
    }

    public func stop() {
        activeStreamID = nil
        cancelCurrentBrowser()
    }

    private func stop(streamID: UUID) {
        guard activeStreamID == streamID else { return }
        stop()
    }

    private func cancelCurrentBrowser() {
        browser?.cancel()
        browser = nil
        continuation?.finish()
        continuation = nil
        activeReceiverIDs.removeAll(keepingCapacity: true)
    }

    private func start(
        continuation: AsyncStream<BonjourReceiverBrowserEvent>.Continuation,
        streamID: UUID
    ) {
        guard activeStreamID == streamID else {
            continuation.finish()
            return
        }
        cancelCurrentBrowser()
        self.continuation = continuation
        let parameters = NWParameters.tcp
        let browser = NWBrowser(
            for: .bonjourWithTXTRecord(type: CamBridgeContract.Discovery.serviceType, domain: nil),
            using: parameters
        )
        browser.stateUpdateHandler = { [weak self] state in
            guard case let .failed(error) = state else { return }
            Task { await self?.publish(.failed(String(describing: error)), streamID: streamID) }
        }
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            Task { await self?.publish(results: results, streamID: streamID) }
        }
        browser.start(queue: callbackQueue)
        self.browser = browser
    }

    private func publish(results: Set<NWBrowser.Result>, streamID: UUID) {
        guard activeStreamID == streamID else { return }
        var currentReceiverIDs: Set<String> = []
        for result in results {
            guard case let .service(serviceName, _, _, _) = result.endpoint,
                  case let .bonjour(txtRecord) = result.metadata,
                  let metadata = try? BonjourReceiverMetadata(dictionary: txtRecord.dictionary) else {
                continue
            }
            currentReceiverIDs.insert(metadata.receiverId)
            continuation?.yield(.discovered(BonjourReceiverCandidate(
                serviceName: serviceName,
                serviceEndpoint: result.endpoint,
                metadata: metadata
            )))
        }
        let removedReceiverIDs = activeReceiverIDs.subtracting(currentReceiverIDs).sorted()
        for receiverId in removedReceiverIDs {
            continuation?.yield(.removed(receiverId: receiverId))
        }
        activeReceiverIDs = currentReceiverIDs
    }

    private func publish(_ event: BonjourReceiverBrowserEvent, streamID: UUID) {
        guard activeStreamID == streamID else { return }
        continuation?.yield(event)
    }

    // Discovery is a replaceable view of current receivers, so retaining only
    // a bounded newest window is sufficient while setup is not being rendered.
    private static let eventStreamBufferCapacity = 16
}

extension BonjourReceiverBrowser: ReceiverBrowsing {}
