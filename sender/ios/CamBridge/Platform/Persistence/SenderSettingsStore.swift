import Foundation
import Observation
import CamBridgeCore

public struct SenderPreferences: Codable, Equatable, Sendable {
    public var resolutionId: String
    public var fps: Int
    public var bitrateBps: Int
    public var receiverId: String?
    public var receiverDisplayName: String?
    public var receiverHost: String?
    public var receiverControlPort: Int?

    public static var `default`: SenderPreferences {
        SenderPreferences(
            resolutionId: SenderVideoCatalog.defaultResolution.id,
            fps: SenderVideoCatalog.defaultFrameRate,
            bitrateBps: SenderVideoCatalog.suggestedBitrateBps(
                resolution: SenderVideoCatalog.defaultResolution,
                fps: SenderVideoCatalog.defaultFrameRate
            ) ?? SenderVideoCatalog.minimumBitrateMbps * SenderVideoCatalog.bitrateUnitBps,
            receiverId: nil,
            receiverDisplayName: nil,
            receiverHost: nil,
            receiverControlPort: nil
        )
    }
}

@MainActor
public protocol SenderSettingsStoring: AnyObject {
    func load() -> SenderPreferences
    func save(_ preferences: SenderPreferences)
}

@MainActor
public final class SenderSettingsStore: SenderSettingsStoring {
    private let defaults: UserDefaults
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    public func load() -> SenderPreferences {
        if let data = defaults.data(forKey: Self.preferencesKey),
           let stored = try? decoder.decode(SenderPreferences.self, from: data),
           let validated = validate(stored) {
            return validated
        }
        if let data = defaults.data(forKey: Self.legacyPreferencesKey),
           let legacy = try? decoder.decode(LegacySenderPreferences.self, from: data),
           let migrated = migrate(legacy) {
            save(migrated)
            return migrated
        }
        return .default
    }

    public func save(_ preferences: SenderPreferences) {
        let validated = validate(preferences) ?? .default
        guard let data = try? encoder.encode(validated) else { return }
        defaults.set(data, forKey: Self.preferencesKey)
    }

    private func validate(_ preferences: SenderPreferences) -> SenderPreferences? {
        guard SenderVideoCatalog.resolution(id: preferences.resolutionId) != nil,
              SenderVideoCatalog.frameRates.contains(preferences.fps),
              BitrateInput.wholeMegabits(fromBitsPerSecond: preferences.bitrateBps) != nil else {
            return nil
        }
        var result = preferences
        if let receiverHost = preferences.receiverHost {
            guard let port = preferences.receiverControlPort,
                  let endpoint = try? ReceiverEndpoint(
                      host: receiverHost,
                      controlPort: port,
                      receiverId: preferences.receiverId,
                      displayName: preferences.receiverDisplayName
                  ) else {
                result.receiverId = nil
                result.receiverDisplayName = nil
                result.receiverHost = nil
                result.receiverControlPort = nil
                return result
            }
            result.receiverHost = endpoint.host
            result.receiverControlPort = endpoint.controlPort
        }
        return result
    }

    private func migrate(_ legacy: LegacySenderPreferences) -> SenderPreferences? {
        let resolution: VideoResolution
        let fps: Int
        switch legacy.modeId {
        case "1080p30":
            resolution = SenderVideoCatalog.fullHd
            fps = 30
        case "1080p60":
            resolution = SenderVideoCatalog.fullHd
            fps = 60
        case "2k30":
            resolution = SenderVideoCatalog.resolution2k
            fps = 30
        case "2k60":
            resolution = SenderVideoCatalog.resolution2k
            fps = 60
        default:
            return nil
        }
        let bitrateBps = BitrateInput.wholeMegabits(fromBitsPerSecond: legacy.bitrateBps) == nil
            ? SenderVideoCatalog.suggestedBitrateBps(resolution: resolution, fps: fps)
            : legacy.bitrateBps
        guard let bitrateBps else { return nil }
        return validate(SenderPreferences(
            resolutionId: resolution.id,
            fps: fps,
            bitrateBps: bitrateBps,
            receiverId: legacy.receiverId,
            receiverDisplayName: legacy.receiverDisplayName,
            receiverHost: legacy.receiverHost,
            receiverControlPort: legacy.receiverControlPort
        ))
    }

    private struct LegacySenderPreferences: Codable {
        let modeId: String
        let bitrateBps: Int
        let orientation: StreamRotation
        let stabilizationPreference: String
        let receiverId: String?
        let receiverDisplayName: String?
        let receiverHost: String?
        let receiverControlPort: Int?
    }

    private static let preferencesKey = "cambridge.sender.preferences.v2"
    private static let legacyPreferencesKey = "cambridge.sender.preferences.v1"
}

@MainActor
@Observable
public final class SenderPreferencesState {
    public private(set) var preferences: SenderPreferences
    public private(set) var isStreamActive = false

    private let settingsStore: any SenderSettingsStoring
    private var changeContinuation: AsyncStream<SenderPreferences>.Continuation?

    public init(settingsStore: any SenderSettingsStoring) {
        self.settingsStore = settingsStore
        preferences = settingsStore.load()
    }

    public func changes() -> AsyncStream<SenderPreferences> {
        AsyncStream(bufferingPolicy: .bufferingNewest(Self.preferenceChangeCapacity)) { continuation in
            changeContinuation = continuation
            continuation.yield(preferences)
            continuation.onTermination = { [weak self] _ in
                Task { @MainActor in
                    self?.changeContinuation = nil
                }
            }
        }
    }

    @discardableResult
    public func update(_ nextPreferences: SenderPreferences) -> Bool {
        guard !isStreamActive else { return false }
        guard preferences != nextPreferences else { return false }
        preferences = nextPreferences
        settingsStore.save(nextPreferences)
        changeContinuation?.yield(preferences)
        return true
    }

    // The coordinator has accepted this exact configuration. This is a state
    // commit, not an interactive mutation while the active stream is running.
    public func commitAccepted(_ nextPreferences: SenderPreferences) {
        preferences = nextPreferences
        settingsStore.save(nextPreferences)
        changeContinuation?.yield(preferences)
    }

    public func setStreamActive(_ active: Bool) {
        isStreamActive = active
    }

    private static let preferenceChangeCapacity = 1
}
