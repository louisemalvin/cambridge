// swift-tools-version: 6.0

import PackageDescription

let package = Package(
    name: "CamBridgeCore",
    products: [
        .library(name: "CamBridgeCore", targets: ["CamBridgeCore"]),
    ],
    targets: [
        .target(
            name: "CamBridgeCore",
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
        .testTarget(
            name: "CamBridgeCoreTests",
            dependencies: ["CamBridgeCore"],
            swiftSettings: [.swiftLanguageMode(.v6)]
        ),
    ]
)
