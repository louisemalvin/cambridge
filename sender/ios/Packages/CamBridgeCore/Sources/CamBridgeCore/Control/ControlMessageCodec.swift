import Foundation

public enum ControlMessageCodecError: Error, Equatable, Sendable {
    case emptyPayload
    case oversizedPayload(Int)
    case invalidJSON
    case rootIsNotObject
    case unknownFields([String])
    case missingField(String)
    case invalidField(String)
    case unsupportedProtocol(Int)
    case unsupportedMessageType(String)
}

public enum ControlMessageCodec {
    public static func encode(_ message: ControlMessage) throws -> Data {
        let object = try object(for: message)
        let data = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
        guard !data.isEmpty else { throw ControlMessageCodecError.emptyPayload }
        guard data.count <= CamBridgeContract.Control.maximumMessageBytes else {
            throw ControlMessageCodecError.oversizedPayload(data.count)
        }
        return data
    }

    public static func decode(_ data: Data) throws -> ControlMessage {
        guard !data.isEmpty else { throw ControlMessageCodecError.emptyPayload }
        guard data.count <= CamBridgeContract.Control.maximumMessageBytes else {
            throw ControlMessageCodecError.oversizedPayload(data.count)
        }
        let object: Any
        do {
            object = try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
        } catch {
            throw ControlMessageCodecError.invalidJSON
        }
        guard let dictionary = object as? [String: Any] else {
            throw ControlMessageCodecError.rootIsNotObject
        }
        return try decode(dictionary)
    }

    private static func object(for message: ControlMessage) throws -> [String: Any] {
        switch message {
        case let .probe(requestId):
            try validateIdentifier(requestId, field: "requestId")
            return [
                "protocolVersion": CamBridgeContract.protocolVersion,
                "type": CamBridgeContract.MessageType.probe,
                "requestId": requestId,
            ]
        case let .capabilities(requestId, receiverId, displayName, maxLongEdge, maxShortEdge):
            try validateIdentifier(requestId, field: "requestId")
            try validateIdentifier(receiverId, field: "receiverId")
            try validateDisplayName(displayName)
            try validateReceiverBounds(maxLongEdge: maxLongEdge, maxShortEdge: maxShortEdge)
            return [
                "protocolVersion": CamBridgeContract.protocolVersion,
                "type": CamBridgeContract.MessageType.capabilities,
                "requestId": requestId,
                "receiverId": receiverId,
                "displayName": displayName,
                "maxLongEdge": maxLongEdge,
                "maxShortEdge": maxShortEdge,
            ]
        case let .hello(sessionId, generation, profileId, codedWidth, codedHeight, rotation, fps, bitrateBps):
            try validateSessionIdentity(sessionId, generation: generation)
            try validateProfileIdentifier(profileId)
            _ = try VideoGeometry(codedWidth: codedWidth, codedHeight: codedHeight)
            try validateFrameRate(fps)
            try validateBitrate(bitrateBps)
            return [
                "protocolVersion": CamBridgeContract.protocolVersion,
                "type": CamBridgeContract.MessageType.hello,
                "sessionId": sessionId,
                "generation": generation,
                "profileId": profileId,
                "codec": CamBridgeContract.Codec.h264,
                "codedWidth": codedWidth,
                "codedHeight": codedHeight,
                "rotationDegrees": rotation.degrees,
                "fps": fps,
                "bitrateBps": bitrateBps,
            ]
        case let .accepted(sessionId, generation, profileId, mediaPort, maxLongEdge, maxShortEdge):
            try validateSessionIdentity(sessionId, generation: generation)
            try validateProfileIdentifier(profileId)
            try validatePort(mediaPort)
            try validateReceiverBounds(maxLongEdge: maxLongEdge, maxShortEdge: maxShortEdge)
            return [
                "protocolVersion": CamBridgeContract.protocolVersion,
                "type": CamBridgeContract.MessageType.accepted,
                "sessionId": sessionId,
                "generation": generation,
                "profileId": profileId,
                "mediaPort": mediaPort,
                "maxLongEdge": maxLongEdge,
                "maxShortEdge": maxShortEdge,
            ]
        case let .stop(sessionId, generation):
            try validateSessionIdentity(sessionId, generation: generation)
            return [
                "protocolVersion": CamBridgeContract.protocolVersion,
                "type": CamBridgeContract.MessageType.stop,
                "sessionId": sessionId,
                "generation": generation,
            ]
        case let .error(message):
            guard !message.isEmpty, message.count <= Self.maximumErrorLength else {
                throw ControlMessageCodecError.invalidField("error")
            }
            return [
                "protocolVersion": CamBridgeContract.protocolVersion,
                "type": CamBridgeContract.MessageType.error,
                "error": message,
            ]
        }
    }

