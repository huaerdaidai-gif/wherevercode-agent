import SwiftUI

/// 左侧抽屉一级菜单严格只有：对话、项目、控制
public enum FlagshipDrawerMenu: String, CaseIterable, Identifiable, Sendable {
    case chat = "对话"
    case projects = "项目"
    case control = "控制"

    public var id: String { rawValue }

    public var iconName: String {
        switch self {
        case .chat: return "bubble.left.and.bubble.right"
        case .projects: return "shippingbox"
        case .control: return "gauge.with.dots.needle.bottom.50percent"
        }
    }
}

/// 70 屏功能领域分类（原「工作台」改为「项目」，原「控制台」改为「控制」，取消「设置」抽屉一级菜单并收纳至底部账号设置）
public enum FlagshipDrawerParent: String, CaseIterable, Identifiable, Sendable {
    case chat = "对话"
    case projects = "项目"
    case control = "控制"
    case settings = "设置"

    public var id: String { rawValue }

    public var iconName: String {
        switch self {
        case .chat: return "bubble.left.and.bubble.right"
        case .projects: return "shippingbox"
        case .control: return "gauge.with.dots.needle.bottom.50percent"
        case .settings: return "gearshape"
        }
    }
}

/// 终端与代码执行环境四分类（严禁将远端执行伪装成本地执行）
public enum ExecutionTargetKind: String, CaseIterable, Identifiable, Sendable {
    case iphoneLocalSandbox = "iphone_local_sandbox"
    case remoteDeviceHand = "remote_device_hand"
    case cloudAgentMcp = "cloud_agent_mcp"
    case unsupportedOnPlatform = "unsupported_on_platform"

    public var id: String { rawValue }

    public var badgeTitle: String {
        switch self {
        case .iphoneLocalSandbox: return "iPhone 本地真实执行 (Sandbox)"
        case .remoteDeviceHand: return "远端设备执行 (Hand 协议)"
        case .cloudAgentMcp: return "云端 Agent 执行 (V5 MCP)"
        case .unsupportedOnPlatform: return "当前平台不支持 (iOS 沙盒禁止任意进程)"
        }
    }
}

public struct FlagshipRoute: Identifiable, Hashable, Sendable {
    public let id: String
    public let title: String
    public let parent: FlagshipDrawerParent
    public let group: String
    public let p0: Bool

    public init(id: String, title: String, parent: FlagshipDrawerParent, group: String, p0: Bool) {
        self.id = id
        self.title = title
        self.parent = parent
        self.group = group
        self.p0 = p0
    }
}

