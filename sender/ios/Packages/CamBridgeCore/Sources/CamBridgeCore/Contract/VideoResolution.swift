import Foundation

public struct VideoResolution: Codable, Equatable, Hashable, Sendable {
    public let id: String
    public let displayName: String
    public let codedWidth: Int
    public let codedHeight: Int

    public init(id: String, displayName: String, codedWidth: Int, codedHeight: Int) {
        self.id = id
        self.displayName = displayName
        self.codedWidth = codedWidth
        self.codedHeight = codedHeight
    }

    public var geometry: VideoGeometry? {
        try? VideoGeometry(codedWidth: codedWidth, codedHeight: codedHeight)
    }

    public func validate() throws {
        guard !id.isEmpty, !displayName.isEmpty, geometry != nil else {
            throw VideoResolutionError.invalid(id)
        }
    }
}

public enum VideoResolutionError: Error, Equatable, Sendable {
    case invalid(String)
}

public enum BitrateInput {
    public static func bitsPerSecond(fromWholeMegabits text: String) -> Int? {
        guard !text.isEmpty,
              text.unicodeScalars.allSatisfy(CharacterSet.decimalDigits.contains),
              let megabits = Int(text),
              megabits >= SenderVideoCatalog.minimumBitrateMbps,
              megabits <= SenderVideoCatalog.maximumBitrateMbps else {
            return nil
        }
        let (bitsPerSecond, overflow) = megabits.multipliedReportingOverflow(by: SenderVideoCatalog.bitrateUnitBps)
        return overflow ? nil : bitsPerSecond
    }

    public static func wholeMegabits(fromBitsPerSecond bitsPerSecond: Int) -> String? {
        guard bitsPerSecond > .zero,
              bitsPerSecond.isMultiple(of: SenderVideoCatalog.bitrateUnitBps) else {
            return nil
        }
        let megabits = bitsPerSecond / SenderVideoCatalog.bitrateUnitBps
        guard megabits >= SenderVideoCatalog.minimumBitrateMbps,
              megabits <= SenderVideoCatalog.maximumBitrateMbps else {
            return nil
        }
        return String(megabits)
    }
}