    private static func decode(_ dictionary: [String: Any]) throws -> ControlMessage {
        let version = try integer(dictionary, field: "protocolVersion")
        guard version == CamBridgeContract.protocolVersion else {
            throw ControlMessageCodecError.unsupportedProtocol(version)
        }
        let type = try string(dictionary, field: "type")
        switch type {
        case CamBridgeContract.MessageType.probe:
            try requireFields(dictionary, exactly: ["protocolVersion", "type", "requestId"])
            return .probe(requestId: try identifier(dictionary, field: "requestId"))
        case CamBridgeContract.MessageType.capabilities:
            try requireFields(dictionary, exactly: ["protocolVersion", "type", "requestId", "receiverId", "displayName", "maxLongEdge", "maxShortEdge"])
            let maxLongEdge = try integer(dictionary, field: "maxLongEdge")
            let maxShortEdge = try integer(dictionary, field: "maxShortEdge")
            try validateReceiverBounds(maxLongEdge: maxLongEdge, maxShortEdge: maxShortEdge)
            return .capabilities(
                requestId: try identifier(dictionary, field: "requestId"),
                receiverId: try identifier(dictionary, field: "receiverId"),
                displayName: try displayName(dictionary, field: "displayName"),
                maxLongEdge: maxLongEdge,
                maxShortEdge: maxShortEdge
            )
        case CamBridgeContract.MessageType.hello:
            try requireFields(dictionary, exactly: ["protocolVersion", "type", "sessionId", "generation", "profileId", "codec", "codedWidth", "codedHeight", "rotationDegrees", "fps", "bitrateBps"])
            guard try string(dictionary, field: "codec") == CamBridgeContract.Codec.h264 else {
                throw ControlMessageCodecError.invalidField("codec")
            }
            let width = try integer(dictionary, field: "codedWidth")
            let height = try integer(dictionary, field: "codedHeight")
            _ = try VideoGeometry(codedWidth: width, codedHeight: height)
            let rotation = try StreamRotation(degrees: integer(dictionary, field: "rotationDegrees"))
            let fps = try integer(dictionary, field: "fps")
            let bitrate = try integer(dictionary, field: "bitrateBps")
            try validateFrameRate(fps)
            try validateBitrate(bitrate)
            return .hello(
                sessionId: try sessionId(dictionary),
                generation: try generation(dictionary),
                profileId: try profileIdentifier(dictionary),
                codedWidth: width,
                codedHeight: height,
                rotation: rotation,
                fps: fps,
                bitrateBps: bitrate
            )
        case CamBridgeContract.MessageType.accepted:
            try requireFields(dictionary, exactly: ["protocolVersion", "type", "sessionId", "generation", "profileId", "mediaPort", "maxLongEdge", "maxShortEdge"])
            let maxLongEdge = try integer(dictionary, field: "maxLongEdge")
            let maxShortEdge = try integer(dictionary, field: "maxShortEdge")
            try validateReceiverBounds(maxLongEdge: maxLongEdge, maxShortEdge: maxShortEdge)
            let mediaPort = try integer(dictionary, field: "mediaPort")
            try validatePort(mediaPort)
            return .accepted(
                sessionId: try sessionId(dictionary),
                generation: try generation(dictionary),
                profileId: try profileIdentifier(dictionary),
                mediaPort: mediaPort,
                maxLongEdge: maxLongEdge,
                maxShortEdge: maxShortEdge
            )
        case CamBridgeContract.MessageType.stop:
            try requireFields(dictionary, exactly: ["protocolVersion", "type", "sessionId", "generation"])
            return .stop(sessionId: try sessionId(dictionary), generation: try generation(dictionary))
        case CamBridgeContract.MessageType.error:
            try requireFields(dictionary, exactly: ["protocolVersion", "type", "error"])
            let message = try string(dictionary, field: "error")
            guard !message.isEmpty, message.count <= Self.maximumErrorLength else {
                throw ControlMessageCodecError.invalidField("error")
            }
            return .error(message: message)
        default:
            throw ControlMessageCodecError.unsupportedMessageType(type)
        }
    }

