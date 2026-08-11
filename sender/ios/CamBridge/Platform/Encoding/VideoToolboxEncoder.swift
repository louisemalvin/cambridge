import Foundation
@preconcurrency import AVFoundation
import CoreMedia
import CoreVideo
@preconcurrency import VideoToolbox
import CamBridgeCore

public enum VideoToolboxEncoderError: Error, Equatable, Sendable {
    case alreadyPrepared
    case notPrepared
    case hardwareEncoderUnavailable
    case createFailed(OSStatus)
    case supportedPropertiesFailed(OSStatus)
    case unsupportedBitrateRange
    case propertyFailed(String, OSStatus)
    case prepareFailed(OSStatus)
    case frameFailed(OSStatus)
    case outputFailed(OSStatus)
    case invalidDataRateLimit
    case dataRateLimitOverflow
    case missingFormatDescription
    case missingParameterSets
    case malformedSample
    case oversizedSample(Int)
}

public struct VideoToolboxEncoderMetrics: Equatable, Sendable {
    public let encoderIdentity: String?
    public let encoderIdentityUnavailableReason: String?
    public let encoderUsesHardwareAccelerated: Bool?
    public let encoderHardwareAvailabilityReason: String?
    public let encodedAccessUnits: Int
    public let encodedKeyframes: Int
    public let encodedBytes: Int
    public let firstPresentationTimeMicroseconds: Int64?
    public let lastPresentationTimeMicroseconds: Int64?
}

public struct VideoToolboxEncoderCapability: Equatable, Sendable {
    public let bitrateRange: ClosedRange<Int>
    public let encoderIdentity: String?
    public let encoderIdentityUnavailableReason: String?
}

public final class VideoToolboxEncoder {
    public let inputQueue: DispatchQueue
    public var onAccessUnit: (@Sendable (Result<EncodedAccessUnit, VideoToolboxEncoderError>) -> Void)?

    private var compressionSession: VTCompressionSession?
    private var configuration: StreamConfiguration?
    private var nalLengthBytes: Int?
    private var parameterSets: [Data] = []
    private var encoderIdentity: String?
    private var encoderIdentityUnavailableReason: String?
    private var encoderUsesHardwareAccelerated: Bool?
    private var encoderHardwareAvailabilityReason: String?
    private var encodedAccessUnits = Int.zero
    private var encodedKeyframes = Int.zero
    private var encodedBytes = Int.zero
    private var firstPresentationTimeMicroseconds: Int64?
    private var lastPresentationTimeMicroseconds: Int64?

    public init(queueLabel: String = "dev.cambridge.sender.encoder") {
        inputQueue = DispatchQueue(label: queueLabel, qos: .userInitiated)
        inputQueue.setSpecific(key: Self.inputQueueKey, value: Self.inputQueueMarker)
    }

    public static func supportedBitrateRange(for mode: VideoMode) throws -> ClosedRange<Int> {
        try supportedEncoderCapability(for: mode).bitrateRange
    }

    public static func supportedEncoderCapability(for mode: VideoMode) throws -> VideoToolboxEncoderCapability {
        guard let geometry = mode.geometry else {
            throw VideoToolboxEncoderError.unsupportedBitrateRange
        }
        let encoderSpecification = Self.hardwareEncoderSpecification()
        var encoderID: CFString?
        var supportedProperties: CFDictionary?
        let status = VTCopySupportedPropertyDictionaryForEncoder(
            width: Int32(geometry.codedWidth),
            height: Int32(geometry.codedHeight),
            codecType: kCMVideoCodecType_H264,
            encoderSpecification: encoderSpecification as CFDictionary,
            encoderIDOut: &encoderID,
            supportedPropertiesOut: &supportedProperties
        )
        guard status == noErr else {
            throw VideoToolboxEncoderError.supportedPropertiesFailed(status)
        }
        guard let supportedProperties else {
            throw VideoToolboxEncoderError.unsupportedBitrateRange
        }
        let propertyDictionary = supportedProperties as NSDictionary
        guard let bitrateProperty = propertyDictionary[kVTCompressionPropertyKey_AverageBitRate as String] as? NSDictionary else {
            throw VideoToolboxEncoderError.unsupportedBitrateRange
        }
        if let minimum = Self.number(
            in: bitrateProperty,
            forKey: kVTPropertySupportedValueMinimumKey
        ), let maximum = Self.number(
            in: bitrateProperty,
            forKey: kVTPropertySupportedValueMaximumKey
        ), minimum <= maximum {
            return VideoToolboxEncoderCapability(
                bitrateRange: minimum...maximum,
                encoderIdentity: encoderID.map { $0 as String },
                encoderIdentityUnavailableReason: encoderID == nil ? "VideoToolbox returned no encoder identifier" : nil
            )
        }
        if let values = bitrateProperty[kVTPropertySupportedValueListKey as String] as? [NSNumber],
           let minimum = values.map(\.intValue).min(),
           let maximum = values.map(\.intValue).max(),
           minimum <= maximum {
            return VideoToolboxEncoderCapability(
                bitrateRange: minimum...maximum,
                encoderIdentity: encoderID.map { $0 as String },
                encoderIdentityUnavailableReason: encoderID == nil ? "VideoToolbox returned no encoder identifier" : nil
            )
        }
        throw VideoToolboxEncoderError.unsupportedBitrateRange
    }

