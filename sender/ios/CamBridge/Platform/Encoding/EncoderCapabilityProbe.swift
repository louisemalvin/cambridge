import Foundation
import CamBridgeCore

public struct EncoderCapability: Equatable, Sendable {
    public let modeId: String
    public let supported: Bool
    public let minimumBitrateBps: Int
    public let maximumBitrateBps: Int
    public let encoderIdentity: String?
    public let reason: String?
}

public protocol EncoderCapabilityProbing: Sendable {
    func probe(mode: VideoMode, bitrateBps: Int) -> EncoderCapability
}

public struct EncoderCapabilityProbe: Sendable {
    public init() {}

    public func probe(mode: VideoMode, bitrateBps: Int) -> EncoderCapability {
        let encoderRange: ClosedRange<Int>
        do {
            encoderRange = try VideoToolboxEncoder.supportedBitrateRange(for: mode)
        } catch {
            return EncoderCapability(
                modeId: mode.id,
                supported: false,
                minimumBitrateBps: .zero,
                maximumBitrateBps: .zero,
                encoderIdentity: nil,
                reason: "Hardware H.264 bitrate range is unavailable: \(error)"
            )
        }
        let bitrates = mode.steppedBitrates(encoderRange: encoderRange)
        guard let firstBitrate = bitrates.first, let lastBitrate = bitrates.last else {
            return EncoderCapability(
                modeId: mode.id,
                supported: false,
                minimumBitrateBps: .zero,
                maximumBitrateBps: .zero,
                encoderIdentity: nil,
                reason: "No stepped bitrate intersects the hardware encoder range"
            )
        }
        let probeBitrate = bitrates.contains(bitrateBps) ? bitrateBps : firstBitrate
        guard let configuration = try? StreamConfiguration(mode: mode, bitrateBps: probeBitrate, orientation: .zero) else {
            return EncoderCapability(modeId: mode.id, supported: false, minimumBitrateBps: firstBitrate, maximumBitrateBps: lastBitrate, encoderIdentity: nil, reason: "Mode cannot form a valid stream configuration")
        }
        let encoder = VideoToolboxEncoder()
        do {
            try encoder.prepare(configuration: configuration)
            let identity = encoder.metrics().encoderIdentity
            encoder.invalidate()
            return EncoderCapability(modeId: mode.id, supported: true, minimumBitrateBps: firstBitrate, maximumBitrateBps: lastBitrate, encoderIdentity: identity, reason: nil)
        } catch {
            encoder.invalidate()
            return EncoderCapability(modeId: mode.id, supported: false, minimumBitrateBps: firstBitrate, maximumBitrateBps: lastBitrate, encoderIdentity: nil, reason: "Hardware H.264 encoder unavailable: \(error)")
        }
    }
}

extension EncoderCapabilityProbe: EncoderCapabilityProbing {}
