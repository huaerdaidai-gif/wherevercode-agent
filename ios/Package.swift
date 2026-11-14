// swift-tools-version: 5.10
import PackageDescription

let package = Package(
    name: "WhereverCodeAgent",
    platforms: [
        .iOS(.v17),
        .macOS(.v14)
    ],
    products: [
        .library(name: "WhereverCodeAgent", targets: ["WhereverCodeAgent"])
    ],
    targets: [
        .target(
            name: "WhereverCodeAgent",
            path: "WhereverCodeAgent",
            exclude: ["Info.plist", "WhereverCodeAgent.entitlements"],
            linkerSettings: [
                .linkedLibrary("sqlite3")
            ]
        ),
        .testTarget(
            name: "WhereverCodeAgentTests",
            dependencies: ["WhereverCodeAgent"],
            path: "WhereverCodeAgentTests"
        )
    ]
)
