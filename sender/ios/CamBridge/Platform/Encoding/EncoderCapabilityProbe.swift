import Foundation
import CamBridgeCore

public struct EncoderCapability: Equatable, Sendable {
    public let modeId: String
    public let supported: Bool
    public let minimumBitrateBps: Int
    public let maximumBitrateBps: Int
    public let encoderIdentity: String?
    public let encoderIdentityUnavailableReason: String?
    public let encoderUsesHardwareAccelerated: Bool?
    public let encoderHardwareAvailabilityReason: String?
    public let reason: String?

    public init(
        modeId: String,
        supported: Bool,
        minimumBitrateBps: Int,
        maximumBitrateBps: Int,
        encoderIdentity: String?,
        encoderIdentityUnavailableReason: String? = nil,
        encoderUsesHardwareAccelerated: Bool? = nil,
        encoderHardwareAvailabilityReason: String? = nil,
        reason: String?
    ) {
        self.modeId = modeId
        self.supported = supported
        self.minimumBitrateBps = minimumBitrateBps
        self.maximumBitrateBps = maximumBitrateBps
        self.encoderIdentity = encoderIdentity
        self.encoderIdentityUnavailableReason = encoderIdentityUnavailableReason
        self.encoderUsesHardwareAccelerated = encoderUsesHardwareAccelerated
        self.encoderHardwareAvailabilityReason = encoderHardwareAvailabilityReason
        self.reason = reason
    }
}

public protocol EncoderCapabilityProbing: Sendable {
    func probe(mode: VideoMode, bitrateBps: Int) -> EncoderCapability
}

public struct EncoderCapabilityProbe: Sendable {
    public init() {}

    public func probe(mode: VideoMode, bitrateBps: Int) -> EncoderCapability {
        let encoderCapability: VideoToolboxEncoderCapability
        do {
            encoderCapability = try VideoToolboxEncoder.supportedEncoderCapability(for: mode)
        } catch {
            return EncoderCapability(
                modeId: mode.id,
                supported: false,
                minimumBitrateBps: .zero,
                maximumBitrateBps: .zero,
                encoderIdentity: nil,
                encoderIdentityUnavailableReason: nil,
                encoderUsesHardwareAccelerated: nil,
                encoderHardwareAvailabilityReason: nil,
                reason: "Hardware H.264 bitrate range is unavailable: \(error)"
            )
        }
        let bitrates = mode.steppedBitrates(encoderRange: encoderCapability.bitrateRange)
        guard let firstBitrate = bitrates.first, let lastBitrate = bitrates.last else {
            return EncoderCapability(
                modeId: mode.id,
                supported: false,
                minimumBitrateBps: .zero,
                maximumBitrateBps: .zero,
                encoderIdentity: nil,
                encoderIdentityUnavailableReason: encoderCapability.encoderIdentityUnavailableReason,
                encoderUsesHardwareAccelerated: nil,
                encoderHardwareAvailabilityReason: nil,
                reason: "No stepped bitrate intersects the hardware encoder range"
            )
        }
        let probeBitrate = bitrates.contains(bitrateBps) ? bitrateBps : firstBitrate
        guard let configuration = try? StreamConfiguration(mode: mode, bitrateBps: probeBitrate, orientation: .zero) else {
            return EncoderCapability(
                modeId: mode.id,
                supported: false,
                minimumBitrateBps: firstBitrate,
                maximumBitrateBps: lastBitrate,
                encoderIdentity: nil,
                encoderIdentityUnavailableReason: encoderCapability.encoderIdentityUnavailableReason,
                encoderUsesHardwareAccelerated: nil,
                encoderHardwareAvailabilityReason: nil,
                reason: "Mode cannot form a valid stream configuration"
            )
        }
        let encoder = VideoToolboxEncoder()
        do {
            try encoder.prepare(configuration: configuration)
            let metrics = encoder.metrics()
            let identity = metrics.encoderIdentity ?? encoderCapability.encoderIdentity
            let identityUnavailableReason = identity == nil
                ? metrics.encoderIdentityUnavailableReason ?? encoderCapability.encoderIdentityUnavailableReason
                : nil
            encoder.invalidate()
            return EncoderCapability(
                modeId: mode.id,
                supported: true,
                minimumBitrateBps: firstBitrate,
                maximumBitrateBps: lastBitrate,
                encoderIdentity: identity,
                encoderIdentityUnavailableReason: identityUnavailableReason,
                encoderUsesHardwareAccelerated: metrics.encoderUsesHardwareAccelerated,
                encoderHardwareAvailabilityReason: metrics.encoderHardwareAvailabilityReason,
                reason: nil
            )
        } catch {
            encoder.invalidate()
            return EncoderCapability(
                modeId: mode.id,
                supported: false,
                minimumBitrateBps: firstBitrate,
                maximumBitrateBps: lastBitrate,
                encoderIdentity: nil,
                encoderIdentityUnavailableReason: encoderCapability.encoderIdentityUnavailableReason,
                encoderUsesHardwareAccelerated: nil,
                encoderHardwareAvailabilityReason: nil,
                reason: "Hardware H.264 encoder unavailable: \(error)"
            )
        }
    }
}

extension EncoderCapabilityProbe: EncoderCapabilityProbing {}
