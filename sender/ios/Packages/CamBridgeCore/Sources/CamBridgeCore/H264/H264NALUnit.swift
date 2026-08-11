import Foundation

public struct H264NALUnit: Equatable, Sendable {
    public let header: UInt8
    public let data: Data

    public init(data: Data) throws {
        guard let header = data.first else { throw H264NALUnitError.empty }
        let nalType = Int(header & Self.nalTypeMask)
        guard nalType != .zero else { throw H264NALUnitError.invalidType }
        self.header = header
        self.data = data
    }

    public var nalType: Int { Int(header & Self.nalTypeMask) }
    public var nalReferenceIdc: UInt8 { header & Self.nalReferenceMask }
    public var isIDR: Bool { nalType == Self.idrType }
    public var isSPS: Bool { nalType == Self.spsType }
    public var isPPS: Bool { nalType == Self.ppsType }

    private static let nalTypeMask: UInt8 = 31
    private static let nalReferenceMask: UInt8 = 224
    private static let idrType = 5
    private static let spsType = 7
    private static let ppsType = 8
}

public enum H264NALUnitError: Error, Equatable, Sendable {
    case empty
    case invalidType
}
