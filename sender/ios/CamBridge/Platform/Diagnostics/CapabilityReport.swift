import Foundation
import CamBridgeCore

public struct CapabilityReportFrameRateRange: Codable, Equatable, Sendable {
    public let minimum: Double
    public let maximum: Double

    public init(minimum: Double, maximum: Double) {
        self.minimum = minimum
        self.maximum = maximum
    }
}

public struct CapabilityReportReceiver: Codable, Equatable, Sendable {
    public let receiverId: String
    public let displayName: String
    public let maxLongEdge: Int
    public let maxShortEdge: Int
    public let host: String

    public init(
        receiverId: String,
        displayName: String,
        maxLongEdge: Int,
        maxShortEdge: Int,
        host: String = "[redacted]"
    ) {
        self.receiverId = receiverId
        self.displayName = displayName
        self.maxLongEdge = maxLongEdge
        self.maxShortEdge = maxShortEdge
        self.host = host
    }
}

public struct CapabilityReportMode: Codable, Equatable, Sendable {
    public let id: String
    public let codedWidth: Int
    public let codedHeight: Int
    public let fps: Int
    public let offered: Bool
    public let reason: String?
    public let formatId: String?
    public let formatWidth: Int?
    public let formatHeight: Int?
    public let frameRateRanges: [CapabilityReportFrameRateRange]
    public let encoderMinimumBitrateBps: Int?
    public let encoderMaximumBitrateBps: Int?
    public let encoderIdentity: String?
    public let encoderIdentityUnavailableReason: String?
    public let encoderUsesHardwareAccelerated: Bool?
    public let encoderHardwareAvailabilityReason: String?

    public init(
        capability: CameraModeCapability,
        encoder: EncoderCapability?
    ) {
        id = capability.mode.id
        codedWidth = capability.mode.codedWidth
        codedHeight = capability.mode.codedHeight
        fps = capability.mode.fps
        offered = capability.supported && encoder?.supported == true
        if !capability.supported {
            reason = capability.reason
        } else if encoder?.supported != true {
            reason = encoder?.reason ?? "Hardware H.264 encoder is unavailable"
        } else {
            reason = nil
        }
        formatId = capability.formatID
        formatWidth = capability.formatWidth
        formatHeight = capability.formatHeight
        frameRateRanges = capability.supportedFrameRateRanges.map {
            CapabilityReportFrameRateRange(minimum: $0.lowerBound, maximum: $0.upperBound)
        }
        encoderMinimumBitrateBps = encoder?.minimumBitrateBps ?? capability.encoderMinimumBitrateBps
        encoderMaximumBitrateBps = encoder?.maximumBitrateBps ?? capability.encoderMaximumBitrateBps
        encoderIdentity = encoder?.encoderIdentity ?? capability.encoderIdentity
        encoderIdentityUnavailableReason = encoder?.encoderIdentityUnavailableReason ?? capability.encoderIdentityUnavailableReason
        encoderUsesHardwareAccelerated = encoder?.encoderUsesHardwareAccelerated ?? capability.encoderUsesHardwareAccelerated
        encoderHardwareAvailabilityReason = encoder?.encoderHardwareAvailabilityReason ?? capability.encoderHardwareAvailabilityReason
    }
}

public struct CapabilityReportCamera: Codable, Equatable, Sendable {
    public let descriptor: CameraDeviceDescriptor
    public let minimumZoomRatio: Double
    public let maximumZoomRatio: Double
    public let supportedStabilization: [String]
    public let modes: [CapabilityReportMode]

    public init(snapshot: CameraCapabilitySnapshot, modes: [CapabilityReportMode]) {
        descriptor = snapshot.device
        minimumZoomRatio = snapshot.minimumZoomRatio
        maximumZoomRatio = snapshot.maximumZoomRatio
        supportedStabilization = snapshot.supportedStabilization.map(\.rawValue)
        self.modes = modes
    }
}

public struct CapabilityReport: Codable, Equatable, Sendable {
    public static let currentSchemaVersion = 1

    public let schemaVersion: Int
    public let generatedAt: Date
    public let appVersion: String
    public let buildVersion: String
    public let operatingSystem: String
    public let deviceModel: String
    public let cameraAuthorization: String
    public let cameras: [CapabilityReportCamera]
    public let selectedCameraID: String?
    public let receiver: CapabilityReportReceiver?
    public let selectedModeID: String?
    public let selectedBitrateBps: Int?
    public let selectedOrientationDegrees: Int?
    public let selectedStabilization: String?
    public let selectedReceiverDisplayName: String?

    public func copyableText() -> String {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        encoder.dateEncodingStrategy = .iso8601
        guard let data = try? encoder.encode(self) else { return "Capability report unavailable" }
        return String(decoding: data, as: UTF8.self)
    }
}

public struct CapabilityReportInput: Sendable {
    public let appVersion: String
    public let buildVersion: String
    public let operatingSystem: String
    public let deviceModel: String
    public let cameraAuthorization: String
    public let cameras: [CapabilityReportCamera]
    public let selectedCameraID: String?
    public let receiver: CapabilityReportReceiver?
    public let selectedModeID: String?
    public let selectedBitrateBps: Int?
    public let selectedOrientationDegrees: Int?
    public let selectedStabilization: String?
    public let selectedReceiverDisplayName: String?

    public init(
        appVersion: String,
        buildVersion: String,
        operatingSystem: String,
        deviceModel: String,
        cameraAuthorization: String,
        cameras: [CapabilityReportCamera],
        selectedCameraID: String?,
        receiver: CapabilityReportReceiver?,
        selectedModeID: String?,
        selectedBitrateBps: Int?,
        selectedOrientationDegrees: Int?,
        selectedStabilization: String?,
        selectedReceiverDisplayName: String?
    ) {
        self.appVersion = appVersion
        self.buildVersion = buildVersion
        self.operatingSystem = operatingSystem
        self.deviceModel = deviceModel
        self.cameraAuthorization = cameraAuthorization
        self.cameras = cameras
        self.selectedCameraID = selectedCameraID
        self.receiver = receiver
        self.selectedModeID = selectedModeID
        self.selectedBitrateBps = selectedBitrateBps
        self.selectedOrientationDegrees = selectedOrientationDegrees
        self.selectedStabilization = selectedStabilization
        self.selectedReceiverDisplayName = selectedReceiverDisplayName
    }
}

public protocol CapabilityReportBuilding: Sendable {
    func build(_ input: CapabilityReportInput) -> CapabilityReport
}

public struct CapabilityReportBuilder: CapabilityReportBuilding {
    private let clock: @Sendable () -> Date

    public init(clock: @escaping @Sendable () -> Date = { Date() }) {
        self.clock = clock
    }

    public func build(_ input: CapabilityReportInput) -> CapabilityReport {
        CapabilityReport(
            schemaVersion: CapabilityReport.currentSchemaVersion,
            generatedAt: clock(),
            appVersion: input.appVersion,
            buildVersion: input.buildVersion,
            operatingSystem: input.operatingSystem,
            deviceModel: input.deviceModel,
            cameraAuthorization: input.cameraAuthorization,
            cameras: input.cameras,
            selectedCameraID: input.selectedCameraID,
            receiver: input.receiver,
            selectedModeID: input.selectedModeID,
            selectedBitrateBps: input.selectedBitrateBps,
            selectedOrientationDegrees: input.selectedOrientationDegrees,
            selectedStabilization: input.selectedStabilization,
            selectedReceiverDisplayName: input.selectedReceiverDisplayName
        )
    }
}