    private static func requireFields(_ dictionary: [String: Any], exactly expected: Set<String>) throws {
        let actual = Set(dictionary.keys)
        guard actual == expected else {
            let unknown = actual.subtracting(expected).sorted()
            if !unknown.isEmpty { throw ControlMessageCodecError.unknownFields(unknown) }
            let missing = expected.subtracting(actual).sorted()
            throw ControlMessageCodecError.missingField(missing.first ?? "")
        }
    }

    private static func string(_ dictionary: [String: Any], field: String) throws -> String {
        guard let value = dictionary[field] as? String else { throw ControlMessageCodecError.invalidField(field) }
        return value
    }

    private static func integer(_ dictionary: [String: Any], field: String) throws -> Int {
        guard let number = dictionary[field] as? NSNumber else {
            throw ControlMessageCodecError.invalidField(field)
        }
        let typeEncoding = String(cString: number.objCType)
        guard typeEncoding != "c", typeEncoding != "B", typeEncoding != "d", typeEncoding != "f" else {
            throw ControlMessageCodecError.invalidField(field)
        }
        let value = number.intValue
        guard NSNumber(value: value) == number else { throw ControlMessageCodecError.invalidField(field) }
        return value
    }

    private static func identifier(_ dictionary: [String: Any], field: String) throws -> String {
        let value = try string(dictionary, field: field)
        try validateIdentifier(value, field: field)
        return value
    }

    private static func profileIdentifier(_ dictionary: [String: Any]) throws -> String {
        let value = try string(dictionary, field: "profileId")
        try validateProfileIdentifier(value)
        return value
    }

    private static func displayName(_ dictionary: [String: Any], field: String) throws -> String {
        let value = try string(dictionary, field: field)
        try validateDisplayName(value)
        return value
    }

    private static func sessionId(_ dictionary: [String: Any]) throws -> String {
        try identifier(dictionary, field: "sessionId")
    }

    private static func generation(_ dictionary: [String: Any]) throws -> UInt64 {
        let value = try integer(dictionary, field: "generation")
        guard value > .zero else { throw ControlMessageCodecError.invalidField("generation") }
        return UInt64(value)
    }

    private static func validateSessionIdentity(_ sessionId: String, generation: UInt64) throws {
        try validateIdentifier(sessionId, field: "sessionId")
        guard generation > .zero else { throw ControlMessageCodecError.invalidField("generation") }
    }

    private static func validateIdentifier(_ value: String, field: String) throws {
        guard !value.isEmpty, value.count <= Self.maximumIdentifierLength else {
            throw ControlMessageCodecError.invalidField(field)
        }
    }

    private static func validateProfileIdentifier(_ value: String) throws {
        guard !value.isEmpty, value.count <= CamBridgeContract.Validation.maximumProfileIdentifierLength else {
            throw ControlMessageCodecError.invalidField("profileId")
        }
    }

    private static func validateDisplayName(_ value: String) throws {
        guard !value.isEmpty, value.count <= Self.maximumIdentifierLength else {
            throw ControlMessageCodecError.invalidField("displayName")
        }
    }

    private static func validateReceiverBounds(maxLongEdge: Int, maxShortEdge: Int) throws {
        guard maxLongEdge >= CamBridgeContract.Geometry.minimumDimension,
              maxLongEdge <= CamBridgeContract.Geometry.maximumLongEdge,
              maxShortEdge >= CamBridgeContract.Geometry.minimumDimension,
              maxShortEdge <= CamBridgeContract.Geometry.maximumShortEdge,
              maxLongEdge >= maxShortEdge else {
            throw ControlMessageCodecError.invalidField("receiver geometry")
        }
    }

    private static func validateFrameRate(_ fps: Int) throws {
        guard fps >= CamBridgeContract.Video.minimumFps, fps <= CamBridgeContract.Video.maximumFps else {
            throw ControlMessageCodecError.invalidField("fps")
        }
    }

    private static func validateBitrate(_ bitrateBps: Int) throws {
        guard bitrateBps >= CamBridgeContract.Bitrate.minimumBps, bitrateBps <= CamBridgeContract.Bitrate.maximumBps else {
            throw ControlMessageCodecError.invalidField("bitrateBps")
        }
    }

    private static func validatePort(_ port: Int) throws {
        guard port >= CamBridgeContract.Validation.minimumPort,
              port <= CamBridgeContract.Validation.maximumPort else {
            throw ControlMessageCodecError.invalidField("mediaPort")
        }
    }

    private static let maximumIdentifierLength = CamBridgeContract.Validation.maximumIdentifierLength
    private static let maximumErrorLength = CamBridgeContract.Validation.maximumErrorLength
}