    deinit {
        invalidate()
    }

    public func prepare(configuration: StreamConfiguration) throws {
        try inputQueue.sync {
            guard compressionSession == nil else { throw VideoToolboxEncoderError.alreadyPrepared }
            var session: VTCompressionSession?
            let encoderSpecification = Self.hardwareEncoderSpecification()
            let imageAttributes: [String: Any] = [
                kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
                kCVPixelBufferWidthKey as String: configuration.geometry.codedWidth,
                kCVPixelBufferHeightKey as String: configuration.geometry.codedHeight,
                kCVImageBufferColorPrimariesKey as String: kCVImageBufferColorPrimaries_ITU_R_709_2,
                kCVImageBufferTransferFunctionKey as String: kCVImageBufferTransferFunction_ITU_R_709_2,
                kCVImageBufferYCbCrMatrixKey as String: kCVImageBufferYCbCrMatrix_ITU_R_709_2
            ]
            let status = VTCompressionSessionCreate(
                allocator: nil,
                width: Int32(configuration.geometry.codedWidth),
                height: Int32(configuration.geometry.codedHeight),
                codecType: kCMVideoCodecType_H264,
                encoderSpecification: encoderSpecification as CFDictionary,
                imageBufferAttributes: imageAttributes as CFDictionary,
                compressedDataAllocator: nil,
                outputCallback: videoToolboxOutputCallback,
                refcon: Unmanaged.passUnretained(self).toOpaque(),
                compressionSessionOut: &session
            )
            guard status == noErr, let session else {
                throw status == kVTVideoEncoderNotAvailableNowErr
                    ? VideoToolboxEncoderError.hardwareEncoderUnavailable
                    : VideoToolboxEncoderError.createFailed(status)
            }
            compressionSession = session
            self.configuration = configuration
            encodedAccessUnits = .zero
            encodedKeyframes = .zero
            encodedBytes = .zero
            firstPresentationTimeMicroseconds = nil
            lastPresentationTimeMicroseconds = nil
            encoderIdentity = nil
            encoderIdentityUnavailableReason = nil
            encoderUsesHardwareAccelerated = nil
            encoderHardwareAvailabilityReason = nil
            do {
                try setProperties(session: session, configuration: configuration)
                let prepareStatus = VTCompressionSessionPrepareToEncodeFrames(session)
                guard prepareStatus == noErr else { throw VideoToolboxEncoderError.prepareFailed(prepareStatus) }
                readSessionProperties(session)
                guard encoderUsesHardwareAccelerated == true else {
                    throw VideoToolboxEncoderError.hardwareEncoderUnavailable
                }
            } catch {
                VTCompressionSessionInvalidate(session)
                compressionSession = nil
                self.configuration = nil
                throw error
            }
        }
    }

    public func submit(sampleBuffer: CMSampleBuffer) {
        dispatchPrecondition(condition: .onQueue(inputQueue))
        guard let session = compressionSession, let imageBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else {
            onAccessUnit?(.failure(.notPrepared))
            return
        }
        let presentationTime = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        let status = VTCompressionSessionEncodeFrame(
            session,
            imageBuffer: imageBuffer,
            presentationTimeStamp: presentationTime,
            duration: .invalid,
            frameProperties: nil,
            sourceFrameRefcon: nil,
            infoFlagsOut: nil
        )
        if status != noErr {
            onAccessUnit?(.failure(.frameFailed(status)))
        }
    }

    public func invalidate() {
        if DispatchQueue.getSpecific(key: Self.inputQueueKey) == Self.inputQueueMarker {
            invalidateOnInputQueue()
        } else {
            inputQueue.sync {
                invalidateOnInputQueue()
            }
        }
    }

