import SwiftUI

/// 一级抽屉严格固定为 4 个同级父入口：对话、工作台、控制台、设置
public enum FlagshipDrawerParent: String, CaseIterable, Identifiable, Sendable {
    case chat = "对话"
    case workbench = "工作台"
    case console = "控制台"
    case settings = "设置"

    public var id: String { rawValue }

    public var iconName: String {
        switch self {
        case .chat: return "bubble.left.and.bubble.right"
        case .workbench: return "hammer"
        case .console: return "gauge.with.dots.needle.bottom.50percent"
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
        .init(id: "compose", title: "新建任务", parent: .workbench, group: "任务", p0: true),
        .init(id: "tasks", title: "任务中心", parent: .workbench, group: "任务", p0: true),
        .init(id: "taskdetail", title: "任务详情", parent: .workbench, group: "任务", p0: true),
        .init(id: "plan", title: "计划与检查点", parent: .workbench, group: "任务", p0: false),
        .init(id: "queue", title: "队列与调度", parent: .workbench, group: "任务", p0: false),
        .init(id: "approvals", title: "审批中心", parent: .workbench, group: "任务", p0: true),
        .init(id: "review", title: "代码审查", parent: .workbench, group: "任务", p0: false),
        .init(id: "artifacts", title: "交付成果", parent: .workbench, group: "任务", p0: true),
        .init(id: "projects", title: "项目列表", parent: .workbench, group: "开发", p0: true),
        .init(id: "projectdetail", title: "项目概览", parent: .workbench, group: "开发", p0: true),
        .init(id: "files", title: "文件浏览器", parent: .workbench, group: "开发", p0: true),
        .init(id: "editor", title: "代码编辑器", parent: .workbench, group: "开发", p0: true),
        .init(id: "diff", title: "变更对比", parent: .workbench, group: "开发", p0: true),
        .init(id: "terminal", title: "终端与日志", parent: .workbench, group: "开发", p0: true),
        .init(id: "git", title: "Git 与工作树", parent: .workbench, group: "开发", p0: false),
        .init(id: "preview", title: "运行预览", parent: .workbench, group: "开发", p0: false),
        .init(id: "team", title: "团队空间", parent: .workbench, group: "协作", p0: false),
        .init(id: "channels", title: "团队频道", parent: .workbench, group: "协作", p0: false),
        .init(id: "channel", title: "频道会话", parent: .workbench, group: "协作", p0: false),
        .init(id: "agents", title: "Agent 列表", parent: .workbench, group: "协作", p0: true),
        .init(id: "agentdetail", title: "Agent 详情", parent: .workbench, group: "协作", p0: true),
        .init(id: "delegate", title: "协作编排", parent: .workbench, group: "协作", p0: false),
        .init(id: "devices", title: "设备列表", parent: .workbench, group: "连接", p0: true),
        .init(id: "devicedetail", title: "设备详情", parent: .workbench, group: "连接", p0: false),
        .init(id: "models", title: "模型与提供方", parent: .workbench, group: "连接", p0: true),
        .init(id: "mcp", title: "MCP 与插件", parent: .workbench, group: "连接", p0: false),
        .init(id: "skills", title: "技能与自动化", parent: .workbench, group: "连接", p0: false),
        .init(id: "connections", title: "账号与连接", parent: .workbench, group: "连接", p0: false),
        .init(id: "dashboard", title: "全局工作台", parent: .console, group: "系统", p0: true),
        .init(id: "activity", title: "事件与通知", parent: .console, group: "系统", p0: false),
        .init(id: "settings", title: "设置", parent: .console, group: "系统", p0: true),
        .init(id: "onboarding", title: "首次使用", parent: .console, group: "系统", p0: true),
        .init(id: "connectmodel", title: "连接模型向导", parent: .workbench, group: "连接", p0: true),
        .init(id: "modelroute", title: "智能模型路由", parent: .workbench, group: "连接", p0: false),
        .init(id: "agentimport", title: "导入现有 Agent", parent: .workbench, group: "连接", p0: false),
        .init(id: "agentresume", title: "Agent 会话续接", parent: .workbench, group: "协作", p0: false),
        .init(id: "autoloop", title: "Auto-loop 自主循环", parent: .console, group: "自动化", p0: false),
        .init(id: "loopguard", title: "循环安全护栏", parent: .console, group: "自动化", p0: false),
        .init(id: "memory", title: "长期记忆", parent: .settings, group: "智能", p0: false),
        .init(id: "skillsmarket", title: "技能市场", parent: .settings, group: "智能", p0: false),
        .init(id: "skillbuilder", title: "技能自生成", parent: .settings, group: "智能", p0: false),
        .init(id: "context", title: "上下文管理", parent: .settings, group: "智能", p0: false),
        .init(id: "research", title: "深度研究", parent: .settings, group: "智能", p0: false),
        .init(id: "browser", title: "浏览器工作台", parent: .workbench, group: "开发", p0: false),
        .init(id: "testmatrix", title: "测试矩阵", parent: .workbench, group: "开发", p0: false),
        .init(id: "agentcompare", title: "多 Agent 对比", parent: .workbench, group: "协作", p0: false),
        .init(id: "orchestrator", title: "可视化编排", parent: .workbench, group: "协作", p0: false),
        .init(id: "handoff", title: "跨设备接力", parent: .workbench, group: "协作", p0: false),
        .init(id: "audit", title: "权限与审计", parent: .console, group: "系统", p0: false),
        .init(id: "notifications", title: "消息中心", parent: .console, group: "系统", p0: false),
        .init(id: "voice", title: "语音与多模态", parent: .chat, group: "对话", p0: false),
        .init(id: "personas", title: "Agent 个性与配置", parent: .settings, group: "智能", p0: false),
        .init(id: "usage", title: "成本与额度", parent: .console, group: "系统", p0: false),
        .init(id: "templates", title: "工作流模板", parent: .console, group: "自动化", p0: false),
        .init(id: "sync", title: "跨端同步", parent: .console, group: "系统", p0: false),
        .init(id: "recovery", title: "恢复与回滚", parent: .console, group: "系统", p0: false),
        .init(id: "automation", title: "自动化中心", parent: .console, group: "自动化", p0: false),
        .init(id: "automaker", title: "创建自动化", parent: .console, group: "自动化", p0: false),
        .init(id: "triggers", title: "触发器与唤醒", parent: .console, group: "自动化", p0: false),
        .init(id: "wakepolicy", title: "唤醒与自治策略", parent: .console, group: "自动化", p0: false),
        .init(id: "network", title: "通信网络", parent: .workbench, group: "连接", p0: false),
        .init(id: "routes", title: "网络路径详情", parent: .workbench, group: "连接", p0: false),
        .init(id: "extensions", title: "扩展中心", parent: .settings, group: "扩展", p0: false),
        .init(id: "extensiondetail", title: "插件权限与安装", parent: .settings, group: "扩展", p0: false),
        .init(id: "harness", title: "Harness 运行时", parent: .settings, group: "扩展", p0: false),
        .init(id: "baseline", title: "Claude 18 屏验收", parent: .console, group: "系统", p0: false)
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

/// 四父级抽屉组件（一级固定且仅有：对话、工作台、控制台、设置；二级分组折叠；三级直达 70 路由）
public struct FlagshipDrawerView: View {
    @Binding public var isOpen: Bool
    @Binding public var selectedParent: FlagshipDrawerParent
    @Binding public var expandedGroup: String?
    public let capabilities: FlagshipCapabilityProvider
    public var onSelectRoute: (FlagshipRoute) -> Void

    @State private var searchText = ""

    public init(
        isOpen: Binding<Bool>,
        selectedParent: Binding<FlagshipDrawerParent>,
        expandedGroup: Binding<String?>,
        capabilities: FlagshipCapabilityProvider,
        onSelectRoute: @escaping (FlagshipRoute) -> Void
    ) {
        self._isOpen = isOpen
        self._selectedParent = selectedParent
        self._expandedGroup = expandedGroup
        self.capabilities = capabilities
        self.onSelectRoute = onSelectRoute
    }

    private var filteredRoutes: [FlagshipRoute] {
        let q = searchText.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !q.isEmpty else { return [] }
        return FlagshipRoutes.all.filter {
            $0.id.lowercased().contains(q) || $0.title.lowercased().contains(q) || $0.group.lowercased().contains(q)
        }
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // 抽屉品牌头
            HStack {
                Text("WhereverCode")
                    .font(.system(size: 25, weight: .regular, design: .serif))
                Spacer()
                Button {
                    withAnimation(.easeOut(duration: 0.2)) { isOpen = false }
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 15, weight: .medium))
                        .foregroundStyle(.secondary)
                        .frame(width: 32, height: 32)
                }
            }
            .padding(.horizontal, 22)
            .padding(.top, 18)
            .padding(.bottom, 12)

            // 70 路由快速搜索框
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass")
                    .foregroundStyle(.secondary)
                TextField("查找 70 个页面 (route_id / 名称)", text: $searchText)
                    .font(.subheadline)
                    .textInputAutocapitalization(.never)
                if !searchText.isEmpty {
                    Button {
                        searchText = ""
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(Color.white, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color(red: 0.88, green: 0.87, blue: 0.84), lineWidth: 1))
            .padding(.horizontal, 16)
            .padding(.bottom, 10)

            // 一级固定 4 父入口（对话、工作台、控制台、设置，严格同级）
            VStack(spacing: 4) {
                ForEach(FlagshipDrawerParent.allCases) { parent in
                    let isSelected = (selectedParent == parent)
                    Button {
                        selectedParent = parent
                        let groups = FlagshipRoutes.groups(for: parent)
                        expandedGroup = groups.first
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: parent.iconName)
                                .font(.system(size: 16, weight: .medium))
                                .frame(width: 22)
                            Text(parent.rawValue)
                                .font(.system(size: 17, weight: isSelected ? .semibold : .regular))
                            Spacer()
                            Text("\(FlagshipRoutes.routes(for: parent).count)")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            Image(systemName: "chevron.right")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                        .foregroundStyle(Color(red: 0.15, green: 0.15, blue: 0.14))
                        .padding(.horizontal, 16)
                        .padding(.vertical, 13)
                        .background(
                            isSelected ? Color(red: 0.91, green: 0.91, blue: 0.89) : Color.clear,
                            in: RoundedRectangle(cornerRadius: 14)
                        )
                    }
                    .accessibilityIdentifier("drawer.parent.\(parent.rawValue)")
                }
            }
            .padding(.horizontal, 12)

            Divider()
                .padding(.vertical, 8)

            // 二级分类与三级路由列表
            ScrollView {
                VStack(alignment: .leading, spacing: 6) {
                    if !searchText.trimmingCharacters(in: .whitespaces).isEmpty {
                        Text("搜索结果 · \(filteredRoutes.count) 路由")
                            .font(.caption.weight(.medium))
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, 18)
                        ForEach(filteredRoutes) { route in
                            routeRow(route)
                        }
                    } else {
                        let parentRoutes = FlagshipRoutes.routes(for: selectedParent)
                        let groups = FlagshipRoutes.groups(for: selectedParent)
                        Text("功能分类 · \(parentRoutes.count) 路由")
                            .font(.caption.weight(.medium))
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, 18)
                            .padding(.top, 4)

                        // 当选中「设置」父项时，顶部同时提供直达「核心平台与模型设置 (settings)」快捷入口
                        if selectedParent == .settings, let coreSettings = FlagshipRoutes.route(for: "settings") {
                            routeRow(coreSettings)
                        }

                        ForEach(groups, id: \.self) { grp in
                            let groupRoutes = parentRoutes.filter { $0.group == grp }
                            let isExpanded = (expandedGroup == grp) || groups.count == 1
                            Button {
                                expandedGroup = (expandedGroup == grp) ? nil : grp
                            } label: {
                                HStack {
                                    Text(grp)
                                        .font(.system(size: 15, weight: .semibold))
                                    Text("\(groupRoutes.count)")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                    Spacer()
                                    Image(systemName: isExpanded ? "chevron.down" : "chevron.right")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                .foregroundStyle(Color(red: 0.20, green: 0.19, blue: 0.18))
                                .padding(.horizontal, 16)
                                .padding(.vertical, 10)
                            }
                            .accessibilityIdentifier("drawer.group.\(grp)")

                            if isExpanded {
                                ForEach(groupRoutes) { route in
                                    routeRow(route)
                                }
                            }
                        }
                    }
                }
                .padding(.horizontal, 12)
                .padding(.bottom, 24)
            }

            // 抽屉底部版本与状态注脚
            HStack {
                Text("V5 · 70 路由全量装配 · iOS SwiftUI")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                Spacer()
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
        }
        .frame(maxWidth: 330, maxHeight: .infinity)
        .background(Color(red: 0.969, green: 0.969, blue: 0.961).ignoresSafeArea())
        .shadow(color: .black.opacity(0.12), radius: 16, x: 6, y: 0)
    }

