import Combine
import Foundation

enum IOSReceiverDiscoveryContract {
    static let serviceType = "_mobile-webcam._tcp."
    static let localDomain = "local."
    static let protocolVersion = Int(IOSV2ContractLimits.protocolVersion)
    static let protocolVersionKey = "version"
    static let displayNameKey = "name"
    static let authenticationKey = "auth"
    static let authenticationRequiredValue = "required"
    static let resolveTimeout: TimeInterval = 5
}

struct IOSDiscoveredReceiver: Identifiable, Equatable {
    let id: String
    let displayName: String
    let endpoint: IOSReceiverEndpoint
}

protocol IOSReceiverDiscovery: AnyObject {
    var receivers: [IOSDiscoveredReceiver] { get }
    var onChange: (([IOSDiscoveredReceiver]) -> Void)? { get set }

    func start()
    func stop()
}

final class BonjourIOSReceiverDiscovery: NSObject, ObservableObject, IOSReceiverDiscovery {
    @Published private(set) var receivers: [IOSDiscoveredReceiver] = []
    var onChange: (([IOSDiscoveredReceiver]) -> Void)?

    private let browser = NetServiceBrowser()
    private var services: [String: NetService] = [:]
    private var discoveredReceivers: [String: IOSDiscoveredReceiver] = [:]
    private var isRunning = false

    override init() {
        super.init()
        browser.delegate = self
    }

    func start() {
        guard !isRunning else { return }
        isRunning = true
        browser.searchForServices(
            ofType: IOSReceiverDiscoveryContract.serviceType,
            inDomain: IOSReceiverDiscoveryContract.localDomain
        )
    }

    func stop() {
        guard isRunning else { return }
        isRunning = false
        browser.stop()
        services.values.forEach { service in
            service.stop()
            service.delegate = nil
        }
        services.removeAll()
        discoveredReceivers.removeAll()
        publish()
    }

    private func resolve(_ service: NetService) {
        service.delegate = self
        service.resolve(withTimeout: IOSReceiverDiscoveryContract.resolveTimeout)
    }

    private func publish() {
        let sorted = discoveredReceivers.values.sorted {
            $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending
        }
        DispatchQueue.main.async { [weak self] in
            self?.receivers = sorted
            self?.onChange?(sorted)
        }
    }

    private func receiver(from service: NetService) -> IOSDiscoveredReceiver? {
        guard let controlPort = UInt16(exactly: service.port) else { return nil }
        guard let host = service.hostName,
              let attributes = service.txtRecordData()
                  .map(NetService.dictionary(fromTXTRecord:)),
              let protocolValue = attributes[IOSReceiverDiscoveryContract.protocolVersionKey]
                  .flatMap({ String(data: $0, encoding: .utf8) })
                  .flatMap(Int.init),
              protocolValue == IOSReceiverDiscoveryContract.protocolVersion else {
            return nil
        }

        let displayName = attributes[IOSReceiverDiscoveryContract.displayNameKey]
            .flatMap { String(data: $0, encoding: .utf8) }
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .flatMap { $0.isEmpty ? nil : $0 }
            ?? service.name
        let authenticationRequired = attributes[IOSReceiverDiscoveryContract.authenticationKey]
            .flatMap { String(data: $0, encoding: .utf8) }
            .map { $0.caseInsensitiveCompare(IOSReceiverDiscoveryContract.authenticationRequiredValue) == .orderedSame }
            ?? false
        let endpoint = IOSReceiverEndpoint(
            host: host,
            controlPort: controlPort,
            receiverId: service.name,
            authenticationRequired: authenticationRequired
        )
        return IOSDiscoveredReceiver(
            id: service.name,
            displayName: displayName,
            endpoint: endpoint
        )
    }
}

extension BonjourIOSReceiverDiscovery: NetServiceBrowserDelegate {
    func netServiceBrowser(
        _ browser: NetServiceBrowser,
        didFind service: NetService,
        moreComing: Bool
    ) {
        services[service.name] = service
        resolve(service)
    }

    func netServiceBrowser(
        _ browser: NetServiceBrowser,
        didRemove service: NetService,
        moreComing: Bool
    ) {
        services.removeValue(forKey: service.name)
        discoveredReceivers.removeValue(forKey: service.name)
        publish()
    }

    func netServiceBrowserDidStopSearch(_ browser: NetServiceBrowser) {}

    func netServiceBrowser(
        _ browser: NetServiceBrowser,
        didNotSearch errorDict: [String: NSNumber]
    ) {
        discoveredReceivers.removeAll()
        publish()
    }

    func netServiceBrowser(
        _ browser: NetServiceBrowser,
        didFindDomain domain: String,
        moreComing: Bool
    ) {}

    func netServiceBrowser(
        _ browser: NetServiceBrowser,
        didRemoveDomain domain: String,
        moreComing: Bool
    ) {}
}

extension BonjourIOSReceiverDiscovery: NetServiceDelegate {
    func netServiceDidResolveAddress(_ sender: NetService) {
        guard let receiver = receiver(from: sender) else { return }
        discoveredReceivers[receiver.id] = receiver
        publish()
    }

    func netService(
        _ sender: NetService,
        didNotResolve errorDict: [String: NSNumber]
    ) {}
}
