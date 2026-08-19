import Foundation
@preconcurrency import AVFoundation
import CoreMedia
import CamBridgeCore

public struct CameraFormatSelection {
    public let format: AVCaptureDevice.Format
    public let descriptor: CameraFormatDescriptor
}

public struct CameraDeviceSelector: Sendable {
    public init() {}

    public func defaultDevice(position: CameraPosition) -> AVCaptureDevice? {
        let avPosition: AVCaptureDevice.Position = position == .front ? .front : .back
        let preferredTypes: [AVCaptureDevice.DeviceType]
        switch position {
        case .back:
            preferredTypes = [
                .builtInTripleCamera,
                .builtInDualWideCamera,
                .builtInDualCamera,
                .builtInWideAngleCamera,
            ]
        case .front:
            preferredTypes = [
                .builtInTrueDepthCamera,
                .builtInWideAngleCamera,
            ]
        }
        return preferredTypes.lazy.compactMap { deviceType in
            AVCaptureDevice.default(deviceType, for: .video, position: avPosition)
        }.first
    }

    public func compatibleFormat(
        for resolution: VideoResolution,
        fps: Int,
        on device: AVCaptureDevice
    ) -> CameraFormatSelection? {
        let selections = device.formats.enumerated().map { index, format -> CameraFormatSelection in
            let dimensions = CMVideoFormatDescriptionGetDimensions(format.formatDescription)
            let ranges = format.videoSupportedFrameRateRanges.map { $0.minFrameRate...$0.maxFrameRate }
            let descriptor = CameraFormatDescriptor(
                formatID: "format-\(index)",
                width: Int(dimensions.width),
                height: Int(dimensions.height),
                supportedFrameRateRanges: ranges
            )
            return CameraFormatSelection(format: format, descriptor: descriptor)
        }
        guard let selectedDescriptor = compatibleFormatDescriptor(
            for: resolution,
            fps: fps,
            in: selections.map(\.descriptor)
        ) else {
            return nil
        }
        return selections.first { $0.descriptor.formatID == selectedDescriptor.formatID }
    }

    public func compatibleFormatDescriptor(
        for resolution: VideoResolution,
        fps: Int,
        in formats: [CameraFormatDescriptor]
    ) -> CameraFormatDescriptor? {
        formats.filter { format in
            format.width >= resolution.codedWidth
                && format.height >= resolution.codedHeight
                && format.width * resolution.codedHeight == format.height * resolution.codedWidth
                && format.supports(fps: fps)
        }.min { lhs, rhs in
            let lhsPixels = lhs.width * lhs.height
            let rhsPixels = rhs.width * rhs.height
            if lhsPixels != rhsPixels { return lhsPixels < rhsPixels }
            return lhs.formatID < rhs.formatID
        }
    }

}
