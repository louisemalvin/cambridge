import Foundation
@preconcurrency import AVFoundation
import CoreMedia
import CamBridgeCore

public struct CameraModeCapability: Equatable, Sendable {
    public let mode: VideoMode
    public let supported: Bool
    public let reason: String?
    public let formatID: String?
    public let supportedStabilization: [CameraStabilizationPreference]
    public let encoderMinimumBitrateBps: Int?
    public let encoderMaximumBitrateBps: Int?

    public init(
        mode: VideoMode,
        supported: Bool,
        reason: String?,
        formatID: String?,
        supportedStabilization: [CameraStabilizationPreference] = [.off],
        encoderMinimumBitrateBps: Int? = nil,
        encoderMaximumBitrateBps: Int? = nil
    ) {
        self.mode = mode
        self.supported = supported
        self.reason = reason
        self.formatID = formatID
        self.supportedStabilization = supportedStabilization
        self.encoderMinimumBitrateBps = encoderMinimumBitrateBps
        self.encoderMaximumBitrateBps = encoderMaximumBitrateBps
    }
}

public struct CameraCapabilityProbe: Sendable {
    public init() {}

    public func rearCameraDescriptors() -> [CameraDeviceDescriptor] {
        discoveryDevices().compactMap { device in
            guard device.position == .back else { return nil }
            return CameraDeviceDescriptor(
                id: device.uniqueID,
                name: device.localizedName,
                position: .back,
                isVirtual: device.deviceType == .builtInDualWideCamera || device.deviceType == .builtInTripleCamera
            )
        }
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
                return CameraModeCapability(mode: mode, supported: false, reason: "Receiver geometry limit", formatID: format.formatID, supportedStabilization: stabilization)
            }
            return CameraModeCapability(mode: mode, supported: true, reason: nil, formatID: format.formatID, supportedStabilization: stabilization)
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
