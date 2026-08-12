import Foundation
import CamBridgeCore

public struct DiagnosticsReport: Codable, Equatable, Sendable {
    public let runId: String
    public let appVersion: String
    public let buildVersion: String
    public let protocolVersion: Int
    public let sessionId: String?
    public let generation: UInt64?
    public let operatingSystem: String
    public let deviceModel: String
    public let receiverId: String?
    public let receiverHost: String?
    public let cameraID: String?
    public let cameraPosition: String?
    public let cameraFormatID: String?
    public let cameraFormatWidth: Int?
    public let cameraFormatHeight: Int?
    public let cameraMinimumFrameRate: Double?
    public let cameraMaximumFrameRate: Double?
    public let cameraZoomRatio: Double?
    public let systemPressureLevel: String?
    public let thermalState: String?
    public let modeId: String?
    public let codedWidth: Int?
    public let codedHeight: Int?
    public let displayWidth: Int?
    public let displayHeight: Int?
    public let rotationDegrees: Int?
    public let fps: Int?
    public let bitrateBps: Int?
    public let requestedStabilization: String?
    public let activeStabilization: String?
    public let encoderIdentity: String?
    public let encoderIdentityUnavailableReason: String?
    public let encoderUsesHardwareAccelerated: Bool?
    public let encoderHardwareAvailabilityReason: String?
    public let encoderAdvisoryPropertyFailures: [String]
    public let encodedAccessUnits: Int
    public let encodedKeyframes: Int
    public let encodedBytes: Int
    public let queueOccupancy: Int
    public let queueMaximumOccupancy: Int
    public let queueDrops: Int
    public let rtpPacketsSent: Int
    public let rtpBytesSent: Int
    public let udpFailures: Int
    public let maximumSendDurationNanoseconds: UInt64
    public let terminalFailure: StreamFailure?
    public let stateTransitions: [String]
    public let firstEncodedAccessUnit: Bool
    public let firstSentRTPAccessUnit: Bool
    public let encodedFrameCadenceFps: Double?
    public let encoderRequiresHardware: Bool?
    public let encoderRealTime: Bool?
    public let encoderFrameReorderingDisabled: Bool?
    public let encoderKeyframeIntervalSeconds: Int?

