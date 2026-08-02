import Foundation

enum IOSMediaEngineEvent: Equatable {
    case starting
    case streaming
    case stopped
    case permissionDenied
    case cameraUnavailable
    case encoderUnavailable
    case networkFailure
}

enum IOSMediaEngineError: Error, Equatable {
    case notImplemented
    case invalidConfiguration(IOSMediaConfigurationError)
    case invalidBitrate
}

typealias IOSMediaEngineEventHandler = (IOSMediaEngineEvent) -> Void

protocol IOSMediaEngine: AnyObject {
    var eventHandler: IOSMediaEngineEventHandler? { get set }

    func start(
        configuration: IOSMediaConfiguration,
        destination: IOSMediaDestination
    ) async throws

    func stop() async
    func requestKeyframe()
    func updateBitrate(_ bitrateBps: Int) async throws
}

final class StubIOSMediaEngine: IOSMediaEngine {
    var eventHandler: IOSMediaEngineEventHandler?

    func start(
        configuration: IOSMediaConfiguration,
        destination: IOSMediaDestination
    ) async throws {
        do {
            try configuration.validate()
            try destination.validate()
        } catch let error as IOSMediaConfigurationError {
            throw IOSMediaEngineError.invalidConfiguration(error)
        }

        eventHandler?(.starting)
        throw IOSMediaEngineError.notImplemented
    }

    func stop() async {
        eventHandler?(.stopped)
    }

    func requestKeyframe() {
        // The native encoder boundary will implement this during the iOS media spike.
    }

    func updateBitrate(_ bitrateBps: Int) async throws {
        guard bitrateBps >= IOSMediaConfigurationLimits.minimumBitrateBps else {
            throw IOSMediaEngineError.invalidBitrate
        }
    }
}
