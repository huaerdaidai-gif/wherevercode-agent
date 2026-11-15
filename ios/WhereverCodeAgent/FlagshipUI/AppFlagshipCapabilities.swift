import Foundation

public struct AppFlagshipCapabilities: FlagshipCapabilityProvider {
    public let appState: AppState?

    /// 具备真实本地交互、SQLite 持久化、Keychain 存储、沙盒文件读写或 MCP/HTTPS 网络调用的 39 个路由（尚未在真机完成端到端 UI 验收，诚实标记为 IMPLEMENTED_UNVERIFIED）
    public static let implementedRoutes: Set<String> = [
        // 对话 (5)
        "chat", "conversation", "history", "search", "voice",
        // 工作台 - 任务 (8)
        "compose", "tasks", "taskdetail", "plan", "queue", "approvals", "review", "artifacts",
        // 工作台 - 开发 (5)
        "projects", "projectdetail", "files", "editor", "diff",
        // 工作台 - 协作 (5)
        "channels", "channel", "agents", "agentdetail", "delegate",
        // 工作台 - 连接 (5)
        "models", "mcp", "skills", "connections", "connectmodel",
        // 控制台 - 系统 (5)
        "dashboard", "activity", "settings", "onboarding", "audit",
        // 设置 - 智能 (6)
        "memory", "skillsmarket", "skillbuilder", "context", "research", "personas"
    ]

    /// 已有本地状态/协议定义，但依赖远端 Hand 配对或服务端尚未开放的 MCP 接口（18 个路由，诚实标记为 ADAPTER_PENDING）
    public static let adapterPendingRoutes: Set<String> = [
        "git", "team", "agentresume", "handoff",
        "devices", "devicedetail", "modelroute", "agentimport", "network", "routes",
        "notifications", "usage", "sync", "recovery", "loopguard", "templates",
        "extensions", "extensiondetail"
    ]

    /// 受 iOS App Sandbox 平台限制不支持本机任意 Shell / 外部 CLI 二进制执行的路由（2 个路由，诚实标记为 UNSUPPORTED）
    public static let platformRestrictedRoutes: Set<String> = [
        "terminal", "harness"
    ]

    /// 依赖平台 MCP 在线连接且在未配置平台 Token 时动态提示未连接的路由
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
                evidence: "UNSUPPORTED：iOS 沙盒禁止本地 fork/exec 任意 Shell 或外部 CLI 进程；提供真实进程内日志流并支持切换远端 Hand 设备或云端 MCP Agent。"
            )
        }
        if Self.platformConnectionDependentRoutes.contains(routeId),
           let appState, !Self.resolveIsConfigured(appState) {
            return CapabilityStatus(
                state: .disconnected,
                evidence: "ADAPTER_PENDING / DISCONNECTED：尚未配置 V5 平台 HTTPS 地址或 Keychain Token；请在「设置 -> 平台连接」中配置。"
            )
        }
        if Self.implementedRoutes.contains(routeId) {
            return CapabilityStatus(
                state: .implementedUnverified,
                evidence: "IMPLEMENTED_UNVERIFIED：已接入 iOS 本地 SQLite / Keychain / BrainEngine / MCP 客户端，待 macOS Xcode 与真机端到端验收。"
            )
        }
        if Self.adapterPendingRoutes.contains(routeId) {
            return CapabilityStatus(
                state: .planned,
                evidence: "ADAPTER_PENDING：本地模型/状态结构已就绪，待服务端 MCP 扩充接口或远端 Hand 设备配对联调。"
            )
        }
        return CapabilityStatus(
            state: .planned,
            evidence: "DESIGN_ONLY：架构与契约预留页面，未接入后端服务，不伪造完成状态。"
        )
    }

    public func openExistingFeature(_ routeId: String) -> Bool {
        Self.implementedRoutes.contains(routeId)
    }

    private static func resolveIsConfigured(_ appState: AppState) -> Bool {
        if Thread.isMainThread {
            return MainActor.assumeIsolated { appState.isConfigured }
        }
        let persistedSettings = AppSettings.load()
        guard persistedSettings.mcpEndpoint != nil,
              let token = try? appState.keychain.loadToken(),
              !token.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return false
        }
        return true
    }
}