public enum FlagshipRoutes {
    /// 70 屏完整路由注册表（与 Android 及 contracts/routes_wiring.json 100% 对齐）
    public static let all: [FlagshipRoute] = [
        .init(id: "chat", title: "对话首页", parent: .chat, group: "对话", p0: true),
        .init(id: "conversation", title: "执行中的对话", parent: .chat, group: "对话", p0: true),
        .init(id: "history", title: "历史会话", parent: .chat, group: "对话", p0: true),
        .init(id: "search", title: "全局搜索", parent: .chat, group: "对话", p0: false),
        .init(id: "compose", title: "新建任务", parent: .projects, group: "任务", p0: true),
        .init(id: "tasks", title: "任务中心", parent: .projects, group: "任务", p0: true),
        .init(id: "taskdetail", title: "任务详情", parent: .projects, group: "任务", p0: true),
        .init(id: "plan", title: "计划与检查点", parent: .projects, group: "任务", p0: false),
        .init(id: "queue", title: "队列与调度", parent: .projects, group: "任务", p0: false),
        .init(id: "approvals", title: "审批中心", parent: .projects, group: "任务", p0: true),
        .init(id: "review", title: "代码审查", parent: .projects, group: "任务", p0: false),
        .init(id: "artifacts", title: "交付成果", parent: .projects, group: "任务", p0: true),
        .init(id: "projects", title: "项目列表", parent: .projects, group: "开发", p0: true),
        .init(id: "projectdetail", title: "项目概览", parent: .projects, group: "开发", p0: true),
        .init(id: "files", title: "文件浏览器", parent: .projects, group: "开发", p0: true),
        .init(id: "editor", title: "代码编辑器", parent: .projects, group: "开发", p0: true),
        .init(id: "diff", title: "变更对比", parent: .projects, group: "开发", p0: true),
        .init(id: "terminal", title: "终端与日志", parent: .projects, group: "开发", p0: true),
        .init(id: "git", title: "Git 与工作树", parent: .projects, group: "开发", p0: false),
        .init(id: "preview", title: "运行预览", parent: .projects, group: "开发", p0: false),
        .init(id: "team", title: "团队空间", parent: .projects, group: "协作", p0: false),
        .init(id: "channels", title: "团队频道", parent: .projects, group: "协作", p0: false),
        .init(id: "channel", title: "频道会话", parent: .projects, group: "协作", p0: false),
        .init(id: "agents", title: "Agent 列表", parent: .projects, group: "协作", p0: true),
        .init(id: "agentdetail", title: "Agent 详情", parent: .projects, group: "协作", p0: true),
        .init(id: "delegate", title: "协作编排", parent: .projects, group: "协作", p0: false),
        .init(id: "devices", title: "设备列表", parent: .projects, group: "连接", p0: true),
        .init(id: "devicedetail", title: "设备详情", parent: .projects, group: "连接", p0: false),
        .init(id: "models", title: "模型与提供方", parent: .projects, group: "连接", p0: true),
        .init(id: "mcp", title: "MCP 与插件", parent: .projects, group: "连接", p0: false),
        .init(id: "skills", title: "技能与自动化", parent: .projects, group: "连接", p0: false),
        .init(id: "connections", title: "账号与连接", parent: .projects, group: "连接", p0: false),
        .init(id: "dashboard", title: "全局工作台", parent: .control, group: "系统", p0: true),
        .init(id: "activity", title: "事件与通知", parent: .control, group: "系统", p0: false),
        .init(id: "settings", title: "设置", parent: .control, group: "系统", p0: true),
        .init(id: "onboarding", title: "首次使用", parent: .control, group: "系统", p0: true),
        .init(id: "connectmodel", title: "连接模型向导", parent: .projects, group: "连接", p0: true),
        .init(id: "modelroute", title: "智能模型路由", parent: .projects, group: "连接", p0: false),
        .init(id: "agentimport", title: "导入现有 Agent", parent: .projects, group: "连接", p0: false),
        .init(id: "agentresume", title: "Agent 会话续接", parent: .projects, group: "协作", p0: false),
        .init(id: "autoloop", title: "Auto-loop 自主循环", parent: .control, group: "自动化", p0: false),
        .init(id: "loopguard", title: "循环安全护栏", parent: .control, group: "自动化", p0: false),
        .init(id: "memory", title: "长期记忆", parent: .settings, group: "智能", p0: false),
        .init(id: "skillsmarket", title: "技能市场", parent: .settings, group: "智能", p0: false),
        .init(id: "skillbuilder", title: "技能自生成", parent: .settings, group: "智能", p0: false),
        .init(id: "context", title: "上下文管理", parent: .settings, group: "智能", p0: false),
        .init(id: "research", title: "深度研究", parent: .settings, group: "智能", p0: false),
        .init(id: "browser", title: "浏览器工作台", parent: .projects, group: "开发", p0: false),
        .init(id: "testmatrix", title: "测试矩阵", parent: .projects, group: "开发", p0: false),
        .init(id: "agentcompare", title: "多 Agent 对比", parent: .projects, group: "协作", p0: false),
        .init(id: "orchestrator", title: "可视化编排", parent: .projects, group: "协作", p0: false),
        .init(id: "handoff", title: "跨设备接力", parent: .projects, group: "协作", p0: false),
        .init(id: "audit", title: "权限与审计", parent: .control, group: "系统", p0: false),
        .init(id: "notifications", title: "消息中心", parent: .control, group: "系统", p0: false),
        .init(id: "voice", title: "语音与多模态", parent: .chat, group: "对话", p0: false),
        .init(id: "personas", title: "Agent 个性与配置", parent: .settings, group: "智能", p0: false),
        .init(id: "usage", title: "成本与额度", parent: .control, group: "系统", p0: false),
        .init(id: "templates", title: "工作流模板", parent: .control, group: "自动化", p0: false),
        .init(id: "sync", title: "跨端同步", parent: .control, group: "系统", p0: false),
        .init(id: "recovery", title: "恢复与回滚", parent: .control, group: "系统", p0: false),
        .init(id: "automation", title: "自动化中心", parent: .control, group: "自动化", p0: false),
        .init(id: "automaker", title: "创建自动化", parent: .control, group: "自动化", p0: false),
        .init(id: "triggers", title: "触发器与唤醒", parent: .control, group: "自动化", p0: false),
        .init(id: "wakepolicy", title: "唤醒与自治策略", parent: .control, group: "自动化", p0: false),
        .init(id: "network", title: "通信网络", parent: .projects, group: "连接", p0: false),
        .init(id: "routes", title: "网络路径详情", parent: .projects, group: "连接", p0: false),
        .init(id: "extensions", title: "扩展中心", parent: .settings, group: "扩展", p0: false),
        .init(id: "extensiondetail", title: "插件权限与安装", parent: .settings, group: "扩展", p0: false),
        .init(id: "harness", title: "Harness 运行时", parent: .settings, group: "扩展", p0: false),
        .init(id: "baseline", title: "Claude 18 屏验收", parent: .control, group: "系统", p0: false)
    ]

