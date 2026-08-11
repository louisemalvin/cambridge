import Foundation
import CamBridgeCore

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
