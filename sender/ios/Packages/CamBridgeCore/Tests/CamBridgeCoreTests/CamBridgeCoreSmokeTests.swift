import Testing
@testable import CamBridgeCore

@Test("CamBridgeCore package is loadable")
func packageIsLoadable() {
    #expect(CamBridgeCore.name == "cambridge-stream")
}
