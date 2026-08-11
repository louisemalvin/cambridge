import XCTest

final class CamBridgeUITests: XCTestCase {
    func testSetupScreenLaunches() {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.buttons["start-stream"].waitForExistence(timeout: 5))
    }
}