    public static func route(for id: String) -> FlagshipRoute? {
        all.first { $0.id == id }
    }

    public static func find(_ id: String) -> FlagshipRoute {
        route(for: id) ?? all[0]
    }

    public static func routes(for parent: FlagshipDrawerParent) -> [FlagshipRoute] {
        all.filter { $0.parent == parent }
    }

    public static func groups(for parent: FlagshipDrawerParent) -> [String] {
        var seen: Set<String> = []
        var result: [String] = []
        for r in routes(for: parent) {
            if seen.insert(r.group).inserted {
                result.append(r.group)
            }
        }
        return result
    }
}

public enum CapabilityState: String, Codable, Sendable {
    case planned = "待开发"
    case implementedUnverified = "待验收"
    case readyVerified = "可用"
    case disconnected = "未连接"
    case failed = "执行失败"
    case unsupported = "此平台暂不支持"
}

public struct CapabilityStatus: Equatable, Sendable {
    public let state: CapabilityState
    public let evidence: String

    public init(state: CapabilityState, evidence: String = "") {
        self.state = state
        self.evidence = evidence
    }
}

public protocol FlagshipCapabilityProvider {
    func status(for routeId: String) -> CapabilityStatus
    func openExistingFeature(_ routeId: String) -> Bool
}

public struct PlannedCapabilities: FlagshipCapabilityProvider {
    public init() {}
    public func status(for routeId: String) -> CapabilityStatus {
        CapabilityStatus(state: .planned, evidence: "未接入真实服务或未完成真机验证")
    }
    public func openExistingFeature(_ routeId: String) -> Bool { false }
}

/// 左侧抽屉组件
/// 严格结构要求：
/// 1. 一级菜单严格只有：对话、项目、控制
/// 2. 中部显示最近会话（加载自本地真实会话记录）
/// 3. 底部固定：左侧账号头像（点击进入设置），右侧「＋ 新对话」
public struct FlagshipDrawerView: View {
    @Binding public var isOpen: Bool
    @Binding public var selectedMenu: FlagshipDrawerMenu
    public var onSelectConversation: (Conversation) -> Void
    public var onOpenSettings: () -> Void
    public var onNewChat: () -> Void

    @State private var recentConversations: [Conversation] = []

