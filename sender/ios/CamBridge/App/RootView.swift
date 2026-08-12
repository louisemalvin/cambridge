import SwiftUI

struct RootView: View {
    @Bindable private var model: AppModel
    @State private var path: [AppModel.Route] = []

    init(model: AppModel) {
        _model = Bindable(wrappedValue: model)
    }

    var body: some View {
        NavigationStack(path: $path) {
            settingsScreen
                .navigationDestination(for: AppModel.Route.self) { route in
                    switch route {
                    case .setup:
                        StreamSetupScreen(model: model.setupModel)
                    case .webcam:
                        WebcamScreen(model: model.webcamModel) {
                            path.append(.settings)
                        }
                    case .settings:
                        settingsScreen
                    }
                }
        }
        .onAppear {
            path = navigationPath(for: model.route)
        }
        .onChange(of: model.route) { _, route in
            path = navigationPath(for: route)
        }
        .onChange(of: path) { _, newPath in
            guard newPath.isEmpty, model.route != .settings else { return }
            model.route = .settings
        }
    }

    private var settingsScreen: some View {
        SettingsScreen(
            model: model.settingsModel,
            onCopyCapabilityReport: {
                Task { await model.copyCapabilityReport() }
            },
            onOpenStreamSetup: {
                model.route = .setup
            }
        )
    }

    private func navigationPath(for route: AppModel.Route) -> [AppModel.Route] {
        switch route {
        case .settings:
            []
        case .setup:
            [.setup]
        case .webcam:
            [.setup, .webcam]
        }
    }
}
