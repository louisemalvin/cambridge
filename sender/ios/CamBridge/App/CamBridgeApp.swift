import SwiftUI

@main
@MainActor
struct CamBridgeApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @State private var environment = AppEnvironment()

    var body: some Scene {
        WindowGroup {
            RootView(model: environment.appModel)
                .onChange(of: scenePhase) { _, phase in
                    environment.lifecycleController.scenePhaseChanged(phase)
                }
                .onChange(of: environment.appModel.streamSnapshot) { _, snapshot in
                    environment.lifecycleController.streamStateChanged(snapshot.state)
                }
        }
    }
}
