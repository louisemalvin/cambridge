import Foundation

public struct H264AccessUnitNormalizer: Sendable {
    public init() {}

    public func normalize(
        sample: Data,
        nalLengthBytes: Int,
        parameterSets: [Data],
        isKeyframe: Bool
    ) throws -> Data {
        guard !sample.isEmpty else { throw H264AccessUnitNormalizerError.emptySample }
        guard nalLengthBytes >= Self.minimumNALLengthBytes, nalLengthBytes <= Self.maximumNALLengthBytes else {
            throw H264AccessUnitNormalizerError.unsupportedNALLengthBytes(nalLengthBytes)
        }
        guard sample.count <= CamBridgeContract.Media.maxAccessUnitBytes else {
            throw H264AccessUnitNormalizerError.oversizedSample(sample.count)
        }
        let parsedParameterSets = try parameterSets.map(H264NALUnit.init(data:))
        if isKeyframe && parsedParameterSets.isEmpty {
            throw H264AccessUnitNormalizerError.missingParameterSets
        }

        var parameterSetBytes = Int.zero
        for parameterSet in parsedParameterSets {
            let requiredBytes = Self.startCode.count + parameterSet.data.count
            guard requiredBytes <= CamBridgeContract.Media.maxAccessUnitBytes - parameterSetBytes else {
                throw H264AccessUnitNormalizerError.oversizedOutput
            }
            parameterSetBytes += requiredBytes
        }
        var output = Data()
        output.reserveCapacity(min(CamBridgeContract.Media.maxAccessUnitBytes, sample.count + parameterSetBytes))
        if isKeyframe {
            for parameterSet in parsedParameterSets {
                try append(parameterSet.data, to: &output)
            }
        }

        var cursor = Int.zero
        var nalCount = Int.zero
        while cursor < sample.count {
            guard sample.count - cursor >= nalLengthBytes else {
                throw H264AccessUnitNormalizerError.truncatedLengthPrefix
            }
            let length = try readLength(sample, offset: cursor, byteCount: nalLengthBytes)
            cursor += nalLengthBytes
            guard length > .zero, length <= sample.count - cursor else {
                throw H264AccessUnitNormalizerError.truncatedNAL
            }
            let end = sample.index(cursor, offsetBy: length)
            let nal = Data(sample[cursor..<end])
            _ = try H264NALUnit(data: nal)
            try append(nal, to: &output)
            cursor = end
            nalCount += Self.oneNAL
        }
        guard nalCount > .zero else { throw H264AccessUnitNormalizerError.emptySample }
        return output
    }

    public func normalize(
        sample: Data,
        configuration: AVCDecoderConfiguration,
        isKeyframe: Bool
    ) throws -> Data {
        try normalize(
            sample: sample,
            nalLengthBytes: configuration.nalLengthBytes,
            parameterSets: configuration.parameterSets,
            isKeyframe: isKeyframe
        )
    }

    private func readLength(_ data: Data, offset: Int, byteCount: Int) throws -> Int {
        var length: UInt64 = .zero
        for byteOffset in 0..<byteCount {
            length = (length << Self.byteShift) | UInt64(data[data.index(offset, offsetBy: byteOffset)])
            guard length <= UInt64(Int.max) else { throw H264AccessUnitNormalizerError.lengthOverflow }
        }
        return Int(length)
    }

    private func append(_ nalData: Data, to output: inout Data) throws {
        guard output.count <= CamBridgeContract.Media.maxAccessUnitBytes - Self.startCode.count - nalData.count else {
            throw H264AccessUnitNormalizerError.oversizedOutput
        }
        output.append(Self.startCode)
        output.append(nalData)
    }

    private static let startCode: Data = Data([.zero, .zero, .zero, startCodeTerminator])
    private static let minimumNALLengthBytes = 1
    private static let maximumNALLengthBytes = 4
    private static let byteShift = 8
    private static let oneNAL = 1
    private static let startCodeTerminator: UInt8 = 1
}

public enum H264AccessUnitNormalizerError: Error, Equatable, Sendable {
    case emptySample
    case unsupportedNALLengthBytes(Int)
    case oversizedSample(Int)
    case missingParameterSets
    case oversizedOutput
    case truncatedLengthPrefix
    case truncatedNAL
    case lengthOverflow
}
