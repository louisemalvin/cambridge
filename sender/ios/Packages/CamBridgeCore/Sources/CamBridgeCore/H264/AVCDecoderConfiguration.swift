import Foundation

public struct AVCDecoderConfiguration: Equatable, Sendable {
    public let nalLengthBytes: Int
    public let sps: [Data]
    public let pps: [Data]

    public init(data: Data) throws {
        var cursor = Int.zero
        guard data.count >= Self.minimumHeaderBytes else { throw AVCDecoderConfigurationError.truncated }
        guard data[cursor] == Self.configurationVersion else {
            throw AVCDecoderConfigurationError.unsupportedVersion(data[cursor])
        }
        cursor += Self.oneByte
        cursor += Self.profileAndCompatibilityBytes
        cursor += Self.levelBytes
        let lengthField = Int(data[cursor] & Self.lengthFieldMask) + Self.oneByte
        cursor += Self.oneByte
        guard lengthField >= Self.minimumNALLengthBytes, lengthField <= Self.maximumNALLengthBytes else {
            throw AVCDecoderConfigurationError.unsupportedNALLengthBytes(lengthField)
        }
        let spsCount = Int(data[cursor] & Self.spsCountMask)
        cursor += Self.oneByte
        guard spsCount > .zero else { throw AVCDecoderConfigurationError.missingParameterSet("SPS") }
        var sps: [Data] = []
        sps.reserveCapacity(spsCount)
        for _ in Int.zero..<spsCount {
            let parameterSet = try Self.readParameterSet(data, cursor: &cursor)
            guard (try? H264NALUnit(data: parameterSet))?.isSPS == true else {
                throw AVCDecoderConfigurationError.invalidParameterSetType("SPS")
            }
            sps.append(parameterSet)
        }
        guard cursor < data.count else { throw AVCDecoderConfigurationError.truncated }
        let ppsCount = Int(data[cursor])
        cursor += Self.oneByte
        guard ppsCount > .zero else { throw AVCDecoderConfigurationError.missingParameterSet("PPS") }
        var pps: [Data] = []
        pps.reserveCapacity(ppsCount)
        for _ in Int.zero..<ppsCount {
            let parameterSet = try Self.readParameterSet(data, cursor: &cursor)
            guard (try? H264NALUnit(data: parameterSet))?.isPPS == true else {
                throw AVCDecoderConfigurationError.invalidParameterSetType("PPS")
            }
            pps.append(parameterSet)
        }
        guard cursor == data.count else { throw AVCDecoderConfigurationError.trailingData }
        nalLengthBytes = lengthField
        self.sps = sps
        self.pps = pps
    }

    public var parameterSets: [Data] { sps + pps }

    private static func readParameterSet(_ data: Data, cursor: inout Int) throws -> Data {
        guard data.count - cursor >= Self.parameterSetLengthBytes else {
            throw AVCDecoderConfigurationError.truncated
        }
        let length = Int(data[cursor]) << Self.byteShift | Int(data[data.index(cursor, offsetBy: Self.oneByte)])
        cursor += Self.parameterSetLengthBytes
        guard length > .zero, length <= data.count - cursor else {
            throw AVCDecoderConfigurationError.invalidParameterSetLength
        }
        let end = data.index(cursor, offsetBy: length)
        let result = Data(data[cursor..<end])
        cursor = end
        return result
    }

    private static let configurationVersion: UInt8 = 1
    private static let lengthFieldMask: UInt8 = 3
    private static let spsCountMask: UInt8 = 31
    private static let minimumHeaderBytes = 6
    private static let minimumNALLengthBytes = 1
    private static let maximumNALLengthBytes = 4
    private static let parameterSetLengthBytes = 2
    private static let profileAndCompatibilityBytes = 2
    private static let levelBytes = 1
    private static let oneByte = 1
    private static let byteShift = 8
}

public enum AVCDecoderConfigurationError: Error, Equatable, Sendable {
    case truncated
    case unsupportedVersion(UInt8)
    case unsupportedNALLengthBytes(Int)
    case missingParameterSet(String)
    case invalidParameterSetLength
    case invalidParameterSetType(String)
    case trailingData
}
