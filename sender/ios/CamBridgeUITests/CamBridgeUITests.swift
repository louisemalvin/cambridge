import XCTest

@MainActor
final class CamBridgeUITests: XCTestCase {
    func testSettingsNavigatesToSetupAndBack() {
        let app = launchFixture()

        assertExists(element(app, id: "settings-screen-title"))
        tap(app, id: "open-stream-setup")
        assertExists(element(app, id: "setup-screen-title"))
        app.navigationBars.buttons.firstMatch.tap()
        assertExists(element(app, id: "settings-screen-title"))
    }

    func testPermissionAndReceiverStatesAreDeterministic() {
        let app = launchFixture()
        tap(app, id: "open-stream-setup")

        tap(app, id: "permission-denied")
        assertLabelContains(element(app, id: "camera-permission-state"), "Denied")
        tap(app, id: "permission-authorized")
        assertLabelContains(element(app, id: "camera-permission-state"), "Authorized")
        tap(app, id: "fixture-receiver-one")
        assertLabelContains(element(app, id: "receiver-status"), "Fixture OBS One")
    }

    func testFreshDefaultsAndIndependentSelectionsApplySuggestedBitrates() {
        let app = launchFixture()
        tap(app, id: "open-stream-setup")

        assertLabelContains(element(app, id: "selected-resolution"), "Full HD")
        assertLabelContains(element(app, id: "selected-frame-rate"), "30 fps")
        assertLabelContains(element(app, id: "selected-bitrate"), "5 Mbps")
        tap(app, id: "frame-rate-60")
        assertLabelContains(element(app, id: "selected-bitrate"), "10 Mbps")
        tap(app, id: "resolution-2k")
        assertLabelContains(element(app, id: "selected-bitrate"), "18 Mbps")
        tap(app, id: "frame-rate-30")
        assertLabelContains(element(app, id: "selected-bitrate"), "9 Mbps")
    }

    func testManualOneMbpsOverrideCanStartAndInvalidTextCannot() {
        let app = launchFixture()
        configureValidStart(app)
        tap(app, id: "frame-rate-60")
        tap(app, id: "bitrate-1")

        assertLabelContains(element(app, id: "selected-bitrate"), "1 Mbps")
        assertEnabled(element(app, id: "start-stream"), true)
        tap(app, id: "bitrate-invalid")
        assertEnabled(element(app, id: "start-stream"), false)
    }

    func testStartTransitionsToWebcamAndStopReturnsToSettings() {
        let app = launchFixture()
        configureValidStart(app)

        tap(app, id: "start-stream")
        assertEnabled(element(app, id: "start-stream"), false)
        tap(app, id: "complete-start")
        assertExists(element(app, id: "webcam-status"))
        tap(app, id: "stop-stream")
        let alert = app.alerts["Stop stream?"]
        assertExists(alert)
        alert.descendants(matching: .button)["confirm-stop"].firstMatch.tap()
        assertExists(element(app, id: "settings-screen-title"))
    }

    func testTerminalFailureRequiresExplicitRetryWithoutChangingSettings() {
        let app = launchFixture()
        configureValidStart(app)
        tap(app, id: "frame-rate-60")
        tap(app, id: "bitrate-1")
        tap(app, id: "start-stream")
        tap(app, id: "complete-start")
        tap(app, id: "simulate-terminal-failure")

        assertExists(element(app, id: "stream-failure"))
        assertLabelContains(element(app, id: "selected-frame-rate"), "60 fps")
        assertLabelContains(element(app, id: "selected-bitrate"), "1 Mbps")
        tap(app, id: "retry-stream")
        XCTAssertFalse(element(app, id: "stream-failure").exists)
        assertEnabled(element(app, id: "start-stream"), true)
    }

    private func launchFixture() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments.append("--cambridge-ui-fixture")
        app.launch()
        return app
    }

    private func configureValidStart(_ app: XCUIApplication) {
        tap(app, id: "open-stream-setup")
        tap(app, id: "permission-authorized")
        tap(app, id: "fixture-receiver-one")
    }

    private func tap(_ app: XCUIApplication, id: String) {
        let control = element(app, id: id)
        assertExists(control, message: "Missing accessibility identifier: \(id)")
        control.tap()
    }

    private func assertExists(_ control: XCUIElement, message: String = "") {
        XCTAssertTrue(control.waitForExistence(timeout: Self.waitTimeout), message)
    }

    private func assertLabelContains(_ control: XCUIElement, _ expectedText: String) {
        XCTAssertTrue(control.label.contains(expectedText), "Expected '\(expectedText)' in '\(control.label)'")
    }

    private func assertEnabled(_ control: XCUIElement, _ expected: Bool) {
        XCTAssertEqual(control.isEnabled, expected)
    }

    private func element(_ app: XCUIApplication, id: String) -> XCUIElement {
        app.descendants(matching: .any)[id].firstMatch
    }

    private static let waitTimeout: TimeInterval = 10
}
