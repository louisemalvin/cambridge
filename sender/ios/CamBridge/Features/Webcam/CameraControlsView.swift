import SwiftUI
import Foundation

struct CameraControlsView: View {
    @Bindable var model: WebcamModel

    var body: some View {
        HStack(spacing: CameraControlsMetrics.controlSpacing) {
            ForEach(model.cameraState.zoomTargets, id: \.self) { target in
                Button(CameraControlsMetrics.label(for: target)) {
                    model.setZoomRatio(target)
                }
                .buttonStyle(.borderedProminent)
                .tint(CameraControlsMetrics.isSelected(target, current: model.cameraState.zoomRatio) ? .accentColor : .gray)
                .accessibilityLabel("Zoom \(CameraControlsMetrics.label(for: target))")
            }
            Spacer()
            Button {
                model.switchCamera()
            } label: {
                Image(systemName: "arrow.triangle.2.circlepath.camera")
            }
            .buttonStyle(.borderedProminent)
            .disabled(model.isSwitchingCamera)
            .accessibilityLabel("Flip camera")
            .accessibilityIdentifier("flip-camera")
        }
        .padding(CameraControlsMetrics.panelPadding)
        .background(
            .ultraThinMaterial,
            in: RoundedRectangle(cornerRadius: CameraControlsMetrics.panelCornerRadius)
        )
    }
}

private enum CameraControlsMetrics {
    static let controlSpacing: CGFloat = 10
    static let panelCornerRadius: CGFloat = 12
    static let panelPadding: CGFloat = 12
    static let zoomDisplayFormat = "%.1f×"
    static let selectedZoomTolerance = 0.01

    static func label(for ratio: Double) -> String {
        if ratio.rounded() == ratio {
            return "\(Int(ratio))×"
        }
        return String(format: zoomDisplayFormat, ratio)
    }

    static func isSelected(_ target: Double, current: Double) -> Bool {
        abs(target - current) <= selectedZoomTolerance
    }
}
