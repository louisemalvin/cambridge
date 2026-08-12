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

    public var opposite: CameraPosition {
        self == .back ? .front : .back
    }
}

public enum CameraZoomPolicy {
    public static let ultraWideTarget = 0.5
    public static let normalTarget = 1.0
    public static let telephotoTarget = 2.0
    // Keep pinch zoom useful without exposing device-specific raw maxima such
    // as 100× or more as normal product UI.
    public static let maximumUserZoomRatio = 10.0
    public static let candidateTargets = [ultraWideTarget, normalTarget, telephotoTarget]
}

public struct CameraZoomMapping: Equatable, Sendable {
    public let rawMinimum: Double
    public let rawMaximum: Double
    public let displayMultiplier: Double

    public init(rawMinimum: Double, rawMaximum: Double, displayMultiplier: Double) {
        self.rawMinimum = rawMinimum
        self.rawMaximum = rawMaximum
        self.displayMultiplier = displayMultiplier
    }

    public var minimumUserRatio: Double {
        rawMinimum * displayMultiplier
    }

    public var maximumUserRatio: Double {
        min(rawMaximum * displayMultiplier, CameraZoomPolicy.maximumUserZoomRatio)
    }

    public var targets: [Double] {
        CameraZoomPolicy.candidateTargets.filter { target in
            target >= minimumUserRatio && target <= maximumUserRatio
        }
    }

    public func rawRatio(forUserRatio ratio: Double) -> Double {
        let bounded = min(max(ratio, minimumUserRatio), maximumUserRatio)
        return bounded / displayMultiplier
    }

    public func userRatio(forRawRatio ratio: Double) -> Double {
        ratio * displayMultiplier
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
    case unknown

    public init(mode: AVCaptureVideoStabilizationMode) {
        if #available(iOS 18.0, *), mode == .cinematicExtendedEnhanced {
            self = CameraStabilizationPreference.cinematicExtendedEnhanced
            return
        }
        switch mode.rawValue {
        case AVCaptureVideoStabilizationMode.off.rawValue:
            self = CameraStabilizationPreference.off
        case AVCaptureVideoStabilizationMode.standard.rawValue:
            self = CameraStabilizationPreference.standard
        case AVCaptureVideoStabilizationMode.cinematic.rawValue:
            self = CameraStabilizationPreference.cinematic
        case AVCaptureVideoStabilizationMode.cinematicExtended.rawValue:
            self = CameraStabilizationPreference.cinematicExtended
        case AVCaptureVideoStabilizationMode.previewOptimized.rawValue:
            self = CameraStabilizationPreference.previewOptimized
        case AVCaptureVideoStabilizationMode.auto.rawValue:
            self = CameraStabilizationPreference.auto
        default:
            self = CameraStabilizationPreference.unknown
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
    public var position: CameraPosition
    public var selectedDeviceID: String?
    public var selectedFormat: CameraFormatDescriptor?
    public var zoomRatio: Double
    public var minimumZoomRatio: Double
    public var maximumZoomRatio: Double
    public var zoomTargets: [Double]
    public var activeStabilization: CameraStabilizationPreference
    public var interruption: CameraInterruptionReason?
    public var runtimeError: String?
    public var systemPressureLevel: CameraSystemPressureLevel?
    public var thermalState: String?

    public static var initial: CameraState {
        CameraState(
            authorization: .notDetermined,
            position: .back,
            selectedDeviceID: nil,
            selectedFormat: nil,
            zoomRatio: CameraZoomPolicy.normalTarget,
            minimumZoomRatio: CameraZoomPolicy.normalTarget,
            maximumZoomRatio: CameraZoomPolicy.normalTarget,
            zoomTargets: [CameraZoomPolicy.normalTarget],
            activeStabilization: .off,
            interruption: nil,
            runtimeError: nil,
            systemPressureLevel: nil,
            thermalState: nil
        )
    }
}