    public func metrics() -> VideoToolboxEncoderMetrics {
        inputQueue.sync {
            VideoToolboxEncoderMetrics(
                encoderIdentity: encoderIdentity,
                encoderIdentityUnavailableReason: encoderIdentityUnavailableReason,
                encoderUsesHardwareAccelerated: encoderUsesHardwareAccelerated,
                encoderHardwareAvailabilityReason: encoderHardwareAvailabilityReason,
                encodedAccessUnits: encodedAccessUnits,
                encodedKeyframes: encodedKeyframes,
                encodedBytes: encodedBytes,
                firstPresentationTimeMicroseconds: firstPresentationTimeMicroseconds,
                lastPresentationTimeMicroseconds: lastPresentationTimeMicroseconds
            )
        }
    }

    fileprivate func handleOutput(
        status: OSStatus,
        sampleBuffer: CMSampleBuffer?
    ) {
        if DispatchQueue.getSpecific(key: Self.inputQueueKey) == Self.inputQueueMarker {
            handleOutputOnInputQueue(status: status, sampleBuffer: sampleBuffer)
        } else {
            inputQueue.sync {
                handleOutputOnInputQueue(status: status, sampleBuffer: sampleBuffer)
            }
        }
    }

    private func handleOutputOnInputQueue(
        status: OSStatus,
        sampleBuffer: CMSampleBuffer?
    ) {
        guard status == noErr else {
            onAccessUnit?(.failure(.outputFailed(status)))
            return
        }
        guard let sampleBuffer,
              let formatDescription = CMSampleBufferGetFormatDescription(sampleBuffer),
              let blockBuffer = CMSampleBufferGetDataBuffer(sampleBuffer) else {
            onAccessUnit?(.failure(.malformedSample))
            return
        }
        do {
            try refreshFormatDescription(formatDescription)
            guard let nalLengthBytes, !parameterSets.isEmpty else {
                throw VideoToolboxEncoderError.missingParameterSets
            }
            let byteCount = CMBlockBufferGetDataLength(blockBuffer)
            guard byteCount > .zero else { throw VideoToolboxEncoderError.malformedSample }
            guard byteCount <= CamBridgeContract.Media.maxAccessUnitBytes else {
                throw VideoToolboxEncoderError.oversizedSample(byteCount)
            }
            var sampleData = Data(repeating: .zero, count: byteCount)
            let copied = sampleData.withUnsafeMutableBytes { bytes -> Bool in
                guard let baseAddress = bytes.baseAddress else { return false }
                return CMBlockBufferCopyDataBytes(
                    blockBuffer,
                    atOffset: .zero,
                    dataLength: byteCount,
                    destination: baseAddress
                ) == kCMBlockBufferNoErr
            }
            guard copied else { throw VideoToolboxEncoderError.malformedSample }
            let isKeyframe = Self.isKeyframe(sampleBuffer)
            let pts = try Self.presentationTimeMicroseconds(CMSampleBufferGetPresentationTimeStamp(sampleBuffer))
            let accessUnit = try Self.assembleAccessUnit(
                sampleData: sampleData,
                nalLengthBytes: nalLengthBytes,
                parameterSets: parameterSets,
                isKeyframe: isKeyframe,
                presentationTimeMicroseconds: pts
            )
            encodedAccessUnits += Self.counterIncrement
            encodedBytes += accessUnit.data.count
            firstPresentationTimeMicroseconds = firstPresentationTimeMicroseconds ?? pts
            lastPresentationTimeMicroseconds = pts
            if isKeyframe { encodedKeyframes += Self.counterIncrement }
            onAccessUnit?(.success(accessUnit))
        } catch let error as VideoToolboxEncoderError {
            onAccessUnit?(.failure(error))
        } catch {
            onAccessUnit?(.failure(.malformedSample))
        }
    }

    // Kept as a pure assembly seam so synthetic callback fixtures can verify
    // the exact AVCC-to-Annex-B boundary without requiring a physical encoder.
    static func assembleAccessUnit(
        sampleData: Data,
        nalLengthBytes: Int,
        parameterSets: [Data],
        isKeyframe: Bool,
        presentationTimeMicroseconds: Int64
    ) throws -> EncodedAccessUnit {
        guard presentationTimeMicroseconds >= .zero else {
            throw VideoToolboxEncoderError.malformedSample
        }
        let normalized = try H264AccessUnitNormalizer().normalize(
            sample: sampleData,
            nalLengthBytes: nalLengthBytes,
            parameterSets: parameterSets,
            isKeyframe: isKeyframe
        )
        return EncodedAccessUnit(
            data: normalized,
            presentationTimeMicroseconds: presentationTimeMicroseconds,
            isKeyframe: isKeyframe
        )
    }

