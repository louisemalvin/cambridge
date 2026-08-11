import SwiftUI

struct RootView: View {
    @Bindable private var model: AppModel

    init(model: AppModel) {
        _model = Bindable(wrappedValue: model)
    }

    var body: some View {
        TabView(selection: $model.route) {
            StreamSetupScreen(model: model.setupModel)
                .tabItem {
                    Label("Setup", systemImage: "antenna.radiowaves.left.and.right")
                        .accessibilityIdentifier("setup-tab")
                }
                .tag(AppModel.Route.setup)
            WebcamScreen(model: model.webcamModel)
                .tabItem {
                    Label("Webcam", systemImage: "video")
                        .accessibilityIdentifier("webcam-tab")
                }
                .tag(AppModel.Route.webcam)
            SettingsScreen(model: model.settingsModel) {
                Task { await model.copyCapabilityReport() }
            }
                .tabItem {
                    Label("Settings", systemImage: "gear")
                        .accessibilityIdentifier("settings-tab")
                }
                .tag(AppModel.Route.settings)
        }
    }
}
