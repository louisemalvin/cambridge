import SwiftUI

@main
@MainActor
struct CamBridgeApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @State private var environment = AppEnvironment()

    #if DEBUG
    private var usesUITestFixture: Bool {
        ProcessInfo.processInfo.arguments.contains(CamBridgeUITestLaunchConfiguration.fixtureArgument)
    }
    #endif

    var body: some Scene {
        WindowGroup {
            #if DEBUG
            if usesUITestFixture {
                UITestFixtureRootView()
            } else {
                standardRootView
            }
            #else
            standardRootView
            #endif
        }
    }

    private var standardRootView: some View {
        RootView(model: environment.appModel)
            .onChange(of: scenePhase) { _, phase in
                environment.lifecycleController.scenePhaseChanged(phase)
            }
            .onChange(of: environment.appModel.streamSnapshot) { _, snapshot in
                environment.lifecycleController.streamStateChanged(snapshot.state)
            }
    }
}