    public init(
        isOpen: Binding<Bool>,
        selectedMenu: Binding<FlagshipDrawerMenu>,
        onSelectConversation: @escaping (Conversation) -> Void,
        onOpenSettings: @escaping () -> Void,
        onNewChat: @escaping () -> Void
    ) {
        self._isOpen = isOpen
        self._selectedMenu = selectedMenu
        self.onSelectConversation = onSelectConversation
        self.onOpenSettings = onOpenSettings
        self.onNewChat = onNewChat
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // 抽屉品牌头
            HStack(alignment: .center) {
                Text("WhereverCode")
                    .font(.system(size: 24, weight: .regular, design: .serif))
                    .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                Spacer()
                Button {
                    withAnimation(.easeOut(duration: 0.2)) { isOpen = false }
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 15, weight: .medium))
                        .foregroundStyle(.secondary)
                        .frame(width: 36, height: 36)
                        .background(Color.white.opacity(0.8), in: Circle())
                }
                .accessibilityIdentifier("drawer.close")
                .accessibilityLabel("关闭抽屉")
            }
            .padding(.horizontal, 20)
            .padding(.top, 20)
            .padding(.bottom, 14)

            // 一级菜单：严格只有「对话」「项目」「控制」
            VStack(spacing: 4) {
                ForEach(FlagshipDrawerMenu.allCases) { menu in
                    let isSelected = (selectedMenu == menu)
                    Button {
                        selectedMenu = menu
                        withAnimation(.easeOut(duration: 0.2)) { isOpen = false }
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: menu.iconName)
                                .font(.system(size: 16, weight: .medium))
                                .frame(width: 24)
                                .foregroundStyle(isSelected ? Color(red: 0.18, green: 0.17, blue: 0.16) : .secondary)
                            Text(menu.rawValue)
                                .font(.system(size: 16, weight: isSelected ? .semibold : .regular))
                                .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                            Spacer()
                        }
                        .padding(.horizontal, 14)
                        .padding(.vertical, 12)
                        .background(
                            isSelected ? Color(red: 0.90, green: 0.89, blue: 0.86) : Color.clear,
                            in: RoundedRectangle(cornerRadius: 12)
                        )
                    }
                    .accessibilityIdentifier("drawer.menu.\(menu.rawValue)")
                    .accessibilityIdentifier("drawer.parent.\(menu.rawValue)")
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 10)

            Divider()
                .padding(.horizontal, 16)
                .padding(.vertical, 6)

            // 中部显示最近会话
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text("最近会话")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(.secondary)
                    Spacer()
                }
                .padding(.horizontal, 20)
                .padding(.top, 8)
                .padding(.bottom, 4)

                ScrollView {
                    VStack(alignment: .leading, spacing: 2) {
                        if recentConversations.isEmpty {
                            VStack(alignment: .leading, spacing: 4) {
                                Text("暂无历史会话")
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                                Text("点击右下角「＋ 新对话」开始")
                                    .font(.caption2)
                                    .foregroundStyle(.secondary.opacity(0.8))
                            }
                            .padding(.horizontal, 20)
                            .padding(.vertical, 16)
                        } else {
                            ForEach(recentConversations) { conv in
                                Button {
                                    onSelectConversation(conv)
                                    withAnimation(.easeOut(duration: 0.2)) { isOpen = false }
                                } label: {
                                    HStack(spacing: 10) {
                                        Image(systemName: "bubble.left")
                                            .font(.system(size: 13))
                                            .foregroundStyle(.secondary)
                                        Text(conv.title)
                                            .font(.system(size: 14))
                                            .foregroundStyle(Color(red: 0.20, green: 0.19, blue: 0.18))
                                            .lineLimit(1)
                                        Spacer()
                                    }
                                    .padding(.horizontal, 14)
                                    .padding(.vertical, 10)
                                    .background(Color.white.opacity(0.4), in: RoundedRectangle(cornerRadius: 10))
                                }
                                .accessibilityIdentifier("drawer.conversation.\(conv.id)")
                            }
                        }
                    }
                    .padding(.horizontal, 16)
                }
            }
            .frame(maxHeight: .infinity)

            Divider()
                .padding(.horizontal, 16)

            // 底部固定：左侧账号头像（点击进入设置），右侧「＋ 新对话」
            HStack(spacing: 10) {
                Button {
                    withAnimation(.easeOut(duration: 0.2)) { isOpen = false }
                    onOpenSettings()
                } label: {
                    HStack(spacing: 10) {
                        Circle()
                            .fill(LinearGradient(
                                colors: [Color(red: 0.82, green: 0.49, blue: 0.35), Color(red: 0.70, green: 0.35, blue: 0.20)],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            ))
                            .frame(width: 36, height: 36)
                            .overlay(
                                Text("W")
                                    .font(.system(size: 15, weight: .bold))
                                    .foregroundStyle(.white)
                            )
                        VStack(alignment: .leading, spacing: 2) {
                            Text("开发者")
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                            Text("设置与配置")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .padding(.vertical, 6)
                    .padding(.horizontal, 4)
                }
                .accessibilityIdentifier("drawer.userProfile")
                .accessibilityIdentifier("drawer.accountSettings")
                .accessibilityIdentifier("drawer.parent.设置")

                Spacer()

                Button {
                    withAnimation(.easeOut(duration: 0.2)) { isOpen = false }
                    onNewChat()
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "plus")
                            .font(.system(size: 13, weight: .semibold))
                        Text("新对话")
                            .font(.system(size: 14, weight: .medium))
                    }
                    .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .background(Color.white, in: Capsule())
                    .overlay(Capsule().stroke(Color(red: 0.85, green: 0.84, blue: 0.82), lineWidth: 1))
                }
                .accessibilityIdentifier("drawer.newChat")
                .accessibilityIdentifier("drawer.route.chat")
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .background(Color(red: 0.94, green: 0.94, blue: 0.92))
        }
        .frame(maxWidth: 320, maxHeight: .infinity)
        .background(Color(red: 0.969, green: 0.969, blue: 0.961).ignoresSafeArea())
        .shadow(color: .black.opacity(0.12), radius: 16, x: 6, y: 0)
        .onAppear(perform: loadRecentConversations)
    }

    private func loadRecentConversations() {
        recentConversations = (try? LocalData.shared.conversations.listConversations()) ?? []
    }
}

