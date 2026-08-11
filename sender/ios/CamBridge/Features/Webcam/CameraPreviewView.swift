import SwiftUI
import UIKit
@preconcurrency import AVFoundation

@MainActor
struct CameraPreviewView: UIViewRepresentable {
    let capture: CaptureService

    func makeUIView(context: Context) -> CameraPreviewContainerView {
        let view = CameraPreviewContainerView()
        Task { @MainActor in
            if let layer = await capture.previewLayer() {
                view.install(layer: layer)
            }
        }
        return view
    }

    func updateUIView(_ uiView: CameraPreviewContainerView, context: Context) {
        uiView.setNeedsLayout()
    }
}

@MainActor
final class CameraPreviewContainerView: UIView {
    private var previewLayer: AVCaptureVideoPreviewLayer?

    func install(layer: AVCaptureVideoPreviewLayer) {
        previewLayer?.removeFromSuperlayer()
        previewLayer = layer
        layer.frame = bounds
        layer.videoGravity = .resizeAspectFill
        self.layer.insertSublayer(layer, at: .zero)
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        previewLayer?.frame = bounds
    }
}
