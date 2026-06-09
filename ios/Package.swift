// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "MritMesh",
    platforms: [
        .iOS(.v14)   // CryptoKit AES-GCM + MultipeerConnectivity, minimum iOS 14
    ],
    products: [
        .library(name: "MritMesh", targets: ["MritMesh"])
    ],
    targets: [
        .target(
            name: "MritMesh",
            dependencies: [],
            path: "Sources/MritMesh"
        ),
        .testTarget(
            name: "MritMeshTests",
            dependencies: ["MritMesh"],
            path: "Tests/MritMeshTests"
        )
    ]
)
