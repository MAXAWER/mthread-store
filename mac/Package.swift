// swift-tools-version:5.10
import PackageDescription

let package = Package(
    name: "MThreadStoreAdmin",
    platforms: [.macOS(.v14)],
    targets: [
        .executableTarget(name: "MThreadStoreAdmin", path: "Sources/MThreadStoreAdmin"),
    ]
)