    public init(
        runId: String,
        appVersion: String,
        buildVersion: String,
        deviceModel: String,
        identity: SessionIdentity?,
        receiver: ReceiverEndpoint?,
        cameraState: CameraState?,
        configuration: StreamConfiguration?,
        requestedStabilization: String?,
        activeStabilization: String?,
        encoderIdentity: String?,
        encoderIdentityUnavailableReason: String?,
        encoderUsesHardwareAccelerated: Bool?,
        encoderHardwareAvailabilityReason: String?,
        encoderAdvisoryPropertyFailures: [String],
        encodedAccessUnits: Int,
        encodedKeyframes: Int,
        encodedBytes: Int,
        queueOccupancy: Int,
        queueMaximumOccupancy: Int,
        queueDrops: Int,
        rtpPacketsSent: Int,
        rtpBytesSent: Int,
        udpFailures: Int,
        maximumSendDurationNanoseconds: UInt64,
        encoderMetricsFirstPresentationTime: Int64?,
        encoderMetricsLastPresentationTime: Int64?,
        terminalFailure: StreamFailure?,
        stateTransitions: [String]
    ) {
        self.runId = runId
        self.appVersion = appVersion
        self.buildVersion = buildVersion
        self.protocolVersion = CamBridgeContract.protocolVersion
        self.sessionId = identity?.sessionId
        self.generation = identity?.generation
        self.operatingSystem = ProcessInfo.processInfo.operatingSystemVersionString
        self.deviceModel = deviceModel
        self.receiverId = receiver?.receiverId
        self.receiverHost = receiver.map(Self.redactedHost)
        self.cameraID = cameraState?.selectedDeviceID
        self.cameraPosition = cameraState?.position.rawValue
        self.cameraFormatID = cameraState?.selectedFormat?.formatID
        self.cameraFormatWidth = cameraState?.selectedFormat?.width
        self.cameraFormatHeight = cameraState?.selectedFormat?.height
        self.cameraMinimumFrameRate = cameraState?.selectedFormat?.minimumFrameRate
        self.cameraMaximumFrameRate = cameraState?.selectedFormat?.maximumFrameRate
        self.cameraZoomRatio = cameraState?.zoomRatio
        self.systemPressureLevel = cameraState?.systemPressureLevel?.rawValue
        self.thermalState = cameraState?.thermalState
        self.modeId = configuration.map { _ in SenderVideoCatalog.profileID }
        self.codedWidth = configuration?.geometry.codedWidth
        self.codedHeight = configuration?.geometry.codedHeight
        self.displayWidth = configuration.map { $0.geometry.displayDimensions(for: $0.orientation).width }
        self.displayHeight = configuration.map { $0.geometry.displayDimensions(for: $0.orientation).height }
        self.rotationDegrees = configuration?.orientation.degrees
        self.fps = configuration?.fps
        self.bitrateBps = configuration?.bitrateBps
        self.requestedStabilization = requestedStabilization
        self.activeStabilization = activeStabilization
        self.encoderIdentity = encoderIdentity
        self.encoderIdentityUnavailableReason = encoderIdentityUnavailableReason
        self.encoderUsesHardwareAccelerated = encoderUsesHardwareAccelerated
        self.encoderHardwareAvailabilityReason = encoderHardwareAvailabilityReason
        self.encoderAdvisoryPropertyFailures = encoderAdvisoryPropertyFailures
        self.encodedAccessUnits = encodedAccessUnits
        self.encodedKeyframes = encodedKeyframes
        self.encodedBytes = encodedBytes
        self.queueOccupancy = queueOccupancy
        self.queueMaximumOccupancy = queueMaximumOccupancy
        self.queueDrops = queueDrops
        self.rtpPacketsSent = rtpPacketsSent
        self.rtpBytesSent = rtpBytesSent
        self.udpFailures = udpFailures
        self.maximumSendDurationNanoseconds = maximumSendDurationNanoseconds
        self.terminalFailure = terminalFailure.map(Self.redactedFailure)
        self.stateTransitions = stateTransitions
        self.firstEncodedAccessUnit = encodedAccessUnits > .zero
        self.firstSentRTPAccessUnit = rtpPacketsSent > .zero
        self.encodedFrameCadenceFps = Self.frameCadence(
            count: encodedAccessUnits,
            firstPresentationTimeMicroseconds: encoderMetricsFirstPresentationTime,
            lastPresentationTimeMicroseconds: encoderMetricsLastPresentationTime
        )
        self.encoderRequiresHardware = configuration.map { _ in true }
        self.encoderRealTime = configuration.map { _ in true }
        self.encoderFrameReorderingDisabled = configuration.map { _ in true }
        self.encoderKeyframeIntervalSeconds = configuration.map { _ in SenderVideoCatalog.keyframeIntervalSeconds }
    }

    public func copyableText() -> String {
        guard let data = try? JSONEncoder().encode(self) else { return "Diagnostics unavailable" }
        return String(decoding: data, as: UTF8.self)
    }

    private static func redactedHost(_: ReceiverEndpoint) -> String {
        return "[redacted]"
    }

    private static func redactedFailure(_ failure: StreamFailure) -> StreamFailure {
        switch failure {
        case .controlConnectionFailed:
            .controlConnectionFailed("control connection failed")
        case .transportFailed:
            .transportFailed("media transport failed")
        case .receiverRejected:
            .receiverRejected("receiver rejected the selected stream")
        case .unexpected:
            .unexpected("unexpected stream failure")
        default:
            failure
        }
    }

    private static func frameCadence(
        count: Int,
        firstPresentationTimeMicroseconds: Int64?,
        lastPresentationTimeMicroseconds: Int64?
    ) -> Double? {
        guard count > Self.minimumCadenceSamples,
              let firstPresentationTimeMicroseconds,
              let lastPresentationTimeMicroseconds,
              lastPresentationTimeMicroseconds > firstPresentationTimeMicroseconds else {
            return nil
        }
        let frameCount = count - Self.oneSample
        let elapsedMicroseconds = lastPresentationTimeMicroseconds - firstPresentationTimeMicroseconds
        return Double(frameCount) * Double(Self.microsecondsPerSecond) / Double(elapsedMicroseconds)
    }

    private static let minimumCadenceSamples = 1
    private static let oneSample = 1
    private static let microsecondsPerSecond: Int64 = 1_000_000
}
