import Foundation
@preconcurrency import AVFoundation
import CamBridgeCore

public enum CameraAuthorizationState: Equatable, Sendable {
    case notDetermined
    case authorized
    case denied
    case restricted
}

public enum CameraPosition: String, Codable, Equatable, Sendable {
    case back
    case front
}

public struct CameraDeviceDescriptor: Identifiable, Codable, Equatable, Sendable {
    public let id: String
    public let name: String
    public let position: CameraPosition
    public let isVirtual: Bool

    public init(id: String, name: String, position: CameraPosition, isVirtual: Bool) {
        self.id = id
        self.name = name
        self.position = position
        self.isVirtual = isVirtual
    }
}

public struct CameraFormatDescriptor: Equatable, Sendable {
    public let formatID: String
    public let width: Int
    public let height: Int
    public let minimumFrameRate: Double
    public let maximumFrameRate: Double
    public let supportedFrameRateRanges: [ClosedRange<Double>]

    public init(
        formatID: String,
        width: Int,
        height: Int,
        minimumFrameRate: Double,
        maximumFrameRate: Double
    ) {
        self.init(
            formatID: formatID,
            width: width,
            height: height,
            supportedFrameRateRanges: [minimumFrameRate...maximumFrameRate]
        )
    }

    public init(
        formatID: String,
        width: Int,
        height: Int,
        supportedFrameRateRanges: [ClosedRange<Double>]
    ) {
        self.formatID = formatID
        self.width = width
        self.height = height
        self.supportedFrameRateRanges = supportedFrameRateRanges
        self.minimumFrameRate = supportedFrameRateRanges.map(\.lowerBound).min() ?? .zero
        self.maximumFrameRate = supportedFrameRateRanges.map(\.upperBound).max() ?? .zero
    }

    public func supports(fps: Int) -> Bool {
        let requested = Double(fps)
        return supportedFrameRateRanges.contains { $0.contains(requested) }
    }
}

public enum CameraStabilizationPreference: String, Codable, CaseIterable, Equatable, Sendable {
    case auto
    case off
    case standard
    case cinematic
    case cinematicExtended
    case previewOptimized
    case cinematicExtendedEnhanced

    public var displayName: String {
        switch self {
        case .auto:
            "Auto"
        case .off:
            "Off"
        case .standard:
            "Standard"
        case .cinematic:
            "Cinematic"
        case .cinematicExtended:
            "Cinematic Extended"
        case .previewOptimized:
            "Preview Optimized"
        case .cinematicExtendedEnhanced:
            "Cinematic Extended Enhanced"
        }
    }

    public init(mode: AVCaptureVideoStabilizationMode) {
        if #available(iOS 18.0, *), mode == .cinematicExtendedEnhanced {
            self = .cinematicExtendedEnhanced
            return
        }
        switch mode.rawValue {
        case AVCaptureVideoStabilizationMode.off.rawValue:
            self = .off
        case AVCaptureVideoStabilizationMode.standard.rawValue:
            self = .standard
        case AVCaptureVideoStabilizationMode.cinematic.rawValue:
            self = .cinematic
        case AVCaptureVideoStabilizationMode.cinematicExtended.rawValue:
            self = .cinematicExtended
        case AVCaptureVideoStabilizationMode.previewOptimized.rawValue:
            self = .previewOptimized
        case AVCaptureVideoStabilizationMode.auto.rawValue:
            self = .auto
        default:
            self = .auto
        }
    }

    public var avFoundationMode: AVCaptureVideoStabilizationMode? {
        switch self {
        case .auto:
            .auto
        case .off:
            .off
        case .standard:
            .standard
        case .cinematic:
            .cinematic
        case .cinematicExtended:
            .cinematicExtended
        case .previewOptimized:
            .previewOptimized
        case .cinematicExtendedEnhanced:
            guard #available(iOS 18.0, *) else { return nil }
            return .cinematicExtendedEnhanced
        }
    }
}

public enum CameraInterruptionReason: Equatable, Sendable {
    case videoDeviceInUse
    case videoDeviceNotAvailable
    case systemPressure
    case unknown
}

public enum CameraSystemPressureLevel: String, Codable, Equatable, Sendable {
    case nominal
    case fair
    case serious
    case critical
    case shutdown
    case unknown

    public init(level: AVCaptureDevice.SystemPressureState.Level) {
        switch level {
        case .nominal:
            self = .nominal
        case .fair:
            self = .fair
        case .serious:
            self = .serious
        case .critical:
            self = .critical
        case .shutdown:
            self = .shutdown
        default:
            self = .unknown
        }
    }

    public var requiresTerminalCleanup: Bool {
        switch self {
        case .serious, .critical, .shutdown:
            true
        case .nominal, .fair, .unknown:
            false
        }
    }
}

public struct CameraState: Equatable, Sendable {
    public var authorization: CameraAuthorizationState
    public var devices: [CameraDeviceDescriptor]
    public var selectedDeviceID: String?
    public var selectedFormat: CameraFormatDescriptor?
    public var zoomRatio: Double
    public var minimumZoomRatio: Double
    public var maximumZoomRatio: Double
    public var supportedStabilization: [CameraStabilizationPreference]
    public var activeStabilization: CameraStabilizationPreference
    public var interruption: CameraInterruptionReason?
    public var runtimeError: String?
    public var systemPressureLevel: CameraSystemPressureLevel?
    public var thermalState: String?

    public static var initial: CameraState {
        CameraState(
            authorization: .notDetermined,
            devices: [],
            selectedDeviceID: nil,
            selectedFormat: nil,
            zoomRatio: Self.defaultZoomRatio,
            minimumZoomRatio: Self.defaultZoomRatio,
            maximumZoomRatio: Self.defaultZoomRatio,
            supportedStabilization: [.auto, .off],
            activeStabilization: .auto,
            interruption: nil,
            runtimeError: nil,
            systemPressureLevel: nil,
            thermalState: nil
        )
    }

    static let defaultZoomRatio: Double = 1
}
