import Foundation
import OSLog

public enum CamBridgeLogCategory: String, Sendable {
    case app
    case discovery
    case control
    case camera
    case encoder
    case rtp
    case session
}

public struct CamBridgeLogger: Sendable {
    private let subsystem: String

    public init(bundle: Bundle = .main) {
        subsystem = bundle.bundleIdentifier ?? "dev.cambridge.sender"
    }

    public func info(_ message: String, category: CamBridgeLogCategory) {
        Logger(subsystem: subsystem, category: category.rawValue).info("\(message, privacy: .public)")
    }

    public func warning(_ message: String, category: CamBridgeLogCategory) {
        Logger(subsystem: subsystem, category: category.rawValue).warning("\(message, privacy: .public)")
    }

    public func error(_ message: String, category: CamBridgeLogCategory) {
        Logger(subsystem: subsystem, category: category.rawValue).error("\(message, privacy: .public)")
    }

    public func event(_ name: String, category: CamBridgeLogCategory, fields: [String: String] = [:]) {
        let fieldsText = fields.sorted(by: { $0.key < $1.key }).map { "\($0.key)=\($0.value)" }.joined(separator: ",")
        Logger(subsystem: subsystem, category: category.rawValue).info("event=\(name, privacy: .public) fields=\(fieldsText, privacy: .private(mask: .hash))")
    }
}
