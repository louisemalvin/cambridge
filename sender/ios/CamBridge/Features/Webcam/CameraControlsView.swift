import SwiftUI
import Foundation

struct CameraControlsView: View {
    @Bindable var model: WebcamModel

    var body: some View {
        VStack(alignment: .leading, spacing: CameraControlsMetrics.sectionSpacing) {
            HStack {
                Image(systemName: "plus.magnifyingglass")
                Slider(
                    value: Binding(
                        get: { model.cameraState.zoomRatio },
                        set: { model.setZoomRatio($0) }
                    ),
                    in: model.cameraState.minimumZoomRatio...max(model.cameraState.minimumZoomRatio, model.cameraState.maximumZoomRatio)
                )
                .accessibilityLabel("Zoom")
                .accessibilityValue(String(format: CameraControlsMetrics.zoomDisplayFormat, model.cameraState.zoomRatio))
                Text(String(format: CameraControlsMetrics.zoomDisplayFormat, model.cameraState.zoomRatio))
                    .monospacedDigit()
            }
        }
        .padding(CameraControlsMetrics.panelPadding)
        .background(
            .ultraThinMaterial,
            in: RoundedRectangle(cornerRadius: CameraControlsMetrics.panelCornerRadius)
        )
    }
}

private enum CameraControlsMetrics {
    static let sectionSpacing: CGFloat = 12
    static let panelCornerRadius: CGFloat = 12
    static let panelPadding: CGFloat = 12
    static let zoomDisplayFormat = "%.1f×"
}