    private func setProperties(session: VTCompressionSession, configuration: StreamConfiguration) throws {
        try set(session: session, key: kVTCompressionPropertyKey_RealTime, value: true, name: "real-time")
        try set(session: session, key: kVTCompressionPropertyKey_AllowFrameReordering, value: false, name: "frame-reordering")
        try set(session: session, key: kVTCompressionPropertyKey_ExpectedFrameRate, value: configuration.mode.fps, name: "frame-rate")
        try set(session: session, key: kVTCompressionPropertyKey_AverageBitRate, value: configuration.bitrateBps, name: "average-bitrate")
        try set(session: session, key: kVTCompressionPropertyKey_MaxKeyFrameInterval, value: configuration.mode.keyframeIntervalSeconds * configuration.mode.fps, name: "keyframe-interval")
        try set(session: session, key: kVTCompressionPropertyKey_MaxKeyFrameIntervalDuration, value: Double(configuration.mode.keyframeIntervalSeconds), name: "keyframe-duration")
        let dataRateLimits = try Self.dataRateLimits(
            bitrateBps: configuration.bitrateBps,
            windowSeconds: Self.rateLimitWindowSeconds
        )
        try set(
            session: session,
            key: kVTCompressionPropertyKey_DataRateLimits,
            value: dataRateLimits as NSArray,
            name: Self.dataRateLimitsPropertyName
        )
    }

    // VideoToolbox defines each data-rate limit as a byte count followed by a
    // time interval. Keep the conversion here so the property shape and unit
    // boundary are tested without requiring a physical encoder.
    static func dataRateLimits(bitrateBps: Int, windowSeconds: Int) throws -> [NSNumber] {
        guard bitrateBps > .zero, windowSeconds > .zero else {
            throw VideoToolboxEncoderError.invalidDataRateLimit
        }
        let (windowBits, overflow) = bitrateBps.multipliedReportingOverflow(by: windowSeconds)
        guard !overflow else { throw VideoToolboxEncoderError.dataRateLimitOverflow }
        let wholeBytes = windowBits / Self.bitsPerByte
        let roundedByte = windowBits % Self.bitsPerByte == .zero ? .zero : Self.byteIncrement
        let allowedBytes = wholeBytes + roundedByte
        guard allowedBytes > .zero else { throw VideoToolboxEncoderError.invalidDataRateLimit }
        return [NSNumber(value: allowedBytes), NSNumber(value: windowSeconds)]
    }

