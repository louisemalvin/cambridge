import Foundation

enum IOSVideoCodec: String, Codable, CaseIterable, Equatable {
    case h264
    case h265
}

struct IOSVideoProfile: Codable, Equatable {
    let width: Int
    let height: Int
    let framesPerSecond: Int
}

struct IOSMediaConfiguration: Equatable {
    let codec: IOSVideoCodec
    let profile: IOSVideoProfile
    let bitrateBps: Int
    let keyframeIntervalSeconds: Int

    func validate() throws {
        guard profile.width >= IOSMediaConfigurationLimits.minimumVideoDimension,
              profile.height >= IOSMediaConfigurationLimits.minimumVideoDimension else {
            throw IOSMediaConfigurationError.invalidVideoDimensions
        }
        guard profile.framesPerSecond >= IOSMediaConfigurationLimits.minimumFrameRate else {
            throw IOSMediaConfigurationError.invalidFrameRate
        }
        guard bitrateBps >= IOSMediaConfigurationLimits.minimumBitrateBps else {
            throw IOSMediaConfigurationError.invalidBitrate
        }
        guard keyframeIntervalSeconds >= IOSMediaConfigurationLimits.minimumKeyframeIntervalSeconds else {
            throw IOSMediaConfigurationError.invalidKeyframeInterval
        }
    }
}

struct IOSMediaDestination: Equatable {
    let sessionID: UUID
    let host: String
    let port: UInt16

    func validate() throws {
        guard !host.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              port != IOSMediaConfigurationLimits.unassignedPort else {
            throw IOSMediaConfigurationError.invalidDestination
        }
    }
}

enum IOSMediaConfigurationError: Error, Equatable {
    case invalidVideoDimensions
    case invalidFrameRate
    case invalidBitrate
    case invalidKeyframeInterval
    case invalidDestination
}

enum IOSMediaConfigurationLimits {
    static let minimumVideoDimension = 1
    static let minimumFrameRate = 1
    static let minimumBitrateBps = 1
    static let minimumKeyframeIntervalSeconds = 1
    static let unassignedPort: UInt16 = 0
}
