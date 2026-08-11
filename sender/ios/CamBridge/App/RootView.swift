import SwiftUI

struct RootView: View {
    @Bindable private var model: AppModel

    init(model: AppModel) {
        _model = Bindable(wrappedValue: model)
    }

    var body: some View {
        TabView(selection: $model.route) {
            StreamSetupScreen(model: model.setupModel)
                .tabItem { Label("Setup", systemImage: "antenna.radiowaves.left.and.right") }
                .tag(AppModel.Route.setup)
            WebcamScreen(model: model.webcamModel)
                .tabItem { Label("Webcam", systemImage: "video") }
                .tag(AppModel.Route.webcam)
            SettingsScreen(model: model.settingsModel)
                .tabItem { Label("Settings", systemImage: "gear") }
                .tag(AppModel.Route.settings)
        }
    }
}
