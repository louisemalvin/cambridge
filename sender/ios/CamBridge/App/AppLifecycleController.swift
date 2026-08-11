import Foundation
import UIKit
import SwiftUI
import CamBridgeCore

public protocol StreamBackgroundEnding: Sendable {
    func endForBackground() async
}

@MainActor
public final class AppLifecycleController {
    private let sessionCoordinator: any StreamBackgroundEnding
    private let logger: CamBridgeLogger
    private var previousIdleTimerDisabled = false
    private var streamActivityIsActive = false

    public init(sessionCoordinator: any StreamBackgroundEnding, logger: CamBridgeLogger) {
        self.sessionCoordinator = sessionCoordinator
        self.logger = logger
    }

    public func scenePhaseChanged(_ phase: ScenePhase) {
        switch phase {
        case .active:
            break
        case .inactive:
            break
        case .background:
            guard streamActivityIsActive else { return }
            streamActivityIsActive = false
            updateIdleTimer(disabled: false)
            Task {
                await sessionCoordinator.endForBackground()
            }
            logger.event("stream_ended_background", category: .session)
        @unknown default:
            break
        }
    }

    public func streamActivityChanged(isActive: Bool) {
        guard streamActivityIsActive != isActive else { return }
        streamActivityIsActive = isActive
        updateIdleTimer(disabled: isActive)
    }

    public func streamStateChanged(_ state: StreamState) {
        switch state {
        case let .connecting(_, configuration), let .streaming(_, configuration, _):
            streamActivityChanged(isActive: true)
            requestInterfaceOrientation(configuration.orientation)
        case .stopping:
            streamActivityChanged(isActive: true)
        case .idle, .failed:
            streamActivityChanged(isActive: false)
            requestInterfaceOrientation(nil)
        }
    }

    private func updateIdleTimer(disabled: Bool) {
        if disabled {
            previousIdleTimerDisabled = UIApplication.shared.isIdleTimerDisabled
            UIApplication.shared.isIdleTimerDisabled = true
        } else {
            UIApplication.shared.isIdleTimerDisabled = previousIdleTimerDisabled
        }
    }

    private func requestInterfaceOrientation(_ rotation: StreamRotation?) {
        let mask: UIInterfaceOrientationMask
        if let rotation {
            switch rotation {
            case .zero:
                mask = .landscapeRight
            case .ninety:
                mask = .portrait
            case .oneEighty:
                mask = .landscapeLeft
            case .twoSeventy:
                mask = .portraitUpsideDown
            }
        } else {
            mask = .all
        }
        for scene in UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }) {
            let preferences = UIWindowScene.GeometryPreferences.iOS(interfaceOrientations: mask)
            scene.requestGeometryUpdate(preferences, errorHandler: nil)
        }
    }
}

extension StreamSessionCoordinator: StreamBackgroundEnding {}
