import XCTest
import SwiftUI
#if canImport(UIKit)
import UIKit
#endif
@testable import WhereverCodeAgent

final class FlagshipRoutesParityTests: XCTestCase {
    func testAll70RoutesPresentAndUnique() {
        let all = FlagshipRoutes.all
        XCTAssertEqual(all.count, 70, "70 屏功能路由不得删减，必须严格为 70 个")
        let uniqueIds = Set(all.map(\.id))
        XCTAssertEqual(uniqueIds.count, 70, "所有 route_id 必须全局唯一")
    }

    func testDrawerPrimaryMenuAndRouteDomainsEnforced() {
        let menus = FlagshipDrawerMenu.allCases.map(\.rawValue)
        XCTAssertEqual(menus, ["对话", "项目", "控制"], "左侧抽屉一级菜单严格只有：对话、项目、控制")
        let domains = FlagshipDrawerParent.allCases.map(\.rawValue)
        XCTAssertEqual(domains, ["对话", "项目", "控制", "设置"], "功能领域严格划分为：对话、项目、控制、设置")
        XCTAssertEqual(FlagshipRoutes.routes(for: .chat).count, 5)
        XCTAssertEqual(FlagshipRoutes.routes(for: .projects).count, 39)
        XCTAssertEqual(FlagshipRoutes.routes(for: .control).count, 17)
        XCTAssertEqual(FlagshipRoutes.routes(for: .settings).count, 9)
    }

    func testTerminalRouteExplicitlyMarksPlatformRestriction() {
        let caps = AppFlagshipCapabilities(appState: nil)
        let termStatus = caps.status(for: "terminal")
        XCTAssertEqual(termStatus.state, .unsupported, "iOS 终端路由必须如实标注 .unsupported（此平台暂不支持本地任意进程），不得伪装本地 Shell")
    }

    @MainActor
    func testCapabilitiesStatusThreadSafeOnMainAndDetachedBackgroundThreads() async {
        let appState = AppState()
        let caps = AppFlagshipCapabilities(appState: appState)
        let routeIds = FlagshipRoutes.all.map(\.id)

        // 1. MainActor 调用验证
        for routeId in routeIds {
            let status = caps.status(for: routeId)
            XCTAssertFalse(status.evidence.isEmpty, "主线程查询路由 \(routeId) 状态证据不得为空")
        }

        // 2. 后台线程 (Task.detached) 并发调用验证：验证脱离主线程时绝不触发 MainActor.assumeIsolated 崩溃
        let bgResults: [String: CapabilityState] = await Task.detached(priority: .userInitiated) {
            var map: [String: CapabilityState] = [:]
            for routeId in routeIds {
                let status = caps.status(for: routeId)
                map[routeId] = status.state
            }
            return map
        }.value

        XCTAssertEqual(bgResults.count, 70, "后台线程必须安全返回全部 70 个路由状态")
        XCTAssertEqual(bgResults["terminal"], .unsupported)
        XCTAssertEqual(bgResults["harness"], .unsupported)
        XCTAssertEqual(bgResults["channels"], .disconnected)
        XCTAssertEqual(bgResults["mcp"], .disconnected)
    }

    func testInteractiveApprovalStoreConcurrentThreadSafety() async {
        let store = InteractiveApprovalStore()
        await withTaskGroup(of: Void.self) { group in
            for i in 0..<20 {
                group.addTask {
                    let id = "act-\(i)"
                    let action = BrainAction(actionId: id, tool: "shell.exec", idempotencyKey: "idem-\(i)")
                    store.enqueue(action)
                    store.decide(actionId: id, approve: i % 2 == 0)
                }
            }
        }
        XCTAssertEqual(store.items.count, 20)
        XCTAssertEqual(store.auditTrail.count, 40)
    }

    #if canImport(UIKit)
    @MainActor
    func testAll70FlagshipRoutesRenderInSimulatorHostingController() {
        let appState = AppState()
        let chatModel = appState.chat
        let caps = AppFlagshipCapabilities(appState: appState)

        // 挂载顶层 Claude 风格无 Tab 壳层与 4 父级抽屉
        let shellHost = UIHostingController(rootView: FlagshipShellView(appState: appState, chatModel: chatModel))
        shellHost.loadViewIfNeeded()
        shellHost.view.frame = CGRect(x: 0, y: 0, width: 393, height: 852)
        shellHost.view.setNeedsLayout()
        shellHost.view.layoutIfNeeded()
        XCTAssertEqual(shellHost.view.bounds.width, 393)

        // 逐屏挂载全部 70 个路由的原生 SwiftUI 页面并执行布局渲染
        for route in FlagshipRoutes.all {
            let destView = NavigationStack {
                FlagshipRouteDestinationView(
                    route: route,
                    appState: appState,
                    chatModel: chatModel,
                    capabilities: caps,
                    onNavigateRoute: { _ in }
                )
            }
            let host = UIHostingController(rootView: destView)
            host.loadViewIfNeeded()
            host.view.frame = CGRect(x: 0, y: 0, width: 393, height: 852)
            host.view.setNeedsLayout()
            host.view.layoutIfNeeded()
            XCTAssertEqual(host.view.bounds.size, CGSize(width: 393, height: 852), "路由 \(route.id) 在模拟器 UIHostingController 中挂载布局失败")
        }
    }
    #endif
}
