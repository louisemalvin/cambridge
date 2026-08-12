import Foundation
import UIKit
import CamBridgeCore

@MainActor
public protocol StreamOrientationProviding {
    func currentRotation() -> StreamRotation
}

@MainActor
public struct InterfaceOrientationProvider: StreamOrientationProviding {
    public init() {}

    public func currentRotation() -> StreamRotation {
        let scenes = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .sorted { $0.session.persistentIdentifier < $1.session.persistentIdentifier }
        let scene = scenes.first(where: { scene in
            scene.windows.contains(where: \.isKeyWindow)
        }) ?? scenes.first(where: { $0.activationState == .foregroundActive }) ?? scenes.first
        return SessionOrientationResolver().rotation(interfaceOrientation: scene?.interfaceOrientation ?? .unknown)
    }
}

public struct SessionOrientationResolution: Equatable, Sendable {
    public let rotation: StreamRotation
    public let previewRotationAngle: CGFloat

    public init(rotation: StreamRotation, previewRotationAngle: CGFloat) {
        self.rotation = rotation
        self.previewRotationAngle = previewRotationAngle
    }
}

public struct SessionOrientationResolver: Sendable {
    public init() {}

    public func rotation(interfaceOrientation: UIInterfaceOrientation) -> StreamRotation {
        switch interfaceOrientation {
        case .landscapeRight:
            .zero
        case .portrait:
            .ninety
        case .landscapeLeft:
            .oneEighty
        case .portraitUpsideDown:
            .twoSeventy
        case .unknown:
            .zero
        @unknown default:
            .zero
        }
    }

    public func resolve(rotation: StreamRotation, cameraPosition: CameraPosition) -> SessionOrientationResolution {
        let angle: CGFloat
        if cameraPosition == .back {
            angle = CGFloat(rotation.degrees)
        } else {
            switch rotation {
            case .zero:
                angle = CGFloat(StreamRotation.oneEighty.degrees)
            case .ninety:
                angle = CGFloat(StreamRotation.ninety.degrees)
            case .oneEighty:
                angle = CGFloat(StreamRotation.zero.degrees)
            case .twoSeventy:
                angle = CGFloat(StreamRotation.twoSeventy.degrees)
            }
        }
        return SessionOrientationResolution(rotation: rotation, previewRotationAngle: angle)
    }
}