    private func readSessionProperties(_ session: VTCompressionSession) {
        let encoderProperty = Self.copySessionProperty(session, key: kVTCompressionPropertyKey_EncoderID)
        if encoderProperty.status == noErr, let identity = Self.stringValue(encoderProperty.value) {
            encoderIdentity = identity
        } else {
            encoderIdentityUnavailableReason = "VideoToolbox encoder identity unavailable (status: \(encoderProperty.status))"
        }

        if #available(iOS 17.4, *) {
            let hardwareProperty = Self.copySessionProperty(
                session,
                key: kVTCompressionPropertyKey_UsingHardwareAcceleratedVideoEncoder
            )
            if hardwareProperty.status == noErr, let value = hardwareProperty.value as? NSNumber {
                encoderUsesHardwareAccelerated = value.boolValue
            } else {
                encoderHardwareAvailabilityReason = "VideoToolbox hardware-use property unavailable (status: \(hardwareProperty.status))"
            }
        } else {
            encoderHardwareAvailabilityReason = "VideoToolbox hardware-use property is unavailable before iOS 17.4"
        }
    }

    private static func copySessionProperty(
        _ session: VTCompressionSession,
        key: CFString
    ) -> (status: OSStatus, value: CFTypeRef?) {
        var unmanagedValue: Unmanaged<AnyObject>?
        let status = VTSessionCopyProperty(session, key: key, allocator: nil, valueOut: &unmanagedValue)
        return (status, unmanagedValue?.takeRetainedValue())
    }

    private static func stringValue(_ value: CFTypeRef?) -> String? {
        guard let value else { return nil }
        if let value = value as? String { return value }
        if let value = value as? NSString { return value as String }
        return nil
    }

    private static func hardwareEncoderSpecification() -> [String: Any] {
        if #available(iOS 17.4, *) {
            return [
                kVTVideoEncoderSpecification_RequireHardwareAcceleratedVideoEncoder as String: true
            ]
        }
        // The SDK exposes this key as an iOS 17.4 symbol, but VideoToolbox
        // accepts the documented key on earlier supported deployment targets.
        return [Self.legacyHardwareSpecificationKey: true]
    }

    private func set(session: VTCompressionSession, key: CFString, value: Any, name: String) throws {
        let status = VTSessionSetProperty(session, key: key, value: value as CFTypeRef)
        guard status == noErr else { throw VideoToolboxEncoderError.propertyFailed(name, status) }
    }

    private func refreshFormatDescription(_ formatDescription: CMFormatDescription) throws {
        var parameterSetCount = Int.zero
        var nalLength: Int32 = .zero
        var status = CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
            formatDescription,
            parameterSetIndex: .zero,
            parameterSetPointerOut: nil,
            parameterSetSizeOut: nil,
            parameterSetCountOut: &parameterSetCount,
            nalUnitHeaderLengthOut: &nalLength
        )
        guard status == noErr, parameterSetCount > .zero else {
            throw VideoToolboxEncoderError.missingFormatDescription
        }
        var sets: [Data] = []
        sets.reserveCapacity(parameterSetCount)
        for index in Int.zero..<parameterSetCount {
            var pointer: UnsafePointer<UInt8>?
            var size = Int.zero
            status = CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
                formatDescription,
                parameterSetIndex: index,
                parameterSetPointerOut: &pointer,
                parameterSetSizeOut: &size,
                parameterSetCountOut: nil,
                nalUnitHeaderLengthOut: nil
            )
            guard status == noErr, let pointer, size > .zero else {
                throw VideoToolboxEncoderError.missingParameterSets
            }
            sets.append(Data(bytes: pointer, count: size))
        }
        nalLengthBytes = Int(nalLength)
        parameterSets = sets
    }

    private static func isKeyframe(_ sampleBuffer: CMSampleBuffer) -> Bool {
        guard let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: false) as? [[String: Any]],
              let first = attachments.first else {
            return false
        }
        guard let notSync = first[kCMSampleAttachmentKey_NotSync as String] as? NSNumber else {
            return true
        }
        return !notSync.boolValue
    }

    private static func presentationTimeMicroseconds(_ time: CMTime) throws -> Int64 {
        guard time.isValid, time.isNumeric else { throw VideoToolboxEncoderError.malformedSample }
        let scaled = CMTimeConvertScale(time, timescale: Self.microsecondsTimeScale, method: .quickTime)
        guard scaled.isValid else { throw VideoToolboxEncoderError.malformedSample }
        return scaled.value
    }

    private static func number(in dictionary: NSDictionary, forKey key: CFString) -> Int? {
        guard let number = dictionary[key as String] as? NSNumber else { return nil }
        let value = number.intValue
        guard NSNumber(value: value) == number else { return nil }
        return value
    }

    private func invalidateOnInputQueue() {
        guard let session = compressionSession else { return }
        let completeStatus = VTCompressionSessionCompleteFrames(session, untilPresentationTimeStamp: .invalid)
        if completeStatus != noErr {
            onAccessUnit?(.failure(.outputFailed(completeStatus)))
        }
        VTCompressionSessionInvalidate(session)
        compressionSession = nil
        configuration = nil
        nalLengthBytes = nil
        parameterSets.removeAll(keepingCapacity: true)
        encoderIdentity = nil
        encoderIdentityUnavailableReason = nil
        encoderUsesHardwareAccelerated = nil
        encoderHardwareAvailabilityReason = nil
        firstPresentationTimeMicroseconds = nil
        lastPresentationTimeMicroseconds = nil
    }

    private static let microsecondsTimeScale: CMTimeScale = 1_000_000
    private static let rateLimitWindowSeconds = 2
    private static let bitsPerByte = 8
    private static let byteIncrement = 1
    private static let dataRateLimitsPropertyName = kVTCompressionPropertyKey_DataRateLimits as String
    private static let legacyHardwareSpecificationKey = "EnableHardwareAcceleratedVideoEncoder"
    private static let inputQueueKey = DispatchSpecificKey<UInt8>()
    private static let inputQueueMarker: UInt8 = 1
    private static let counterIncrement = 1
}

private func videoToolboxOutputCallback(
    outputCallbackRefCon: UnsafeMutableRawPointer?,
    sourceFrameRefCon: UnsafeMutableRawPointer?,
    status: OSStatus,
    infoFlags: VTEncodeInfoFlags,
    sampleBuffer: CMSampleBuffer?
) {
    guard let outputCallbackRefCon else { return }
    let encoder = Unmanaged<VideoToolboxEncoder>.fromOpaque(outputCallbackRefCon).takeUnretainedValue()
    encoder.handleOutput(status: status, sampleBuffer: sampleBuffer)
}
