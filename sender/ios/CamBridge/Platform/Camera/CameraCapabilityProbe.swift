import Foundation
@preconcurrency import AVFoundation
import CoreMedia
import CamBridgeCore

public struct CameraModeCapability: Equatable, Sendable {
    public let mode: VideoMode
    public let supported: Bool
    public let reason: String?
    public let formatID: String?
    public let formatWidth: Int?
    public let formatHeight: Int?
    public let supportedFrameRateRanges: [ClosedRange<Double>]
    public let supportedStabilization: [CameraStabilizationPreference]
    public let encoderMinimumBitrateBps: Int?
    public let encoderMaximumBitrateBps: Int?
    public let encoderIdentity: String?
    public let encoderIdentityUnavailableReason: String?
    public let encoderUsesHardwareAccelerated: Bool?
    public let encoderHardwareAvailabilityReason: String?

    public init(
        mode: VideoMode,
        supported: Bool,
        reason: String?,
        formatID: String?,
        formatWidth: Int? = nil,
        formatHeight: Int? = nil,
        supportedFrameRateRanges: [ClosedRange<Double>] = [],
        supportedStabilization: [CameraStabilizationPreference] = [.off],
        encoderMinimumBitrateBps: Int? = nil,
        encoderMaximumBitrateBps: Int? = nil,
        encoderIdentity: String? = nil,
        encoderIdentityUnavailableReason: String? = nil,
        encoderUsesHardwareAccelerated: Bool? = nil,
        encoderHardwareAvailabilityReason: String? = nil
    ) {
        self.mode = mode
        self.supported = supported
        self.reason = reason
        self.formatID = formatID
        self.formatWidth = formatWidth
        self.formatHeight = formatHeight
        self.supportedFrameRateRanges = supportedFrameRateRanges
        self.supportedStabilization = supportedStabilization
        self.encoderMinimumBitrateBps = encoderMinimumBitrateBps
        self.encoderMaximumBitrateBps = encoderMaximumBitrateBps
        self.encoderIdentity = encoderIdentity
        self.encoderIdentityUnavailableReason = encoderIdentityUnavailableReason
        self.encoderUsesHardwareAccelerated = encoderUsesHardwareAccelerated
        self.encoderHardwareAvailabilityReason = encoderHardwareAvailabilityReason
    }
}

public struct CameraCapabilityProbe: Sendable {
    public init() {}

    public func rearCameraDescriptors() -> [CameraDeviceDescriptor] {
        discoveryDevices()
            .filter { $0.position == .back }
            .map(descriptor(for:))
    }

    public func capabilities(
        for device: AVCaptureDevice,
        modes: [VideoMode],
        receiver: ReceiverCapabilities?,
        orientation: StreamRotation = .zero
    ) -> [CameraModeCapability] {
        modes.map { mode in
            guard let format = exactFormat(for: mode, on: device) else {
                return CameraModeCapability(mode: mode, supported: false, reason: "Camera format or FPS is unavailable", formatID: nil, supportedStabilization: [.off])
            }
            let stabilization = supportedStabilization(for: format, on: device)
            if let receiver,
               let geometry = mode.geometry,
               !receiver.supports(geometry, rotation: orientation) {
                return CameraModeCapability(
                    mode: mode,
                    supported: false,
                    reason: "Receiver geometry limit",
                    formatID: format.formatID,
                    formatWidth: format.width,
                    formatHeight: format.height,
                    supportedFrameRateRanges: format.supportedFrameRateRanges,
                    supportedStabilization: stabilization
                )
            }
            return CameraModeCapability(
                mode: mode,
                supported: true,
                reason: nil,
                formatID: format.formatID,
                formatWidth: format.width,
                formatHeight: format.height,
                supportedFrameRateRanges: format.supportedFrameRateRanges,
                supportedStabilization: stabilization
            )
        }
    }

    public func capabilitySnapshots(
        modes: [VideoMode],
        receiver: ReceiverCapabilities?,
        orientation: StreamRotation
    ) -> [CameraCapabilitySnapshot] {
        discoveryDevices()
            .filter { $0.position == .back }
            .map { device in
                let descriptor = descriptor(for: device)
                let modeCapabilities = capabilities(for: device, modes: modes, receiver: receiver, orientation: orientation)
                let stabilization = CameraStabilizationPreference.allCases.filter { preference in
                    modeCapabilities.contains { $0.supportedStabilization.contains(preference) }
                }
                return CameraCapabilitySnapshot(
                    device: descriptor,
                    minimumZoomRatio: Double(device.minAvailableVideoZoomFactor),
                    maximumZoomRatio: Double(device.maxAvailableVideoZoomFactor),
                    supportedStabilization: stabilization.isEmpty ? [.off] : stabilization,
                    modeCapabilities: modeCapabilities
                )
            }
    }

    public func exactFormat(for mode: VideoMode, on device: AVCaptureDevice) -> CameraFormatDescriptor? {
        let descriptors = device.formats.compactMap { format in
            let dimensions = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
            let ranges = format.videoSupportedFrameRateRanges
            return CameraFormatDescriptor(
                formatID: String(describing: ObjectIdentifier(format as AnyObject)),
                width: Int(dimensions.width),
                height: Int(dimensions.height),
                supportedFrameRateRanges: ranges.map { $0.minFrameRate...$0.maxFrameRate }
            )
        }
        return exactFormat(for: mode, in: descriptors)
    }

    public func exactFormat(for mode: VideoMode, in formats: [CameraFormatDescriptor]) -> CameraFormatDescriptor? {
        formats.first { format in
            format.width == mode.codedWidth &&
                format.height == mode.codedHeight &&
                format.supports(fps: mode.fps)
        }
    }

    public func device(withID identifier: String) -> AVCaptureDevice? {
        discoveryDevices().first(where: { $0.uniqueID == identifier })
    }

    private func supportedStabilization(for format: CameraFormatDescriptor, on device: AVCaptureDevice) -> [CameraStabilizationPreference] {
        guard let avFormat = device.formats.first(where: { String(describing: ObjectIdentifier($0 as AnyObject)) == format.formatID }) else {
            return [.off]
        }
        var supported = CameraStabilizationPreference.allCases.filter { preference in
            guard let mode = preference.avFoundationMode else { return false }
            return avFormat.isVideoStabilizationModeSupported(mode)
        }
        if !supported.contains(.off) {
            supported.insert(.off, at: .zero)
        }
        return supported
    }

    private func descriptor(for device: AVCaptureDevice) -> CameraDeviceDescriptor {
        CameraDeviceDescriptor(
            id: device.uniqueID,
            name: device.localizedName,
            position: device.position == .front ? .front : .back,
            isVirtual: device.deviceType == .builtInDualCamera ||
                device.deviceType == .builtInDualWideCamera ||
                device.deviceType == .builtInTripleCamera
        )
    }

    private func discoveryDevices() -> [AVCaptureDevice] {
        let discovery = AVCaptureDevice.DiscoverySession(
            deviceTypes: [
                .builtInWideAngleCamera,
                .builtInUltraWideCamera,
                .builtInTelephotoCamera,
                .builtInDualCamera,
                .builtInDualWideCamera,
                .builtInTripleCamera
            ],
            mediaType: .video,
            position: .unspecified
        )
        return discovery.devices
    }
}
