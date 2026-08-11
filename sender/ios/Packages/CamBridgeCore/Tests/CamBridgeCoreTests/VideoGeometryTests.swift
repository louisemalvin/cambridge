import Testing
import CamBridgeCore

@Test("all supported rotations derive display dimensions")
func displayDimensionsForEveryRotation() throws {
    let geometry = try VideoGeometry(codedWidth: 1920, codedHeight: 1080)
    #expect(geometry.displayDimensions(for: .zero).width == 1920)
    #expect(geometry.displayDimensions(for: .zero).height == 1080)
    #expect(geometry.displayDimensions(for: .ninety).width == 1080)
    #expect(geometry.displayDimensions(for: .ninety).height == 1920)
    #expect(geometry.displayDimensions(for: .oneEighty).width == 1920)
    #expect(geometry.displayDimensions(for: .oneEighty).height == 1080)
    #expect(geometry.displayDimensions(for: .twoSeventy).width == 1080)
    #expect(geometry.displayDimensions(for: .twoSeventy).height == 1920)
}

@Test("geometry rejects odd, undersized, and oversized dimensions")
func invalidGeometryIsRejected() {
    #expect(throws: Error.self) { try VideoGeometry(codedWidth: 1919, codedHeight: 1080) }
    #expect(throws: Error.self) { try VideoGeometry(codedWidth: 8, codedHeight: 8) }
    #expect(throws: Error.self) { try VideoGeometry(codedWidth: 3842, codedHeight: 2160) }
}
