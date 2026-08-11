import Foundation
import Testing
import CamBridgeCore

@Test("every v6 control message round trips")
func controlMessagesRoundTrip() throws {
    let messages: [ControlMessage] = [
        .probe(requestId: CamBridgeTestFixtures.requestId),
        .capabilities(
            requestId: CamBridgeTestFixtures.requestId,
            receiverId: "cambridge-obs-source",
            displayName: "OBS receiver",
            maxLongEdge: 3840,
            maxShortEdge: 2160
        ),
        CamBridgeTestFixtures.hello(),
        CamBridgeTestFixtures.accepted(),
        .stop(sessionId: CamBridgeTestFixtures.sessionId, generation: CamBridgeTestFixtures.generation),
        .error(message: "receiver rejected stream")
    ]
    for message in messages {
        let encoded = try ControlMessageCodec.encode(message)
        #expect(try ControlMessageCodec.decode(encoded) == message)
    }
}

@Test("control JSON uses exact wire field names")
func controlFieldNamesAreExact() throws {
    let data = try ControlMessageCodec.encode(CamBridgeTestFixtures.hello())
    let json = String(decoding: data, as: UTF8.self)
    #expect(json.contains("\"codedWidth\""))
    #expect(json.contains("\"codedHeight\""))
    #expect(json.contains("\"rotationDegrees\""))
    #expect(!json.contains("\"width\""))
    #expect(!json.contains("\"height\""))
}

@Test("malformed control input and unknown fields are rejected")
func malformedControlInputIsRejected() throws {
    let unknownField = Data(#"{"protocolVersion":6,"type":"probe","requestId":"r","extra":true}"#.utf8)
    #expect(throws: Error.self) { try ControlMessageCodec.decode(unknownField) }
    #expect(throws: Error.self) { try ControlMessageCodec.decode(Data(#"{"protocolVersion":5}"#.utf8)) }
    #expect(throws: Error.self) { try ControlMessageCodec.decode(Data("not-json".utf8)) }
    #expect(throws: Error.self) { try ControlMessageCodec.decode(Data(repeating: 0, count: CamBridgeContract.Control.maximumMessageBytes + 1)) }

    let oversizedProfileID = String(repeating: "p", count: CamBridgeContract.Validation.maximumProfileIdentifierLength + 1)
    let oversizedProfile = Data("{\"protocolVersion\":6,\"type\":\"hello\",\"sessionId\":\"session\",\"generation\":1,\"profileId\":\"\(oversizedProfileID)\",\"codec\":\"h264\",\"codedWidth\":1920,\"codedHeight\":1080,\"rotationDegrees\":0,\"fps\":30,\"bitrateBps\":8000000}".utf8)
    #expect(throws: Error.self) { try ControlMessageCodec.decode(oversizedProfile) }

    let fractionalInteger = Data(#"{"protocolVersion":6,"type":"probe","requestId":1.0}"#.utf8)
    #expect(throws: Error.self) { try ControlMessageCodec.decode(fractionalInteger) }
}
