import XCTest

@MainActor
final class CamBridgeUITests: XCTestCase {
    func testSettingsLaunchesAndNavigatesToSetupAndBack() {
        let app = launchFixture()

        assertExists(element(app, id: "settings-screen-title"))
        tap(app, id: "open-stream-setup")
        assertExists(element(app, id: "setup-screen-title"))
        app.navigationBars.buttons.firstMatch.tap()
        assertExists(element(app, id: "settings-screen-title"))
    }

    func testPermissionStatesAndCameraAccessAreDeterministic() {
        let app = launchFixture()

        tap(app, id: "open-stream-setup")
        tap(app, id: "permission-denied")
        assertLabelContains(element(app, id: "camera-permission-state"), "Denied")
        tap(app, id: "permission-restricted")
        assertLabelContains(element(app, id: "camera-permission-state"), "Restricted")
        tap(app, id: "permission-authorized")
        assertLabelContains(element(app, id: "camera-permission-state"), "Authorized")
    }

    func testReceiverDiscoveryAndManualProbeStates() {
        let app = launchFixture()

        tap(app, id: "open-stream-setup")
        assertLabelContains(element(app, id: "receiver-status"), "Select")
        tap(app, id: "fixture-receiver-one")
        assertLabelContains(element(app, id: "receiver-status"), "Fixture OBS One")
        tap(app, id: "fixture-receiver-two")
        assertLabelContains(element(app, id: "receiver-status"), "Fixture OBS Two")

        let host = element(app, id: "manual-receiver-host")
        host.tap()
        tap(app, id: "probe-manual-receiver")
        assertLabelContains(element(app, id: "manual-probe-status"), "Probe failed")
        host.typeText("192.0.2.10")
        tap(app, id: "probe-manual-receiver")
        assertLabelContains(element(app, id: "manual-probe-status"), "Ready")
    }

    func testUnsupportedModeExplainsWhyStartIsDisabledUntilSelectionsAreValid() {
        let app = launchFixture()
        tap(app, id: "open-stream-setup")
        let start = element(app, id: "start-stream")

        assertEnabled(start, false)
        assertLabelContains(element(app, id: "mode-capability-reason"), "Unavailable")
        tap(app, id: "permission-authorized")
        tap(app, id: "fixture-receiver-one")
        assertEnabled(start, false)
        tap(app, id: "mode-1080p30")
        assertLabelContains(element(app, id: "mode-capability-reason"), "Supported")
        assertEnabled(start, true)
    }

    func testStartProgressPreventsDuplicateStartAndTransitionsToWebcam() {
        let app = launchFixture()
        configureValidStart(app)

        let start = element(app, id: "start-stream")
        assertEnabled(start, true)
        start.tap()
        assertEnabled(start, false)
        assertExists(element(app, id: "complete-start"))
        element(app, id: "complete-start").tap()
        assertExists(element(app, id: "webcam-status"))
        assertExists(element(app, id: "stop-stream"))
    }

    func testStopConfirmationSupportsCancelAndConfirm() {
        let app = launchFixture()
        configureValidStart(app)
        startAndComplete(app)

        tap(app, id: "stop-stream")
        let alert = app.alerts["Stop stream?"]
        assertExists(alert)
        let cancel = alert.descendants(matching: .button)["cancel-stop"].firstMatch
        assertExists(cancel)
        cancel.tap()
        let alertAfterCancel = alert.exists
        XCTAssertFalse(alertAfterCancel)
        tap(app, id: "stop-stream")
        assertExists(alert)
        let stop = alert.descendants(matching: .button)["confirm-stop"].firstMatch
        assertExists(stop)
        stop.tap()
        assertExists(element(app, id: "settings-screen-title"))
        tap(app, id: "open-stream-setup")
        assertExists(element(app, id: "start-stream"))
    }

    func testTerminalFailureReturnsToSetupAndRetryRestoresEditableState() {
        let app = launchFixture()
        configureValidStart(app)
        startAndComplete(app)
        tap(app, id: "simulate-terminal-failure")

        assertExists(element(app, id: "stream-failure"))
        tap(app, id: "retry-stream")
        let failureExistsAfterRetry = element(app, id: "stream-failure").exists
        XCTAssertFalse(failureExistsAfterRetry)
        assertEnabled(element(app, id: "start-stream"), true)
    }

    func testSettingsEditsAreAvailableIdleAndLockedDuringActiveStream() {
        let app = launchFixture()
        let idleMode = element(app, id: "settings-mode-1080p30")
        assertEnabled(idleMode, true)
        idleMode.tap()

        configureValidStart(app)
        startAndComplete(app)
        tap(app, id: "webcam-settings")

        assertExists(element(app, id: "settings-locked"))
        assertEnabled(idleMode, false)

        app.navigationBars.buttons.firstMatch.tap()
        tap(app, id: "stop-stream")
        let stopAlert = app.alerts["Stop stream?"]
        assertExists(stopAlert)
        let stop = stopAlert.descendants(matching: .button)["confirm-stop"].firstMatch
        assertExists(stop)
        stop.tap()
        assertEnabled(idleMode, true)
    }

    func testPreStreamCapabilityReportCanBeCopied() {
        let app = launchFixture()
        tap(app, id: "open-stream-setup")
        tap(app, id: "copy-capability-report")

        assertLabelContains(element(app, id: "capability-report-status"), "copied")
        app.navigationBars.buttons.firstMatch.tap()
        tap(app, id: "copy-capability-report-settings")
        assertExists(element(app, id: "copy-capability-report-settings"))
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
        tap(app, id: "mode-1080p30")
    }

    private func startAndComplete(_ app: XCUIApplication) {
        let start = element(app, id: "start-stream")
        assertExists(start)
        assertEnabled(start, true)
        start.tap()
        let complete = element(app, id: "complete-start")
        assertExists(complete)
        complete.tap()
        assertExists(element(app, id: "stop-stream"))
    }

    private func tap(_ app: XCUIApplication, id: String) {
        let control = element(app, id: id)
        assertExists(control, message: "Missing accessibility identifier: \(id)")
        control.tap()
    }

    private func assertExists(_ control: XCUIElement, message: String = "") {
        let exists = control.waitForExistence(timeout: Self.waitTimeout)
        XCTAssertTrue(exists, message)
    }

    private func assertLabelContains(_ control: XCUIElement, _ expectedText: String) {
        let label = control.label
        XCTAssertTrue(label.contains(expectedText), "Expected '\(expectedText)' in '\(label)'")
    }

    private func assertEnabled(_ control: XCUIElement, _ expected: Bool) {
        let isEnabled = control.isEnabled
        XCTAssertEqual(isEnabled, expected)
    }

    private func element(_ app: XCUIApplication, id: String) -> XCUIElement {
        app.descendants(matching: .any)[id].firstMatch
    }

    private static let waitTimeout: TimeInterval = 10
}
