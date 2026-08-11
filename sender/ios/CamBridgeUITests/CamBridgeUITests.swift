import XCTest

final class CamBridgeUITests: XCTestCase {
    func testSetupLaunchesAndNavigatesToSettingsAndBack() {
        let app = launchFixture()

        XCTAssertTrue(element(app, id: "start-stream").waitForExistence(timeout: Self.waitTimeout))
        tap(app, id: "settings-tab")
        XCTAssertTrue(app.navigationBars["Settings"].waitForExistence(timeout: Self.waitTimeout))
        tap(app, id: "setup-tab")
        XCTAssertTrue(app.navigationBars["CamBridge"].waitForExistence(timeout: Self.waitTimeout))
    }

    func testPermissionStatesAndCameraAccessAreDeterministic() {
        let app = launchFixture()

        tap(app, id: "permission-denied")
        XCTAssertTrue(element(app, id: "camera-permission-state").label.contains("Denied"))
        tap(app, id: "permission-restricted")
        XCTAssertTrue(element(app, id: "camera-permission-state").label.contains("Restricted"))
        tap(app, id: "permission-authorized")
        XCTAssertTrue(element(app, id: "camera-permission-state").label.contains("Authorized"))
    }

    func testReceiverDiscoveryAndManualProbeStates() {
        let app = launchFixture()

        XCTAssertTrue(element(app, id: "receiver-status").label.contains("Select"))
        tap(app, id: "fixture-receiver-one")
        XCTAssertTrue(element(app, id: "receiver-status").label.contains("Fixture OBS One"))
        tap(app, id: "fixture-receiver-two")
        XCTAssertTrue(element(app, id: "receiver-status").label.contains("Fixture OBS Two"))

        let host = element(app, id: "manual-receiver-host")
        host.tap()
        tap(app, id: "probe-manual-receiver")
        XCTAssertTrue(element(app, id: "manual-probe-status").label.contains("Probe failed"))
        host.typeText("192.0.2.10")
        tap(app, id: "probe-manual-receiver")
        XCTAssertTrue(element(app, id: "manual-probe-status").label.contains("Ready"))
    }

    func testUnsupportedModeExplainsWhyStartIsDisabledUntilSelectionsAreValid() {
        let app = launchFixture()
        let start = element(app, id: "start-stream")

        XCTAssertFalse(start.isEnabled)
        XCTAssertTrue(element(app, id: "mode-capability-reason").label.contains("Unavailable"))
        tap(app, id: "permission-authorized")
        tap(app, id: "fixture-receiver-one")
        XCTAssertFalse(start.isEnabled)
        tap(app, id: "mode-1080p30")
        XCTAssertTrue(element(app, id: "mode-capability-reason").label.contains("Supported"))
        XCTAssertTrue(start.isEnabled)
    }

    func testStartProgressPreventsDuplicateStartAndTransitionsToWebcam() {
        let app = launchFixture()
        configureValidStart(app)

        let start = element(app, id: "start-stream")
        XCTAssertTrue(start.isEnabled)
        start.tap()
        XCTAssertFalse(start.isEnabled)
        XCTAssertTrue(element(app, id: "complete-start").waitForExistence(timeout: Self.waitTimeout))
        element(app, id: "complete-start").tap()
        XCTAssertTrue(element(app, id: "webcam-status").waitForExistence(timeout: Self.waitTimeout))
        XCTAssertTrue(element(app, id: "stop-stream").exists)
    }

    func testStopConfirmationSupportsCancelAndConfirm() {
        let app = launchFixture()
        configureValidStart(app)
        startAndComplete(app)

        tap(app, id: "stop-stream")
        let alert = app.alerts["Stop stream?"]
        XCTAssertTrue(alert.waitForExistence(timeout: Self.waitTimeout))
        alert.buttons["Cancel"].tap()
        XCTAssertFalse(alert.exists)
        tap(app, id: "stop-stream")
        XCTAssertTrue(alert.waitForExistence(timeout: Self.waitTimeout))
        alert.buttons["Stop"].tap()
        XCTAssertTrue(element(app, id: "start-stream").waitForExistence(timeout: Self.waitTimeout))
    }

    func testTerminalFailureReturnsToSetupAndRetryRestoresEditableState() {
        let app = launchFixture()
        configureValidStart(app)
        startAndComplete(app)
        tap(app, id: "simulate-terminal-failure")

        XCTAssertTrue(element(app, id: "stream-failure").waitForExistence(timeout: Self.waitTimeout))
        tap(app, id: "retry-stream")
        XCTAssertFalse(element(app, id: "stream-failure").exists)
        XCTAssertTrue(element(app, id: "start-stream").isEnabled)
    }

    func testSettingsEditsAreAvailableIdleAndLockedDuringActiveStream() {
        let app = launchFixture()
        tap(app, id: "settings-tab")
        let idleMode = element(app, id: "settings-mode-1080p30")
        XCTAssertTrue(idleMode.isEnabled)
        idleMode.tap()

        tap(app, id: "setup-tab")
        configureValidStart(app)
        startAndComplete(app)
        tap(app, id: "settings-tab")

        XCTAssertTrue(element(app, id: "settings-locked").waitForExistence(timeout: Self.waitTimeout))
        XCTAssertFalse(idleMode.isEnabled)

        tap(app, id: "webcam-tab")
        tap(app, id: "stop-stream")
        app.alerts["Stop stream?"].buttons["Stop"].tap()
        tap(app, id: "settings-tab")
        XCTAssertTrue(idleMode.isEnabled)
    }

    func testPreStreamCapabilityReportCanBeCopied() {
        let app = launchFixture()
        tap(app, id: "copy-capability-report")

        XCTAssertTrue(element(app, id: "capability-report-status").label.contains("copied"))
        tap(app, id: "settings-tab")
        tap(app, id: "copy-capability-report-settings")
        XCTAssertTrue(element(app, id: "copy-capability-report-settings").exists)
    }

    private func launchFixture() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments.append("--cambridge-ui-fixture")
        app.launch()
        return app
    }

    private func configureValidStart(_ app: XCUIApplication) {
        tap(app, id: "permission-authorized")
        tap(app, id: "fixture-receiver-one")
        tap(app, id: "mode-1080p30")
    }

    private func startAndComplete(_ app: XCUIApplication) {
        let start = element(app, id: "start-stream")
        XCTAssertTrue(start.waitForExistence(timeout: Self.waitTimeout))
        XCTAssertTrue(start.isEnabled)
        start.tap()
        let complete = element(app, id: "complete-start")
        XCTAssertTrue(complete.waitForExistence(timeout: Self.waitTimeout))
        complete.tap()
        XCTAssertTrue(element(app, id: "stop-stream").waitForExistence(timeout: Self.waitTimeout))
    }

    private func tap(_ app: XCUIApplication, id: String) {
        let control = element(app, id: id)
        XCTAssertTrue(control.waitForExistence(timeout: Self.waitTimeout), "Missing accessibility identifier: \(id)")
        control.tap()
    }

    private func element(_ app: XCUIApplication, id: String) -> XCUIElement {
        app.descendants(matching: .any)[id].firstMatch
    }

    private static let waitTimeout: TimeInterval = 5
}
