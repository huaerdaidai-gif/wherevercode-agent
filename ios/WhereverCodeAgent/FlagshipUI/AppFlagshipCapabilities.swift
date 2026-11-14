import Foundation

public struct AppFlagshipCapabilities: FlagshipCapabilityProvider {
    public let appState: AppState?

    /// 已完成真实 iOS 本地/SQLite/Keychain/MCP/Brain 接线的路由集合（按诚实门禁标为 .implementedUnverified，待真机端到端验收后升为 .readyVerified）
    public static let implementedRoutes: Set<String> = [
        // 对话 (5/5)
        "chat", "conversation", "history", "search", "voice",
        // 工作台 - 任务 (8/8)
        "compose", "tasks", "taskdetail", "plan", "queue", "approvals", "review", "artifacts",
        // 工作台 - 开发 (7/10 真实沙盒/SQLite接线)
        "projects", "projectdetail", "files", "editor", "diff", "git", "testmatrix",
        // 工作台 - 协作 (6/10)
        "team", "channels", "channel", "agents", "agentdetail", "delegate",
        // 工作台 - 连接 (9/11)
        "devices", "devicedetail", "models", "mcp", "skills", "connections", "connectmodel", "modelroute", "agentimport",
        // 控制台 - 系统 (9/10)
        "dashboard", "activity", "settings", "onboarding", "audit", "notifications", "usage", "recovery", "baseline",
        // 控制台 - 自动化 (2/7)
        "loopguard", "templates",
        // 设置 - 智能 (6/6)
        "memory", "skillsmarket", "skillbuilder", "context", "research", "personas",
        // 设置 - 扩展 (3/3)
        "extensions", "extensiondetail", "harness"
    ]

    /// 受 iOS 沙盒平台限制不支持本地任意进程/后台常驻唤醒的路由或需特殊标注的路由
    public static let platformRestrictedRoutes: Set<String> = [
        "terminal"
    ]

    /// 依赖平台 MCP 在线连接且在未配置平台 Token 时动态返回 .disconnected 的路由
    public static let platformConnectionDependentRoutes: Set<String> = [
        "channels", "channel", "mcp"
    ]

    public static let routeToTab: [String: AppTab] = [
        "chat": .chat,
        "conversation": .chat,
        "history": .chat,
        "tasks": .tasks,
        "taskdetail": .tasks,
        "compose": .tasks,
        "projects": .projects,
        "projectdetail": .projects,
        "agents": .agents,
        "agentdetail": .agents,
        "channels": .channels,
        "channel": .channels,
        "devices": .devices,
        "devicedetail": .devices,
        "dashboard": .console,
        "activity": .console,
        "settings": .settings,
        "models": .settings,
        "connectmodel": .settings,
        "connections": .settings
    ]

    public init(appState: AppState? = nil) {
        self.appState = appState
    }

    public func status(for routeId: String) -> CapabilityStatus {
        if Self.platformRestrictedRoutes.contains(routeId) {
            return CapabilityStatus(
                state: .unsupported,
                evidence: "iOS 沙盒禁止本地 fork/exec 任意 Shell 进程；本页提供真实进程内 EngineEvent 日志流，并支持切换远端 Hand 设备或云端 MCP Agent 执行。"
            )
        }
        if Self.platformConnectionDependentRoutes.contains(routeId),
           let appState, !appState.isConfigured {
            return CapabilityStatus(
                state: .disconnected,
                evidence: "尚未配置 V5 平台 HTTPS 地址或 Keychain Token；请在「设置 -> 平台连接」中配置。"
            )
        }
        if Self.implementedRoutes.contains(routeId) {
            return CapabilityStatus(
                state: .implementedUnverified,
                evidence: "已接入 iOS 本地 SQLite / Keychain / BrainEngine / MCP 客户端，待真机端到端验收。"
            )
        }
        return CapabilityStatus(
            state: .planned,
            evidence: "V5 服务端或跨端协议尚未提供对应后端接口（ADAPTER_PENDING / DESIGN_ONLY），保留统一契约视图。"
        )
    }

    public func openExistingFeature(_ routeId: String) -> Bool {
        Self.implementedRoutes.contains(routeId)
    }
}
