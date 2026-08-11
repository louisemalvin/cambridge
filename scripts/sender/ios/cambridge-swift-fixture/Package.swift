// swift-tools-version: 6.0

import PackageDescription

let package = Package(
    name: "CamBridgeSwiftFixture",
    products: [
        .executable(name: "cambridge-swift-fixture", targets: ["CamBridgeSwiftFixture"]),
    ],
    dependencies: [
        .package(path: "../../../../sender/ios/Packages/CamBridgeCore"),
    ],
    targets: [
        .executableTarget(
            name: "CamBridgeSwiftFixture",
            dependencies: [
                .product(name: "CamBridgeCore", package: "CamBridgeCore"),
            ]
        ),
    ]
)
