import XCTest
@testable import WhereverCodeAgent

final class FlagshipRoutesParityTests: XCTestCase {
    func testAll70RoutesPresentAndUnique() {
        let all = FlagshipRoutes.all
        XCTAssertEqual(all.count, 70, "70 屏功能路由不得删减，必须严格为 70 个")
        let uniqueIds = Set(all.map(\.id))
        XCTAssertEqual(uniqueIds.count, 70, "所有 route_id 必须全局唯一")
    }

    func testFourDrawerParentsStrictlyEnforced() {
        let parents = FlagshipDrawerParent.allCases.map(\.rawValue)
        XCTAssertEqual(parents, ["对话", "工作台", "控制台", "设置"], "一级抽屉必须严格固定为：对话、工作台、控制台、设置")
        XCTAssertEqual(FlagshipRoutes.routes(for: .chat).count, 5)
        XCTAssertEqual(FlagshipRoutes.routes(for: .workbench).count, 39)
        XCTAssertEqual(FlagshipRoutes.routes(for: .console).count, 17)
        XCTAssertEqual(FlagshipRoutes.routes(for: .settings).count, 9)
    }

    func testTerminalRouteExplicitlyMarksPlatformRestriction() {
        let caps = AppFlagshipCapabilities(appState: nil)
        let termStatus = caps.status(for: "terminal")
        XCTAssertEqual(termStatus.state, .unsupported, "iOS 终端路由必须如实标注 .unsupported（此平台暂不支持本地任意进程），不得伪装本地 Shell")
    }
}
