import SwiftUI

struct WebcamScreen: View {
    @Bindable var model: WebcamModel
    let onShowSettings: () -> Void

    init(model: WebcamModel, onShowSettings: @escaping () -> Void = {}) {
        self.model = model
        self.onShowSettings = onShowSettings
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            CameraPreviewView(capture: model.capture)
                .ignoresSafeArea()
            VStack {
                HStack {
                    Label(model.statusText, systemImage: "circle.fill")
                        .foregroundStyle(.white)
                        .padding(.horizontal)
                        .padding(.vertical, WebcamScreenMetrics.statusVerticalPadding)
                        .background(.black.opacity(WebcamScreenMetrics.statusBackgroundOpacity), in: Capsule())
                    Spacer()
                    Button(action: onShowSettings) {
                        Image(systemName: "gear")
                    }
                    .accessibilityLabel("Settings")
                    .accessibilityIdentifier("webcam-settings")
                    .buttonStyle(.borderedProminent)
                    Button {
                        model.toggleDimmedPresentation()
                    } label: {
                        Image(systemName: model.isDimmed ? "sun.max" : "moon")
                    }
                    .accessibilityLabel(model.isDimmed ? "Brighten screen" : "Dim screen")
                    .buttonStyle(.borderedProminent)
                }
                .padding()
                Spacer()
                CameraControlsView(model: model)
                HStack {
                    Button("Stop stream", role: .destructive) { model.requestStop() }
                        .buttonStyle(.borderedProminent)
                        .accessibilityIdentifier("stop-stream")
                    if model.failure != nil {
                        Button("Copy diagnostics") { model.copyDiagnostics() }
                            .accessibilityIdentifier("webcam-copy-diagnostics")
                    }
                }
                .padding(.bottom)
            }
            if model.isDimmed {
                Color.black.opacity(WebcamScreenMetrics.dimmedOverlayOpacity)
                    .ignoresSafeArea()
                    .allowsHitTesting(false)
            }
        }
        .confirmationDialog("Stop streaming?", isPresented: $model.isStopConfirmationPresented, titleVisibility: .visible) {
            Button("Stop stream", role: .destructive) { Task { await model.confirmStop() } }
            Button("Keep streaming", role: .cancel) { model.cancelStopRequest() }
        } message: {
            Text("The receiver session will be closed. Start again from setup when you are ready.")
        }
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
    }
}

private enum WebcamScreenMetrics {
    static let statusVerticalPadding: CGFloat = 8
    static let statusBackgroundOpacity: Double = 0.55
    static let dimmedOverlayOpacity: Double = 0.88
}
