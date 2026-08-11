import Foundation
import Observation
import CamBridgeCore

public struct SenderPreferences: Codable, Equatable, Sendable {
    public var modeId: String
    public var bitrateBps: Int
    public var orientation: StreamRotation
    public var stabilizationPreference: String
    public var receiverId: String?
    public var receiverDisplayName: String?
    public var receiverHost: String?
    public var receiverControlPort: Int?

    public static var `default`: SenderPreferences {
        SenderPreferences(
            modeId: VideoMode.defaultMode.id,
            bitrateBps: VideoMode.defaultMode.defaultBitrateBps,
            orientation: .zero,
            stabilizationPreference: CameraStabilizationPreference.auto.rawValue,
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
        guard let data = defaults.data(forKey: Self.preferencesKey),
              let stored = try? decoder.decode(SenderPreferences.self, from: data),
              let validated = validate(stored) else {
            return .default
        }
        return validated
    }

    public func save(_ preferences: SenderPreferences) {
        let validated = validate(preferences) ?? .default
        guard let data = try? encoder.encode(validated) else { return }
        defaults.set(data, forKey: Self.preferencesKey)
    }

    private func validate(_ preferences: SenderPreferences) -> SenderPreferences? {
        guard let mode = VideoMode.allModes.first(where: { $0.id == preferences.modeId }),
              mode.availability == .product,
              mode.steppedBitrates(encoderRange: CamBridgeContract.Bitrate.minimumBps...CamBridgeContract.Bitrate.maximumBps).contains(preferences.bitrateBps),
              let stabilization = CameraStabilizationPreference(rawValue: preferences.stabilizationPreference),
              stabilization.avFoundationMode != nil else {
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

    private static let preferencesKey = "cambridge.sender.preferences.v1"
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
