import Foundation

struct IOSSenderAdvertisement: Codable, Equatable {
    let senderID: UUID
    let displayName: String
    let controlPort: UInt16
}

enum IOSSenderControlServerError: Error, Equatable {
    case notImplemented
}

protocol IOSSenderControlServer: AnyObject {
    func start() throws
    func stop()
}

final class StubIOSSenderControlServer: IOSSenderControlServer {
    func start() throws {
        throw IOSSenderControlServerError.notImplemented
    }

    func stop() {
        // The native listener will be added with the iOS control integration.
    }
}
