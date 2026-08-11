import Foundation
import Testing
import CamBridgeCore

@Test("frame decoder handles partial header and payload")
func partialControlFrame() throws {
    let payload = Data("payload".utf8)
    let frame = try ControlFrameEncoder.frame(payload)
    var decoder = ControlFrameDecoder()
    #expect(try decoder.append(Data(frame.prefix(2))).isEmpty)
    #expect(decoder.hasPendingFrame)
    #expect(try decoder.append(Data(frame.dropFirst(2).prefix(3))).isEmpty)
    #expect(try decoder.append(Data(frame.dropFirst(5))) == [payload])
    #expect(!decoder.hasPendingFrame)
}

@Test("frame decoder yields multiple complete frames")
func multipleControlFrames() throws {
    let first = Data("first".utf8)
    let second = Data("second".utf8)
    let combined = try ControlFrameEncoder.frame(first) + ControlFrameEncoder.frame(second)
    var decoder = ControlFrameDecoder()
    #expect(try decoder.append(combined) == [first, second])
}

@Test("empty and oversized frames are rejected before allocation")
func invalidControlFrames() throws {
    var decoder = ControlFrameDecoder()
    #expect(throws: Error.self) { try decoder.append(Data([0, 0, 0, 0])) }
    let oversized = Data([0, 0, 0x20, 0x01])
    #expect(throws: Error.self) { try decoder.append(oversized) }
}
