import Foundation
import CamBridgeCore

@MainActor
public final class AppEnvironment {
    public let logger: CamBridgeLogger
    public let settingsStore: SenderSettingsStore
    public let browser: BonjourReceiverBrowser
    public let probe: CamBridgeReceiverProbe
    public let capture: CaptureService
    public let encoderProbe: EncoderCapabilityProbe
    public let sessionCoordinator: StreamSessionCoordinator
    public let appModel: AppModel
    public let lifecycleController: AppLifecycleController

    public init() {
        logger = CamBridgeLogger()
        settingsStore = SenderSettingsStore()
        browser = BonjourReceiverBrowser()
        probe = CamBridgeReceiverProbe()
        capture = CaptureService()
        encoderProbe = EncoderCapabilityProbe()
        sessionCoordinator = StreamSessionCoordinator(capture: capture, logger: logger)
        appModel = AppModel(settingsStore: settingsStore, browser: browser, probe: probe, capture: capture, encoderProbe: encoderProbe, sessionCoordinator: sessionCoordinator, logger: logger)
        lifecycleController = AppLifecycleController(sessionCoordinator: sessionCoordinator, logger: logger)
    }
}
