import Foundation

public enum VideoModeAvailability: String, Codable, Equatable, Hashable, Sendable {
    case product
    case testOnly = "test-only"
}

public struct VideoMode: Codable, Equatable, Hashable, Sendable {
    public let id: String
    public let codedWidth: Int
    public let codedHeight: Int
    public let fps: Int
    public let minimumBitrateBps: Int
    public let defaultBitrateBps: Int
    public let maximumBitrateBps: Int
    public let bitrateStepBps: Int
    public let keyframeIntervalSeconds: Int
    public let availability: VideoModeAvailability

    public init(
        id: String,
        codedWidth: Int,
        codedHeight: Int,
        fps: Int,
        minimumBitrateBps: Int,
        defaultBitrateBps: Int,
        maximumBitrateBps: Int,
        bitrateStepBps: Int,
        keyframeIntervalSeconds: Int,
        availability: VideoModeAvailability
    ) {
        self.id = id
        self.codedWidth = codedWidth
        self.codedHeight = codedHeight
        self.fps = fps
        self.minimumBitrateBps = minimumBitrateBps
        self.defaultBitrateBps = defaultBitrateBps
        self.maximumBitrateBps = maximumBitrateBps
        self.bitrateStepBps = bitrateStepBps
        self.keyframeIntervalSeconds = keyframeIntervalSeconds
        self.availability = availability
    }

    public var geometry: VideoGeometry? {
        try? VideoGeometry(codedWidth: codedWidth, codedHeight: codedHeight)
    }

    public func validate() throws {
        guard !id.isEmpty,
              codedWidth >= CamBridgeContract.Geometry.minimumDimension,
              codedHeight >= CamBridgeContract.Geometry.minimumDimension,
              codedWidth.isMultiple(of: CamBridgeContract.Geometry.dimensionAlignment),
              codedHeight.isMultiple(of: CamBridgeContract.Geometry.dimensionAlignment),
              fps >= CamBridgeContract.Video.minimumFps,
              fps <= CamBridgeContract.Video.maximumFps,
              minimumBitrateBps >= CamBridgeContract.Bitrate.minimumBps,
              maximumBitrateBps <= CamBridgeContract.Bitrate.maximumBps,
              minimumBitrateBps <= defaultBitrateBps,
              defaultBitrateBps <= maximumBitrateBps,
              bitrateStepBps > .zero,
              keyframeIntervalSeconds > .zero else {
            throw VideoModeError.invalid(id)
        }
        guard geometry != nil else {
            throw VideoModeError.invalidGeometry(id)
        }
    }

    public func steppedBitrates(encoderRange: ClosedRange<Int>) -> [Int] {
        let lowerBound = max(minimumBitrateBps, encoderRange.lowerBound, CamBridgeContract.Bitrate.minimumBps)
        let upperBound = min(maximumBitrateBps, encoderRange.upperBound, CamBridgeContract.Bitrate.maximumBps)
        guard lowerBound <= upperBound else { return [] }
        let first = ceilToStep(lowerBound)
        guard first <= upperBound else { return [] }
        return stride(from: first, through: floorToStep(upperBound), by: bitrateStepBps).map { $0 }
    }

    private func ceilToStep(_ value: Int) -> Int {
        let remainder = value % bitrateStepBps
        return remainder == .zero ? value : value + bitrateStepBps - remainder
    }

    private func floorToStep(_ value: Int) -> Int {
        value - (value % bitrateStepBps)
    }
}

public enum VideoModeError: Error, Equatable, Sendable {
    case invalid(String)
    case invalidGeometry(String)
}