/// 顶层无 TabBar 壳层：
/// 根页面根据当前一级抽屉（对话、项目、控制）切换主工作区，支持左滑或点击顶部汉堡按钮呼出抽屉；
/// 底部头像直接唤起账号设置；全部 70 个二级/三级路由均保留并通过业务入口与开发者诊断直达。
public struct FlagshipShellView: View {
    @ObservedObject public var appState: AppState
    @ObservedObject public var chatModel: LocalChatModel
    @State private var isDrawerOpen = false
    @State private var selectedMenu: FlagshipDrawerMenu = .chat
    @State private var activeRoute: FlagshipRoute? = nil
    @State private var isSettingsOpen = false

    public init(appState: AppState, chatModel: LocalChatModel) {
        self.appState = appState
        self.chatModel = chatModel
    }

    private var capabilities: AppFlagshipCapabilities {
        AppFlagshipCapabilities(appState: appState)
    }

    public var body: some View {
        ZStack(alignment: .leading) {
            NavigationStack {
                Group {
                    switch selectedMenu {
                    case .chat:
                        LocalChatHomeView(
                            appState: appState,
                            model: chatModel,
                            onOpenDrawer: {
                                withAnimation(.easeOut(duration: 0.2)) { isDrawerOpen = true }
                            },
                            onSelectRoute: { routeId in
                                navigateTo(routeId)
                            }
                        )
                    case .projects:
                        ProjectsHomeView(
                            appState: appState,
                            onOpenDrawer: {
                                withAnimation(.easeOut(duration: 0.2)) { isDrawerOpen = true }
                            },
                            onNavigateRoute: { routeId in
                                navigateTo(routeId)
                            }
                        )
                    case .control:
                        ControlHomeView(
                            appState: appState,
                            onOpenDrawer: {
                                withAnimation(.easeOut(duration: 0.2)) { isDrawerOpen = true }
                            },
                            onNavigateRoute: { routeId in
                                navigateTo(routeId)
                            }
                        )
                    }
                }
                .navigationDestination(item: $activeRoute) { route in
                    FlagshipRouteDestinationView(
                        route: route,
                        appState: appState,
                        chatModel: chatModel,
                        capabilities: capabilities,
                        onNavigateRoute: { nextId in
                            navigateTo(nextId)
                        }
                    )
                    .toolbar {
                        ToolbarItem(placement: .topBarLeading) {
                            Button {
                                withAnimation(.easeOut(duration: 0.2)) { isDrawerOpen = true }
                            } label: {
                                Image(systemName: "line.3.horizontal")
                            }
                            .accessibilityIdentifier("navigation.menu")
                            .accessibilityLabel("打开导航抽屉")
                        }
                    }
                }
            }
            .sheet(isPresented: $isSettingsOpen) {
                NavigationStack {
                    AccountSettingsView(
                        appState: appState,
                        chatModel: chatModel,
                        capabilities: capabilities,
                        onNavigateRoute: { routeId in
                            isSettingsOpen = false
                            navigateTo(routeId)
                        },
                        onDismiss: {
                            isSettingsOpen = false
                        }
                    )
                }
            }
            .onAppear {
                appState.onSelectRoute = { nextId in
                    navigateTo(nextId)
                }
            }

            // 抽屉背景半透明遮罩与侧滑抽屉
            if isDrawerOpen {
                Color.black.opacity(0.28)
                    .ignoresSafeArea()
                    .onTapGesture {
                        withAnimation(.easeOut(duration: 0.2)) { isDrawerOpen = false }
                    }

                FlagshipDrawerView(
                    isOpen: $isDrawerOpen,
                    selectedMenu: $selectedMenu,
                    onSelectConversation: { conv in
                        chatModel.open(conv)
                        selectedMenu = .chat
                        activeRoute = nil
                    },
                    onOpenSettings: {
                        isSettingsOpen = true
                    },
                    onNewChat: {
                        chatModel.newConversation()
                        selectedMenu = .chat
                        activeRoute = nil
                    }
                )
                .transition(.move(edge: .leading))
            }
        }
    }

    private func navigateTo(_ routeId: String) {
        let resolved = (routeId == "settings-root") ? "settings" : routeId
        if resolved == "chat" || resolved == "home" {
            selectedMenu = .chat
            activeRoute = nil
            isSettingsOpen = false
        } else if resolved == "projects" {
            selectedMenu = .projects
            activeRoute = nil
            isSettingsOpen = false
        } else if resolved == "console" || resolved == "control" {
            selectedMenu = .control
            activeRoute = nil
            isSettingsOpen = false
        } else if resolved == "settings" {
            isSettingsOpen = true
        } else if let target = FlagshipRoutes.route(for: resolved) {
            activeRoute = target
        }
    }
}
