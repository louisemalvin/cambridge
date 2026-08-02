import Foundation

enum IOSApplicationLifecyclePhase: Equatable {
    case active
    case inactive
    case background
}

enum IOSPermissionStatus: Equatable {
    case unknown
    case granted
    case denied
    case notDetermined
}

protocol IOSPlatformLifecycle: AnyObject {
    var phase: IOSApplicationLifecyclePhase { get }

    func applicationDidBecomeActive()
    func applicationWillResignActive()
    func applicationDidEnterBackground()
}

protocol IOSPermissionCoordinator: AnyObject {
    func cameraStatus() -> IOSPermissionStatus
    func requestCameraAccess() async -> IOSPermissionStatus
}

final class StubIOSPlatformLifecycle: IOSPlatformLifecycle {
    private(set) var phase: IOSApplicationLifecyclePhase = .active

    func applicationDidBecomeActive() {
        phase = .active
    }

    func applicationWillResignActive() {
        phase = .inactive
    }

    func applicationDidEnterBackground() {
        phase = .background
    }
}

final class StubIOSPermissionCoordinator: IOSPermissionCoordinator {
    func cameraStatus() -> IOSPermissionStatus {
        .notDetermined
    }

    func requestCameraAccess() async -> IOSPermissionStatus {
        .notDetermined
    }
}