    @ViewBuilder
    private func routeRow(_ route: FlagshipRoute) -> some View {
        let status = capabilities.status(for: route.id)
        Button {
            onSelectRoute(route)
            withAnimation(.easeOut(duration: 0.2)) { isOpen = false }
        } label: {
            HStack(spacing: 8) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(route.title)
                        .font(.system(size: 15))
                        .foregroundStyle(Color(red: 0.15, green: 0.15, blue: 0.14))
                    Text("\(route.id) · \(route.p0 ? "P0" : "扩展")")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Text(status.state.rawValue)
                    .font(.caption2)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 3)
                    .background(Color(red: 0.92, green: 0.90, blue: 0.87), in: Capsule())
                    .foregroundStyle(Color(red: 0.35, green: 0.33, blue: 0.30))
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 9)
            .background(Color.white.opacity(0.65), in: RoundedRectangle(cornerRadius: 10))
        }
        .accessibilityIdentifier("drawer.route.\(route.id)")
    }
}

/// 顶层无 TabBar 壳层：根页面固定为 Claude 极简风格对话首页，左滑或点左上角按钮呼出 4 父级抽屉，支持进入全部 70 个二级/三级页面
public struct FlagshipShellView: View {
    @ObservedObject public var appState: AppState
    @ObservedObject public var chatModel: LocalChatModel
    @State private var isDrawerOpen = false
    @State private var selectedParent: FlagshipDrawerParent = .chat
    @State private var expandedGroup: String? = "对话"
    @State private var activeRoute: FlagshipRoute? = nil

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
                LocalChatHomeView(
                    appState: appState,
                    model: chatModel,
                    onOpenDrawer: {
                        withAnimation(.easeOut(duration: 0.2)) {
                            isDrawerOpen = true
                        }
                    },
                    onSelectRoute: { routeId in
                        if routeId == "chat" {
                            activeRoute = nil
                        } else if let target = FlagshipRoutes.route(for: routeId) {
                            activeRoute = target
                        }
                    }
                )
                .navigationDestination(item: $activeRoute) { route in
                    FlagshipRouteDestinationView(
                        route: route,
                        appState: appState,
                        chatModel: chatModel,
                        capabilities: capabilities,
                        onNavigateRoute: { nextId in
                            if nextId == "chat" {
                                activeRoute = nil
                            } else if let next = FlagshipRoutes.route(for: nextId) {
                                activeRoute = next
                            }
                        }
                    )
                    .toolbar {
                        ToolbarItem(placement: .topBarLeading) {
                            Button {
                                withAnimation(.easeOut(duration: 0.2)) {
                                    isDrawerOpen = true
                                }
                            } label: {
                                Image(systemName: "line.3.horizontal")
                            }
                            .accessibilityIdentifier("navigation.menu")
                            .accessibilityLabel("打开导航抽屉")
                        }
                    }
                }
            }
            .onAppear {
                appState.onSelectRoute = { nextId in
                    let resolvedId = (nextId == "settings-root") ? "settings" : nextId
                    if resolvedId == "chat" || resolvedId == "home" {
                        activeRoute = nil
                    } else if let next = FlagshipRoutes.route(for: resolvedId) {
                        activeRoute = next
                    }
                }
            }

            if isDrawerOpen {
                Color.black.opacity(0.28)
                    .ignoresSafeArea()
                    .onTapGesture {
                        withAnimation(.easeOut(duration: 0.2)) {
                            isDrawerOpen = false
                        }
                    }

                FlagshipDrawerView(
                    isOpen: $isDrawerOpen,
                    selectedParent: $selectedParent,
                    expandedGroup: $expandedGroup,
                    capabilities: capabilities,
                    onSelectRoute: { route in
                        if route.id == "chat" {
                            activeRoute = nil
                        } else {
                            activeRoute = route
                        }
                    }
                )
                .transition(.move(edge: .leading))
            }
        }
    }
}
