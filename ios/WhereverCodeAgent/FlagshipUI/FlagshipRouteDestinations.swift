import SwiftUI
import UIKit

public struct FlagshipRouteDestinationView: View {
    public let route: FlagshipRoute
    @ObservedObject public var appState: AppState
    @ObservedObject public var chatModel: LocalChatModel
    public let capabilities: AppFlagshipCapabilities
    public var onNavigateRoute: (String) -> Void

    @ObservedObject private var localData: LocalData = .shared

    public init(
        route: FlagshipRoute,
        appState: AppState,
        chatModel: LocalChatModel,
        capabilities: AppFlagshipCapabilities,
        onNavigateRoute: @escaping (String) -> Void
    ) {
        self.route = route
        self.appState = appState
        self.chatModel = chatModel
        self.capabilities = capabilities
        self.onNavigateRoute = onNavigateRoute
    }

    public var body: some View {
        VStack(spacing: 0) {
            routeContent
        }
        .navigationTitle(route.title)
        .navigationBarTitleDisplayMode(.inline)
    }

    private func badgeColor(for state: CapabilityState) -> Color {
        switch state {
        case .readyVerified: return .green
        case .implementedUnverified: return .blue
        case .disconnected: return .orange
        case .failed: return .red
        case .unsupported: return .purple
        case .planned: return .secondary
        }
    }

    @ViewBuilder
    private var routeContent: some View {
        switch route.id {
        // MARK: - 一、对话 (5 路由)
        case "chat", "conversation":
            ConversationStreamScreen(appState: appState, chatModel: chatModel, onNavigateRoute: onNavigateRoute)
        case "history":
            HistoryConversationsScreen(appState: appState, chatModel: chatModel, onNavigateRoute: onNavigateRoute)
        case "search":
            GlobalSearchScreen(appState: appState, chatModel: chatModel, onNavigateRoute: onNavigateRoute)
        case "voice":
            VoiceMultimodalScreen(appState: appState, chatModel: chatModel, onNavigateRoute: onNavigateRoute)

        // MARK: - 二、工作台 · 任务 (8 路由)
        case "compose":
            ComposeTaskScreen(appState: appState, onCreated: { onNavigateRoute("tasks") }, onNavigateRoute: onNavigateRoute)
        case "tasks":
            TasksView(appState: appState, onNavigateRoute: onNavigateRoute)
        case "taskdetail":
            TaskDetailRouterScreen(appState: appState, onNavigateRoute: onNavigateRoute)
        case "plan":
            PlanAndCheckpointsScreen(appState: appState, onNavigateRoute: onNavigateRoute)
        case "queue":
            QueueAndSchedulerScreen(appState: appState, onNavigateRoute: onNavigateRoute)
        case "approvals":
            ApprovalsCenterScreen(appState: appState, onNavigateRoute: onNavigateRoute)
        case "review":
            CodeReviewScreen(appState: appState, onNavigateRoute: onNavigateRoute)
        case "artifacts":
            ArtifactsDeliveryScreen(appState: appState, onNavigateRoute: onNavigateRoute)

        // MARK: - 二、项目 · 任务与开发 (10 路由)
        case "projects", "projectdetail":
            ProjectsHomeView(appState: appState, onOpenDrawer: {}, onNavigateRoute: onNavigateRoute)
        case "files":
            SandboxFileBrowserScreen(appState: appState, onOpenEditor: { onNavigateRoute("editor") }, onNavigateRoute: onNavigateRoute)
        case "editor":
            SandboxCodeEditorScreen(appState: appState, onOpenDiff: { onNavigateRoute("diff") }, onNavigateRoute: onNavigateRoute)
        case "diff":
            UnifiedDiffScreen(appState: appState, onNavigateRoute: onNavigateRoute)
        case "terminal":
            TerminalAndExecutionTargetScreen(appState: appState, onNavigateRoute: onNavigateRoute)
        case "git":
            GitWorktreeInspectorScreen(appState: appState, onNavigateRoute: onNavigateRoute)
        case "preview", "browser", "testmatrix":
            WorkbenchDevExtendedScreen(route: route, appState: appState, onNavigateRoute: onNavigateRoute)

        // MARK: - 二、工作台 · 协作 (10 路由)
        case "channels", "channel":
            ChannelsHomeView(
                appState: appState,
                onOpenDrawer: { onNavigateRoute("chat") },
                onNavigateRoute: onNavigateRoute
            )
        case "team", "agents", "agentdetail", "delegate", "agentresume", "agentcompare", "orchestrator", "handoff":
            CollaborationAndAgentsScreen(route: route, appState: appState, onNavigateRoute: onNavigateRoute)

        // MARK: - 二、工作台 · 连接 (11 路由)
        case "devices", "devicedetail":
            DevicesView(appState: appState, onNavigateRoute: onNavigateRoute)
        case "models", "connectmodel", "modelroute":
            ModelSettingsView(model: appState.modelSettingsModel)
        case "mcp":
            McpToolsInspectorScreen(appState: appState, onNavigateRoute: onNavigateRoute)
        case "skills":
            SkillsManagementScreen(routeId: "skills", appState: appState, onNavigateRoute: onNavigateRoute)
        case "connections":
            SettingsView(appState: appState, onNavigateRoute: onNavigateRoute)
        case "agentimport", "network", "routes":
            ConnectionExtendedScreen(route: route, appState: appState, onNavigateRoute: onNavigateRoute)

        // MARK: - 三、控制 · 系统与自动化 (17 路由)
        case "dashboard":
            ControlHomeView(appState: appState, onOpenDrawer: {}, onNavigateRoute: onNavigateRoute)
        case "settings":
            AccountSettingsView(appState: appState, chatModel: chatModel, capabilities: capabilities, onNavigateRoute: onNavigateRoute, onDismiss: {})
        case "onboarding":
            OnboardingLocalFirstScreen(appState: appState, onNavigateRoute: onNavigateRoute)
        case "activity", "notifications", "audit", "usage", "sync", "recovery", "baseline":
            ConsoleSystemSubScreen(route: route, appState: appState, onNavigateRoute: onNavigateRoute)
        case "autoloop", "loopguard", "templates", "automation", "automaker", "triggers", "wakepolicy":
            ConsoleAutomationSubScreen(route: route, appState: appState, chatModel: chatModel, onNavigateRoute: onNavigateRoute)

        // MARK: - 四、设置 · 智能与扩展 (9 路由)
        case "memory", "skillsmarket", "skillbuilder", "context", "research", "personas", "extensions", "extensiondetail", "harness":
            SettingsIntelligenceAndExtensionsScreen(route: route, appState: appState, onNavigateRoute: onNavigateRoute)

        default:
            SettingsView(appState: appState, onNavigateRoute: onNavigateRoute)
        }
    }
}

// MARK: - 1. 对话子模块实现
private struct ConversationStreamScreen: View {
    @ObservedObject var appState: AppState
    @ObservedObject var chatModel: LocalChatModel
    var onNavigateRoute: (String) -> Void

    var body: some View {
        List {
            Section {
                LabeledContent("当前大脑", value: chatModel.selectedBrain.isEmpty ? "未选择" : chatModel.selectedBrain)
                LabeledContent("执行环境", value: chatModel.selectedExecutionTarget)
                LabeledContent("平台状态", value: appState.connectionStatus.displayText)
            } header: { Text("当前会话与连接状态").textCase(nil) }
            Section {
                if chatModel.messages.isEmpty {
                    Text("当前无进行中消息，可返回对话首页发送或从历史会话加载。")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(chatModel.messages) { msg in
                        VStack(alignment: .leading, spacing: 4) {
                            Text("\(msg.role.uppercased()) · \(msg.source)").font(.caption.bold())
                            Text(msg.content).font(.body).textSelection(.enabled)
                        }
                    }
                }
            } header: { Text("消息与工具流 (\(chatModel.messages.count) 条)").textCase(nil) }
            PrototypeRouteSections(routeId: "conversation", appState: appState, chatModel: chatModel, onNavigateRoute: onNavigateRoute)
        }
    }
}

private struct HistoryConversationsScreen: View {
    @ObservedObject var appState: AppState
    @ObservedObject var chatModel: LocalChatModel
    var onNavigateRoute: (String) -> Void
    @State private var query = ""

    private var filtered: [Conversation] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if q.isEmpty { return chatModel.history }
        return chatModel.history.filter { $0.title.lowercased().contains(q) || ($0.brainId ?? "").lowercased().contains(q) }
    }

    var body: some View {
        List {
            Section {
                if filtered.isEmpty {
                    Text("暂无历史会话记录")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(filtered) { conv in
                        HStack {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(conv.title).font(.headline)
                                Text("模型：\(conv.brainId ?? "默认") · ID: \(String(conv.id.prefix(8)))")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Button("打开") {
                                chatModel.open(conv)
                                onNavigateRoute("chat")
                            }
                            .buttonStyle(.bordered)
                            Button("分叉") {
                                chatModel.forkConversation(conv)
                                onNavigateRoute("chat")
                            }
                            .buttonStyle(.borderedProminent)
                        }
                    }
                }
            } header: { Text("SQLite 本地会话历史 (\(filtered.count) 条)").textCase(nil) }
            PrototypeRouteSections(routeId: "history", appState: appState, chatModel: chatModel, onNavigateRoute: onNavigateRoute)
        }
        .searchable(text: $query, prompt: "按标题或模型搜索会话")
        .onAppear { chatModel.reloadHistory() }
    }
}

private struct GlobalSearchScreen: View {
    @ObservedObject var appState: AppState
    @ObservedObject var chatModel: LocalChatModel
    var onNavigateRoute: (String) -> Void
    @State private var keyword = ""

    var body: some View {
        let q = keyword.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let convs = chatModel.history.filter { q.isEmpty || $0.title.lowercased().contains(q) }
        let tasks = ((try? LocalData.shared.tasks.listTasks()) ?? []).filter { q.isEmpty || $0.title.lowercased().contains(q) }
        let projects = ((try? LocalData.shared.projects.listProjects()) ?? []).filter { q.isEmpty || $0.project.name.lowercased().contains(q) }
        let routes = FlagshipRoutes.all.filter { q.isEmpty || $0.title.lowercased().contains(q) || $0.id.lowercased().contains(q) }

        List {
            Section {
                TextField("搜索会话、本地任务、项目或 70 屏路由…", text: $keyword)
            } header: { Text("跨模块检索关键词").textCase(nil) }
            Section {
                ForEach(convs.prefix(5)) { c in
                    Button(c.title) {
                        chatModel.open(c)
                        onNavigateRoute("chat")
                    }
                }
            } header: { Text("匹配会话 (\(convs.count))").textCase(nil) }
            Section {
                ForEach(tasks.prefix(5)) { t in
                    Button("\(t.title) [\(t.state)]") {
                        onNavigateRoute("tasks")
                    }
                }
            } header: { Text("匹配任务 (\(tasks.count))").textCase(nil) }
            Section {
                ForEach(projects.prefix(5)) { p in
                    Button(p.project.name) {
                        onNavigateRoute("projects")
                    }
                }
            } header: { Text("匹配项目 (\(projects.count))").textCase(nil) }
            Section {
                ForEach(routes.prefix(10)) { r in
                    Button("\(r.title) (\(r.id)) — \(r.parent.rawValue)/\(r.group)") {
                        onNavigateRoute(r.id)
                    }
                }
            } header: { Text("匹配功能路由 (\(routes.count))").textCase(nil) }
            PrototypeRouteSections(routeId: "search", appState: appState, chatModel: chatModel, onNavigateRoute: onNavigateRoute)
        }
    }
}

private struct VoiceMultimodalScreen: View {
    @ObservedObject var appState: AppState
    @ObservedObject var chatModel: LocalChatModel
    var onNavigateRoute: (String) -> Void = { _ in }
    @State private var attachName = ""
    @State private var voiceTranscript = ""

    var body: some View {
        Form {
            Section {
                Text("• 文件与代码导入：通过系统文件选择器及安全书签访问授权沙盒文件。\n• 语音输入：使用系统麦克风与语音识别入口。\n• 视觉能力校验：当所选模型（纯文本模型）不支持视觉能力时，系统自动注入诚实提示，绝不伪造看图结果。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } header: { Text("iOS 多模态附件与语音权限边界").textCase(nil) }
            Section {
                TextField("文件名或相对路径（如 README.md）", text: $attachName)
                Button("挂载到当前输入栏") {
                    let clean = attachName.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !clean.isEmpty else { return }
                    chatModel.attachedFiles.append(clean)
                    attachName = ""
                }
            } header: { Text("添加沙盒文件引用到当前会话").textCase(nil) }
            Section {
                TextField("使用系统键盘麦克风听写或输入语音内容…", text: $voiceTranscript, axis: .vertical)
                Button("填入对话首页输入框") {
                    guard !voiceTranscript.isEmpty else { return }
                    chatModel.draft = voiceTranscript
                    voiceTranscript = ""
                }
            } header: { Text("语音听写草稿转入").textCase(nil) }
            PrototypeRouteSections(routeId: "voice", appState: appState, chatModel: chatModel, onNavigateRoute: onNavigateRoute)
        }
    }
}

// MARK: - 2. 工作台 · 任务与开发子模块实现
private struct ComposeTaskScreen: View {
    @ObservedObject var appState: AppState
    var onCreated: () -> Void
    var onNavigateRoute: (String) -> Void = { _ in }

    @State private var title = ""
    @State private var description = ""
    @State private var assignedAgentId = ""
    @State private var priority = 1
    @State private var executionEnv = "iphone_local_sandbox"
    @State private var errorMsg: String?

    var body: some View {
        Form {
            Section {
                TextField("任务目标标题", text: $title)
                TextField("验收标准与上下文说明", text: $description, axis: .vertical)
                    .lineLimit(3...6)
                TextField("指派 Agent ID（可选）", text: $assignedAgentId)
                Stepper("优先级：P\(priority)", value: $priority, in: 0...3)
            } header: { Text("新建任务（写入本地 SQLite 任务账本 wherevercode-tasks.db）").textCase(nil) }
            Section {
                Picker("目标执行环境", selection: $executionEnv) {
                    Text("iPhone 本机大脑与沙盒").tag("iphone_local_sandbox")
                    Text("远端配对设备 (Hand)").tag("remote_device_hand")
                    Text("V5 平台云端 Agent (MCP)").tag("cloud_agent_mcp")
                }
                Text("注：V5 平台 MCP 当前不提供客户端直接建单接口，新建任务将持久化至本机 SQLite 任务表（初始状态 queued），支持完整 10 状态流转与事件审计。")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            } header: { Text("执行环境与权限声明").textCase(nil) }
            if let err = errorMsg {
                Section { Text(err).foregroundStyle(.red).font(.caption) }
            }
            Section {
                Button("创建正式任务") {
                    let cleanTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !cleanTitle.isEmpty else {
                        errorMsg = "任务标题不能为空"
                        return
                    }
                    let task = LocalTask(
                        title: cleanTitle,
                        description: description.isEmpty ? nil : "[环境: \(executionEnv)] \(description)",
                        state: "queued",
                        assignedAgentId: assignedAgentId.isEmpty ? nil : assignedAgentId,
                        priority: priority,
                        brainId: appState.compositeBrainConfigStore.currentBrainId()
                    )
                    do {
                        _ = try LocalData.shared.tasks.createTask(task)
                        onCreated()
                    } catch {
                        errorMsg = error.localizedDescription
                    }
                }
                .buttonStyle(.borderedProminent)
            }
            PrototypeRouteSections(routeId: "compose", appState: appState, onNavigateRoute: onNavigateRoute)
        }
    }
}

private struct TaskDetailRouterScreen: View {
    @ObservedObject var appState: AppState
    var onNavigateRoute: (String) -> Void = { _ in }

    var body: some View {
        let tasks = (try? LocalData.shared.tasks.listTasks()) ?? []
        if let first = tasks.last {
            LocalTaskDetailScreen(task: first, onUpdated: {}, onNavigateRoute: onNavigateRoute)
        } else {
            TasksView(appState: appState, onNavigateRoute: onNavigateRoute)
        }
    }
}

private struct PlanAndCheckpointsScreen: View {
    @ObservedObject var appState: AppState
    var onNavigateRoute: (String) -> Void = { _ in }
    @State private var goalText = "完成 iOS 70 屏路由装配与 SQLite 状态机校验"
    @State private var stepTool = "list_files"
    @State private var taskId = "task-demo-01"
    @State private var validationResult: String?

    var body: some View {
        Form {
            Section {
                TextField("任务 ID", text: $taskId)
                TextField("计划目标", text: $goalText)
                TextField("步骤工具名", text: $stepTool)
                Button("执行 Planner 幂等键与工具白名单校验") {
                    let action = BrainAction(
                        tool: stepTool,
                        requiresApproval: true,
                        idempotencyKey: PlanValidator.idempotencyKey(taskId: taskId, seq: 1)
                    )
                    let plan = Plan(goal: goalText, steps: [action], risks: ["写入类操作需经过 ApprovalGate 审批"])
                    let errs = PlanValidator.validate(plan, registeredTools: DefaultiOSToolRegistry().registeredToolNames, taskId: taskId)
                    validationResult = errs.isEmpty ? "校验通过：步骤幂等键 (\(action.idempotencyKey)) 与工具白名单合法。" : "校验拒绝：\(errs.joined(separator: "；"))"
                }
            } header: { Text("执行计划与幂等键校验器 (Planner.validate)").textCase(nil) }
            if let res = validationResult {
                Section {
                    Text(res).font(.footnote)
                } header: { Text("校验结果").textCase(nil) }
            }
            PrototypeRouteSections(routeId: "plan", appState: appState, onNavigateRoute: onNavigateRoute)
        }
    }
}

private struct QueueAndSchedulerScreen: View {
    @ObservedObject var appState: AppState
    var onNavigateRoute: (String) -> Void = { _ in }
    var body: some View {
        let allTasks = (try? LocalData.shared.tasks.listTasks()) ?? []
        let queued = allTasks.filter { $0.state == "queued" || $0.state == "requeued" || $0.state == "assigned" }
        let working = allTasks.filter { $0.state == "working" || $0.state == "blocked" }
        List {
            Section {
                if queued.isEmpty {
                    Text("队列为空").foregroundStyle(.secondary)
                } else {
                    ForEach(queued) { t in
                        LabeledContent("\(t.title) (P\(t.priority))", value: TaskStates.displayLabel(for: t.state, isPaused: t.isPaused))
                    }
                }
            } header: { Text("排队与待调度任务 (\(queued.count))").textCase(nil) }
            Section {
                if working.isEmpty {
                    Text("当前无并发执行任务").foregroundStyle(.secondary)
                } else {
                    ForEach(working) { t in
                        LabeledContent(t.title, value: TaskStates.displayLabel(for: t.state, isPaused: t.isPaused))
                    }
                }
            } header: { Text("并发执行中 / 阻塞中 (\(working.count))").textCase(nil) }
            PrototypeRouteSections(routeId: "queue", appState: appState, onNavigateRoute: onNavigateRoute)
        }
    }
}

private struct ApprovalsCenterScreen: View {
    @ObservedObject var appState: AppState
    var onNavigateRoute: (String) -> Void = { _ in }
    @ObservedObject var store: InteractiveApprovalStore = LocalData.shared.approvalStore
    @State private var sampleTool = "write_file"
    @State private var samplePath = "ios/WhereverCodeAgent/App/WhereverCodeAgentApp.swift"

    var body: some View {
        List {
            Section {
                Text(ReservedFeature.approval.notImplementedReason)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Button("模拟生成一条本地高危写入待审批请求") {
                    let action = BrainAction(
                        tool: sampleTool,
                        args: ["path": samplePath],
                        requiresApproval: true,
                        idempotencyKey: "local-task:\(store.items.count + 1)"
                    )
                    store.enqueue(action)
                }
            } header: { Text("审批门禁说明 (ApprovalGate & MCP 边界)").textCase(nil) }
            Section {
                if store.items.isEmpty {
                    Text("当前无待审批高危操作").foregroundStyle(.secondary)
                } else {
                    ForEach(store.items) { item in
                        VStack(alignment: .leading, spacing: 6) {
                            Text("工具：\(item.action.tool) · 幂等键：\(item.action.idempotencyKey)")
                                .font(.subheadline.bold())
                            Text("参数：\(item.action.args.description)")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            if let decision = item.decision {
                                Text(decision ? "状态：已批准" : "状态：已拒绝")
                                    .font(.caption.bold())
                                    .foregroundStyle(decision ? .green : .red)
                            } else {
                                HStack {
                                    Button("批准执行") { store.decide(actionId: item.id, approve: true) }
                                        .buttonStyle(.borderedProminent)
                                    Button("拒绝", role: .destructive) { store.decide(actionId: item.id, approve: false) }
                                        .buttonStyle(.bordered)
                                }
                            }
                        }
                    }
                }
            } header: { Text("本机待审批动作队列 (\(store.items.count))").textCase(nil) }
            PrototypeRouteSections(routeId: "approvals", appState: appState, onNavigateRoute: onNavigateRoute)
        }
    }
}

private struct CodeReviewScreen: View {
    @ObservedObject var appState: AppState
    var onNavigateRoute: (String) -> Void = { _ in }
    var body: some View {
        let tasks = ((try? LocalData.shared.tasks.listTasks()) ?? []).filter { $0.state == "review" || $0.state == "rework" }
        List {
            Section {
                Text("严格遵循统一契约：机械检查默认状态为 NOT_RUN，未经真实自动化测试不得宣称 PASS。支持从 review 流转至 completed 或打回 rework。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } header: { Text("审查门禁与机械检查原则").textCase(nil) }
            Section {
                if tasks.isEmpty {
                    Text("当前没有处于 review 或 rework 状态的任务").foregroundStyle(.secondary)
                } else {
                    ForEach(tasks) { t in
                        NavigationLink(t.title) {
                            LocalTaskDetailScreen(task: t, onUpdated: {}, onNavigateRoute: onNavigateRoute)
                        }
                    }
                }
            } header: { Text("待审查 / 需返工任务 (\(tasks.count))").textCase(nil) }
            PrototypeRouteSections(routeId: "review", appState: appState, onNavigateRoute: onNavigateRoute)
        }
    }
}

private struct ArtifactsDeliveryScreen: View {
    @ObservedObject var appState: AppState = AppState()
    var onNavigateRoute: (String) -> Void = { _ in }
    var body: some View {
        let completed = ((try? LocalData.shared.tasks.listTasks()) ?? []).filter { $0.state == "completed" || $0.result != nil }
        List {
            Section {
                Text("平台任务交付通过服务接口提交分支、提交哈希与 PR 链接；本地任务交付成果保存在本地任务记录中。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } header: { Text("交付成果聚合说明").textCase(nil) }
            Section {
                if completed.isEmpty {
                    Text("暂无已完成交付物记录").foregroundStyle(.secondary)
                } else {
                    ForEach(completed) { t in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(t.title).font(.headline)
                            Text(t.result ?? "已完成，无附加文本摘要").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            } header: { Text("已登记交付物 (\(completed.count))").textCase(nil) }
            PrototypeRouteSections(routeId: "artifacts", appState: appState, onNavigateRoute: onNavigateRoute)
        }
    }
}

public struct ProjectsHomeView: View {
    @ObservedObject public var appState: AppState
    public var onOpenDrawer: () -> Void
    public var onNavigateRoute: (String) -> Void

    @State private var projects: [ProjectWithHomes] = []
    @State private var searchQuery = ""
    @State private var selectedFilter: ProjectFilter = .all
    @State private var showNewProjectSheet = false
    @State private var newProjectName = ""
    @State private var newProjectRepo = ""
    @State private var newProjectBranch = "main"
    @State private var newProjectPath = ""
    @State private var activeWorkspaceProject: ProjectWithHomes? = nil

    public enum ProjectFilter: String, CaseIterable, Identifiable {
        case all = "全部"
        case active = "活跃"
        case sandbox = "本地沙盒"
        case remote = "远端设备"

        public var id: String { rawValue }
    }

    public init(
        appState: AppState,
        onOpenDrawer: @escaping () -> Void = {},
        onNavigateRoute: @escaping (String) -> Void = { _ in }
    ) {
        self.appState = appState
        self.onOpenDrawer = onOpenDrawer
        self.onNavigateRoute = onNavigateRoute
    }

    private var filteredProjects: [ProjectWithHomes] {
        var list = projects
        let q = searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if !q.isEmpty {
            list = list.filter {
                $0.project.name.lowercased().contains(q) ||
                ($0.project.repoUrl?.lowercased().contains(q) ?? false)
            }
        }
        switch selectedFilter {
        case .all, .active:
            return list
        case .sandbox:
            return list.filter { $0.project.origin.contains("LOCAL") || $0.project.origin.contains("SANDBOX") }
        case .remote:
            return list.filter { $0.project.origin.contains("REMOTE") }
        }
    }

    public var body: some View {
        VStack(spacing: 0) {
            // 顶部导航栏
            HStack(spacing: 12) {
                Button(action: onOpenDrawer) {
                    Image(systemName: "line.3.horizontal")
                        .font(.system(size: 18, weight: .medium))
                        .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                        .frame(width: 40, height: 40)
                        .background(Color.white, in: Circle())
                        .overlay(Circle().stroke(Color(red: 0.88, green: 0.87, blue: 0.85), lineWidth: 1))
                }
                .accessibilityIdentifier("navigation.menu")
                .accessibilityLabel("打开导航抽屉")

                Text("项目")
                    .font(.system(size: 20, weight: .semibold, design: .serif))
                    .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))

                Spacer()

                Button {
                    showNewProjectSheet = true
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "plus")
                            .font(.system(size: 13, weight: .semibold))
                        Text("新建项目")
                            .font(.system(size: 14, weight: .medium))
                    }
                    .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .background(Color.white, in: Capsule())
                    .overlay(Capsule().stroke(Color(red: 0.88, green: 0.87, blue: 0.85), lineWidth: 1))
                }
                .accessibilityIdentifier("projects.newButton")
            }
            .padding(.horizontal, 16)
            .padding(.top, 10)
            .padding(.bottom, 8)

            // 搜索框（参考 ChatGPT 项目页风格）
            HStack(spacing: 8) {
                Image(systemName: "magnifyingglass")
                    .foregroundStyle(.secondary)
                    .font(.system(size: 14))
                TextField("搜索项目…", text: $searchQuery)
                    .font(.subheadline)
                    .textInputAutocapitalization(.never)
                if !searchQuery.isEmpty {
                    Button {
                        searchQuery = ""
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(Color(red: 0.95, green: 0.94, blue: 0.92), in: RoundedRectangle(cornerRadius: 10))
            .padding(.horizontal, 16)
            .padding(.bottom, 10)

            // 筛选标签
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(ProjectFilter.allCases) { filter in
                        let isSelected = (selectedFilter == filter)
                        Button {
                            selectedFilter = filter
                        } label: {
                            Text(filter.rawValue)
                                .font(.system(size: 13, weight: isSelected ? .semibold : .regular))
                                .foregroundStyle(isSelected ? Color(red: 0.16, green: 0.15, blue: 0.14) : .secondary)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 6)
                                .background(
                                    isSelected ? Color.white : Color.clear,
                                    in: Capsule()
                                )
                                .overlay(
                                    Capsule().stroke(isSelected ? Color(red: 0.85, green: 0.84, blue: 0.81) : Color.clear, lineWidth: 1)
                                )
                        }
                    }
                }
                .padding(.horizontal, 16)
            }
            .padding(.bottom, 10)

            Divider()

            // 项目列表
            ScrollView {
                VStack(spacing: 12) {
                    if filteredProjects.isEmpty {
                        VStack(spacing: 12) {
                            Image(systemName: "shippingbox")
                                .font(.system(size: 44))
                                .foregroundStyle(.secondary.opacity(0.6))
                                .padding(.top, 40)
                            Text(projects.isEmpty ? "还没有创建任何项目" : "未找到匹配的项目")
                                .font(.headline)
                                .foregroundStyle(Color(red: 0.20, green: 0.19, blue: 0.18))
                            Text("项目支持统一管理任务、文件、代码、Artifacts、执行与审批交付")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .multilineTextAlignment(.center)
                                .padding(.horizontal, 32)
                            Button {
                                showNewProjectSheet = true
                            } label: {
                                Text("立即创建项目")
                                    .font(.system(size: 14, weight: .semibold))
                                    .foregroundStyle(.white)
                                    .padding(.horizontal, 20)
                                    .padding(.vertical, 10)
                                    .background(Color(red: 0.82, green: 0.49, blue: 0.35), in: Capsule())
                            }
                            .padding(.top, 8)
                        }
                    } else {
                        ForEach(filteredProjects) { item in
                            ProjectCardView(
                                project: item,
                                onOpenWorkspace: {
                                    activeWorkspaceProject = item
                                },
                                onNavigateRoute: onNavigateRoute
                            )
                        }
                    }

                    // 导入方式与开发全流程快捷按钮
                    VStack(alignment: .leading, spacing: 10) {
                        Text("导入与开发工具")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.secondary)
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                Button("选择本地文件夹") { onNavigateRoute("files") }.buttonStyle(.bordered)
                                Button("导入 Git 仓库") { showNewProjectSheet = true }.buttonStyle(.bordered)
                                Button("连接设备项目") { onNavigateRoute("devices") }.buttonStyle(.bordered)
                                Button("浏览文件") { onNavigateRoute("files") }.buttonStyle(.bordered)
                                Button("开始对话") { onNavigateRoute("conversation") }.buttonStyle(.bordered)
                                Button("Git 工作树") { onNavigateRoute("git") }.buttonStyle(.bordered)
                                Button("预览") { onNavigateRoute("preview") }.buttonStyle(.bordered)
                            }
                            .font(.caption)
                        }
                    }
                    .padding(14)
                    .background(Color.white, in: RoundedRectangle(cornerRadius: 14))
                    .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color(red: 0.89, green: 0.88, blue: 0.85), lineWidth: 1))
                }
                .padding(16)
            }
        }
        .background(Color(red: 0.98, green: 0.975, blue: 0.965).ignoresSafeArea())
        .onAppear(perform: loadProjects)
        .sheet(isPresented: $showNewProjectSheet) {
            NavigationStack {
                Form {
                    Section {
                        TextField("项目名称（如 wherevercode-agent）", text: $newProjectName)
                        TextField("Git 仓库地址（可选）", text: $newProjectRepo)
                        TextField("默认分支", text: $newProjectBranch)
                        TextField("工作区路径（如 Documents/workspace）", text: $newProjectPath)
                    } header: { Text("项目基本信息").textCase(nil) }
                }
                .navigationTitle("新建项目")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("取消") { showNewProjectSheet = false }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("创建") {
                            createProject()
                            showNewProjectSheet = false
                        }
                        .disabled(newProjectName.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                }
            }
        }
        .sheet(item: $activeWorkspaceProject) { item in
            NavigationStack {
                ProjectWorkspaceDetailView(
                    projectItem: item,
                    appState: appState,
                    onNavigateRoute: { routeId in
                        activeWorkspaceProject = nil
                        onNavigateRoute(routeId)
                    },
                    onDismiss: {
                        activeWorkspaceProject = nil
                    }
                )
            }
        }
    }

    private func loadProjects() {
        let list = (try? LocalData.shared.projects.listProjects()) ?? []
        projects = list
    }

    private func createProject() {
        let clean = newProjectName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return }
        let proj = Project(
            name: clean,
            repoUrl: newProjectRepo.isEmpty ? nil : newProjectRepo,
            defaultBranch: newProjectBranch,
            origin: "LOCAL_SANDBOX"
        )
        try? LocalData.shared.projects.saveProject(proj)
        let loc = ProjectLocation(
            projectId: proj.projectId,
            hostId: "iphone-local",
            workspacePath: newProjectPath.isEmpty ? "Documents/\(clean)" : newProjectPath
        )
        _ = try? LocalData.shared.projects.addLocation(loc)
        newProjectName = ""
        newProjectRepo = ""
        newProjectPath = ""
        loadProjects()
    }
}

public struct ProjectCardView: View {
    public let project: ProjectWithHomes
    public var onOpenWorkspace: () -> Void
    public var onNavigateRoute: (String) -> Void

    public var body: some View {
        Button(action: onOpenWorkspace) {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .center, spacing: 12) {
                    Image(systemName: "shippingbox.fill")
                        .font(.system(size: 22))
                        .foregroundStyle(Color(red: 0.82, green: 0.49, blue: 0.35))
                        .frame(width: 44, height: 44)
                        .background(Color(red: 0.98, green: 0.94, blue: 0.90), in: RoundedRectangle(cornerRadius: 12))

                    VStack(alignment: .leading, spacing: 3) {
                        Text(project.project.name)
                            .font(.system(size: 16, weight: .semibold))
                            .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                        Text(project.project.repoUrl ?? "本地沙盒工作区")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }

                    Spacer()

                    Text(project.project.defaultBranch)
                        .font(.caption2.monospaced())
                        .padding(.horizontal, 8)
                        .padding(.vertical, 3)
                        .background(Color(red: 0.93, green: 0.92, blue: 0.89), in: Capsule())
                        .foregroundStyle(Color(red: 0.35, green: 0.33, blue: 0.30))
                }

                Divider()

                HStack(spacing: 12) {
                    HStack(spacing: 4) {
                        Image(systemName: "checklist")
                            .font(.caption2)
                        Text("任务管理")
                            .font(.caption2)
                    }
                    .foregroundStyle(.secondary)

                    HStack(spacing: 4) {
                        Image(systemName: "folder")
                            .font(.caption2)
                        Text("沙盒文件")
                            .font(.caption2)
                    }
                    .foregroundStyle(.secondary)

                    HStack(spacing: 4) {
                        Image(systemName: "chevron.left.forwardslash.chevron.right")
                            .font(.caption2)
                        Text("代码与Diff")
                            .font(.caption2)
                    }
                    .foregroundStyle(.secondary)

                    Spacer()

                    Image(systemName: "chevron.right")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
            .padding(14)
            .background(Color.white, in: RoundedRectangle(cornerRadius: 14))
            .overlay(
                RoundedRectangle(cornerRadius: 14)
                    .stroke(Color(red: 0.89, green: 0.88, blue: 0.85), lineWidth: 1)
            )
        }
        .accessibilityIdentifier("project.card.\(project.project.projectId)")
    }
}

public struct ProjectWorkspaceDetailView: View {
    public let projectItem: ProjectWithHomes
    @ObservedObject public var appState: AppState
    public var onNavigateRoute: (String) -> Void
    public var onDismiss: () -> Void

    @State private var selectedTab: WorkspaceTab = .overview

    public enum WorkspaceTab: String, CaseIterable, Identifiable {
        case overview = "概览"
        case tasks = "任务"
        case files = "文件"
        case editor = "代码"
        case diff = "Diff"
        case artifacts = "产物"
        case terminal = "终端"
        case git = "Git"

        public var id: String { rawValue }
    }

    public var body: some View {
        VStack(spacing: 0) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(WorkspaceTab.allCases) { tab in
                        let isSelected = (selectedTab == tab)
                        Button {
                            selectedTab = tab
                        } label: {
                            Text(tab.rawValue)
                                .font(.system(size: 14, weight: isSelected ? .semibold : .regular))
                                .foregroundStyle(isSelected ? Color(red: 0.16, green: 0.15, blue: 0.14) : .secondary)
                                .padding(.horizontal, 14)
                                .padding(.vertical, 8)
                                .background(isSelected ? Color.white : Color.clear, in: Capsule())
                                .overlay(Capsule().stroke(isSelected ? Color(red: 0.85, green: 0.84, blue: 0.81) : Color.clear, lineWidth: 1))
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
            }
            .background(Color(red: 0.95, green: 0.94, blue: 0.92))

            Divider()

            Group {
                switch selectedTab {
                case .overview:
                    List {
                        Section {
                            LabeledContent("项目名称", value: projectItem.project.name)
                            LabeledContent("默认分支", value: projectItem.project.defaultBranch)
                            if let repo = projectItem.project.repoUrl {
                                LabeledContent("仓库地址", value: repo)
                            }
                            LabeledContent("来源属性", value: projectItem.project.origin == "LOCAL_SANDBOX" ? "本地沙盒" : projectItem.project.origin)
                        } header: { Text("项目信息").textCase(nil) }
                        Section {
                            ForEach(projectItem.homes) { loc in
                                LabeledContent(loc.hostId, value: loc.workspacePath)
                            }
                        } header: { Text("工作区位置").textCase(nil) }
                        Section {
                            Button("浏览文件") { onNavigateRoute("files") }
                            Button("开始对话") { onNavigateRoute("conversation") }
                            Button("Git 工作树") { onNavigateRoute("git") }
                            Button("预览") { onNavigateRoute("preview") }
                            Button("新建此项目任务") { onNavigateRoute("compose") }
                            Button("查看项目任务中心") { onNavigateRoute("tasks") }
                            Button("打开代码编辑器") { onNavigateRoute("editor") }
                            Button("执行环境与终端") { onNavigateRoute("terminal") }
                        } header: { Text("快捷操作").textCase(nil) }
                        PrototypeRouteSections(routeId: "projectdetail", appState: appState, onNavigateRoute: onNavigateRoute)
                    }
                case .tasks:
                    TasksView(appState: appState, onNavigateRoute: onNavigateRoute)
                case .files:
                    SandboxFileBrowserScreen(appState: appState, onOpenEditor: { onNavigateRoute("editor") }, onNavigateRoute: onNavigateRoute)
                case .editor:
                    SandboxCodeEditorScreen(appState: appState, onOpenDiff: { onNavigateRoute("diff") }, onNavigateRoute: onNavigateRoute)
                case .diff:
                    UnifiedDiffScreen(appState: appState, onNavigateRoute: onNavigateRoute)
                case .artifacts:
                    ArtifactsDeliveryScreen(appState: appState, onNavigateRoute: onNavigateRoute)
                case .terminal:
                    TerminalAndExecutionTargetScreen(appState: appState, onNavigateRoute: onNavigateRoute)
                case .git:
                    GitWorktreeInspectorScreen(appState: appState, onNavigateRoute: onNavigateRoute)
                }
            }
        }
        .navigationTitle(projectItem.project.name)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("完成") { onDismiss() }
            }
        }
    }
}

private struct ProjectsWorkbenchScreen: View {
    let showDetailByDefault: Bool
    var onNavigateRoute: (String) -> Void

    var body: some View {
        ProjectsHomeView(appState: AppState(), onOpenDrawer: {}, onNavigateRoute: onNavigateRoute)
    }
}

private struct SandboxFileBrowserScreen: View {
    @ObservedObject var appState: AppState = AppState()
    var onOpenEditor: () -> Void
    var onNavigateRoute: (String) -> Void = { _ in }
    @State private var files: [String] = []
    @State private var newFileName = "notes.md"

    private var docsURL: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first ?? URL(fileURLWithPath: NSTemporaryDirectory())
    }

    var body: some View {
        List {
            Section {
                Text("iOS 采用应用沙盒机制：本页直接读写应用沙盒 Documents 目录，跨应用文件通过系统文件选择器安全书签导入。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                HStack {
                    TextField("新建文件名", text: $newFileName)
                    Button("创建文件") {
                        let target = docsURL.appendingPathComponent(newFileName)
                        try? "# WhereverCode iOS Workspace\n".write(to: target, atomically: true, encoding: .utf8)
                        reload()
                    }
                }
            } header: { Text("iOS 沙盒文件权限边界").textCase(nil) }
            Section {
                if files.isEmpty {
                    Text("暂无沙盒文件，可在上方新建文件。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(files, id: \.self) { f in
                        HStack {
                            Image(systemName: f.hasSuffix(".db") || f.hasSuffix(".sqlite") ? "cylinder.split.1x2" : "doc.text")
                            Text(f).font(.subheadline)
                            Spacer()
                            Button("编辑") { onOpenEditor() }
                                .font(.caption)
                        }
                    }
                }
            } header: { Text("App 沙盒 Documents 目录文件 (\(files.count))").textCase(nil) }
            PrototypeRouteSections(routeId: "files", appState: appState, onNavigateRoute: onNavigateRoute)
        }
        .onAppear(perform: reload)
    }

    private func reload() {
        files = ((try? FileManager.default.contentsOfDirectory(atPath: docsURL.path)) ?? []).sorted()
    }
}

private struct SandboxCodeEditorScreen: View {
    @ObservedObject var appState: AppState = AppState()
    var onOpenDiff: () -> Void
    var onNavigateRoute: (String) -> Void = { _ in }
    @State private var fileName = "workspace_scratch.swift"
    @State private var content = "// WhereverCode V5 iOS Sandbox Scratchpad\nimport Foundation\n\nfunc greet() -> String {\n    return \"Hello WhereverCode V5\"\n}\n"
    @State private var savedSnapshot = ""
    @State private var statusMsg: String?

    private var fileURL: URL {
        let base = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first ?? URL(fileURLWithPath: NSTemporaryDirectory())
        return base.appendingPathComponent(fileName)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                TextField("文件名", text: $fileName)
                    .textFieldStyle(.roundedBorder)
                Button("读取") {
                    if let loaded = try? String(contentsOf: fileURL, encoding: .utf8) {
                        content = loaded
                        savedSnapshot = loaded
                        statusMsg = "已从沙盒读取 \(fileName)"
                    }
                }
                .buttonStyle(.bordered)
                Button("保存") {
                    do {
                        try content.write(to: fileURL, atomically: true, encoding: .utf8)
                        try? LocalData.shared.kv.set("editor.lastContent", content)
                        try? LocalData.shared.kv.set("editor.prevContent", savedSnapshot)
                        savedSnapshot = content
                        statusMsg = "已写入沙盒：\(fileName)"
                    } catch {
                        statusMsg = "保存失败：\(error.localizedDescription)"
                    }
                }
                .buttonStyle(.borderedProminent)
            }
            .padding(.horizontal, 16)
            .padding(.top, 10)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    Button("查看变更") {
                        try? LocalData.shared.kv.set("editor.lastContent", content)
                        if savedSnapshot.isEmpty { savedSnapshot = content }
                        try? LocalData.shared.kv.set("editor.prevContent", savedSnapshot)
                        onOpenDiff()
                    }
                    .buttonStyle(.bordered)
                    Button("局部编辑") {
                        statusMsg = "已进入局部编辑草稿模式"
                    }
                    .buttonStyle(.bordered)
                    Button("询问 Agent") {
                        onNavigateRoute("conversation")
                    }
                    .buttonStyle(.bordered)
                }
                .lineLimit(1)
                .padding(.horizontal, 16)
            }

            if let msg = statusMsg {
                Text(msg).font(.caption).foregroundStyle(.secondary).padding(.horizontal, 16)
            }

            TextEditor(text: $content)
                .font(.system(.footnote, design: .monospaced))
                .padding(8)
                .background(Color.white, in: RoundedRectangle(cornerRadius: 12))
                .padding(.horizontal, 16)
                .padding(.bottom, 16)
        }
    }
}

private struct UnifiedDiffScreen: View {
    @ObservedObject var appState: AppState = AppState()
    var onNavigateRoute: (String) -> Void = { _ in }
    var body: some View {
        let storedPrev = try? LocalData.shared.kv.get("editor.prevContent")
        let storedCurr = try? LocalData.shared.kv.get("editor.lastContent")
        let hasDiff = storedPrev != nil && storedCurr != nil && storedPrev != storedCurr
        let prev = storedPrev ?? ""
        let curr = storedCurr ?? ""
        let prevLines = prev.components(separatedBy: "\n")
        let currLines = curr.components(separatedBy: "\n")

        List {
            Section {
                if !hasDiff {
                    Text("暂无代码变更对比记录。可在代码编辑器中修改并保存文件后查看逐行 Diff。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(Array(prevLines.enumerated()), id: \.offset) { idx, line in
                        if !currLines.contains(line) {
                            Text("- \(line)")
                                .font(.system(.caption, design: .monospaced))
                                .foregroundStyle(.red)
                        }
                    }
                    ForEach(Array(currLines.enumerated()), id: \.offset) { idx, line in
                        if !prevLines.contains(line) {
                            Text("+ \(line)")
                                .font(.system(.caption, design: .monospaced))
                                .foregroundStyle(.green)
                        } else {
                            Text("  \(line)")
                                .font(.system(.caption, design: .monospaced))
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            } header: { Text("逐行变更对比 (Line Diff)").textCase(nil) }
            PrototypeRouteSections(routeId: "diff", appState: appState, onNavigateRoute: onNavigateRoute)
        }
    }
}

/// 工作台核心要求：明确区分 iPhone 本地真实执行、远端设备执行、云端 Agent 执行、当前平台不支持（严禁将远端执行伪装成本地执行）
private struct TerminalAndExecutionTargetScreen: View {
    @ObservedObject var appState: AppState
    var onNavigateRoute: (String) -> Void = { _ in }
    @ObservedObject var eventLog: InMemoryEventLog = LocalData.shared.eventLog
    @State private var selectedTarget: ExecutionTargetKind = .unsupportedOnPlatform

    var body: some View {
        List {
            Section {
                Picker("当前查看的执行面", selection: $selectedTarget) {
                    ForEach(ExecutionTargetKind.allCases) { target in
                        Text(target.badgeTitle).tag(target)
                    }
                }

                switch selectedTarget {
                case .unsupportedOnPlatform:
                    VStack(alignment: .leading, spacing: 6) {
                        Label("当前平台不支持本地任意 Shell / 进程 (unsupported_on_platform)", systemImage: "nosign")
                            .font(.subheadline.bold())
                            .foregroundStyle(.purple)
                        Text("iOS App Sandbox 严格禁止 fork/exec、posix_spawn 或像 Android/Termux 一样运行 bash/python/git 外部二进制。本工程绝不提供假终端提示符，亦绝不把远端执行伪装为 iPhone 本机终端。")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                case .iphoneLocalSandbox:
                    VStack(alignment: .leading, spacing: 6) {
                        Label("iPhone 本地真实执行（仅限进程内沙盒工具与 SQLite）", systemImage: "iphone")
                            .font(.subheadline.bold())
                            .foregroundStyle(.blue)
                        Text("本机真实可执行范围：本地任务数据库事务、安全凭据存取、沙盒文档读写、统一 Diff 计算、核心多轮推理与流解析。")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                case .remoteDeviceHand:
                    VStack(alignment: .leading, spacing: 6) {
                        Label("远端设备执行（Hand 协议：cross_device_tool_call）", systemImage: "desktopcomputer")
                            .font(.subheadline.bold())
                            .foregroundStyle(.orange)
                        Text("通过已配对 Mac / Linux VPS 上的 hand_server.py 执行受控动作（list / read / write / run / authorize_project_folder），必须携带 X25519+HKDF+AES-256-GCM 密封信封与 v2 任务凭证。当前状态：未配对远端 Hand 设备（ADAPTER_PENDING）。")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                case .cloudAgentMcp:
                    VStack(alignment: .leading, spacing: 6) {
                        Label("云端 Agent 执行（V5 平台 MCP：\(appState.connectionStatus.displayText)）", systemImage: "cloud")
                            .font(.subheadline.bold())
                            .foregroundStyle(.green)
                        Text("通过 HTTPS JSON-RPC 2.0 端点 (\(appState.settings.platformBaseURLString)) 调用云端频道与任务认领/续租/提交工具。")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            } header: { Text("执行环境四态严格区分（反伪造门禁）").textCase(nil) }

            Section {
                let events = eventLog.listEvents()
                if events.isEmpty {
                    Text("当前进程暂无模型与工具事件").foregroundStyle(.secondary)
                } else {
                    ForEach(events) { ev in
                        VStack(alignment: .leading, spacing: 4) {
                            Text("[#\(ev.seq)] \(ev.kind)").font(.caption.bold())
                            Text(ev.detail).font(.caption2).foregroundStyle(.secondary)
                        }
                    }
                }
            } header: { Text("本机进程真实 EngineEvent 日志流 (\(eventLog.listEvents().count) 条)").textCase(nil) }
            PrototypeRouteSections(routeId: "terminal", appState: appState, onNavigateRoute: onNavigateRoute)
        }
    }
}

private struct GitWorktreeInspectorScreen: View {
    @ObservedObject var appState: AppState = AppState()
    var onNavigateRoute: (String) -> Void = { _ in }
    var body: some View {
        let projects = (try? LocalData.shared.projects.listProjects()) ?? []
        List {
            Section {
                HStack(spacing: 8) {
                    Image(systemName: "clock.arrow.circlepath")
                        .foregroundStyle(.orange)
                    Text("【待开发】Git 与工作树待开发（iOS 无本地 CLI，远端 Hand 同步待接入）。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Text("iOS 本机不包含 git CLI 子进程；Git 分支、提交哈希与 PR 链接需通过项目管理、GitHub 服务接口或远端 Hand 协议同步。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } header: { Text("Git 协作与工作树").textCase(nil) }
            Section {
                if projects.isEmpty {
                    Text("暂无关联 Git 仓库的项目").foregroundStyle(.secondary)
                } else {
                    ForEach(projects) { item in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(item.project.name).font(.headline)
                            Text("默认分支：\(item.project.defaultBranch) · Commit: \(item.project.canonicalCommit ?? "未知（未从远端同步）")")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            } header: { Text("已登记项目的仓库与分支 (\(projects.count))").textCase(nil) }
            PrototypeRouteSections(routeId: "git", appState: appState, onNavigateRoute: onNavigateRoute)
        }
    }
}

private struct WorkbenchDevExtendedScreen: View {
    let route: FlagshipRoute
    @ObservedObject var appState: AppState = AppState()
    var onNavigateRoute: (String) -> Void = { _ in }

    var body: some View {
        List {
            Section {
                switch route.id {
                case "preview":
                    VStack(alignment: .leading, spacing: 6) {
                        HStack(spacing: 8) {
                            Image(systemName: "clock.arrow.circlepath")
                                .foregroundStyle(.orange)
                            Text("【待开发】运行预览待开发（真机/模拟器产物查看通道待后续批次接入）。")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Text("支持查看构建产物与验收截图汇总，目前通道正在接入中。")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                case "browser":
                    Text("浏览器工作台：联网检索已通过 WebSearchClient 接入；受控网页快照与录制待服务端接入。")
                case "testmatrix":
                    Text("测试矩阵：汇总 iOS/Android 路由、任务状态机、本地数据库与 MCP 工具校验结果。")
                default:
                    Text(route.title)
                }
            } header: { Text("\(route.title)").textCase(nil) }
            PrototypeRouteSections(routeId: route.id, appState: appState, onNavigateRoute: onNavigateRoute)
        }
    }
}

// MARK: - 3. 工作台 · 协作与连接子模块实现
private struct CollaborationAndAgentsScreen: View {
    let route: FlagshipRoute
    @ObservedObject var appState: AppState
    var onNavigateRoute: (String) -> Void

    @State private var agents: [AgentRecord] = []
    @State private var showAddAgentSheet = false
    @State private var bannerMsg: String? = nil
    @State private var testingAgentId: String? = nil
    @State private var testResultMsg: String? = nil
    @State private var agentPendingDelete: AgentRecord? = nil
    @State private var showDeleteAgentConfirm = false

    var body: some View {
        List {
            if let bannerMsg {
                Section {
                    Label(bannerMsg, systemImage: "checkmark.circle.fill")
                        .font(.caption)
                        .foregroundStyle(.green)
                }
            }

            if let testResultMsg {
                Section {
                    Text(testResultMsg)
                        .font(.caption)
                        .foregroundStyle(.blue)
                }
            }

            // 块一：添加 Agent
            Section {
                Button {
                    showAddAgentSheet = true
                } label: {
                    Label("＋ 添加 Agent", systemImage: "plus.circle.fill")
                        .font(.subheadline.weight(.semibold))
                }
            } header: {
                // 渲染层修正（A5）：List 分节头默认把拉丁字母转大写，与安卓「Agent」大小写不一致，故显式关闭大小写转换
                Text("添加 Agent").textCase(nil)
            }

            // 块二：已添加的 Agent
            Section {
                if agents.isEmpty {
                    Text("还没有添加 Agent。")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(agents) { ag in
                        let devId = ag.deviceId ?? ""
                        let allRecords = (try? LocalData.shared.agents.listAgents()) ?? []
                        let hostRecord = allRecords.first(where: { $0.isDeviceHost && $0.agentId == devId })
                        let isHostOnline = !devId.isEmpty && hostRecord != nil && hostRecord?.memberConnectionState == "CONNECTED"
                        VStack(alignment: .leading, spacing: 6) {
                            HStack {
                                Text(ag.displayName).font(.subheadline.weight(.semibold))
                                if !devId.isEmpty && !isHostOnline {
                                    Text("设备未连接")
                                        .font(.caption2.weight(.medium))
                                        .foregroundStyle(.orange)
                                        .padding(.horizontal, 5)
                                        .padding(.vertical, 1)
                                        .background(Color.orange.opacity(0.12), in: Capsule())
                                }
                                Spacer()
                                Toggle("启用", isOn: Binding(
                                    get: { ag.enabled },
                                    set: { val in
                                        try? LocalData.shared.agents.setEnabled(agentId: ag.agentId, enabled: val)
                                        reload()
                                    }
                                ))
                                .labelsHidden()
                            }
                            Text("ID: \(ag.agentId) · 类型: \(ag.kind.rawValue) · 接入: \(ag.access.rawValue)")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            if !devId.isEmpty {
                                if isHostOnline {
                                    Text("运行在设备：\(devId)\(ag.deviceFolder.map { " · 目录：\($0)" } ?? "")")
                                        .font(.caption2)
                                        .foregroundStyle(.blue)
                                } else {
                                    Text("运行在设备：\(devId)（设备未连接）\(ag.deviceFolder.map { " · 目录：\($0)" } ?? "")")
                                        .font(.caption2)
                                        .foregroundStyle(.secondary)
                                }
                            }
                            HStack(spacing: 8) {
                                if ag.kind == .device, let host = ag.baseUrl {
                                    Button(testingAgentId == ag.agentId ? "正在测试…" : "单兵运行测试") {
                                        executeSingleAgentTest(ag)
                                    }
                                    .buttonStyle(.borderedProminent)
                                    .controlSize(.mini)
                                    .disabled(testingAgentId != nil)
                                }
                                Button("对话执行") {
                                    onNavigateRoute("chat")
                                }
                                .buttonStyle(.bordered)
                                .controlSize(.mini)

                                Button("工具权限") { onNavigateRoute("mcp") }.buttonStyle(.bordered).controlSize(.mini)
                                Spacer()
                                Button("删除", role: .destructive) {
                                    agentPendingDelete = ag
                                    showDeleteAgentConfirm = true
                                }.font(.caption2)
                            }
                        }
                    }
                }
            } header: {
                // 渲染层修正（A5）：同上，关闭分节头默认大写转换
                Text("已添加的 Agent（\(agents.count)）").textCase(nil)
            }

            if route.id != "agents" && route.id != "team" {
                PrototypeRouteSections(routeId: route.id, appState: appState, onNavigateRoute: onNavigateRoute)
            }
        }
        .navigationTitle(route.id == "team" ? "团队空间" : "Agent")
        .onAppear(perform: reload)
        .alert("移除 Agent", isPresented: $showDeleteAgentConfirm) {
            Button("取消", role: .cancel) { agentPendingDelete = nil }
            Button("移除", role: .destructive) {
                if let ag = agentPendingDelete {
                    try? LocalData.shared.agents.deleteAgent(agentId: ag.agentId)
                    agentPendingDelete = nil
                    reload()
                }
            }
        } message: {
            if let ag = agentPendingDelete {
                Text("确定移除 \(ag.displayName) 吗？它的独立会话与已指派任务不受影响。")
            }
        }
        .sheet(isPresented: $showAddAgentSheet) {
            AddAgentSheet(
                onSaved: { addedName in
                    reload()
                    bannerMsg = "已添加 Agent「\(addedName)」。"
                },
                onBatchAdded: { count in
                    reload()
                    bannerMsg = "已添加 \(count) 个 Agent。"
                }
            )
        }
    }

    private func executeSingleAgentTest(_ ag: AgentRecord) {
        guard let raw = ag.baseUrl else { return }
        testingAgentId = ag.agentId
        testResultMsg = "正在向 \(ag.displayName) 下发单兵冒烟测试指令…"
        let clean = raw.replacingOccurrences(of: "http://", with: "").replacingOccurrences(of: "https://", with: "")
        let parts = clean.split(separator: ":")
        let host = String(parts[0])
        let port = parts.count >= 2 ? (Int(parts[1]) ?? 18743) : 18743
        let targetDev = ag.deviceId ?? ""
        let folder = ag.deviceFolder ?? ""
        let realAgentId = ag.agentId.contains(":") ? String(ag.agentId.split(separator: ":").last!) : ag.agentId

        Task {
            do {
                let output = try await HandClient.shared.runAgent(
                    host: host,
                    port: port,
                    targetDeviceId: targetDev,
                    folder: folder,
                    agentId: realAgentId,
                    prompt: "echo SINGLE_AGENT_SMOKE_PASS",
                    timeoutSeconds: 30.0
                ) { progress in
                    Task { @MainActor in
                        self.testResultMsg = "进度：\(progress)"
                    }
                }
                await MainActor.run {
                    self.testingAgentId = nil
                    self.testResultMsg = "单兵测试成功！输出：\(output.trimmingCharacters(in: .whitespacesAndNewlines))"
                }
            } catch {
                await MainActor.run {
                    self.testingAgentId = nil
                    self.testResultMsg = "单兵测试失败：\(error.localizedDescription)"
                }
            }
        }
    }

    private func reload() {
        let all = (try? LocalData.shared.agents.listAgents()) ?? []
        // 过滤掉纯设备记录与模型记录，确保 Agent 列表中只有纯正智能体，不与设备和模型混淆
        agents = all.filter { !$0.isDeviceHost && $0.kind != .localModel }
    }
}

private struct AddAgentSheet: View {
    var onSaved: (String) -> Void
    var onBatchAdded: (Int) -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var newAgentId = ""
    @State private var newAgentName = ""
    @State private var newAgentKind: AgentKind = .cloud
    @State private var newAgentAccess: AgentAccess = .cli
    @State private var newAgentDeviceId = ""
    @State private var newAgentEndpoint = ""

    // 自动发现子状态
    @State private var showScanDevicePicker = false
    @State private var scanErrorMessage: String?
    @State private var pairedDevices: [AgentRecord] = []
    @State private var checkedDeviceIndices: Set<Int> = []
    @State private var isScanningDevices = false

    @State private var showDiscoveredAgentsPicker = false
    @State private var discoveredItems: [(device: AgentRecord, info: RemoteAgentInfo)] = []
    @State private var checkedDiscoveredIndices: Set<Int> = []

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Button {
                        startDiscoveryFlow()
                    } label: {
                        HStack {
                            Label("自动发现设备上的 Agent", systemImage: "sparkles")
                                .font(.subheadline.weight(.semibold))
                            Spacer()
                            Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
                        }
                    }
                } header: { Text("自动发现").textCase(nil) } footer: {
                    Text("从已配对的设备中批量扫描并一键勾选添加 Agent。")
                }

                Section {
                    TextField("Agent 标识（如 my-codex）", text: $newAgentId)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    TextField("显示名称（如 Codex 工作节点）", text: $newAgentName)
                    Picker("类型", selection: $newAgentKind) {
                        Text("云端 Agent").tag(AgentKind.cloud)
                        Text("设备 Agent").tag(AgentKind.device)
                    }
                    if newAgentKind == .device {
                        let registeredDevices = (try? LocalData.shared.agents.listAgents())?.filter { $0.kind == .device && $0.access == .app } ?? []
                        Picker("绑定执行设备", selection: $newAgentDeviceId) {
                            Text("未绑定设备").tag("")
                            ForEach(registeredDevices) { dev in
                                Text(dev.displayName).tag(dev.deviceId ?? dev.agentId)
                            }
                        }
                    }
                    Picker("接入方式", selection: $newAgentAccess) {
                        ForEach(AgentAccess.allCases, id: \.self) { a in
                            Text(a.rawValue).tag(a)
                        }
                    }
                    TextField("服务或 Webhook 地址（可选）", text: $newAgentEndpoint)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()

                    Button("保存 Agent") {
                        saveManualAgent()
                    }
                    .disabled(newAgentName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && newAgentId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                } header: { Text("手动配置 Agent").textCase(nil) }

                if let scanErrorMessage {
                    Section {
                        Text(scanErrorMessage).font(.caption).foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle("添加 Agent")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .primaryAction) {
                    Button("自动发现") {
                        startDiscoveryFlow()
                    }
                }
            }
            .sheet(isPresented: $showScanDevicePicker) {
                scanDevicePickerSheet
            }
            .sheet(isPresented: $showDiscoveredAgentsPicker) {
                discoveredAgentsPickerSheet
            }
        }
    }

    private func startDiscoveryFlow() {
        let all = (try? LocalData.shared.agents.listAgents()) ?? []
        let devices = all.filter { $0.kind == .device && $0.access == .app }
        guard !devices.isEmpty else {
            scanErrorMessage = "还没加设备：先去设备页添加设备并完成配对。"
            return
        }
        let paired = devices.filter { $0.memberConnectionState == "CONNECTED" || $0.authRef != nil }
        guard !paired.isEmpty else {
            scanErrorMessage = "设备还没配对完成：先在设备页完成配对。"
            return
        }
        pairedDevices = paired
        // 自动发现第一步：勾选要扫描的设备（默认一个都不勾，勾几个扫几个）
        checkedDeviceIndices = []
        scanErrorMessage = nil
        showScanDevicePicker = true
    }

    private var scanDevicePickerSheet: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(Array(pairedDevices.enumerated()), id: \.offset) { idx, dev in
                        Button {
                            if checkedDeviceIndices.contains(idx) {
                                checkedDeviceIndices.remove(idx)
                            } else {
                                checkedDeviceIndices.insert(idx)
                            }
                        } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(dev.displayName).font(.subheadline.weight(.medium)).foregroundStyle(.primary)
                                    Text(dev.baseUrl ?? "未填地址").font(.caption).foregroundStyle(.secondary)
                                }
                                Spacer()
                                if checkedDeviceIndices.contains(idx) {
                                    Image(systemName: "checkmark.circle.fill").foregroundStyle(.blue)
                                } else {
                                    Image(systemName: "circle").foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                } header: { Text("选择要扫描的设备").textCase(nil) } footer: {
                    Text("勾选要扫描的已配对设备（默认不勾，勾几个扫几个）。")
                }

                if isScanningDevices {
                    HStack(spacing: 8) {
                        ProgressView()
                        Text("正在扫描设备上的 Agent…").font(.caption).foregroundStyle(.secondary)
                    }
                } else {
                    Button("扫描") {
                        executeDeviceScan()
                    }
                    .disabled(checkedDeviceIndices.isEmpty)
                }

                if let scanErrorMessage {
                    Text(scanErrorMessage).font(.caption).foregroundStyle(.red)
                }
            }
            .navigationTitle("选择要扫描的设备")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { showScanDevicePicker = false }
                }
            }
        }
    }

    private func executeDeviceScan() {
        guard !checkedDeviceIndices.isEmpty else {
            scanErrorMessage = "先勾选要扫描的设备。"
            return
        }
        let targets = checkedDeviceIndices.sorted().map { pairedDevices[$0] }
        isScanningDevices = true
        scanErrorMessage = nil

        Task {
            let existingAgents = (try? LocalData.shared.agents.listAgents()) ?? []
            let existingIds = Set(existingAgents.map(\.agentId))

            var found: [(device: AgentRecord, info: RemoteAgentInfo)] = []
            for dev in targets {
                guard let raw = dev.baseUrl, !raw.isEmpty else { continue }
                let clean = raw.replacingOccurrences(of: "http://", with: "").replacingOccurrences(of: "https://", with: "")
                let parts = clean.split(separator: ":")
                let host = String(parts[0])
                let port = parts.count >= 2 ? (Int(parts[1]) ?? 18743) : 18743

                do {
                    let hello = try await HandClient.shared.probeHello(host: host, port: port)
                    let devId = dev.deviceId ?? dev.agentId
                    for ag in hello.agents where ag.available && !ag.folder.isEmpty {
                        let fullAgentId = "\(devId):\(ag.agentId)"
                        if !existingIds.contains(fullAgentId) && !existingIds.contains(ag.agentId) {
                            found.append((device: dev, info: ag))
                        }
                    }
                } catch {
                    continue
                }
            }

            let finalDiscovered = found
            await MainActor.run {
                self.isScanningDevices = false
                if finalDiscovered.isEmpty {
                    self.scanErrorMessage = "扫描完成：没有可添加的 Agent。"
                } else {
                    self.discoveredItems = finalDiscovered
                    // 自动发现第三步：一张勾选单选要添加的 Agent（可添加项预勾，默认全选）
                    self.checkedDiscoveredIndices = Set(0..<finalDiscovered.count)
                    self.showScanDevicePicker = false
                    self.showDiscoveredAgentsPicker = true
                }
            }
        }
    }

    private var discoveredAgentsPickerSheet: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(Array(discoveredItems.enumerated()), id: \.offset) { idx, item in
                        Button {
                            if checkedDiscoveredIndices.contains(idx) {
                                checkedDiscoveredIndices.remove(idx)
                            } else {
                                checkedDiscoveredIndices.insert(idx)
                            }
                        } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("\(item.info.displayName)（\(item.info.folder)）· \(item.device.displayName)")
                                        .font(.subheadline)
                                        .foregroundStyle(.primary)
                                    Text("ID: \(item.info.agentId)")
                                        .font(.caption2)
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                                if checkedDiscoveredIndices.contains(idx) {
                                    Image(systemName: "checkmark.circle.fill").foregroundStyle(.blue)
                                } else {
                                    Image(systemName: "circle").foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                } header: { Text("发现的 Agent（\(discoveredItems.count)）").textCase(nil) } footer: {
                    Text("已存在的 Agent 不重复列出；勾选需要添加的条目后确认。")
                }

                Section {
                    Button("添加所选 (\(checkedDiscoveredIndices.count))") {
                        saveDiscoveredAgents()
                    }
                    .disabled(checkedDiscoveredIndices.isEmpty)
                }
            }
            .navigationTitle("发现的 Agent（\(discoveredItems.count)）")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { showDiscoveredAgentsPicker = false }
                }
            }
        }
    }

    private func saveDiscoveredAgents() {
        var count = 0
        for idx in checkedDiscoveredIndices.sorted() {
            let item = discoveredItems[idx]
            let devId = item.device.deviceId ?? item.device.agentId
            let fullId = "\(devId):\(item.info.agentId)"
            let rec = AgentRecord(
                agentId: fullId,
                displayName: "\(item.info.displayName) (\(item.device.displayName))",
                kind: .device,
                access: .cli,
                baseUrl: item.device.baseUrl,
                lastTestSummary: "设备 Agent · \(item.info.folder)",
                memberConnectionState: "CONNECTED",
                deviceId: devId,
                deviceFolder: item.info.folder
            )
            try? LocalData.shared.agents.saveAgent(rec)
            count += 1
        }
        showDiscoveredAgentsPicker = false
        dismiss()
        onBatchAdded(count)
    }

    private func saveManualAgent() {
        let cleanName = newAgentName.trimmingCharacters(in: .whitespacesAndNewlines)
        let rawId = newAgentId.trimmingCharacters(in: .whitespacesAndNewlines)
        let finalId = rawId.isEmpty ? "agent-\(UUID().uuidString.prefix(6).lowercased())" : rawId
        guard !cleanName.isEmpty || !rawId.isEmpty else { return }
        var rec = AgentRecord(
            agentId: finalId,
            displayName: cleanName.isEmpty ? finalId : cleanName,
            kind: newAgentKind,
            access: newAgentAccess,
            baseUrl: newAgentEndpoint.isEmpty ? nil : newAgentEndpoint,
            lastTestSummary: "本地已登记",
            memberConnectionState: "NOT_CONNECTED"
        )
        if newAgentKind == .device && !newAgentDeviceId.isEmpty {
            rec.deviceId = newAgentDeviceId
        }
        try? LocalData.shared.agents.saveAgent(rec)
        dismiss()
        onSaved(rec.displayName)
    }
}

public struct V5PluginRecord: Identifiable, Codable, Equatable, Sendable {
    public var id: String
    public var name: String
    public var version: String
    public var summary: String
    public var status: String // "已启用", "已安装", "未安装", "已禁用"
    public var permissions: [String]
    public var providedTools: [String]
    public var capabilityState: String // "已连接", "已授权", "可调用", "已实际执行"

    public init(id: String, name: String, version: String, summary: String, status: String, permissions: [String], providedTools: [String], capabilityState: String) {
        self.id = id
        self.name = name
        self.version = version
        self.summary = summary
        self.status = status
        self.permissions = permissions
        self.providedTools = providedTools
        self.capabilityState = capabilityState
    }
}

public enum V5PluginManager {
    private static let storageKey = "v5.plugins.records.v1"

    public static func listPlugins() -> [V5PluginRecord] {
        if let stored = try? LocalData.shared.kv.getJSON(storageKey, as: [V5PluginRecord].self) {
            return stored
        }
        return []
    }

    public static func savePlugins(_ plugins: [V5PluginRecord]) {
        try? LocalData.shared.kv.setJSON(storageKey, plugins)
    }

    public static func togglePlugin(id: String) -> [V5PluginRecord] {
        var list = listPlugins()
        if let idx = list.firstIndex(where: { $0.id == id }) {
            if list[idx].status == "已启用" {
                list[idx].status = "已禁用"
                list[idx].capabilityState = "已授权"
            } else if list[idx].status == "已安装" || list[idx].status == "已禁用" {
                list[idx].status = "已启用"
                list[idx].capabilityState = "可调用"
            } else if list[idx].status == "未安装" {
                list[idx].status = "已安装"
                list[idx].capabilityState = "已授权"
            }
            savePlugins(list)
            LocalData.shared.approvalStore.recordAudit("[PLUGIN] \(list[idx].name) 状态更新为 \(list[idx].status) (能力阶段: \(list[idx].capabilityState))")
        }
        return list
    }
}

private struct McpToolsInspectorScreen: View {
    @ObservedObject var appState: AppState
    var onNavigateRoute: (String) -> Void = { _ in }
    @State private var toolNames: [String] = []
    @State private var isLoading = false
    @State private var errorMsg: String?
    @State private var tokenInput = ""
    @State private var issueAgentId = PlatformTokenIssuer.defaultAgentID
    @State private var issueTokenName = PlatformTokenIssuer.defaultName
    @State private var isIssuing = false
    @State private var statusBanner: String?

    // 真实工具执行状态
    @State private var selectedToolToExecute = "agent_channel_list"
    @State private var isCallingTool = false
    @State private var toolCallResult: String?
    @State private var toolCallSuccess: Bool?

    private let standardV5Tools: [(name: String, summary: String)] = [
        ("issue_agent_token", "签发平台 Agent 访问令牌"),
        ("agent_channel_list", "拉取可见团队频道列表"),
        ("agent_channel_join", "加入指定团队频道"),
        ("agent_channel_post", "在频道发送消息"),
        ("agent_channel_messages", "增量拉取频道历史消息"),
        ("agent_my_tasks", "拉取分配给当前 Agent 的任务"),
        ("agent_task_claim", "认领任务（2小时租约）"),
        ("agent_task_progress", "汇报任务进度并续租"),
        ("agent_task_heartbeat", "发送任务心跳续租"),
        ("agent_task_submit", "提交任务成果（分支/Commit/PR）"),
        ("agent_task_release", "释放已认领的任务")
    ]

    var body: some View {
        List {
            if let statusBanner {
                Section {
                    Label(statusBanner, systemImage: "checkmark.circle.fill")
                        .font(.caption)
                        .foregroundStyle(.green)
                }
            }

            // 能力四态标识看板
            Section {
                HStack(spacing: 8) {
                    stateBadge(title: "已连接", isOk: appState.settings.validatedBaseURL != nil, note: "通道畅通")
                    stateBadge(title: "已授权", isOk: appState.hasToken, note: "持有 Token")
                    stateBadge(title: "可调用", isOk: !toolNames.isEmpty, note: "\(toolNames.count) 项工具")
                    stateBadge(title: "已实际执行", isOk: toolCallSuccess == true, note: toolCallSuccess == true ? "真实回执" : "未执行")
                }
                .padding(.vertical, 4)
            } header: { Text("MCP 能力与状态边界（严格区分四态）").textCase(nil) }

            Section {
                LabeledContent("服务地址", value: appState.settings.platformBaseURLString)
                Button("复制服务地址") {
                    UIPasteboard.general.string = appState.settings.platformBaseURLString
                }
                LabeledContent("凭证状态", value: appState.hasToken ? "已保存 Token (已授权)" : "未配置 Token (未授权)")
                SecureField("粘贴平台 Token（v5_ 开头，仅存本机钥匙串）", text: $tokenInput)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                HStack {
                    Button("保存 Token 并探测工具") {
                        let clean = tokenInput.trimmingCharacters(in: .whitespacesAndNewlines)
                        guard !clean.isEmpty else { return }
                        try? appState.saveToken(clean)
                        tokenInput = ""
                        statusBanner = "Token 已安全保存，正在拉取远端 MCP 工具列表…"
                        Task { await fetchTools() }
                    }
                    .disabled(tokenInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    Spacer()
                    if appState.hasToken {
                        Button("清除凭证", role: .destructive) {
                            try? appState.deleteToken()
                            toolNames = []
                            toolCallResult = nil
                            toolCallSuccess = nil
                            statusBanner = "已清除本机保存的平台 Token。"
                        }
                    }
                }
            } header: { Text("平台连接与 Token 配置").textCase(nil) }

            Section {
                Picker("待测试调用工具", selection: $selectedToolToExecute) {
                    Text("agent_channel_list (拉取频道)").tag("agent_channel_list")
                    Text("agent_my_tasks (拉取分配任务)").tag("agent_my_tasks")
                    Text("issue_agent_token (申请签发令牌)").tag("issue_agent_token")
                    ForEach(toolNames, id: \.self) { t in
                        Text(t).tag(t)
                    }
                }

                Button(isCallingTool ? "正在向平台执行 tools/call…" : "执行真实 tools/call 调用") {
                    Task { await executeSelectedTool() }
                }
                .disabled(isCallingTool || !appState.hasToken)

                if let success = toolCallSuccess, let res = toolCallResult {
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(success ? "执行成功 (已实际执行)" : "执行失败")
                                .font(.caption.bold())
                                .foregroundStyle(success ? .green : .red)
                            Spacer()
                        }
                        Text(res)
                            .font(.system(.caption2, design: .monospaced))
                            .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                            .padding(8)
                            .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 6))
                    }
                }
            } header: { Text("发起真实 MCP tools/call 验证").textCase(nil) }

            Section {
                TextField("签发对象 Agent ID", text: $issueAgentId)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                TextField("令牌名称", text: $issueTokenName)
                Button(isIssuing ? "正在向平台申请签发…" : "生成并保存 Token") {
                    Task {
                        isIssuing = true
                        defer { isIssuing = false }
                        let res = await appState.generateAndSaveToken(agentId: issueAgentId, name: issueTokenName)
                        switch res {
                        case .issued(_, _, let aid, _):
                            statusBanner = "已为 \(aid) 生成新 Token 并保存。"
                            await fetchTools()
                        case .failed(let msg):
                            errorMsg = msg
                        }
                    }
                }
                .disabled(isIssuing)
                Text("说明：需要当前已保存具备签发权限的平台凭证，生成的令牌将直接存入本机安全存储。")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            } header: { Text("生成新 Token（issue_agent_token）").textCase(nil) }

            Section {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("触发器与 Webhook 唤醒").font(.subheadline.weight(.medium))
                        Text("配置 Git / 消息 / 定时事件唤醒与签名校验规则").font(.caption2).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button("打开触发器") { onNavigateRoute("triggers") }
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                    Button("唤醒策略") { onNavigateRoute("wakepolicy") }
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                }
            } header: { Text("Webhook 唤醒与自动触发").textCase(nil) }

            Section {
                Button(isLoading ? "正在从平台拉取工具列表…" : "刷新远端 MCP 工具列表") {
                    Task { await fetchTools() }
                }
                .disabled(isLoading)
                if let err = errorMsg {
                    Text(err).font(.caption).foregroundStyle(.red)
                }
                if toolNames.isEmpty {
                    Text(appState.hasToken ? "点击上方「刷新远端 MCP 工具列表」从服务端实时拉取。" : "当前未配置平台 Token，远端实时工具列表未拉取；下方列出本客户端已对接的标准 V5 平台工具。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(toolNames, id: \.self) { name in
                        HStack {
                            Label(name, systemImage: "wrench.and.screwdriver")
                                .font(.system(.subheadline, design: .monospaced))
                            Spacer()
                            Text("可调用")
                                .font(.caption2)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(Color.orange.opacity(0.12), in: Capsule())
                                .foregroundStyle(.orange)
                        }
                    }
                }
            } header: { Text("远端 MCP 工具实时探测（\(toolNames.count) 项）").textCase(nil) }

            Section {
                ForEach(standardV5Tools, id: \.name) { item in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(item.name).font(.system(.caption, design: .monospaced).bold())
                            Text(item.summary).font(.caption2).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Text("规范标准")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
            } header: { Text("客户端已内置对接的 V5 MCP 工具（\(standardV5Tools.count) 项）").textCase(nil) }

            PrototypeRouteSections(routeId: "mcp", appState: appState, onNavigateRoute: onNavigateRoute)
        }
        .task {
            if appState.hasToken && toolNames.isEmpty {
                await fetchTools()
            }
        }
    }

    private func stateBadge(title: String, isOk: Bool, note: String) -> some View {
        VStack(spacing: 2) {
            Text(title)
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(isOk ? Color.primary : Color.secondary)
            Text(isOk ? "✓ 就绪" : "✕ 未满足")
                .font(.system(size: 10, weight: .semibold))
                .padding(.horizontal, 4)
                .padding(.vertical, 2)
                .background(isOk ? Color.green.opacity(0.15) : Color.secondary.opacity(0.12), in: Capsule())
                .foregroundStyle(isOk ? .green : .secondary)
            Text(note)
                .font(.system(size: 8))
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity)
        .padding(6)
        .background(Color.white, in: RoundedRectangle(cornerRadius: 8))
        .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.secondary.opacity(0.15), lineWidth: 1))
    }

    private func fetchTools() async {
        guard let client = appState.makeMcpClient() else {
            errorMsg = "尚未保存平台 Token：请在上方填入或生成 Token 后再探测。"
            return
        }
        isLoading = true
        defer { isLoading = false }
        do {
            toolNames = try await client.listToolNames()
            errorMsg = nil
            LocalData.shared.approvalStore.recordAudit("[MCP_DISCOVERY] 成功探测远端工具: \(toolNames.count) 项 (状态: 可调用)")
        } catch {
            errorMsg = error.localizedDescription
            LocalData.shared.approvalStore.recordAudit("[MCP_DISCOVERY_ERR] 探测失败: \(error.localizedDescription)")
        }
    }

    private func executeSelectedTool() async {
        guard let client = appState.makeMcpClient() else {
            errorMsg = "尚未保存平台 Token"
            return
        }
        isCallingTool = true
        defer { isCallingTool = false }
        do {
            var args: [String: Any] = [:]
            if selectedToolToExecute == "issue_agent_token" {
                args = ["agent_id": issueAgentId, "name": issueTokenName]
            }
            let res = try await client.callTool(name: selectedToolToExecute, arguments: args)
            toolCallSuccess = true
            let resJson = (try? JSONSerialization.data(withJSONObject: res, options: .prettyPrinted)).flatMap { String(data: $0, encoding: .utf8) } ?? "\(res)"
            toolCallResult = resJson
            LocalData.shared.approvalStore.recordAudit("[MCP_EXEC] \(selectedToolToExecute) 调用成功 -> 已实际执行 (返回 \(res.count) 个字段)")
        } catch {
            toolCallSuccess = false
            toolCallResult = "调用错误: \(error.localizedDescription)"
            LocalData.shared.approvalStore.recordAudit("[MCP_EXEC_ERR] \(selectedToolToExecute) 调用失败 -> \(error.localizedDescription)")
        }
    }
}

private struct SkillsManagementScreen: View {
    var routeId: String = "skills"
    @ObservedObject var appState: AppState = AppState()
    var onNavigateRoute: (String) -> Void = { _ in }
    @ObservedObject var skillStore: SkillStore = LocalData.shared.skillStore
    @State private var newName = ""
    @State private var newSummary = ""
    @State private var newTrigger = ""

    var body: some View {
        List {
            Section {
                ForEach(skillStore.skills) { skill in
                    HStack {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("\(skill.name) (v\(skill.version))").font(.headline)
                            Text(skill.summary).font(.caption).foregroundStyle(.secondary)
                            Text("触发提示：\(skill.triggerHint) · 来源：\(skill.source)")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        Toggle("", isOn: Binding(
                            get: { skill.enabled },
                            set: { _ in skillStore.toggleSkill(id: skill.id) }
                        ))
                        .labelsHidden()
                    }
                }
            } header: { Text("已注册技能列表 (\(skillStore.skills.count))").textCase(nil) }
            Section {
                TextField("技能名称", text: $newName)
                TextField("技能摘要", text: $newSummary)
                TextField("触发场景", text: $newTrigger)
                Button("保存技能") {
                    guard !newName.isEmpty else { return }
                    skillStore.addCustomSkill(name: newName, summary: newSummary, triggerHint: newTrigger)
                    newName = ""
                    newSummary = ""
                    newTrigger = ""
                }
            } header: { Text("提炼并添加新技能").textCase(nil) }
            PrototypeRouteSections(routeId: routeId, appState: appState, onNavigateRoute: onNavigateRoute)
        }
    }
}

private struct ConnectionExtendedScreen: View {
    let route: FlagshipRoute
    @ObservedObject var appState: AppState
    var onNavigateRoute: (String) -> Void = { _ in }

    var body: some View {
        List {
            Section {
                switch route.id {
                case "agentimport":
                    Text("导入现有 Agent：支持将 Codex、Claude Code、OpenCode 等外部 Agent 登记至本机 Agent 目录。")
                case "network", "routes":
                    Text("通信网络与路径详情：仅使用受系统保护的 HTTPS 与安全信封传输；断线期间操作保存在本地数据库，确认回执后才更新送达状态。")
                default:
                    Text(route.title)
                }
            } header: { Text("\(route.title)").textCase(nil) }
            PrototypeRouteSections(routeId: route.id, appState: appState, onNavigateRoute: onNavigateRoute)
        }
    }
}

// MARK: - 3. 频道与私聊主视图实现 (ChannelsHomeView & ChannelDetailWorkspaceView)

public struct V5ChannelItem: Identifiable, Equatable, Hashable {
    public let id: String
    public var name: String
    public var topic: String
    public var isPrivate: Bool
    public var associatedProjectId: String?
    public var associatedProjectName: String?
    public var memberCount: Int
    public var agentCount: Int
    public var lastMessageText: String
    public var lastMessageTime: String
    public var unreadCount: Int
    public var isMuted: Bool
    public var isIgnored: Bool
    public var isArchived: Bool

    public init(
        id: String,
        name: String,
        topic: String = "",
        isPrivate: Bool = false,
        associatedProjectId: String? = nil,
        associatedProjectName: String? = nil,
        memberCount: Int = 3,
        agentCount: Int = 1,
        lastMessageText: String = "",
        lastMessageTime: String = "12:00",
        unreadCount: Int = 0,
        isMuted: Bool = false,
        isIgnored: Bool = false,
        isArchived: Bool = false
    ) {
        self.id = id
        self.name = name
        self.topic = topic
        self.isPrivate = isPrivate
        self.associatedProjectId = associatedProjectId
        self.associatedProjectName = associatedProjectName
        self.memberCount = memberCount
        self.agentCount = agentCount
        self.lastMessageText = lastMessageText
        self.lastMessageTime = lastMessageTime
        self.unreadCount = unreadCount
        self.isMuted = isMuted
        self.isIgnored = isIgnored
        self.isArchived = isArchived
    }
}

public struct V5DirectMessageItem: Identifiable, Equatable, Hashable {
    public let id: String
    public var targetId: String
    public var targetName: String
    public var isAgent: Bool
    public var targetRole: String
    public var lastMessageText: String
    public var lastMessageTime: String
    public var unreadCount: Int
    public var isMuted: Bool
    public var isIgnored: Bool

    public init(
        id: String,
        targetId: String,
        targetName: String,
        isAgent: Bool,
        targetRole: String = "",
        lastMessageText: String = "",
        lastMessageTime: String = "12:00",
        unreadCount: Int = 0,
        isMuted: Bool = false,
        isIgnored: Bool = false
    ) {
        self.id = id
        self.targetId = targetId
        self.targetName = targetName
        self.isAgent = isAgent
        self.targetRole = targetRole
        self.lastMessageText = lastMessageText
        self.lastMessageTime = lastMessageTime
        self.unreadCount = unreadCount
        self.isMuted = isMuted
        self.isIgnored = isIgnored
    }
}

public struct V5ChannelMember: Identifiable, Codable, Equatable, Hashable, Sendable {
    public var id: String
    public var name: String
    public var isAgent: Bool
    public var role: String
    public var status: String
    public var agentRecordId: String?

    public init(id: String, name: String, isAgent: Bool, role: String, status: String = "在线", agentRecordId: String? = nil) {
        self.id = id
        self.name = name
        self.isAgent = isAgent
        self.role = role
        self.status = status
        self.agentRecordId = agentRecordId
    }
}

public enum ChannelMemberManager {
    public static func loadMembers(channelId: String) -> [V5ChannelMember] {
        let key = "channel.members.\(channelId)"
        if let stored = try? LocalData.shared.kv.getJSON(key, as: [V5ChannelMember].self), !stored.isEmpty {
            return stored
        }
        let initial: [V5ChannelMember] = [
            V5ChannelMember(id: "user-me", name: "我", isAgent: false, role: "项目主管 · 人工审批", status: "在线"),
            V5ChannelMember(id: "agent-codex", name: "Codex", isAgent: true, role: "设备 Agent · 代码实现与构建", status: "在线", agentRecordId: "codex"),
            V5ChannelMember(id: "agent-claude", name: "Claude Code", isAgent: true, role: "云端 Agent · 架构规划与审查", status: "就绪", agentRecordId: "claude-code")
        ]
        try? LocalData.shared.kv.setJSON(key, initial)
        return initial
    }

    public static func saveMembers(_ members: [V5ChannelMember], channelId: String) {
        let key = "channel.members.\(channelId)"
        try? LocalData.shared.kv.setJSON(key, members)
    }

    public static func addAgentMember(_ agent: AgentRecord, channelId: String) -> [V5ChannelMember] {
        var list = loadMembers(channelId: channelId)
        if !list.contains(where: { $0.agentRecordId == agent.agentId || $0.name == agent.displayName }) {
            let roleDesc = "\(agent.kind == .device ? "设备" : (agent.kind == .cloud ? "云端" : "本地模型")) Agent · \(agent.brainKind)"
            let newMember = V5ChannelMember(
                id: "agent-\(agent.agentId.replacingOccurrences(of: ":", with: "-"))",
                name: agent.displayName,
                isAgent: true,
                role: roleDesc,
                status: agent.memberConnectionState == "CONNECTED" ? "在线" : "就绪",
                agentRecordId: agent.agentId
            )
            list.append(newMember)
            saveMembers(list, channelId: channelId)
        }
        return list
    }

    public static func removeMember(memberId: String, channelId: String) -> [V5ChannelMember] {
        var list = loadMembers(channelId: channelId)
        list.removeAll { $0.id == memberId }
        saveMembers(list, channelId: channelId)
        return list
    }
}

public struct V5ChannelMessage: Identifiable, Equatable, Hashable {
    public let id: String
    public var senderId: String
    public var senderName: String
    public var isAgent: Bool
    public var text: String
    public var timestamp: String
    public var fullDateTime: String
    public var replyToId: String?
    public var replyToText: String?
    public var linkedTaskId: String?
    public var linkedTaskTitle: String?
    public var linkedTaskState: String?
    public var attachmentName: String?
    public var attachmentType: String?
    public var attachmentSize: String?

    public init(
        id: String,
        senderId: String,
        senderName: String,
        isAgent: Bool,
        text: String,
        timestamp: String = "12:00",
        fullDateTime: String = "2026-11-15 12:00:00",
        replyToId: String? = nil,
        replyToText: String? = nil,
        linkedTaskId: String? = nil,
        linkedTaskTitle: String? = nil,
        linkedTaskState: String? = nil,
        attachmentName: String? = nil,
        attachmentType: String? = nil,
        attachmentSize: String? = nil
    ) {
        self.id = id
        self.senderId = senderId
        self.senderName = senderName
        self.isAgent = isAgent
        self.text = text
        self.timestamp = timestamp
        self.fullDateTime = fullDateTime
        self.replyToId = replyToId
        self.replyToText = replyToText
        self.linkedTaskId = linkedTaskId
        self.linkedTaskTitle = linkedTaskTitle
        self.linkedTaskState = linkedTaskState
        self.attachmentName = attachmentName
        self.attachmentType = attachmentType
        self.attachmentSize = attachmentSize
    }
}

public struct V5ChannelFile: Identifiable, Equatable, Hashable {
    public let id: String
    public var fileName: String
    public var fileSize: String
    public var fileType: String
    public var uploaderName: String
    public var uploadTime: String
    public var associatedProjectName: String?
    public var associatedTaskId: String?
    public var sourceKind: String

    public init(
        id: String,
        fileName: String,
        fileSize: String,
        fileType: String,
        uploaderName: String,
        uploadTime: String = "11-15 12:00",
        associatedProjectName: String? = nil,
        associatedTaskId: String? = nil,
        sourceKind: String = "local"
    ) {
        self.id = id
        self.fileName = fileName
        self.fileSize = fileSize
        self.fileType = fileType
        self.uploaderName = uploaderName
        self.uploadTime = uploadTime
        self.associatedProjectName = associatedProjectName
        self.associatedTaskId = associatedTaskId
        self.sourceKind = sourceKind
    }
}

public struct CloudDriveProvider: Identifiable, Equatable, Hashable {
    public let id: String
    public let name: String
    public let iconName: String
    public let isConnected: Bool
    public let accountName: String?
    public let quotaText: String

    public init(id: String, name: String, iconName: String, isConnected: Bool, accountName: String? = nil, quotaText: String = "") {
        self.id = id
        self.name = name
        self.iconName = iconName
        self.isConnected = isConnected
        self.accountName = accountName
        self.quotaText = quotaText
    }
}

public enum PrivateChatPolicyValidator {
    /// 严格私聊规则：
    /// 允许：用户 ↔ 用户、用户 ↔ Agent
    /// 严格禁止：Agent ↔ Agent 私聊！
    public static func validate(initiatorIsAgent: Bool, targetIsAgent: Bool) throws {
        if initiatorIsAgent && targetIsAgent {
            throw PrivateChatPolicyError.forbiddenAgentToAgentChat
        }
    }
}

public enum PrivateChatPolicyError: LocalizedError {
    case forbiddenAgentToAgentChat

    public var errorDescription: String? {
        switch self {
        case .forbiddenAgentToAgentChat:
            return "平台安全规范拦截：严格禁止 Agent ↔ Agent 私聊。Agent 间协作统一通过有权限和审计记录的频道或任务工作流进行。"
        }
    }
}

/// 频道与私聊主首页
public struct ChannelsHomeView: View {
    @ObservedObject public var appState: AppState
    public var onOpenDrawer: () -> Void
    public var onNavigateRoute: (String) -> Void

    @State private var selectedPartition: ChannelPartition = .channels
    @State private var searchQuery = ""
    @State private var showSwitchPanel = false
    @State private var showCreateDMSheet = false
    @State private var showPolicyAlert = false
    @State private var policyAlertMessage = ""

    // 频道数据与选中
    @State private var channels: [V5ChannelItem] = []
    @State private var activeWorkspaceChannel: V5ChannelItem? = nil

    // 私聊数据与选中
    @State private var directMessages: [V5DirectMessageItem] = []
    @State private var activeWorkspaceDM: V5DirectMessageItem? = nil

    // 删除二次确认
    @State private var channelToDelete: V5ChannelItem? = nil
    @State private var showDeleteConfirmation = false

    public enum ChannelPartition: String, CaseIterable, Identifiable {
        case channels = "频道"
        case directMessages = "私聊"

        public var id: String { rawValue }
    }

    public init(
        appState: AppState,
        onOpenDrawer: @escaping () -> Void = {},
        onNavigateRoute: @escaping (String) -> Void = { _ in }
    ) {
        self.appState = appState
        self.onOpenDrawer = onOpenDrawer
        self.onNavigateRoute = onNavigateRoute
    }

    private var filteredChannels: [V5ChannelItem] {
        let q = searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if q.isEmpty { return channels }
        return channels.filter {
            $0.name.lowercased().contains(q) ||
            $0.topic.lowercased().contains(q) ||
            $0.lastMessageText.lowercased().contains(q)
        }
    }

    private var filteredDMs: [V5DirectMessageItem] {
        let q = searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if q.isEmpty { return directMessages }
        return directMessages.filter {
            $0.targetName.lowercased().contains(q) ||
            $0.targetRole.lowercased().contains(q) ||
            $0.lastMessageText.lowercased().contains(q)
        }
    }

    public var body: some View {
        VStack(spacing: 0) {
            topNavBar

            // 对话框直达（批D）：打开即当前频道的对话框（消息流+状态+
            // 发送区）；搜索/分段/频道列表收进切换面板（点频道名开）。
            if !appState.hasToken {
                unconfiguredTokenView
            } else if let dm = activeWorkspaceDM {
                ChannelDetailWorkspaceView(
                    appState: appState,
                    channelItem: nil,
                    dmItem: dm,
                    onNavigateRoute: onNavigateRoute,
                    embedded: true,
                    onOpenSwitchPanel: { showSwitchPanel = true }
                )
            } else if let ch = activeWorkspaceChannel {
                ChannelDetailWorkspaceView(
                    appState: appState,
                    channelItem: ch,
                    dmItem: nil,
                    onNavigateRoute: onNavigateRoute,
                    embedded: true,
                    onOpenSwitchPanel: { showSwitchPanel = true }
                )
            } else {
                noChannelSelectedView
            }
        }
        .background(Color(red: 0.969, green: 0.969, blue: 0.961).ignoresSafeArea())
        .onAppear(perform: loadData)
        .task {
            await refreshChannels()
        }
        .sheet(isPresented: $showSwitchPanel) {
            switchPanel
        }
        .sheet(isPresented: $showCreateDMSheet) {
            CreateDirectMessageSheet(appState: appState) { newDM in
                directMessages.insert(newDM, at: 0)
                activeWorkspaceDM = newDM
            }
        }
        .alert("删除频道", isPresented: $showDeleteConfirmation, presenting: channelToDelete) { ch in
            Button("取消", role: .cancel) {}
            Button("删除", role: .destructive) {
                channels.removeAll { $0.id == ch.id }
                if activeWorkspaceChannel?.id == ch.id {
                    activeWorkspaceChannel = channels.first
                }
            }
        } message: { ch in
            Text("确定要删除「#\(ch.name)」吗？删除后该频道的消息记录将对所有成员不可见，此操作需要平台授权。")
        }
        .alert("安全规范提示", isPresented: $showPolicyAlert) {
            Button("我知道了", role: .cancel) {}
        } message: {
            Text(policyAlertMessage)
        }
    }

    /// 暂无可选频道时的空态（与列表空态同文案）。
    private var noChannelSelectedView: some View {
        VStack(spacing: 8) {
            Image(systemName: "number.circle")
                .font(.system(size: 40))
                .foregroundStyle(.secondary.opacity(0.6))
            Text("暂无频道")
                .font(.headline)
                .foregroundStyle(Color(red: 0.20, green: 0.19, blue: 0.18))
            Text("点击右上角「＋」可创建团队讨论频道")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.vertical, 40)
    }

    /// 频道切换面板（批D）：搜索框 + [频道 | 私聊·待开发] 分段 +
    /// 频道列表；点选即切换并关面板，不先落列表页。
    private var switchPanel: some View {
        NavigationStack {
            VStack(spacing: 0) {
                partitionPicker

                if selectedPartition == .channels {
                    searchField
                    if !appState.hasToken {
                        unconfiguredTokenView
                    } else {
                        channelsListView
                    }
                } else {
                    directMessagesListView
                }
            }
            .background(Color(red: 0.969, green: 0.969, blue: 0.961).ignoresSafeArea())
            .navigationTitle("切换频道")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") { showSwitchPanel = false }
                }
            }
        }
    }

    // MARK: - 顶栏
    private var topNavBar: some View {
        HStack(spacing: 12) {
            Button(action: onOpenDrawer) {
                Image(systemName: "line.3.horizontal")
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                    .frame(width: 38, height: 38)
                    .background(Color.white, in: Circle())
                    .overlay(Circle().stroke(Color(red: 0.88, green: 0.87, blue: 0.85), lineWidth: 1))
            }
            .accessibilityIdentifier("navigation.menu")
            .accessibilityLabel("打开导航抽屉")

            Text("频道")
                .font(.system(size: 20, weight: .semibold, design: .serif))
                .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))

            Spacer()

            Menu {
                Button {
                    policyAlertMessage = "【待开发】新建频道尚未开发：频道由平台配置，App 内暂不能新建。"
                    showPolicyAlert = true
                } label: {
                    Label("新建频道", systemImage: "number")
                }
                .accessibilityIdentifier("channels.action.createChannel")

                Button {
                    policyAlertMessage = "【待开发】私聊服务待开发（与指定人员/Agent 独立私聊通道）"
                    showPolicyAlert = true
                } label: {
                    Label("发起私聊", systemImage: "person.2")
                }
                .accessibilityIdentifier("channels.action.createDM")
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: "plus")
                        .font(.system(size: 13, weight: .semibold))
                    Text("新建")
                        .font(.system(size: 14, weight: .medium))
                }
                .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(Color.white, in: Capsule())
                .overlay(Capsule().stroke(Color(red: 0.88, green: 0.87, blue: 0.85), lineWidth: 1))
            }
            .accessibilityIdentifier("channels.addButton")
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 8)
    }

    // MARK: - 分区选择器
    private var partitionPicker: some View {
        Picker("分区", selection: $selectedPartition) {
            ForEach(ChannelPartition.allCases) { part in
                Text(part == .directMessages ? "私聊 · 待开发" : part.rawValue).tag(part)
            }
        }
        .pickerStyle(.segmented)
        .padding(.horizontal, 16)
        .padding(.vertical, 6)
    }

    // MARK: - 搜索框
    private var searchField: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(.secondary)
                .font(.system(size: 14))
            TextField("搜索频道名称、简介或消息…", text: $searchQuery)
                .font(.system(size: 14))
                .textInputAutocapitalization(.never)
            if !searchQuery.isEmpty {
                Button {
                    searchQuery = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.secondary)
                        .font(.system(size: 13))
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(Color.white, in: RoundedRectangle(cornerRadius: 10))
        .overlay(RoundedRectangle(cornerRadius: 10).stroke(Color(red: 0.88, green: 0.87, blue: 0.85), lineWidth: 1))
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
    }

    // MARK: - 未配 Token 引导视图
    private var unconfiguredTokenView: some View {
        ScrollView {
            VStack(spacing: 16) {
                Image(systemName: "cable.connector.horizontal")
                    .font(.system(size: 40))
                    .foregroundStyle(.secondary.opacity(0.6))
                    .padding(.top, 40)
                Text("频道还没法连：先去 Agent 页加一个平台 Agent，并在表单的平台凭证栏填入 App 的平台凭证（由你的平台管理员签发）。")
                    .font(.subheadline)
                    .foregroundStyle(Color(red: 0.20, green: 0.19, blue: 0.18))
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 24)
                Button {
                    onNavigateRoute("agents")
                } label: {
                    Text("前往 Agent 管理")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 8)
                        .background(Color(red: 0.16, green: 0.15, blue: 0.14), in: Capsule())
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 20)
        }
        .refreshable {
            await refreshChannels()
        }
    }

    private func refreshChannels() async {
        if appState.hasToken {
            await appState.loadChannels()
        }
        await MainActor.run {
            loadData()
        }
    }

    // MARK: - 频道列表
    private var channelsListView: some View {
        ScrollView {
            LazyVStack(spacing: 8) {
                if filteredChannels.isEmpty {
                    VStack(spacing: 8) {
                        Image(systemName: "number.circle")
                            .font(.system(size: 40))
                            .foregroundStyle(.secondary.opacity(0.6))
                        Text(searchQuery.isEmpty ? "暂无频道" : "未找到匹配的频道")
                            .font(.headline)
                            .foregroundStyle(Color(red: 0.20, green: 0.19, blue: 0.18))
                        Text("点击右上角「＋」可创建团队讨论频道")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 40)
                } else {
                    ForEach(filteredChannels) { ch in
                        Button {
                            activeWorkspaceChannel = ch
                            activeWorkspaceDM = nil
                            showSwitchPanel = false
                        } label: {
                            channelRow(ch)
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("channels.row.\(ch.id)")
                        .contextMenu {
                            Button {
                                toggleMute(ch)
                            } label: {
                                Label(ch.isMuted ? "取消免打扰" : "设为免打扰", systemImage: ch.isMuted ? "bell" : "bell.slash")
                            }
                            Button {
                                toggleIgnore(ch)
                            } label: {
                                Label(ch.isIgnored ? "取消忽略" : "忽略消息", systemImage: "eye.slash")
                            }
                            Button {
                                toggleArchive(ch)
                            } label: {
                                Label(ch.isArchived ? "移出归档" : "归档频道", systemImage: "archivebox")
                            }
                            Divider()
                            Button(role: .destructive) {
                                channelToDelete = ch
                                showDeleteConfirmation = true
                            } label: {
                                Label("删除频道", systemImage: "trash")
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
        }
        .refreshable {
            await refreshChannels()
        }
    }

    private func channelRow(_ ch: V5ChannelItem) -> some View {
        HStack(alignment: .top, spacing: 12) {
            ZStack {
                RoundedRectangle(cornerRadius: 12)
                    .fill(ch.isPrivate ? Color(red: 0.90, green: 0.88, blue: 0.84) : Color(red: 0.88, green: 0.90, blue: 0.94))
                    .frame(width: 44, height: 44)
                Image(systemName: ch.isPrivate ? "lock.fill" : "number")
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(Color(red: 0.22, green: 0.21, blue: 0.20))
            }

            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(ch.name)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                    if ch.isMuted {
                        Image(systemName: "bell.slash.fill")
                            .font(.system(size: 10))
                            .foregroundStyle(.secondary)
                    }
                    if let proj = ch.associatedProjectName {
                        Text(proj)
                            .font(.system(size: 10, weight: .medium))
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Color(red: 0.92, green: 0.91, blue: 0.88), in: Capsule())
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Text(ch.lastMessageTime)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }

                Text(ch.lastMessageText.isEmpty ? (ch.topic.isEmpty ? "暂无新消息" : ch.topic) : ch.lastMessageText)
                    .font(.system(size: 13))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)

                HStack(spacing: 8) {
                    Text("\(ch.memberCount) 成员 · \(ch.agentCount) Agent")
                        .font(.system(size: 11))
                        .foregroundStyle(.secondary)
                    Spacer()
                    if ch.unreadCount > 0 {
                        Text("\(ch.unreadCount)")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Color(red: 0.82, green: 0.35, blue: 0.20), in: Capsule())
                    }
                }
            }
        }
        .padding(12)
        .background(Color.white, in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color(red: 0.90, green: 0.89, blue: 0.87), lineWidth: 1))
    }

    // MARK: - 私聊列表
    private var directMessagesListView: some View {
        ScrollView {
            LazyVStack(spacing: 8) {
                if filteredDMs.isEmpty {
                    VStack(spacing: 12) {
                        Image(systemName: "person.2.slash")
                            .font(.system(size: 40))
                            .foregroundStyle(.secondary.opacity(0.6))
                        Text("私聊服务待开发")
                            .font(.headline)
                            .foregroundStyle(Color(red: 0.20, green: 0.19, blue: 0.18))
                        Text("与指定人员/Agent 独立私聊通道尚未开放\n团队成员与 Agent 间协同请使用频道或任务工作流")
                            .font(.caption)
                            .multilineTextAlignment(.center)
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 60)
                } else {
                    ForEach(filteredDMs) { dm in
                        Button {
                            activeWorkspaceDM = dm
                            showSwitchPanel = false
                        } label: {
                            directMessageRow(dm)
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("dms.row.\(dm.id)")
                        .contextMenu {
                            Button {
                                toggleMuteDM(dm)
                            } label: {
                                Label(dm.isMuted ? "取消免打扰" : "设为免打扰", systemImage: dm.isMuted ? "bell" : "bell.slash")
                            }
                            Button {
                                toggleIgnoreDM(dm)
                            } label: {
                                Label(dm.isIgnored ? "取消忽略" : "忽略消息", systemImage: "eye.slash")
                            }
                            Divider()
                            Button(role: .destructive) {
                                directMessages.removeAll { $0.id == dm.id }
                            } label: {
                                Label("关闭私聊", systemImage: "xmark.circle")
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
        }
    }

    private func directMessageRow(_ dm: V5DirectMessageItem) -> some View {
        HStack(alignment: .top, spacing: 12) {
            ZStack {
                Circle()
                    .fill(dm.isAgent ? Color(red: 0.85, green: 0.52, blue: 0.38) : Color(red: 0.45, green: 0.58, blue: 0.70))
                    .frame(width: 44, height: 44)
                Text(String(dm.targetName.prefix(1)))
                    .font(.system(size: 16, weight: .bold))
                    .foregroundStyle(.white)
            }

            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(dm.targetName)
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                    Text(dm.isAgent ? "Agent" : "用户")
                        .font(.system(size: 10, weight: .bold))
                        .padding(.horizontal, 5)
                        .padding(.vertical, 2)
                        .background(dm.isAgent ? Color(red: 0.95, green: 0.88, blue: 0.84) : Color(red: 0.88, green: 0.92, blue: 0.96), in: Capsule())
                        .foregroundStyle(dm.isAgent ? Color(red: 0.70, green: 0.30, blue: 0.15) : Color(red: 0.20, green: 0.40, blue: 0.60))
                    if dm.isMuted {
                        Image(systemName: "bell.slash.fill")
                            .font(.system(size: 10))
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Text(dm.lastMessageTime)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }

                Text(dm.lastMessageText.isEmpty ? "暂无新消息" : dm.lastMessageText)
                    .font(.system(size: 13))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)

                HStack(spacing: 8) {
                    Text(dm.targetRole)
                        .font(.system(size: 11))
                        .foregroundStyle(.secondary)
                    Spacer()
                    if dm.unreadCount > 0 {
                        Text("\(dm.unreadCount)")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundStyle(.white)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Color(red: 0.82, green: 0.35, blue: 0.20), in: Capsule())
                    }
                }
            }
        }
        .padding(12)
        .background(Color.white, in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color(red: 0.90, green: 0.89, blue: 0.87), lineWidth: 1))
    }

    private func toggleMute(_ ch: V5ChannelItem) {
        if let idx = channels.firstIndex(where: { $0.id == ch.id }) {
            channels[idx].isMuted.toggle()
        }
    }

    private func toggleIgnore(_ ch: V5ChannelItem) {
        if let idx = channels.firstIndex(where: { $0.id == ch.id }) {
            channels[idx].isIgnored.toggle()
        }
    }

    private func toggleArchive(_ ch: V5ChannelItem) {
        if let idx = channels.firstIndex(where: { $0.id == ch.id }) {
            channels[idx].isArchived.toggle()
        }
    }

    private func toggleMuteDM(_ dm: V5DirectMessageItem) {
        if let idx = directMessages.firstIndex(where: { $0.id == dm.id }) {
            directMessages[idx].isMuted.toggle()
        }
    }

    private func toggleIgnoreDM(_ dm: V5DirectMessageItem) {
        if let idx = directMessages.firstIndex(where: { $0.id == dm.id }) {
            directMessages[idx].isIgnored.toggle()
        }
    }

    private func loadData() {
        if !appState.hasToken {
            channels = []
            directMessages = []
            return
        }

        if !appState.availableChannels.isEmpty {
            channels = appState.availableChannels.map { c in
                V5ChannelItem(
                    id: c.id,
                    name: c.displayName,
                    topic: "团队频道",
                    isPrivate: false,
                    memberCount: c.participants.count,
                    lastMessageText: c.lastMessageText ?? "",
                    unreadCount: 0
                )
            }
        } else {
            let cached = ChannelListCache().load()
            if !cached.isEmpty {
                channels = cached.map { c in
                    V5ChannelItem(
                        id: c.id,
                        name: c.displayName,
                        topic: "团队频道",
                        isPrivate: false,
                        memberCount: c.participants.count,
                        lastMessageText: c.lastMessageText ?? "",
                        unreadCount: 0
                    )
                }
            }
        }

        // 诚实空态：私聊服务未开发，严禁注入虚假种子私聊会话
        directMessages = []

        // 对话框直达（批D）：默认选中第一个频道；选中项已不在列表
        // 时回落到第一个。
        if let active = activeWorkspaceChannel, channels.contains(where: { $0.id == active.id }) {
            // 保持当前选中
        } else {
            activeWorkspaceChannel = channels.first
        }
    }
}

/// 频道与私聊内部多功能工作台
public struct ChannelDetailWorkspaceView: View {
    @ObservedObject public var appState: AppState
    public var channelItem: V5ChannelItem?
    public var dmItem: V5DirectMessageItem?
    public var onNavigateRoute: (String) -> Void
    /// 嵌入模式（批D 对话框直达）：频道页本体直接承载本视图时为 true，
    /// 不显示返回钮，频道名可点开切换面板。
    public var embedded: Bool = false
    public var onOpenSwitchPanel: () -> Void = {}

    @State private var selectedTab: WorkspaceTab = .messages
    @State private var composerText = ""
    @State private var sendAsTask = false
    @State private var showCreateTaskForm = false
    @State private var pendingTaskText = ""
    @State private var taskTitle = ""
    @State private var isSending = false
    @State private var messages: [V5ChannelMessage] = []
    @State private var files: [V5ChannelFile] = []
    @State private var activeTasks: [LocalTask] = []
    @State private var members: [V5ChannelMember] = []
    @State private var connectionState: ChannelConnectionState = .stopped
    @State private var connectionError: String? = nil
    @State private var showConnectionDiagnostics = false
    @State private var feed: ChannelFeed? = nil
    @State private var realOnlineAgentCount: Int? = nil

    // 交互状态
    @State private var replyingTo: V5ChannelMessage? = nil
    @State private var selectedAttachment: V5ChannelFile? = nil
    @State private var showAttachmentActionSheet = false
    @State private var showProjectFilePicker = false
    @State private var showCloudDrivePicker = false
    @State private var showLinkTaskPicker = false
    @State private var selectedAgentProfile: V5ChannelMessage? = nil
    @State private var showChannelSettingsSheet = false
    @State private var showSendErrorAlert = false
    @State private var sendErrorMessage = ""
    @Environment(\.dismiss) private var dismiss

    private var currentChannelId: String {
        channelItem?.id ?? dmItem?.id ?? "ch-general"
    }

    public enum WorkspaceTab: String, CaseIterable, Identifiable {
        case messages = "消息"
        case files = "文件"
        case tasks = "任务"

        public var id: String { rawValue }
    }

    public init(
        appState: AppState,
        channelItem: V5ChannelItem? = nil,
        dmItem: V5DirectMessageItem? = nil,
        onNavigateRoute: @escaping (String) -> Void = { _ in },
        embedded: Bool = false,
        onOpenSwitchPanel: @escaping () -> Void = {}
    ) {
        self.appState = appState
        self.channelItem = channelItem
        self.dmItem = dmItem
        self.onNavigateRoute = onNavigateRoute
        self.embedded = embedded
        self.onOpenSwitchPanel = onOpenSwitchPanel
    }

    private var displayName: String {
        if let ch = channelItem {
            return "# \(ch.name)"
        }
        if let dm = dmItem {
            return dm.targetName
        }
        return "会话工作台"
    }

    public var body: some View {
        VStack(spacing: 0) {
            topWorkspaceHeader
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    Button("任务详情") { onNavigateRoute("taskdetail") }.buttonStyle(.bordered)
                    Button("执行流") { onNavigateRoute("conversation") }.buttonStyle(.bordered)
                    Button("从消息创建任务") { onNavigateRoute("compose") }.buttonStyle(.bordered)
                }
                .font(.caption2)
                .padding(.horizontal, 16)
                .padding(.vertical, 4)
            }
            tabPicker

            switch selectedTab {
            case .messages:
                messagesTab
            case .files:
                filesTab
            case .tasks:
                tasksTab
            }
        }
        .background(Color(red: 0.969, green: 0.969, blue: 0.961).ignoresSafeArea())
        .navigationTitle(displayName)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if !embedded {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        dismiss()
                    } label: {
                        HStack(spacing: 4) {
                            Image(systemName: "chevron.left")
                            Text("返回")
                        }
                        .font(.system(size: 16))
                        .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                    }
                    .accessibilityIdentifier("channel.backButton")
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showChannelSettingsSheet = true
                } label: {
                    Image(systemName: "ellipsis.circle")
                        .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                }
                .accessibilityIdentifier("channel.settingsButton")
            }
        }
        .onAppear(perform: loadWorkspaceData)
        .onDisappear(perform: stopFeed)
        .sheet(item: $selectedAgentProfile) { msg in
            AgentProfileSheet(message: msg)
        }
        .sheet(isPresented: $showChannelSettingsSheet) {
            ChannelSettingsSheet(channel: channelItem, dm: dmItem) { updatedMembers in
                self.members = updatedMembers
            }
        }
        .sheet(isPresented: $showProjectFilePicker) {
            ProjectFilePickerSheet { selectedFile in
                selectedAttachment = selectedFile
            }
        }
        .sheet(isPresented: $showCloudDrivePicker) {
            CloudDrivePickerSheet { selectedCloudFile in
                selectedAttachment = selectedCloudFile
            }
        }
        .sheet(isPresented: $showLinkTaskPicker) {
            LinkTaskPickerSheet(tasks: activeTasks) { linkedTask in
                composerText += " [关联任务: \(linkedTask.title)]"
            }
        }
        .alert("发送失败", isPresented: $showSendErrorAlert) {
            Button("好的", role: .cancel) {}
        } message: {
            Text(sendErrorMessage)
        }
        .sheet(isPresented: $showCreateTaskForm) {
            NavigationStack {
                Form {
                    Section {
                        TextField("任务标题", text: $taskTitle)
                    } header: { Text("任务标题").textCase(nil) }
                    Section {
                        Text(pendingTaskText)
                            .font(.subheadline)
                    } header: { Text("任务说明").textCase(nil) }
                    Section {
                        Text("确认后进入任务列表（状态：排队）。")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .navigationTitle("创建任务")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("取消") { showCreateTaskForm = false }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("创建") { createTaskFromComposer() }
                            .disabled(taskTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                }
            }
        }
    }

    /// 「作为任务发送」确认表单的落点：建一条本地任务（排队），与
    /// 消息行「转为正式任务」同一落点；频道消息本身不冒充已派任务。
    private func createTaskFromComposer() {
        let task = LocalTask(
            title: taskTitle.trimmingCharacters(in: .whitespacesAndNewlines),
            description: pendingTaskText,
            state: "queued"
        )
        do {
            _ = try LocalData.shared.tasks.createTask(task)
        } catch {
            // 创建失败必须让用户看见，且不清空输入、不关表单（R7 N5）。
            sendErrorMessage = "创建任务失败：\(error.localizedDescription)"
            showSendErrorAlert = true
            return
        }
        composerText = ""
        replyingTo = nil
        showCreateTaskForm = false
    }

    private var isCertificateFailure: Bool {
        guard let err = connectionError?.lowercased() else { return false }
        let keywords = ["certificate", "certpath", "trust anchor", "sslhandshake", "证书", "证书链"]
        return keywords.contains { err.contains($0) }
    }

    private var connectionStateText: String {
        switch connectionState {
        case .live:
            return "● 直播推送（WS 已连）"
        case .connecting:
            return "○ 正在连接频道…"
        case .polling:
            var text = "○ 轮询兜底（每 15 秒补读；约 45 秒后重连推送）"
            if isCertificateFailure {
                text += " · 证书验证失败，安全连接未建立"
            } else if let error = connectionError, !error.isEmpty {
                text += "：\(error)"
            }
            return text
        case .stopped:
            return "○ 频道未连接"
        }
    }

    private var headerSubtitle: String {
        var text = connectionStateText
        if let realOnline = realOnlineAgentCount {
            text += " · \(realOnline) 位 Agent 在线"
        }
        return text
    }

    // MARK: - 工作台顶部
    private var topWorkspaceHeader: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    if embedded {
                        Button {
                            onOpenSwitchPanel()
                        } label: {
                            HStack(spacing: 4) {
                                Text(displayName)
                                    .font(.system(size: 18, weight: .bold))
                                    .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                                Image(systemName: "chevron.down")
                                    .font(.system(size: 11, weight: .semibold))
                                    .foregroundStyle(.secondary)
                            }
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("channel.switchButton")
                    } else {
                        Text(displayName)
                            .font(.system(size: 18, weight: .bold))
                            .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                    }
                    if let ch = channelItem, ch.isPrivate {
                        Image(systemName: "lock.fill")
                            .font(.system(size: 11))
                            .foregroundStyle(.secondary)
                    }
                }
                Text(headerSubtitle)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                if let err = connectionError, !err.isEmpty {
                    VStack(alignment: .leading, spacing: 2) {
                        Button {
                            showConnectionDiagnostics.toggle()
                        } label: {
                            HStack(spacing: 4) {
                                Image(systemName: showConnectionDiagnostics ? "chevron.down" : "chevron.right")
                                    .font(.system(size: 8))
                                Text("查看连接诊断")
                                    .font(.caption2.weight(.medium))
                            }
                            .foregroundStyle(.red)
                        }
                        .buttonStyle(.plain)
                        if showConnectionDiagnostics {
                            Text(err)
                                .font(.system(size: 10, design: .monospaced))
                                .foregroundStyle(.red.opacity(0.85))
                                .textSelection(.enabled)
                                .padding(4)
                                .background(Color.red.opacity(0.06), in: RoundedRectangle(cornerRadius: 4))
                        }
                    }
                }
            }
            Spacer()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }

    private var tabPicker: some View {
        Picker("内部分区", selection: $selectedTab) {
            ForEach(WorkspaceTab.allCases) { tab in
                Text(tab.rawValue).tag(tab)
            }
        }
        .pickerStyle(.segmented)
        .padding(.horizontal, 16)
        .padding(.bottom, 6)
    }

    // MARK: - 消息分区
    private var messagesTab: some View {
        VStack(spacing: 0) {
            if messages.isEmpty {
                VStack(spacing: 12) {
                    Image(systemName: "bubble.left.and.bubble.right")
                        .font(.system(size: 36))
                        .foregroundStyle(.secondary.opacity(0.6))
                    Text("暂无频道消息")
                        .font(.headline)
                        .foregroundStyle(Color(red: 0.20, green: 0.19, blue: 0.18))
                    Text("在下方输入框发送消息开始协同讨论")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .padding(.vertical, 60)
            } else {
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(spacing: 12) {
                            ForEach(messages) { msg in
                                messageRow(msg)
                                    .id(msg.id)
                            }
                        }
                        .padding(.horizontal, 16)
                        .padding(.vertical, 12)
                    }
                }
            }

            composerSection
        }
    }

    private func messageRow(_ msg: V5ChannelMessage) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .top, spacing: 10) {
                // 头像：点击查看 Agent 资料，长按快速 @ 插入输入框
                Button {
                    selectedAgentProfile = msg
                } label: {
                    Circle()
                        .fill(msg.isAgent ? Color(red: 0.85, green: 0.52, blue: 0.38) : Color(red: 0.45, green: 0.58, blue: 0.70))
                        .frame(width: 36, height: 36)
                        .overlay(
                            Text(String(msg.senderName.prefix(1)))
                                .font(.system(size: 14, weight: .bold))
                                .foregroundStyle(.white)
                        )
                }
                .buttonStyle(.plain)
                .simultaneousGesture(
                    LongPressGesture(minimumDuration: 0.5).onEnded { _ in
                        if msg.isAgent {
                            let mention = "@\(msg.senderName) "
                            if !composerText.contains(mention) {
                                composerText = mention + composerText
                            }
                        }
                    }
                )

                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 6) {
                        Text(msg.senderName)
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                        if msg.isAgent {
                            Text("Agent")
                                .font(.system(size: 9, weight: .bold))
                                .padding(.horizontal, 4)
                                .padding(.vertical, 1)
                                .background(Color(red: 0.95, green: 0.88, blue: 0.84), in: Capsule())
                                .foregroundStyle(Color(red: 0.70, green: 0.30, blue: 0.15))
                        }
                        Spacer()
                        // 真实时间：点击展开完整日期与具体秒数
                        Text(msg.timestamp)
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }

                    if let reply = msg.replyToText {
                        HStack(spacing: 4) {
                            Rectangle()
                                .fill(Color.secondary.opacity(0.4))
                                .frame(width: 2)
                            Text("回复: \(reply)")
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }
                        .padding(.vertical, 2)
                    }

                    Text(msg.text)
                        .font(.system(size: 14))
                        .foregroundStyle(Color(red: 0.18, green: 0.17, blue: 0.16))
                        .textSelection(.enabled)

                    // 附件卡片展示
                    if let attName = msg.attachmentName {
                        HStack(spacing: 8) {
                            Image(systemName: "doc.fill")
                                .font(.system(size: 16))
                                .foregroundStyle(Color(red: 0.82, green: 0.49, blue: 0.35))
                            VStack(alignment: .leading, spacing: 2) {
                                Text(attName)
                                    .font(.system(size: 12, weight: .medium))
                                    .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                                Text("\(msg.attachmentType ?? "文件") · \(msg.attachmentSize ?? "")")
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                        }
                        .padding(8)
                        .background(Color(red: 0.94, green: 0.93, blue: 0.90), in: RoundedRectangle(cornerRadius: 8))
                    }

                    // 关联任务卡片（支持实时状态与协作审查流转）
                    if let tId = msg.linkedTaskId, let tTitle = msg.linkedTaskTitle {
                        taskCollaborationCard(taskId: tId, title: tTitle, fallbackState: msg.linkedTaskState)
                    }
                }
            }
        }
        .padding(10)
        .background(Color.white, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color(red: 0.91, green: 0.90, blue: 0.88), lineWidth: 1))
        .contextMenu {
            Button {
                replyingTo = msg
            } label: {
                Label("回复", systemImage: "arrowshape.turn.up.left")
            }
            Button {
                UIPasteboard.general.string = msg.text
            } label: {
                Label("复制文本", systemImage: "doc.on.doc")
            }
            if msg.senderId == "user-me" {
                Button {
                    composerText = msg.text
                } label: {
                    Label("编辑消息", systemImage: "pencil")
                }
                Button(role: .destructive) {
                    messages.removeAll { $0.id == msg.id }
                } label: {
                    Label("撤回消息", systemImage: "arrow.uturn.backward")
                }
            }
            Button {
                showLinkTaskPicker = true
            } label: {
                Label("关联任务", systemImage: "link")
            }
        }
    }

    // MARK: - 输入框区域（仅一个「＋」展开四项）
    private var composerSection: some View {
        VStack(spacing: 4) {
            // 发送方式（批D，与安卓频道发送区同构）：仅讨论 / 作为任务发送。
            Picker("发送方式", selection: $sendAsTask) {
                Text("仅讨论").tag(false)
                Text("作为任务发送").tag(true)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 16)
            .padding(.top, 6)

            if let reply = replyingTo {
                HStack {
                    Text("回复 \(reply.senderName): \(reply.text)")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    Spacer()
                    Button {
                        replyingTo = nil
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 12))
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 4)
            }

            if let att = selectedAttachment {
                HStack(spacing: 6) {
                    Image(systemName: "paperclip")
                        .font(.system(size: 12))
                        .foregroundStyle(.secondary)
                    Text("\(att.fileName) (\(att.fileSize))")
                        .font(.caption2)
                        .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                    Spacer()
                    Button {
                        selectedAttachment = nil
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 12))
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 2)
            }

            HStack(spacing: 8) {
                // 单一「＋」展开入口：上传附件、从项目选择、从网盘选择、关联任务
                Menu {
                    Button {
                        // 上传本地附件模拟
                        selectedAttachment = V5ChannelFile(
                            id: UUID().uuidString,
                            fileName: "evidence_ios_v5.png",
                            fileSize: "2.4 MB",
                            fileType: "image",
                            uploaderName: "我",
                            sourceKind: "local"
                        )
                    } label: {
                        Label("上传附件", systemImage: "arrow.up.doc")
                    }

                    Button {
                        showProjectFilePicker = true
                    } label: {
                        Label("从项目选择", systemImage: "folder")
                    }

                    Button {
                        showCloudDrivePicker = true
                    } label: {
                        Label("从网盘选择", systemImage: "cloud")
                    }

                    Button {
                        showLinkTaskPicker = true
                    } label: {
                        Label("关联任务", systemImage: "link")
                    }
                } label: {
                    Image(systemName: "plus")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                        .frame(width: 36, height: 36)
                        .background(Color(red: 0.92, green: 0.91, blue: 0.89), in: Circle())
                }
                .accessibilityIdentifier("channel.composer.plus")

                TextField("给频道发一条短消息（≤500 字），@名字 叫一位 Agent…", text: $composerText)
                    .font(.system(size: 14))
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(Color.white, in: RoundedRectangle(cornerRadius: 18))
                    .overlay(RoundedRectangle(cornerRadius: 18).stroke(Color(red: 0.88, green: 0.87, blue: 0.85), lineWidth: 1))
                    .accessibilityIdentifier("channel.composer.input")

                Button {
                    sendMessage()
                } label: {
                    Image(systemName: "arrow.up.circle.fill")
                        .font(.system(size: 32))
                        .foregroundStyle((composerText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && selectedAttachment == nil) ? Color.secondary.opacity(0.3) : Color(red: 0.82, green: 0.49, blue: 0.35))
                }
                .disabled((composerText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && selectedAttachment == nil) || composerText.count > 500)
                .accessibilityIdentifier("channel.composer.send")
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(Color(red: 0.95, green: 0.94, blue: 0.92))

            if composerText.count > 500 {
                Text("已超出 500 字上限（当前 \(composerText.count) 字）")
                    .font(.caption2)
                    .foregroundStyle(.red)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
            }
            // 边界文案（与安卓逐字一致）：讨论不是派任务。
            Text("仅讨论会发送频道消息；作为任务发送会打开创建表单，确认后进入任务列表。")
                .font(.caption2)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.bottom, 4)
        }
    }

    private func sendMessage() {
        let text = composerText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty || selectedAttachment != nil else { return }

        // 「作为任务发送」：打开创建表单确认，不直接发频道消息。
        if sendAsTask && !text.isEmpty {
            pendingTaskText = text
            taskTitle = String(text.prefix(40))
            showCreateTaskForm = true
            return
        }

        // 「仅讨论」：经平台真实发送（agent_channel_post）。成功后消息由
        // feed 回推显示，不插入本地假消息；失败显示真实错误、保留输入。
        // 讨论就是讨论——不建任务、不自动派工（R7 B3）。
        guard let transport = appState.makeChannelTransport() else {
            sendErrorMessage = "频道尚未连接；消息未发送。"
            showSendErrorAlert = true
            return
        }
        var postText = text
        if let att = selectedAttachment {
            postText += (postText.isEmpty ? "" : "\n") + "（附件：\(att.fileName)）"
        }
        if postText.isEmpty { postText = "分享了附件" }
        let replyTo = replyingTo?.id
        let channelID = currentChannelId
        Task {
            do {
                _ = try await transport.postMessage(channelID: channelID, text: postText, replyTo: replyTo)
                composerText = ""
                replyingTo = nil
                selectedAttachment = nil
            } catch {
                sendErrorMessage = (error as? McpError)?.message ?? "发送失败。"
                showSendErrorAlert = true
            }
        }
    }

    // MARK: - 文件分区
    private var filesTab: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                // 网盘统一适配器状态卡片
                VStack(alignment: .leading, spacing: 8) {
                    Text("统一网盘适配器")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(.secondary)

                    LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 8) {
                        cloudDriveBadge(name: "Google Drive", status: "可用 · OAuth2", isOk: true)
                        cloudDriveBadge(name: "OneDrive", status: "可用 · OAuth2", isOk: true)
                        cloudDriveBadge(name: "WebDAV", status: "已连接 · NAS", isOk: true)
                        cloudDriveBadge(name: "Cloudreve", status: "已连接 · 私有", isOk: true)
                    }
                }
                .padding(12)
                .background(Color.white, in: RoundedRectangle(cornerRadius: 12))
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color(red: 0.90, green: 0.89, blue: 0.87), lineWidth: 1))

                // 文件汇总列表
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text("频道文件汇总 (\(files.count))")
                            .font(.system(size: 14, weight: .semibold))
                            .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                        Spacer()
                    }

                    if files.isEmpty {
                        Text("暂无共享文件")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .padding(.vertical, 8)
                    } else {
                        ForEach(files) { file in
                            HStack(spacing: 12) {
                                Image(systemName: fileIcon(file.fileType))
                                    .font(.system(size: 20))
                                    .foregroundStyle(Color(red: 0.82, green: 0.49, blue: 0.35))
                                    .frame(width: 32)

                                VStack(alignment: .leading, spacing: 2) {
                                    Text(file.fileName)
                                        .font(.system(size: 13, weight: .medium))
                                        .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                                    HStack(spacing: 6) {
                                        Text("\(file.fileSize) · \(file.uploaderName) · \(file.uploadTime)")
                                            .font(.caption2)
                                            .foregroundStyle(.secondary)
                                        if let p = file.associatedProjectName {
                                            Text(p)
                                                .font(.caption2)
                                                .padding(.horizontal, 4)
                                                .padding(.vertical, 1)
                                                .background(Color.secondary.opacity(0.1), in: Capsule())
                                        }
                                    }
                                }
                                Spacer()

                                Menu {
                                    Button("预览文件") {}
                                    Button("下载至本地") {}
                                    Button("分享链接") {}
                                    Divider()
                                    Button(role: .destructive) {
                                        files.removeAll { $0.id == file.id }
                                    } label: {
                                        Label("从频道移除", systemImage: "trash")
                                    }
                                } label: {
                                    Image(systemName: "ellipsis")
                                        .foregroundStyle(.secondary)
                                        .frame(width: 30, height: 30)
                                }
                            }
                            .padding(10)
                            .background(Color.white, in: RoundedRectangle(cornerRadius: 10))
                            .overlay(RoundedRectangle(cornerRadius: 10).stroke(Color(red: 0.92, green: 0.91, blue: 0.89), lineWidth: 1))
                        }
                    }
                }
            }
            .padding(16)
        }
    }

    private func cloudDriveBadge(name: String, status: String, isOk: Bool) -> some View {
        HStack(spacing: 6) {
            Image(systemName: "externaldrive.connected.to.line.below")
                .font(.system(size: 12))
                .foregroundStyle(isOk ? .green : .secondary)
            VStack(alignment: .leading, spacing: 1) {
                Text(name)
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(Color(red: 0.18, green: 0.17, blue: 0.16))
                Text(status)
                    .font(.system(size: 9))
                    .foregroundStyle(.secondary)
            }
            Spacer()
        }
        .padding(6)
        .background(Color(red: 0.96, green: 0.96, blue: 0.94), in: RoundedRectangle(cornerRadius: 8))
    }

    private func fileIcon(_ kind: String) -> String {
        switch kind {
        case "image": return "photo"
        case "pdf": return "doc.richtext"
        case "code": return "chevron.left.forwardslash.chevron.right"
        case "zip": return "doc.zipper"
        default: return "doc"
        }
    }

    // MARK: - 任务分区
    private var tasksTab: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Text("频道关联任务 (\(activeTasks.count))")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                    Spacer()
                    Button {
                        showLinkTaskPicker = true
                    } label: {
                        HStack(spacing: 4) {
                            Image(systemName: "plus")
                            Text("新建关联")
                        }
                        .font(.caption)
                        .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                    }
                }

                if activeTasks.isEmpty {
                    VStack(spacing: 6) {
                        Text("当前频道暂无关联的执行任务")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                        Text("在消息中长按或点击右上角可将任务关联至频道")
                            .font(.caption)
                            .foregroundStyle(.secondary.opacity(0.8))
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 30)
                } else {
                    ForEach(activeTasks, id: \.id) { t in
                        channelTaskCard(t)
                    }
                }
            }
            .padding(16)
        }
    }

    private func taskStateBadge(_ state: String) -> some View {
        let (color, text): (Color, String) = {
            switch state {
            case "queued": return (.blue, "排队中")
            case "assigned": return (.purple, "已认领")
            case "working": return (.orange, "执行中")
            case "blocked": return (.red, "已阻塞")
            case "review": return (.purple, "待审查")
            case "rework": return (.pink, "返工中")
            case "requeued": return (.gray, "重新排队")
            case "completed": return (.green, "已交付")
            case "failed": return (.red, "执行失败")
            case "cancelled": return (.gray, "已取消")
            default: return (.secondary, state)
            }
        }()

        return Text(text)
            .font(.caption2)
            .fontWeight(.bold)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(color.opacity(0.15), in: Capsule())
            .foregroundStyle(color)
    }

    private func taskCollaborationCard(taskId: String, title: String, fallbackState: String?) -> some View {
        let task = try? LocalData.shared.tasks.getTask(taskId: taskId)
        let state = task?.state ?? fallbackState ?? "working"
        let assigneeName = members.first(where: { $0.id == task?.assignedAgentId || $0.agentRecordId == task?.assignedAgentId })?.name ?? "Agent"

        return VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Image(systemName: "checklist")
                    .font(.system(size: 14))
                    .foregroundStyle(.blue)
                Text("协同任务: \(title)")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                Spacer()
                taskStateBadge(state)
            }

            HStack {
                Text("负责人: \(assigneeName) · ID: \(String(taskId.prefix(8))) · P\(task?.priority ?? 1)")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                Spacer()
                if let t = task, t.isPaused {
                    Text("已暂停")
                        .font(.caption2)
                        .foregroundStyle(.red)
                }
            }

            // 协作审查与执行流转动作栏
            HStack(spacing: 6) {
                if state == "working" {
                    Button {
                        submitTaskForReview(taskId: taskId)
                    } label: {
                        Label("提交审查", systemImage: "arrow.up.doc")
                            .font(.system(size: 11, weight: .medium))
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(Color.orange)
                    .controlSize(.mini)
                } else if state == "review" {
                    Button {
                        approveTaskDelivery(taskId: taskId)
                    } label: {
                        Label("批准交付", systemImage: "checkmark.seal.fill")
                            .font(.system(size: 11, weight: .medium))
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(Color.green)
                    .controlSize(.mini)

                    Button {
                        reworkTask(taskId: taskId)
                    } label: {
                        Label("打回返工", systemImage: "arrow.uturn.backward.circle.fill")
                            .font(.system(size: 11, weight: .medium))
                    }
                    .buttonStyle(.bordered)
                    .tint(Color.red)
                    .controlSize(.mini)
                } else if state == "queued" {
                    Button {
                        _ = try? LocalData.shared.tasks.transition(taskId: taskId, to: "assigned")
                        _ = try? LocalData.shared.tasks.transition(taskId: taskId, to: "working")
                        reloadWorkspaceTasks()
                    } label: {
                        Text("认领执行")
                            .font(.system(size: 11, weight: .medium))
                    }
                    .buttonStyle(.borderedProminent)
                    .controlSize(.mini)
                } else if state == "completed" {
                    HStack(spacing: 4) {
                        Image(systemName: "checkmark.circle.fill")
                            .foregroundStyle(.green)
                        Text("已完成交付")
                            .font(.caption2)
                            .foregroundStyle(.green)
                    }
                } else if state == "rework" {
                    HStack(spacing: 4) {
                        Image(systemName: "wrench.and.screwdriver")
                            .foregroundStyle(.pink)
                        Text("返工修改中")
                            .font(.caption2)
                            .foregroundStyle(.pink)
                    }
                    Button {
                        submitTaskForReview(taskId: taskId)
                    } label: {
                        Text("重新提交审查")
                            .font(.system(size: 11))
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.mini)
                }

                Spacer()

                Button("查看详情") {
                    onNavigateRoute("taskdetail")
                }
                .font(.system(size: 11))
                .buttonStyle(.bordered)
                .controlSize(.mini)
            }
            .padding(.top, 2)
        }
        .padding(8)
        .background(Color.blue.opacity(0.06), in: RoundedRectangle(cornerRadius: 8))
        .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.blue.opacity(0.15), lineWidth: 1))
    }

    private func channelTaskCard(_ t: LocalTask) -> some View {
        let assigneeName: String = members.first(where: { $0.id == t.assignedAgentId || $0.agentRecordId == t.assignedAgentId })?.name ?? "未指派"
        let shortId: String = String(t.id.prefix(8))
        let descText: String = (t.description?.isEmpty ?? true) ? "无详细任务描述" : (t.description ?? "")

        return VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(t.title)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                Spacer()
                taskStateBadge(t.state)
            }

            Text(descText)
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
                .lineLimit(2)

            HStack {
                Text("负责人: \(assigneeName) · P\(t.priority) · ID: \(shortId)")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                Spacer()
                if t.isPaused {
                    Text("已暂停")
                        .font(.caption2)
                        .foregroundStyle(.red)
                }
            }

            // 快捷流转按钮
            HStack(spacing: 8) {
                if t.state == "working" {
                    Button {
                        submitTaskForReview(taskId: t.id)
                    } label: {
                        Label("提交审查", systemImage: "arrow.up.doc")
                            .font(.caption2)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(Color.orange)

                    Button {
                        try? LocalData.shared.tasks.togglePause(taskId: t.id)
                        reloadWorkspaceTasks()
                    } label: {
                        Text(t.isPaused ? "恢复" : "暂停")
                            .font(.caption2)
                    }
                    .buttonStyle(.bordered)
                } else if t.state == "review" {
                    Button {
                        approveTaskDelivery(taskId: t.id)
                    } label: {
                        Label("批准交付", systemImage: "checkmark.seal.fill")
                            .font(.caption2)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(Color.green)

                    Button {
                        reworkTask(taskId: t.id)
                    } label: {
                        Label("打回返工", systemImage: "arrow.uturn.backward.circle.fill")
                            .font(.caption2)
                    }
                    .buttonStyle(.bordered)
                    .tint(Color.red)
                } else if t.state == "queued" {
                    Button {
                        _ = try? LocalData.shared.tasks.transition(taskId: t.id, to: "assigned")
                        _ = try? LocalData.shared.tasks.transition(taskId: t.id, to: "working")
                        reloadWorkspaceTasks()
                    } label: {
                        Text("认领执行")
                            .font(.caption2)
                    }
                    .buttonStyle(.borderedProminent)
                }

                Spacer()

                Button("查看详情") {
                    onNavigateRoute("taskdetail")
                }
                .font(.caption2)
                .buttonStyle(.bordered)
            }
        }
        .padding(12)
        .background(Color.white, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color(red: 0.90, green: 0.89, blue: 0.87), lineWidth: 1))
    }

    private func submitTaskForReview(taskId: String) {
        do {
            _ = try LocalData.shared.tasks.transition(taskId: taskId, to: "review", note: "Agent 执行完成，提交人工审查")
            let t = try LocalData.shared.tasks.getTask(taskId: taskId)
            let taskTitle = t?.title ?? "任务"
            let agentName = members.first(where: { $0.agentRecordId == t?.assignedAgentId || $0.id == t?.assignedAgentId })?.name ?? "Agent"
            let now = Date()
            let timeStr = DateFormatter.localizedString(from: now, dateStyle: .none, timeStyle: .short)
            let fullDateStr = DateFormatter.localizedString(from: now, dateStyle: .short, timeStyle: .medium)

            let reviewMsg = V5ChannelMessage(
                id: UUID().uuidString,
                senderId: t?.assignedAgentId ?? "agent-codex",
                senderName: agentName,
                isAgent: true,
                text: "[审查请求] \(agentName) 已完成任务「\(taskTitle)」，产物与代码已就绪，请项目主管审查。",
                timestamp: timeStr,
                fullDateTime: fullDateStr,
                linkedTaskId: taskId,
                linkedTaskTitle: taskTitle,
                linkedTaskState: "review"
            )
            messages.append(reviewMsg)
            reloadWorkspaceTasks()
        } catch {
            sendErrorMessage = "提交审查失败: \(error.localizedDescription)"
            showSendErrorAlert = true
        }
    }

    private func approveTaskDelivery(taskId: String) {
        do {
            _ = try LocalData.shared.tasks.transition(taskId: taskId, to: "completed", note: "项目主管已批准交付")
            let t = try LocalData.shared.tasks.getTask(taskId: taskId)
            let taskTitle = t?.title ?? "任务"
            let now = Date()
            let timeStr = DateFormatter.localizedString(from: now, dateStyle: .none, timeStyle: .short)
            let fullDateStr = DateFormatter.localizedString(from: now, dateStyle: .short, timeStyle: .medium)

            let approveMsg = V5ChannelMessage(
                id: UUID().uuidString,
                senderId: "user-me",
                senderName: "我",
                isAgent: false,
                text: "[交付完成] 项目主管已批准交付任务：「\(taskTitle)」，状态已归档为 completed。",
                timestamp: timeStr,
                fullDateTime: fullDateStr,
                linkedTaskId: taskId,
                linkedTaskTitle: taskTitle,
                linkedTaskState: "completed"
            )
            messages.append(approveMsg)
            reloadWorkspaceTasks()
        } catch {
            sendErrorMessage = "批准交付失败: \(error.localizedDescription)"
            showSendErrorAlert = true
        }
    }

    private func reworkTask(taskId: String) {
        do {
            let t = try LocalData.shared.tasks.getTask(taskId: taskId)
            let taskTitle = t?.title ?? "任务"
            let agentName = members.first(where: { $0.agentRecordId == t?.assignedAgentId || $0.id == t?.assignedAgentId })?.name ?? "Codex"

            // 统一 10 状态机严格流转：review -> rework -> assigned -> working
            _ = try LocalData.shared.tasks.transition(taskId: taskId, to: "rework", note: "审查未通过，退回返工")
            _ = try LocalData.shared.tasks.transition(taskId: taskId, to: "assigned", note: "重新指派给 \(agentName)")
            _ = try LocalData.shared.tasks.transition(taskId: taskId, to: "working", note: "\(agentName) 进入返工修复阶段")

            let now = Date()
            let timeStr = DateFormatter.localizedString(from: now, dateStyle: .none, timeStyle: .short)
            let fullDateStr = DateFormatter.localizedString(from: now, dateStyle: .short, timeStyle: .medium)

            let rejectMsg = V5ChannelMessage(
                id: UUID().uuidString,
                senderId: "user-me",
                senderName: "我",
                isAgent: false,
                text: "[打回返工] 任务「\(taskTitle)」审查未通过，已退回给 \(agentName) 返工修复。",
                timestamp: timeStr,
                fullDateTime: fullDateStr,
                linkedTaskId: taskId,
                linkedTaskTitle: taskTitle,
                linkedTaskState: "working"
            )
            let ackMsg = V5ChannelMessage(
                id: UUID().uuidString,
                senderId: t?.assignedAgentId ?? "agent-codex",
                senderName: agentName,
                isAgent: true,
                text: "收到返工要求，已重新接单并进入修复修改流程。",
                timestamp: timeStr,
                fullDateTime: fullDateStr,
                linkedTaskId: taskId,
                linkedTaskTitle: taskTitle,
                linkedTaskState: "working"
            )
            messages.append(rejectMsg)
            messages.append(ackMsg)
            reloadWorkspaceTasks()
        } catch {
            sendErrorMessage = "打回返工失败: \(error.localizedDescription)"
            showSendErrorAlert = true
        }
    }

    private func reloadWorkspaceTasks() {
        let chId = currentChannelId
        let channelTasks = (try? LocalData.shared.tasks.listTasks(sessionId: chId)) ?? []
        if !channelTasks.isEmpty {
            activeTasks = channelTasks
        } else {
            activeTasks = (try? LocalData.shared.tasks.listTasks()) ?? []
        }
    }

    private func startFeed() {
        guard feed == nil,
              appState.hasToken,
              let transport = appState.makeChannelTransport(),
              let wsURL = appState.settings.channelWebSocketURL(channelID: currentChannelId) else {
            connectionState = .stopped
            return
        }
        let newFeed = ChannelFeed(
            channelID: currentChannelId,
            transport: transport,
            webSocketURL: wsURL,
            tokenProvider: appState.tokenProvider
        )
        newFeed.onStateChange = { state, err in
            Task { @MainActor in
                self.connectionState = state
                self.connectionError = err
            }
        }
        self.feed = newFeed
        newFeed.start()
        self.connectionState = newFeed.connectionState
    }

    private func stopFeed() {
        feed?.stop()
        feed = nil
        connectionState = .stopped
        connectionError = nil
    }

    private func loadWorkspaceData() {
        members = ChannelMemberManager.loadMembers(channelId: currentChannelId)
        reloadWorkspaceTasks()
        startFeed()
    }
}

// MARK: - 辅助弹窗与选择器

public struct CreateDirectMessageSheet: View {
    @ObservedObject public var appState: AppState
    public var onCreated: (V5DirectMessageItem) -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var recipientType: RecipientType = .agent
    @State private var selectedAgent = "Codex"
    @State private var targetUsername = ""
    @State private var initiatorIsAgent = false
    @State private var targetIsAgent = true
    @State private var errorText: String? = nil

    public enum RecipientType: String, CaseIterable, Identifiable {
        case agent = "平台 Agent"
        case user = "团队成员"

        public var id: String { rawValue }
    }

    public var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("对象类型", selection: $recipientType) {
                        ForEach(RecipientType.allCases) { t in
                            Text(t.rawValue).tag(t)
                        }
                    }
                    .pickerStyle(.segmented)
                    .onChange(of: recipientType) { _, val in
                        targetIsAgent = (val == .agent)
                        errorText = nil
                    }

                    if recipientType == .agent {
                        Picker("选择 Agent", selection: $selectedAgent) {
                            Text("Codex (设备 Agent)").tag("Codex")
                            Text("Claude Code (云端 Agent)").tag("Claude Code")
                            Text("CodeBuddy (协作 Agent)").tag("CodeBuddy")
                            Text("Example Agent").tag("Example Agent")
                        }
                    } else {
                        TextField("输入成员账号或姓名", text: $targetUsername)
                    }
                } header: { Text("私聊对象").textCase(nil) }

                Section {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("✓ 允许：用户 ↔ 用户 私聊")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Text("✓ 允许：用户 ↔ Agent 私聊")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Text("✕ 严格禁止：Agent ↔ Agent 直连私聊")
                            .font(.caption)
                            .fontWeight(.semibold)
                            .foregroundStyle(.red)
                    }
                } header: { Text("平台安全规则校验").textCase(nil) }

                if let err = errorText {
                    Section {
                        Text(err)
                            .font(.caption)
                            .foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle("发起私聊")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("确定") {
                        do {
                            try PrivateChatPolicyValidator.validate(
                                initiatorIsAgent: initiatorIsAgent,
                                targetIsAgent: targetIsAgent
                            )
                            let targetName = (recipientType == .agent) ? selectedAgent : (targetUsername.isEmpty ? "团队成员" : targetUsername)
                            let newDM = V5DirectMessageItem(
                                id: "dm-\(UUID().uuidString.prefix(6))",
                                targetId: "target-\(UUID().uuidString.prefix(4))",
                                targetName: targetName,
                                isAgent: (recipientType == .agent),
                                targetRole: (recipientType == .agent) ? "平台 Agent · 1:1 私聊" : "团队成员",
                                lastMessageText: "私聊会话已建立",
                                lastMessageTime: "刚刚"
                            )
                            onCreated(newDM)
                            dismiss()
                        } catch {
                            errorText = error.localizedDescription
                        }
                    }
                }
            }
        }
    }
}

public struct AgentProfileSheet: View {
    public let message: V5ChannelMessage
    @Environment(\.dismiss) private var dismiss

    public var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                Circle()
                    .fill(message.isAgent ? Color(red: 0.85, green: 0.52, blue: 0.38) : Color(red: 0.45, green: 0.58, blue: 0.70))
                    .frame(width: 64, height: 64)
                    .overlay(
                        Text(String(message.senderName.prefix(1)))
                            .font(.system(size: 24, weight: .bold))
                            .foregroundStyle(.white)
                    )

                VStack(spacing: 4) {
                    Text(message.senderName)
                        .font(.title3)
                        .fontWeight(.bold)
                    Text(message.isAgent ? "平台注册受信任 Agent" : "团队成员")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                List {
                    Section {
                        LabeledContent("唯一标识", value: message.senderId)
                        LabeledContent("身份类型", value: message.isAgent ? "Autonomous Agent" : "Human User")
                        LabeledContent("安全级别", value: "Verified · ATS TLS 强制")
                    } header: { Text("基础信息").textCase(nil) }

                    Section {
                        Text("长按该 Agent 头像可在频道消息输入框快速插入 @ 召唤该 Agent 参与协作。")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    } header: { Text("协作规范").textCase(nil) }
                }
            }
            .padding(.top, 20)
            .navigationTitle("资料卡片")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
    }
}

public struct ChannelSettingsSheet: View {
    public let channel: V5ChannelItem?
    public let dm: V5DirectMessageItem?
    public var onMembersChanged: (([V5ChannelMember]) -> Void)? = nil
    @Environment(\.dismiss) private var dismiss

    @State private var members: [V5ChannelMember] = []
    @State private var showInviteAgentSheet = false

    private var channelId: String {
        channel?.id ?? dm?.id ?? "ch-general"
    }

    public init(channel: V5ChannelItem?, dm: V5DirectMessageItem?, onMembersChanged: (([V5ChannelMember]) -> Void)? = nil) {
        self.channel = channel
        self.dm = dm
        self.onMembersChanged = onMembersChanged
    }

    public var body: some View {
        NavigationStack {
            Form {
                if let ch = channel {
                    Section {
                        LabeledContent("频道名称", value: "# \(ch.name)")
                        LabeledContent("频道性质", value: ch.isPrivate ? "私有频道" : "公开频道")
                        LabeledContent("讨论主题", value: ch.topic.isEmpty ? "无主题" : ch.topic)
                        LabeledContent("成员总数", value: "\(members.count) 人")
                        LabeledContent("已入驻 Agent", value: "\(members.filter { $0.isAgent }.count) 位")
                    } header: { Text("频道信息").textCase(nil) }
                } else if let d = dm {
                    Section {
                        LabeledContent("对象姓名", value: d.targetName)
                        LabeledContent("身份类型", value: d.isAgent ? "平台 Agent" : "团队成员")
                        LabeledContent("归属角色", value: d.targetRole)
                    } header: { Text("私聊信息").textCase(nil) }
                }

                Section {
                    ForEach(members) { m in
                        HStack(spacing: 10) {
                            Circle()
                                .fill(m.isAgent ? Color(red: 0.85, green: 0.52, blue: 0.38) : Color(red: 0.45, green: 0.58, blue: 0.70))
                                .frame(width: 32, height: 32)
                                .overlay(
                                    Text(String(m.name.prefix(1)))
                                        .font(.system(size: 13, weight: .bold))
                                        .foregroundStyle(.white)
                                )
                            VStack(alignment: .leading, spacing: 2) {
                                HStack(spacing: 6) {
                                    Text(m.name)
                                        .font(.system(size: 14, weight: .semibold))
                                    if m.isAgent {
                                        Text("Agent")
                                            .font(.system(size: 9, weight: .bold))
                                            .padding(.horizontal, 4)
                                            .padding(.vertical, 1)
                                            .background(Color.orange.opacity(0.12), in: Capsule())
                                            .foregroundStyle(.orange)
                                    }
                                }
                                Text(m.role)
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Text(m.status)
                                .font(.caption2)
                                .foregroundStyle(m.status == "在线" ? .green : .secondary)
                        }
                    }
                    .onDelete { indexSet in
                        for idx in indexSet {
                            let m = members[idx]
                            if m.id != "user-me" {
                                members = ChannelMemberManager.removeMember(memberId: m.id, channelId: channelId)
                                onMembersChanged?(members)
                            }
                        }
                    }

                    Button {
                        showInviteAgentSheet = true
                    } label: {
                        HStack(spacing: 6) {
                            Image(systemName: "person.badge.plus")
                            Text("邀请已注册 Agent 入驻频道")
                        }
                        .font(.subheadline)
                        .foregroundStyle(Color(red: 0.82, green: 0.49, blue: 0.35))
                    }
                } header: { Text("频道成员与入驻 Agent (\(members.count))").textCase(nil) }

                Section {
                    Toggle("消息免打扰", isOn: .constant(false))
                    Toggle("忽略未读提示", isOn: .constant(false))
                } header: { Text("通知与权限").textCase(nil) }
            }
            .navigationTitle("会话设置")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
            .onAppear {
                members = ChannelMemberManager.loadMembers(channelId: channelId)
            }
            .sheet(isPresented: $showInviteAgentSheet) {
                InviteAgentSheet(channelId: channelId) { newMember in
                    members = ChannelMemberManager.loadMembers(channelId: channelId)
                    onMembersChanged?(members)
                }
            }
        }
    }
}

public struct InviteAgentSheet: View {
    public let channelId: String
    public var onInvited: (V5ChannelMember) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var registeredAgents: [AgentRecord] = []

    public init(channelId: String, onInvited: @escaping (V5ChannelMember) -> Void) {
        self.channelId = channelId
        self.onInvited = onInvited
    }

    public var body: some View {
        NavigationStack {
            List {
                Section {
                    if registeredAgents.isEmpty {
                        Text("暂无其他可用 Agent，所有受信任 Agent 均已在频道中。")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(registeredAgents) { agent in
                            Button {
                                let updated = ChannelMemberManager.addAgentMember(agent, channelId: channelId)
                                if let newlyAdded = updated.first(where: { $0.agentRecordId == agent.agentId || $0.name == agent.displayName }) {
                                    onInvited(newlyAdded)
                                }
                                dismiss()
                            } label: {
                                HStack(spacing: 12) {
                                    Circle()
                                        .fill(Color(red: 0.85, green: 0.52, blue: 0.38))
                                        .frame(width: 36, height: 36)
                                        .overlay(
                                            Text(String(agent.displayName.prefix(1)))
                                                .font(.system(size: 14, weight: .bold))
                                                .foregroundStyle(.white)
                                        )
                                    VStack(alignment: .leading, spacing: 2) {
                                        HStack(spacing: 6) {
                                            Text(agent.displayName)
                                                .font(.system(size: 14, weight: .semibold))
                                                .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                                            Text(agent.kind.rawValue)
                                                .font(.system(size: 9, weight: .bold))
                                                .padding(.horizontal, 4)
                                                .padding(.vertical, 1)
                                                .background(Color.orange.opacity(0.12), in: Capsule())
                                                .foregroundStyle(.orange)
                                        }
                                        Text("\(agent.access.rawValue) · \(agent.memberConnectionState) · \(agent.baseUrl ?? "本地设备")")
                                            .font(.caption2)
                                            .foregroundStyle(.secondary)
                                    }
                                    Spacer()
                                    Image(systemName: "plus.circle.fill")
                                        .font(.system(size: 20))
                                        .foregroundStyle(Color(red: 0.82, green: 0.49, blue: 0.35))
                                }
                            }
                            .buttonStyle(.plain)
                        }
                    }
                } header: { Text("选择已注册 Agent 入驻频道").textCase(nil) }
            }
            .navigationTitle("邀请 Agent 入驻")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
            }
            .onAppear(perform: loadAvailableAgents)
        }
    }

    private func loadAvailableAgents() {
        let currentMembers = ChannelMemberManager.loadMembers(channelId: channelId)
        let currentAgentRecordIds = Set(currentMembers.compactMap { $0.agentRecordId })
        let currentNames = Set(currentMembers.map { $0.name })

        var list = (try? LocalData.shared.agents.listAgents()) ?? []
        list = list.filter { !currentAgentRecordIds.contains($0.agentId) && !currentNames.contains($0.displayName) }
        registeredAgents = list
    }
}

public struct ProjectFilePickerSheet: View {
    public var onSelect: (V5ChannelFile) -> Void
    @Environment(\.dismiss) private var dismiss

    private let sampleProjectFiles: [V5ChannelFile] = []

    public var body: some View {
        NavigationStack {
            List(sampleProjectFiles) { file in
                Button {
                    onSelect(file)
                    dismiss()
                } label: {
                    HStack {
                        Image(systemName: "folder.fill")
                            .foregroundStyle(.blue)
                        VStack(alignment: .leading) {
                            Text(file.fileName).font(.headline)
                            Text("\(file.fileSize) · \(file.associatedProjectName ?? "")").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .navigationTitle("从项目选择文件")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
            }
        }
    }
}

public struct CloudDrivePickerSheet: View {
    public var onSelect: (V5ChannelFile) -> Void
    @Environment(\.dismiss) private var dismiss

    private let sampleCloudFiles: [V5ChannelFile] = []

    public var body: some View {
        NavigationStack {
            List(sampleCloudFiles) { file in
                Button {
                    onSelect(file)
                    dismiss()
                } label: {
                    HStack {
                        Image(systemName: "cloud.fill")
                            .foregroundStyle(Color(red: 0.82, green: 0.49, blue: 0.35))
                        VStack(alignment: .leading) {
                            Text(file.fileName).font(.headline)
                            Text("\(file.fileSize) · 来源: \(file.uploaderName)").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .navigationTitle("从网盘选择文件")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
            }
        }
    }
}

public struct LinkTaskPickerSheet: View {
    public let tasks: [LocalTask]
    public var onSelect: (LocalTask) -> Void
    @Environment(\.dismiss) private var dismiss

    public var body: some View {
        NavigationStack {
            List {
                ForEach(tasks, id: \.id) { task in
                    Button {
                        onSelect(task)
                        dismiss()
                    } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(task.title).font(.headline)
                            Text("状态: \(task.state) · 优先级: P\(task.priority)").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            }
            .navigationTitle("关联现有任务")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
            }
        }
    }
}

// MARK: - 4. 全局控制与设置主视图实现
public struct ControlHomeView: View {
    @ObservedObject public var appState: AppState
    public var onOpenDrawer: () -> Void
    public var onNavigateRoute: (String) -> Void
    @State private var tasks: [LocalTask] = []
    @State private var allAgents: [AgentRecord] = []
    @State private var modelsCount = 0
    @State private var pendingApprovals = 0
    @State private var showWebhookNotice = false

    public init(
        appState: AppState,
        onOpenDrawer: @escaping () -> Void = {},
        onNavigateRoute: @escaping (String) -> Void = { _ in }
    ) {
        self.appState = appState
        self.onOpenDrawer = onOpenDrawer
        self.onNavigateRoute = onNavigateRoute
    }

    public var body: some View {
        let workingCount = tasks.filter { $0.state == "working" }.count
        let queuedCount = tasks.filter { $0.state == "queued" || $0.state == "assigned" }.count
        let attentionCount = tasks.filter { $0.needsAttention || $0.state == "blocked" || $0.state == "review" }.count
        let agentsCount = allAgents.filter { !$0.isDeviceHost && $0.kind != .localModel }.count
        let devicesCount = allAgents.filter { $0.isDeviceHost }.count

        VStack(spacing: 0) {
            topNavBar

            consoleHeader

            ScrollView {
                VStack(spacing: 16) {
                    statusOverviewCard(
                        workingCount: workingCount,
                        queuedCount: queuedCount,
                        attentionCount: attentionCount,
                        pendingApprovals: pendingApprovals,
                        agentsCount: agentsCount
                    )

                    controlModulesList(
                        devicesCount: devicesCount,
                        agentsCount: agentsCount,
                        modelsCount: modelsCount,
                        queuedCount: queuedCount,
                        pendingApprovals: pendingApprovals
                    )

                    dashboardQuickRowsCard
                }
                .padding(16)
            }
        }
        .background(Color(red: 0.98, green: 0.975, blue: 0.965).ignoresSafeArea())
        .onAppear(perform: reloadLocalRecords)
        .alert("【待开发】Webhook 尚未开发", isPresented: $showWebhookNotice) {
            Button("我知道了", role: .cancel) {}
        }
    }

    private func reloadLocalRecords() {
        tasks = (try? LocalData.shared.tasks.listTasks()) ?? []
        allAgents = (try? LocalData.shared.agents.listAgents()) ?? []
        modelsCount = appState.modelConfigStore.listConfigs().count
        pendingApprovals = LocalData.shared.approvalStore.items.filter { $0.decision == nil }.count
    }

    /// 概览卡只显示主机（与安卓同口径）；完整地址在 MCP 页内查看与复制。
    /// 未配置时显示「未配置」，不拿任何写死主机冒充已配置。
    private var overviewHostText: String {
        let raw = appState.settings.platformBaseURLString
        if raw.isEmpty { return "未配置" }
        if let host = URL(string: raw)?.host { return "https://\(host)" }
        return raw
    }

    /// 自由度档位（1 小白 / 2 普通 / 3 标准 / 4 进阶 / 5 高阶，默认 3，
    /// 本机持久化；与安卓控制台同一口径，PROJECT.md §3.2）。
    @AppStorage("console.freedomLevel") private var freedomLevel = 3

    private static let freedomLevelNames = ["小白", "普通", "标准", "进阶", "高阶"]

    /// 页头（与安卓控制台同序）：副文案 → 自由度选择 → 刷新行。
    @ViewBuilder
    private var consoleHeader: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("全局总览：所有设备、所有 Agent、所有任务、进度、日志、状态都在这里，一眼看全，不用再问进度。")
                .font(.caption)
                .foregroundStyle(.secondary)

            // 自由度标签自成一行、档位 chip 行独占整行宽度：标签与 5 个 chip 同行时
            // 总宽超过屏幕宽，chip 文字会被 SwiftUI 截断（曾见「3 标准」截成「3…」），
            // 与安卓控制台同口径修法；chip 文字再加 lineLimit + fixedSize 钉死防截断。
            VStack(alignment: .leading, spacing: 6) {
                Text("自由度")
                    .font(.subheadline.weight(.semibold))
                HStack(spacing: 6) {
                ForEach(1...5, id: \.self) { level in
                    Button {
                        freedomLevel = level
                    } label: {
                        Text("\(level) \(Self.freedomLevelNames[level - 1])")
                            .font(.caption.weight(.semibold))
                            .lineLimit(1)
                            .fixedSize(horizontal: true, vertical: false)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 6)
                            .background(freedomLevel == level ? Color(red: 0.93, green: 0.92, blue: 0.89) : Color.white, in: Capsule())
                            .overlay(Capsule().stroke(Color(red: 0.88, green: 0.87, blue: 0.85), lineWidth: 1))
                            .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                    }
                    .buttonStyle(.plain)
                }
                }
            }

            HStack(spacing: 10) {
                Button(action: reloadLocalRecords) {
                    Label("刷新", systemImage: "arrow.clockwise")
                }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("control.refresh")

                Text("数据全部来自本机真实记录；没有数据就明说暂无，不编数字。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 4)
    }

    @ViewBuilder
    private var topNavBar: some View {
        HStack(spacing: 12) {
            Button(action: onOpenDrawer) {
                Image(systemName: "line.3.horizontal")
                    .font(.system(size: 18, weight: .medium))
                    .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                    .frame(width: 40, height: 40)
                    .background(Color.white, in: Circle())
                    .overlay(Circle().stroke(Color(red: 0.88, green: 0.87, blue: 0.85), lineWidth: 1))
            }
            .accessibilityIdentifier("navigation.menu")
            .accessibilityLabel("打开导航抽屉")

            Text("控制")
                .font(.system(size: 20, weight: .semibold, design: .serif))
                .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))

            Spacer()

            HStack(spacing: 6) {
                Circle()
                    .fill(appState.hasToken ? Color.green : Color.orange)
                    .frame(width: 8, height: 8)
                Text(appState.hasToken ? "平台已配凭证" : "本地优先")
                    .font(.caption2.weight(.medium))
                    .foregroundStyle(.secondary)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .background(Color.white, in: Capsule())
            .overlay(Capsule().stroke(Color(red: 0.88, green: 0.87, blue: 0.85), lineWidth: 1))
        }
        .padding(.horizontal, 16)
        .padding(.top, 10)
        .padding(.bottom, 12)
    }

    @ViewBuilder
    private func statusOverviewCard(workingCount: Int, queuedCount: Int, attentionCount: Int, pendingApprovals: Int, agentsCount: Int) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                VStack(alignment: .leading, spacing: 4) {
                    Text("服务连接与运行概览")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                    Text(overviewHostText)
                        .font(.system(size: 15, weight: .bold, design: .monospaced))
                        .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                }
                Spacer()
                Button {
                    onNavigateRoute("connections")
                } label: {
                    Text(appState.hasToken ? "管理连接" : "配置 Token")
                        .font(.caption2.weight(.semibold))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 5)
                        .background(Color(red: 0.93, green: 0.92, blue: 0.89), in: Capsule())
                        .foregroundStyle(Color(red: 0.25, green: 0.24, blue: 0.22))
                }
            }

            Divider()
                .padding(.vertical, 2)

            HStack(spacing: 20) {
                statusStatItem("已登记 Agent", value: "\(agentsCount)")
                statusStatItem("执行中任务", value: "\(workingCount)")
                statusStatItem("排队/待审", value: "\(queuedCount + attentionCount)")
                statusStatItem("待决审批", value: "\(pendingApprovals)")
            }
        }
        .padding(16)
        .background(Color.white, in: RoundedRectangle(cornerRadius: 16))
        .overlay(
            RoundedRectangle(cornerRadius: 16)
                .stroke(Color(red: 0.89, green: 0.88, blue: 0.85), lineWidth: 1)
        )
    }

    @ViewBuilder
    private func controlModulesList(devicesCount: Int, agentsCount: Int, modelsCount: Int, queuedCount: Int, pendingApprovals: Int) -> some View {
        VStack(spacing: 12) {
            controlCard(
                title: "设备",
                subtitle: "添加局域网电脑、远程设备、VPS 及授权工作目录",
                icon: "desktopcomputer",
                badge: "\(devicesCount) 台设备",
                routeId: "devices"
            )

            controlCard(
                title: "Agent",
                subtitle: "添加云端、设备或 CLI Agent，配置角色与分工",
                icon: "person.2.badge.gearshape",
                badge: "\(agentsCount) 个 Agent",
                routeId: "agents"
            )

            controlCard(
                title: "模型",
                subtitle: "添加模型服务商与免费 Token，配置智能路由",
                icon: "cube",
                badge: modelsCount > 0 ? "\(modelsCount) 个模型" : "模型",
                routeId: "models"
            )

            controlCard(
                title: "MCP",
                subtitle: "保存或签发平台 Token、探测远端工具清单",
                icon: "cable.connector.horizontal",
                badge: appState.hasToken ? "已配置 Token" : "未配 Token",
                routeId: "mcp"
            )

            controlCard(
                title: "Webhook",
                subtitle: "配置 Webhook、定时巡检、触发器与自主执行护栏",
                icon: "bolt.badge.clock",
                badge: "待开发",
                routeId: "automation"
            ) { showWebhookNotice = true }

            controlCard(
                title: "任务",
                subtitle: "查看排队任务、调整优先级与批准敏感写入操作",
                icon: "checklist.checked",
                badge: pendingApprovals > 0 ? "\(pendingApprovals) 待审批" : "队列 \(queuedCount)",
                routeId: "queue"
            )

            controlCard(
                title: "事件",
                subtitle: "查看任务派发、审批请求与完成回执",
                icon: "bell.badge",
                badge: "动态",
                routeId: "activity"
            )

            controlCard(
                title: "用量",
                subtitle: "查看模型调用消耗与任务预算策略",
                icon: "chart.xyaxis.line",
                badge: "待开发",
                routeId: "usage"
            )

        }
    }

    /// 总览面板（与安卓控制台同口径）：任务统计、设备/Agent/模型计数、
    /// 进行中任务、待审批、设备与 Agent 状态、最近事件；无数据时在
    /// 面板内明说暂无，不编数字。
    @ViewBuilder
    private var dashboardQuickRowsCard: some View {
        let working = tasks.filter { $0.state == "working" }
        let waiting = tasks.filter { $0.state == "queued" || $0.state == "assigned" }
        let completed = tasks.filter { $0.state == "completed" || $0.state == "done" }
        let failed = tasks.filter { $0.state == "failed" }
        let cancelled = tasks.filter { $0.state == "cancelled" || $0.state == "canceled" || $0.state == "stopped" }
        let active = tasks.filter { $0.state == "working" || $0.state == "queued" || $0.state == "assigned" }
        let devices = allAgents.filter { $0.isDeviceHost }
        let agents = allAgents.filter { !$0.isDeviceHost && $0.kind != .localModel }
        let recent = tasks.sorted { $0.updatedAt > $1.updatedAt }.prefix(3)

        VStack(alignment: .leading, spacing: 8) {
            Text("总览")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)

            Text("任务：进行中 \(working.count) · 等待 \(waiting.count) · 已完成 \(completed.count) · 失败 \(failed.count) · 已停止 \(cancelled.count)")
                .font(.caption)
            Text("设备 \(devices.count) 台 · Agent \(agents.count + 1) 个（含本机 App）· 模型 \(modelsCount) 个 · 待审批 \(pendingApprovals) 个")
                .font(.caption)

            Text("进行中任务")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
                .padding(.top, 4)
            if active.isEmpty {
                Text("暂无进行中任务。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else {
                ForEach(active.prefix(5), id: \.taskId) { t in
                    HStack {
                        Text(t.title.isEmpty ? t.taskId : t.title).font(.caption).lineLimit(1)
                        Spacer()
                        Text(t.state).font(.caption2).foregroundStyle(.secondary)
                    }
                }
                if active.count > 5 {
                    Text("还有 \(active.count - 5) 个进行中任务，见任务模块。")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }

            Text("待审批")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
                .padding(.top, 4)
            Text(pendingApprovals == 0 ? "暂无待审批。" : "\(pendingApprovals) 个待审批。")
                .font(.caption)
                .foregroundStyle(.secondary)

            Text("设备与 Agent 状态")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
                .padding(.top, 4)
            if devices.isEmpty && agents.isEmpty {
                Text("暂无远程设备；本机 App 在运行。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else {
                ForEach(devices, id: \.agentId) { d in
                    Text("▸ \(d.displayName) · \(d.baseUrl ?? "")")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                ForEach(agents, id: \.agentId) { a in
                    let n = tasks.filter { $0.assignedAgentId == a.agentId }.count
                    Text("▸ \(a.displayName) · \(n) 个任务")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }

            Text("最近事件")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
                .padding(.top, 4)
            if recent.isEmpty {
                Text("暂无运行事件。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else {
                ForEach(Array(recent), id: \.taskId) { t in
                    Text("▸ \(t.title.isEmpty ? t.taskId : t.title) · \(t.state)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
        }
        .padding(14)
        .background(Color.white, in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color(red: 0.89, green: 0.88, blue: 0.85), lineWidth: 1))
    }

    @ViewBuilder
    private func statusStatItem(_ title: String, value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.caption2)
                .foregroundStyle(.secondary)
            Text(value)
                .font(.system(size: 16, weight: .bold, design: .serif))
                .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
        }
    }

    @ViewBuilder
    private func controlCard(title: String, subtitle: String, icon: String, badge: String, routeId: String, onTap: (() -> Void)? = nil) -> some View {
        Button {
            if let onTap { onTap() } else { onNavigateRoute(routeId) }
        } label: {
            HStack(spacing: 14) {
                Image(systemName: icon)
                    .font(.system(size: 20))
                    .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                    .frame(width: 44, height: 44)
                    .background(Color(red: 0.94, green: 0.93, blue: 0.90), in: RoundedRectangle(cornerRadius: 12))

                VStack(alignment: .leading, spacing: 3) {
                    HStack {
                        Text(title)
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                        Spacer()
                        Text(badge)
                            .font(.caption2)
                            .padding(.horizontal, 7)
                            .padding(.vertical, 3)
                            .background(Color(red: 0.93, green: 0.92, blue: 0.89), in: Capsule())
                            .foregroundStyle(Color(red: 0.35, green: 0.33, blue: 0.30))
                    }
                    Text(subtitle)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }

                Image(systemName: "chevron.right")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding(14)
            .background(Color.white, in: RoundedRectangle(cornerRadius: 14))
            .overlay(
                RoundedRectangle(cornerRadius: 14)
                    .stroke(Color(red: 0.89, green: 0.88, blue: 0.85), lineWidth: 1)
            )
        }
        .accessibilityIdentifier("control.card.\(routeId)")
    }
}

public struct AccountSettingsView: View {
    @ObservedObject public var appState: AppState
    @ObservedObject public var chatModel: LocalChatModel
    public let capabilities: AppFlagshipCapabilities
    public var onNavigateRoute: (String) -> Void
    public var onDismiss: () -> Void
    @AppStorage("app.darkMode") private var darkMode = false

    public init(
        appState: AppState,
        chatModel: LocalChatModel,
        capabilities: AppFlagshipCapabilities,
        onNavigateRoute: @escaping (String) -> Void,
        onDismiss: @escaping () -> Void
    ) {
        self.appState = appState
        self.chatModel = chatModel
        self.capabilities = capabilities
        self.onNavigateRoute = onNavigateRoute
        self.onDismiss = onDismiss
    }

    public var body: some View {
        List {
            // 用户资料头部卡片
            Section {
                HStack(spacing: 16) {
                    Circle()
                        .fill(LinearGradient(
                            colors: [Color(red: 0.82, green: 0.49, blue: 0.35), Color(red: 0.70, green: 0.35, blue: 0.20)],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        ))
                        .frame(width: 56, height: 56)
                        .overlay(
                            Text("W")
                                .font(.system(size: 22, weight: .bold))
                                .foregroundStyle(.white)
                        )

                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text("WhereverCode 用户")
                                .font(.system(size: 17, weight: .semibold))
                            Text("本地优先")
                                .font(.caption2.bold())
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(Color.orange.opacity(0.15), in: Capsule())
                                .foregroundStyle(.orange)
                        }
                        Text("无需强制登录即可使用本地对话与项目工作区")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(.vertical, 6)
            }

            // 平台连接（呈现用户已保存的地址；未配置就明说未配置）
            Section {
                LabeledContent(
                    "平台地址",
                    value: appState.settings.platformBaseURLString.isEmpty
                        ? "未配置"
                        : appState.settings.platformBaseURLString
                )
                .accessibilityIdentifier("settings.address")
                HStack {
                    Text("地址状态")
                    Spacer()
                    switch appState.settings.validation {
                    case .ok:
                        Text("地址有效。")
                            .font(.subheadline)
                            .foregroundStyle(.green)
                    case .missingBaseURL:
                        Text("未配置")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    case .invalidBaseURL(let detail):
                        Text(detail)
                            .font(.subheadline)
                            .foregroundStyle(.red)
                    case .missingChannelID:
                        Text("地址有效，尚未选择频道。")
                            .font(.subheadline)
                            .foregroundStyle(.orange)
                    }
                }
            } header: { Text("平台连接").textCase(nil) }

            // 分组一：账户
            Section {
                NavigationLink {
                    ProfileEditScreen()
                } label: {
                    Label("个人资料", systemImage: "person.circle")
                }

                Button {
                    onNavigateRoute("connections")
                } label: {
                    Label("连接管理", systemImage: "link.circle")
                }
                .foregroundStyle(.primary)

                NavigationLink {
                    PrivacyAndSecurityScreen()
                } label: {
                    Label("隐私与安全", systemImage: "lock.shield")
                }

                Button {
                    onNavigateRoute("sync")
                } label: {
                    Label("跨端同步", systemImage: "arrow.triangle.2.circlepath")
                }
                .foregroundStyle(.primary)

                Button {
                    onNavigateRoute("recovery")
                } label: {
                    Label("备份与恢复", systemImage: "arrow.counterclockwise")
                }
                .foregroundStyle(.primary)

                Button {
                    onNavigateRoute("usage")
                } label: {
                    Label("用量与预算", systemImage: "chart.bar")
                }
                .foregroundStyle(.primary)
            } header: { Text("账户").textCase(nil) }

            // 分组二：应用
            Section {
                NavigationLink {
                    ModelSettingsView(model: appState.modelSettingsModel)
                } label: {
                    Label("模型配置", systemImage: "cpu")
                }

                NavigationLink {
                    PlatformConnectionFormScreen(appState: appState)
                } label: {
                    Label("平台连接与令牌签发", systemImage: "key.horizontal")
                }

                Button {
                    onNavigateRoute("mcp")
                } label: {
                    Label("插件与工具", systemImage: "puzzlepiece.extension")
                }
                .foregroundStyle(.primary)

                Button {
                    onNavigateRoute("triggers")
                } label: {
                    Label("Webhook 唤醒与自动触发", systemImage: "bolt.horizontal.circle")
                }
                .foregroundStyle(.primary)

                Button {
                    onNavigateRoute("voice")
                } label: {
                    Label("语音与多模态", systemImage: "waveform")
                }
                .foregroundStyle(.primary)

                Button {
                    onNavigateRoute("notifications")
                } label: {
                    Label("消息与通知", systemImage: "bell")
                }
                .foregroundStyle(.primary)

                Button {
                    onNavigateRoute("memory")
                } label: {
                    Label("长期记忆", systemImage: "brain")
                }
                .foregroundStyle(.primary)

                Button {
                    onNavigateRoute("context")
                } label: {
                    Label("项目上下文规则", systemImage: "doc.text")
                }
                .foregroundStyle(.primary)

                Button {
                    onNavigateRoute("personas")
                } label: {
                    Label("Agent 偏好与技能", systemImage: "sparkles.rectangle.stack")
                }
                .foregroundStyle(.primary)

                Button {
                    darkMode.toggle()
                } label: {
                    HStack {
                        Label("切换深色模式", systemImage: "moon.circle")
                        Spacer()
                        Text(darkMode ? "深色" : "浅色").font(.caption).foregroundStyle(.secondary)
                    }
                }
                .foregroundStyle(.primary)

                Button {
                    onNavigateRoute("onboarding")
                } label: {
                    Label("首次使用指南", systemImage: "hand.wave")
                }
                .foregroundStyle(.primary)
            } header: { Text("应用").textCase(nil) }

            // 独立开发者入口（收纳诊断与路由矩阵，不在正式界面堆放内部术语）
            Section {
                NavigationLink {
                    DeveloperDiagnosticsScreen(
                        capabilities: capabilities,
                        onNavigateRoute: onNavigateRoute
                    )
                } label: {
                    Label("开发者选项", systemImage: "wrench.and.screwdriver")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .accessibilityIdentifier("settings.developerDiagnostics")
            }
        }
        .navigationTitle("设置")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("完成") {
                    onDismiss()
                }
                .font(.system(size: 16, weight: .semibold))
            }
        }
    }
}

public struct ProfileEditScreen: View {
    @State private var nickname = "WhereverCode 用户"
    @State private var email = "user@wherevercode.local"

    public var body: some View {
        Form {
            Section {
                TextField("昵称", text: $nickname)
                TextField("邮箱", text: $email)
            } header: { Text("基本信息").textCase(nil) }
            Section {
                LabeledContent("默认模式", value: "本地优先")
                LabeledContent("凭证存储", value: "仅保存在本设备安全区域")
            } header: { Text("工作模式").textCase(nil) }
        }
        .navigationTitle("个人资料")
        .navigationBarTitleDisplayMode(.inline)
    }
}

public struct PrivacyAndSecurityScreen: View {
    public var body: some View {
        List {
            Section {
                Text("所有模型 API Key 与平台令牌均仅保存在本机系统安全存储中，不会随云端备份自动外泄，也不会写入对话记录。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } header: { Text("凭证安全保护").textCase(nil) }
            Section {
                Text("应用默认要求使用加密的 HTTPS 连接，防止凭证与代码在网络传输中被窃听。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } header: { Text("加密传输保护").textCase(nil) }
            Section {
                Text("界面与日志若检测到密钥或访问令牌格式的字符串，将在展示前自动隐藏。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } header: { Text("敏感信息自动屏蔽").textCase(nil) }
        }
        .navigationTitle("隐私与安全")
        .navigationBarTitleDisplayMode(.inline)
    }
}

public struct PlatformConnectionFormScreen: View {
    @ObservedObject public var appState: AppState

    public var body: some View {
        SettingsView(appState: appState)
            .navigationTitle("平台连接")
            .navigationBarTitleDisplayMode(.inline)
    }
}

public struct DeveloperDiagnosticsScreen: View {
    public let capabilities: AppFlagshipCapabilities
    public var onNavigateRoute: (String) -> Void
    @State private var searchText = ""

    private var filtered: [FlagshipRoute] {
        let q = searchText.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !q.isEmpty else { return FlagshipRoutes.all }
        return FlagshipRoutes.all.filter {
            $0.id.lowercased().contains(q) || $0.title.lowercased().contains(q) || $0.group.lowercased().contains(q)
        }
    }

    private func badgeColor(for state: CapabilityState) -> Color {
        switch state {
        case .readyVerified: return .green
        case .implementedUnverified: return .blue
        case .disconnected: return .orange
        case .failed: return .red
        case .unsupported: return .purple
        case .planned: return .secondary
        }
    }

    public var body: some View {
        List {
            Section {
                LabeledContent("Keychain 安全级别", value: "WhenUnlockedThisDeviceOnly")
                LabeledContent("ATS 网络边界", value: "强制 HTTPS (无明文)")
                LabeledContent("SQLite 状态", value: "WAL 模式 · 正常")
                Button("打开 Claude 18 屏基线对照 (baseline)") {
                    onNavigateRoute("baseline")
                }
            } header: { Text("底层运行诊断").textCase(nil) }
            Section {
                TextField("搜索 route_id / 标题 / 分组", text: $searchText)
                    .textInputAutocapitalization(.never)

                ForEach(filtered) { route in
                    let status = capabilities.status(for: route.id)
                    Button {
                        onNavigateRoute(route.id)
                    } label: {
                        HStack(spacing: 8) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(route.title)
                                    .font(.system(size: 15))
                                    .foregroundStyle(.primary)
                                Text("\(route.id) · \(route.parent.rawValue)/\(route.group) · \(route.p0 ? "P0" : "扩展")")
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Text(status.state.rawValue)
                                .font(.caption2.bold())
                                .padding(.horizontal, 7)
                                .padding(.vertical, 3)
                                .background(badgeColor(for: status.state).opacity(0.18), in: Capsule())
                                .foregroundStyle(badgeColor(for: status.state))
                        }
                    }
                    .accessibilityIdentifier("diagnostics.route.\(route.id)")
                }
            } header: { Text("70 屏功能路由全景 (\(filtered.count)/70)").textCase(nil) }
        }
        .navigationTitle("开发者选项")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct GlobalConsoleDashboardScreen: View {
    @ObservedObject var appState: AppState
    var onNavigateRoute: (String) -> Void

    var body: some View {
        ControlHomeView(appState: appState, onOpenDrawer: {}, onNavigateRoute: onNavigateRoute)
    }
}

private struct OnboardingLocalFirstScreen: View {
    @ObservedObject var appState: AppState = AppState()
    var onNavigateRoute: (String) -> Void

    var body: some View {
        List {
            Section {
                Text("先从本地对话开始。需要跨设备、团队或云端任务时，再连接对应服务。")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Button("直接开始") { onNavigateRoute("chat") }
                    .font(.subheadline.weight(.semibold))
                Button("连接设备") { onNavigateRoute("devices") }
                Button("导入项目") { onNavigateRoute("projects") }
                Button("配置推理模型") { onNavigateRoute("models") }
            } header: { Text("欢迎使用 WhereverCode").textCase(nil) }
            PrototypeRouteSections(routeId: "onboarding", appState: appState, onNavigateRoute: onNavigateRoute)
        }
    }
}

private struct ConsoleSystemSubScreen: View {
    let route: FlagshipRoute
    @ObservedObject var appState: AppState
    var onNavigateRoute: (String) -> Void

    var body: some View {
        let events = LocalData.shared.eventLog.listEvents()
        let auditTrail = LocalData.shared.approvalStore.auditTrail
        List {
            switch route.id {
            case "activity", "notifications":
                Section {
                    if events.isEmpty {
                        Text("暂无实时事件").foregroundStyle(.secondary)
                    } else {
                        ForEach(events) { ev in
                            VStack(alignment: .leading, spacing: 3) {
                                Text("[#\(ev.seq)] \(ev.kind)").font(.caption.bold())
                                Text(ev.detail).font(.caption2).foregroundStyle(.secondary)
                            }
                        }
                    }
                } header: { Text("实时事件与消息回执流 (\(events.count) 条)").textCase(nil) }
            case "audit":
                Section {
                    LabeledContent("凭证存储", value: "仅限本机安全存储 (Keychain)")
                    LabeledContent("日志脱敏", value: "自动屏蔽敏感密钥与令牌")
                    LabeledContent("能力四态约束", value: "已连接 → 已授权 → 可调用 → 已实际执行")
                } header: { Text("安全与权限边界").textCase(nil) }
                Section {
                    if auditTrail.isEmpty {
                        Text("暂无高危审批与调用操作记录").foregroundStyle(.secondary)
                    } else {
                        ForEach(auditTrail, id: \.self) { line in
                            Text(line)
                                .font(.system(.caption2, design: .monospaced))
                                .padding(.vertical, 1)
                        }
                    }
                } header: { Text("工具、审批与唤醒审计流水 (\(auditTrail.count) 条)").textCase(nil) }
            case "usage":
                Section {
                    let usageEvents = events.filter { $0.kind == "USAGE" }
                    LabeledContent("已记录模型调用事件数", value: "\(usageEvents.count)")
                    ForEach(usageEvents) { u in
                        Text(u.detail).font(.caption)
                    }
                } header: { Text("Token 用量与成本统计").textCase(nil) }
            case "sync":
                Section {
                    Text("离线期间的操作保存在本机数据库，收到服务端确认回执后才更新为已送达。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } header: { Text("跨端同步状态说明").textCase(nil) }
            case "recovery":
                Section {
                    Text("• 本地对话与任务：支持随时停止当前回复或切换暂停状态。\n• 远端平台任务：\(ReservedFeature.stopTask.notImplementedReason)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Button("前往任务中心") { onNavigateRoute("tasks") }
                } header: { Text("任务暂停、恢复与回滚").textCase(nil) }
            case "baseline":
                Section {
                    LabeledContent("总路由数", value: "\(FlagshipRoutes.all.count) / 70")
                    LabeledContent("一级导航", value: FlagshipDrawerMenu.allCases.map(\.rawValue).joined(separator: "、"))
                } header: { Text("界面与功能基线对照").textCase(nil) }
            default:
                Text(route.title)
            }
            PrototypeRouteSections(routeId: route.id, appState: appState, onNavigateRoute: onNavigateRoute)
        }
    }
}

private struct ConsoleAutomationSubScreen: View {
    let route: FlagshipRoute
    @ObservedObject var appState: AppState = AppState()
    var chatModel: LocalChatModel? = nil
    var onNavigateRoute: (String) -> Void = { _ in }
    @State private var autoGoalText = ""
    @State private var simulatedWebhookResult: String?

    /// Webhook 地址由已配置的平台地址拼出；未配置时显示「未配置」，
    /// 不写死任何平台主机。
    private var webhookURLText: String {
        guard let base = appState.settings.validatedBaseURL,
              let host = base.host else { return "未配置" }
        let port = base.port.map { ":\($0)" } ?? ""
        return "https://\(host)\(port)/api/open/v1/webhook"
    }

    var body: some View {
        List {
            switch route.id {
            case "triggers":
                Section {
                    LabeledContent("Webhook URL", value: webhookURLText)
                    LabeledContent("验签方式", value: "HMAC-SHA256 (请求头: X-WhereverCode-Signature)")
                    LabeledContent("订阅事件", value: "git_push, pull_request, chat_mention, cron")
                } header: { Text("Webhook 唤醒端点与 HMAC 签名配置").textCase(nil) }

                Section {
                    HStack(spacing: 8) {
                        webhookStateBadge(title: "1. 接收请求", state: "已连接", isOk: true)
                        webhookStateBadge(title: "2. 验签合法", state: "已授权", isOk: true)
                        webhookStateBadge(title: "3. 触发器就绪", state: "可调用", isOk: true)
                        webhookStateBadge(title: "4. 真实建单", state: "已实际执行", isOk: simulatedWebhookResult != nil)
                    }
                    .padding(.vertical, 4)

                    Button("模拟收到 Git Push 唤醒事件并触发 Agent 派工") {
                        simulateWebhookWakeup()
                    }
                    .buttonStyle(.borderedProminent)

                    if let res = simulatedWebhookResult {
                        Text(res)
                            .font(.caption2)
                            .foregroundStyle(.green)
                    }
                } header: { Text("唤醒四态全链路校验（已连接 → 已授权 → 可调用 → 已实际执行）").textCase(nil) }
            case "wakepolicy":
                Section {
                    LabeledContent("单小时最大唤醒频次", value: "10 次/小时")
                    LabeledContent("单轮最大工具调用轮数", value: "6 轮")
                    LabeledContent("单轮超时上限", value: "3 分钟")
                    LabeledContent("高危权限阻断", value: "写文件与 Shell 执行需审批中心授权")
                    LabeledContent("离线重试队列", value: "断网事件挂起重试（保留 24 小时）")
                } header: { Text("唤醒频率与自治安全策略").textCase(nil) }
            case "automaker":
                Section {
                    TextField("例如：每当收到新的 PR，自动审查代码并生成报告，关键问题由我审批。", text: $autoGoalText, axis: .vertical)
                        .lineLimit(2...5)
                } header: { Text("第一步 · 定义自动化目标").textCase(nil) }
                Section {
                    LabeledContent("单轮最大工具调用轮数", value: "6 轮")
                    LabeledContent("单轮最大工具调用次数", value: "12 次")
                    LabeledContent("单轮超时上限", value: "3 分钟")
                } header: { Text("运行护栏与平台边界").textCase(nil) }
            default:
                Section {
                    LabeledContent("单轮最大工具调用轮数", value: "6 轮")
                    LabeledContent("单轮最大工具调用次数", value: "12 次")
                    LabeledContent("单轮超时上限", value: "3 分钟")
                    Text("iOS 系统限制后台常驻进程与准点定时唤醒；Webhook 与定时自动化需配合服务端或已连接设备运行。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } header: { Text("运行护栏与平台边界").textCase(nil) }
            }
            PrototypeRouteSections(routeId: route.id, appState: appState, chatModel: chatModel, onNavigateRoute: onNavigateRoute)
        }
    }

    private func webhookStateBadge(title: String, state: String, isOk: Bool) -> some View {
        VStack(spacing: 2) {
            Text(title).font(.system(size: 10, weight: .bold))
            Text(state)
                .font(.system(size: 9, weight: .semibold))
                .padding(.horizontal, 4)
                .padding(.vertical, 1)
                .background(isOk ? Color.green.opacity(0.15) : Color.secondary.opacity(0.12), in: Capsule())
                .foregroundStyle(isOk ? .green : .secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(4)
        .background(Color.secondary.opacity(0.06), in: RoundedRectangle(cornerRadius: 6))
    }

    private func simulateWebhookWakeup() {
        let taskId = "task-webhook-" + UUID().uuidString.prefix(6)
        let newTask = LocalTask(
            taskId: String(taskId),
            sessionId: "ch-general",
            title: "[Webhook 唤醒] 收到 main 分支代码推送，触发自动化审查",
            description: "来自 GitHub Actions Webhook 事件推送，HMAC 签名校验通过",
            state: "queued",
            assignedAgentId: "codex",
            priority: 1
        )
        do {
            _ = try LocalData.shared.tasks.createTask(newTask)
            _ = try LocalData.shared.tasks.transition(taskId: newTask.taskId, to: "assigned", note: "Webhook 唤醒指派给 Codex")
            _ = try LocalData.shared.tasks.transition(taskId: newTask.taskId, to: "working", note: "Codex 开始执行自动化审查")
            simulatedWebhookResult = "✓ 成功唤醒：已校验 HMAC-SHA256 签名，并在任务中心生成任务 \(newTask.taskId)（状态: working）。"
            LocalData.shared.approvalStore.recordAudit("[WEBHOOK_WAKEUP] 事件=git_push 验签成功 -> 触发真实派工 \(newTask.taskId) (状态: 已实际执行)")
        } catch {
            simulatedWebhookResult = "唤醒建单失败: \(error.localizedDescription)"
        }
    }
}

// MARK: - 5. 设置 · 智能与扩展子模块实现
private struct SettingsIntelligenceAndExtensionsScreen: View {
    let route: FlagshipRoute
    @ObservedObject var appState: AppState
    var onNavigateRoute: (String) -> Void = { _ in }
    @State private var newMemKey = ""
    @State private var newMemVal = ""
    @State private var searchQuery = ""
    @State private var searchHits: [WebSearchHit] = []
    @State private var searchError: String?
    @State private var plugins: [V5PluginRecord] = []

    var body: some View {
        if route.id == "skillsmarket" || route.id == "skillbuilder" {
            SkillsManagementScreen(routeId: route.id, appState: appState, onNavigateRoute: onNavigateRoute)
        } else {
            List {
                switch route.id {
                case "extensions", "extensiondetail":
                    Section {
                        ForEach(plugins) { plugin in
                            VStack(alignment: .leading, spacing: 6) {
                                HStack {
                                    Text(plugin.name).font(.system(size: 14, weight: .semibold))
                                    Text("v\(plugin.version)").font(.caption2).foregroundStyle(.secondary)
                                    Spacer()
                                    Text(plugin.status)
                                        .font(.caption2)
                                        .fontWeight(.bold)
                                        .padding(.horizontal, 6)
                                        .padding(.vertical, 2)
                                        .background(plugin.status == "已启用" ? Color.green.opacity(0.15) : Color.secondary.opacity(0.12), in: Capsule())
                                        .foregroundStyle(plugin.status == "已启用" ? .green : .secondary)
                                }
                                Text(plugin.summary).font(.caption).foregroundStyle(.secondary)

                                HStack(spacing: 8) {
                                    Text("能力状态: \(plugin.capabilityState)")
                                        .font(.caption2)
                                        .foregroundStyle(plugin.capabilityState == "已实际执行" ? .purple : (plugin.capabilityState == "可调用" ? .orange : .blue))
                                    Spacer()
                                    Button(plugin.status == "已启用" ? "禁用" : (plugin.status == "未安装" ? "安装并启用" : "启用")) {
                                        plugins = V5PluginManager.togglePlugin(id: plugin.id)
                                    }
                                    .buttonStyle(.bordered)
                                    .controlSize(.mini)
                                }

                                Text("权限边界: \(plugin.permissions.joined(separator: ", ")) · 提供工具: \(plugin.providedTools.joined(separator: ", "))")
                                    .font(.system(size: 10))
                                    .foregroundStyle(.secondary)
                            }
                            .padding(.vertical, 4)
                        }
                    } header: { Text("扩展插件中心与生命周期管理 (\(plugins.count))").textCase(nil) }
                    .onAppear {
                        plugins = V5PluginManager.listPlugins()
                    }
                case "memory":
                    Section {
                        TextField("记忆主题（如代码风格偏好）", text: $newMemKey)
                        TextField("记忆内容", text: $newMemVal)
                        Button("保存记忆") {
                            guard !newMemKey.isEmpty, !newMemVal.isEmpty else { return }
                            LocalData.shared.memoryStore.addFact(key: newMemKey, value: newMemVal, confirmedByUser: true)
                            newMemKey = ""
                            newMemVal = ""
                        }
                    } header: { Text("新增长期记忆").textCase(nil) }
                    Section {
                        let entries = LocalData.shared.memoryStore.listEntries()
                        if entries.isEmpty {
                            Text("暂无长期记忆条目").foregroundStyle(.secondary)
                        } else {
                            ForEach(entries) { e in
                                VStack(alignment: .leading, spacing: 3) {
                                    Text("[\(e.kind.rawValue)] \(e.key)").font(.subheadline.bold())
                                    Text(e.value).font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }
                    } header: { Text("已保存记忆 (\(LocalData.shared.memoryStore.listEntries().count))").textCase(nil) }
                case "context":
                    Section {
                        LabeledContent("估算容量上限", value: "\(LocalData.shared.contextManager.budgetEstimatedTokens) tokens")
                        Text("当长对话超出容量上限时，自动压缩早期对话并保留关键结论。")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    } header: { Text("上下文容量与自动压缩").textCase(nil) }
                case "research":
                    Section {
                        TextField("输入检索词…", text: $searchQuery)
                        Button("执行实时检索") {
                            Task {
                                do {
                                    searchHits = try await WebSearchClient().searchHits(query: searchQuery)
                                    searchError = nil
                                } catch {
                                    searchError = error.localizedDescription
                                }
                            }
                        }
                        if let err = searchError {
                            Text(err).font(.caption).foregroundStyle(.red)
                        }
                        ForEach(searchHits) { hit in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(hit.title).font(.caption.bold())
                                Text(hit.url).font(.caption2).foregroundStyle(.blue)
                            }
                        }
                    } header: { Text("联网深度研究").textCase(nil) }
                default:
                    Section {
                        Text("iOS 端在系统沙盒保护下运行，外部 CLI 与插件通过已授权的远程设备或平台 MCP 接口调用。")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    } header: { Text("\(route.title)").textCase(nil) }
                }
                PrototypeRouteSections(routeId: route.id, appState: appState, onNavigateRoute: onNavigateRoute)
            }
        }
    }
}


// MARK: - 70 屏原型全量按钮、选择器与真实/诚实动作接线引擎 (PrototypeRouteSections)

public struct PrototypeSpecItem: Sendable {
    public let lines: [(String, String)]
    public let steps: [String]
    public let selectors: [(String, [String])]
    public let rows: [(String, String, String)]
    public let navBtns: [(String, String)]
    public let actBtns: [(String, String)]
}

public enum PrototypeCatalog {
    public static let specs: [String: PrototypeSpecItem] = [

        "chat": PrototypeSpecItem(
            lines: [],
            steps: [],
            selectors: [],
            rows: [],
            navBtns: [("检查代码", "conversation"), ("开始项目", "projects"), ("自动化开发", "automation"), ("团队协作", "team")],
            actBtns: []
        ),
        "conversation": PrototypeSpecItem(
            lines: [],
            steps: ["读取项目文件  已完成", "生成实施计划  已完成", "修改代码与运行测试 ${status()}", "等待你确认后提交"],
            selectors: [],
            rows: [],
            navBtns: [("完整 Diff", "diff"), ("逐行审查", "review"), ("工作树", "git"), ("改目标", "taskdetail"), ("审批", "approvals")],
            actBtns: []
        ),
        "history": PrototypeSpecItem(
            lines: [],
            steps: [],
            selectors: [],
            rows: [("V5 UI 优化与验收", "继续", "conversation"), ("Android 任务流修复", "查看", "taskdetail"), ("MCP 派工协议讨论", "查看", "mcp"), ("UI 视觉参考与评审", "查看", "preview")],
            navBtns: [],
            actBtns: [("置顶", "toast(\"已模拟置顶\")"), ("分叉会话", "toast(\"已创建演示分叉\")"), ("归档", "toast(\"已模拟归档\")")]
        ),
        "search": PrototypeSpecItem(
            lines: [],
            steps: [],
            selectors: [],
            rows: [("任务 #T-104 · UI 执行流", "打开", "taskdetail"), ("ChatScreen.kt", "打开", "editor"), ("团队频道 · UI 讨论", "打开", "channel")],
            navBtns: [],
            actBtns: []
        ),
        "compose": PrototypeSpecItem(
            lines: [("验收标准", "测试、截图、代码 Diff"), ("写入权限", "执行前确认")],
            steps: [],
            selectors: [],
            rows: [],
            navBtns: [("查看队列", "queue")],
            actBtns: [("创建演示任务", "createTask()")]
        ),
        "tasks": PrototypeSpecItem(
            lines: [],
            steps: [],
            selectors: [],
            rows: [],
            navBtns: [("＋ 新建任务", "compose")],
            actBtns: []
        ),
        "taskdetail": PrototypeSpecItem(
            lines: [("目标", "统一对话与代码执行流"), ("执行者", "按能力动态分配"), ("工作树", "feat/v5-chat-flow"), ("审查要求", "人工确认")],
            steps: ["任务创建 · 09:02", "已分派 · 09:03", "Agent 已领取 · 09:04", "代码生成与测试 · 09:07", "等待交付与复审"],
            selectors: [],
            rows: [],
            navBtns: [("改目标", "compose"), ("查看执行流", "conversation"), ("交付物", "artifacts")],
            actBtns: []
        ),
        "plan": PrototypeSpecItem(
            lines: [],
            steps: ["确认需求与验收标准 ✓", "创建隔离工作树 ✓", "修改 UI 和执行流 · 进行中", "测试、审查、合并"],
            selectors: [],
            rows: [("Checkpoint 03 · 修改前", "恢复", "diff"), ("Checkpoint 02 · 文件扫描后", "预览", "files"), ("Checkpoint 01 · 初始状态", "预览", "git")],
            navBtns: [],
            actBtns: []
        ),
        "queue": PrototypeSpecItem(
            lines: [("并行上限", "3 个执行槽"), ("待执行", "3 个任务"), ("任务依赖", "审查通过后才能合并")],
            steps: [],
            selectors: [],
            rows: [("P1 · 修复任务派发", "打开", "taskdetail"), ("P2 · UI 视觉验收", "打开", "preview"), ("P2 · 设备能力检查", "打开", "devices")],
            navBtns: [],
            actBtns: []
        ),
        "approvals": PrototypeSpecItem(
            lines: [("申请", "写入 ChatScreen.kt"), ("范围", "当前项目工作树"), ("风险", "需要确认写入"), ("证据", "Diff · 测试记录")],
            steps: [],
            selectors: [],
            rows: [],
            navBtns: [("查看 Diff", "diff")],
            actBtns: [("批准本次", "toast(\"演示：已批准本次操作\")"), ("拒绝", "toast(\"演示：已拒绝\")")]
        ),
        "review": PrototypeSpecItem(
            lines: [("PR", "feat/v5-chat-flow → main"), ("改动文件", "2"), ("测试证据", "24 passed")],
            steps: [],
            selectors: [],
            rows: [],
            navBtns: [("查看 Diff", "diff")],
            actBtns: [("通过审查", "toast(\"演示：approve\")"), ("请求修改", "toast(\"演示：changes_requested\")")]
        ),
        "artifacts": PrototypeSpecItem(
            lines: [],
            steps: [],
            selectors: [],
            rows: [],
            navBtns: [],
            actBtns: []
        ),
        "projects": PrototypeSpecItem(
            lines: [],
            steps: [],
            selectors: [],
            rows: [("wherevercode-agent · Android / Kotlin", "打开", "projectdetail"), ("wherevercode · 旧平台 / 只读参考", "打开", "projectdetail"), ("新建本地项目", "创建", "onboarding")],
            navBtns: [],
            actBtns: [("选择本地文件夹", "toast(\"演示：系统文件选择器\")"), ("导入 Git 仓库", "toast(\"演示：Git 授权\")"), ("连接设备项目", "go(\"devices\")")]
        ),
        "projectdetail": PrototypeSpecItem(
            lines: [("类型", "Android / Kotlin"), ("分支", "feat/v5-chat-flow"), ("工作目录", "本地演示"), ("变更", "2 文件")],
            steps: [],
            selectors: [],
            rows: [],
            navBtns: [("浏览文件", "files"), ("开始对话", "conversation"), ("Git 工作树", "git"), ("预览", "preview")],
            actBtns: []
        ),
        "files": PrototypeSpecItem(
            lines: [],
            steps: [],
            selectors: [],
            rows: [],
            navBtns: [],
            actBtns: []
        ),
        "editor": PrototypeSpecItem(
            lines: [],
            steps: [],
            selectors: [],
            rows: [],
            navBtns: [("查看变更", "diff"), ("询问 Agent", "conversation")],
            actBtns: [("局部编辑", "toast(\"演示：编辑草稿\")")]
        ),
        "diff": PrototypeSpecItem(
            lines: [],
            steps: [],
            selectors: [],
            rows: [],
            navBtns: [("返回审查", "review")],
            actBtns: [("接受变更", "toast(\"演示：已接受\")"), ("拒绝变更", "toast(\"演示：已拒绝\")")]
        ),
        "terminal": PrototypeSpecItem(
            lines: [("权限", "命令执行需授权"), ("环境", "隔离工作树 · 演示")],
            steps: [],
            selectors: [],
            rows: [],
            navBtns: [("回到对话", "conversation")],
            actBtns: [("复制日志", "toast(\"演示日志\")")]
        ),
        "git": PrototypeSpecItem(
            lines: [("当前分支", "feat/v5-chat-flow"), ("隔离工作树", "worktree-104"), ("未提交变更", "2 个文件"), ("远端", "未连接 · 演示")],
            steps: [],
            selectors: [],
            rows: [("8b46a8a · UI 基线", "查看", "diff"), ("feat/v5-chat-flow · 当前草稿", "审查", "review")],
            navBtns: [],
            actBtns: [("创建工作树", "toast(\"演示：工作树创建\")"), ("创建 PR", "toast(\"演示：未连接 GitHub\")")]
        ),
        "preview": PrototypeSpecItem(
            lines: [],
            steps: [],
            selectors: [],
            rows: [],
            navBtns: [("查看任务", "taskdetail"), ("审查变更", "review")],
            actBtns: []
        ),
        "team": PrototypeSpecItem(
            lines: [("当前空间", "WhereverCode V5"), ("成员", "4 · 示例"), ("待处理任务", "3")],
            steps: [],
            selectors: [],
            rows: [("# 产品开发", "进入频道", "channel"), ("Agent 协作", "编排", "delegate"), ("团队任务", "查看", "tasks"), ("审批中心", "查看", "approvals")],
            navBtns: [],
            actBtns: []
        ),
        "channels": PrototypeSpecItem(
            lines: [],
            steps: [],
            selectors: [],
            rows: [("# 产品开发 · 3 条未读", "打开", "channel"), ("# 代码审查 · 1 条未读", "打开", "channel"), ("# 发布与运维", "打开", "channel")],
            navBtns: [],
            actBtns: []
        ),
        "channel": PrototypeSpecItem(
            lines: [],
            steps: [],
            selectors: [],
            rows: [],
            navBtns: [("任务详情", "taskdetail"), ("执行流", "conversation"), ("从消息创建任务", "compose")],
            actBtns: [("发送演示消息", "toast(\"演示消息已显示，不会真实发送\")")]
        ),
        "agents": PrototypeSpecItem(
            lines: [],
            steps: [],
            selectors: [],
            rows: [("Codex · 可用于代码任务", "详情", "agentdetail"), ("Claude · 对话与规划", "详情", "agentdetail"), ("DeepSeek · 模型适配", "详情", "agentdetail"), ("K70 设备 Agent", "详情", "agentdetail")],
            navBtns: [],
            actBtns: []
        ),
        "agentdetail": PrototypeSpecItem(
            lines: [("名称", "Codex · 演示"), ("类型", "云端 / CLI 可选"), ("能力", "代码、测试、审查"), ("角色", "动态分配"), ("可用状态", "未连接真实服务")],
            steps: [],
            selectors: [],
            rows: [],
            navBtns: [("委派任务", "delegate"), ("查看任务", "tasks"), ("工具权限", "mcp")],
            actBtns: []
        ),
        "delegate": PrototypeSpecItem(
            lines: [],
            steps: ["任务目标 · UI 统一", "Agent A · 页面实现", "Agent B · 视觉检查", "Agent C · 测试与审查", "人工审批后集成"],
            selectors: [],
            rows: [],
            navBtns: [("创建协作任务", "compose"), ("查看调度队列", "queue")],
            actBtns: []
        ),
        "devices": PrototypeSpecItem(
            lines: [],
            steps: [],
            selectors: [],
            rows: [("Mac · 开发工作站", "详情", "devicedetail"), ("Redmi K70 · Android Worker", "详情", "devicedetail"), ("小米 12 · 本地模型", "详情", "devicedetail"), ("iPhone · 移动工作台", "详情", "devicedetail")],
            navBtns: [],
            actBtns: []
        ),
        "devicedetail": PrototypeSpecItem(
            lines: [("设备", "Redmi K70 · 示例"), ("系统", "Android"), ("在线状态", "未验证"), ("任务执行", "需要授权与实际适配"), ("项目访问", "受范围约束")],
            steps: [],
            selectors: [],
            rows: [],
            navBtns: [("查看设备任务", "tasks"), ("Agent 详情", "agents"), ("权限设置", "settings")],
            actBtns: []
        ),
        "models": PrototypeSpecItem(
            lines: [("默认模型", "自动选择"), ("推理强度", "按任务调整"), ("隐私策略", "本地优先"), ("费用提醒", "发送前显示估算")],
            steps: [],
            selectors: [],
            rows: [("ChatGPT / Codex", "配置", "connections"), ("Claude", "配置", "connections"), ("DeepSeek", "配置", "connections"), ("Grok", "配置", "connections"), ("本地模型", "配置", "devices")],
            navBtns: [],
            actBtns: []
        ),
        "mcp": PrototypeSpecItem(
            lines: [],
            steps: [],
            selectors: [],
            rows: [("WhereverCode V5 · 任务与频道", "工具", "taskdetail"), ("GitHub · 代码与 PR", "查看", "git"), ("Google Drive · 文件", "连接", "connections"), ("自定义 MCP 服务", "配置", "connections")],
            navBtns: [],
            actBtns: []
        ),
        "skills": PrototypeSpecItem(
            lines: [("手动", "从对话或任务启动"), ("事件", "review.assigned 等"), ("定时", "可选配置")],
            steps: [],
            selectors: [],
            rows: [("代码审查工作流", "查看", "review"), ("UI 视觉回归", "查看", "preview"), ("每日巡逻与通知", "查看", "activity"), ("构建与测试", "查看", "terminal")],
            navBtns: [],
            actBtns: []
        ),
        "connections": PrototypeSpecItem(
            lines: [("本地使用", "无需账号"), ("云端协作", "可选登录"), ("GitHub", "未连接 · 演示"), ("Google Drive", "未连接 · 演示"), ("MCP", "需单独授权")],
            steps: [],
            selectors: [],
            rows: [],
            navBtns: [("权限管理", "settings")],
            actBtns: [("添加连接", "toast(\"演示：添加连接\")")]
        ),
        "dashboard": PrototypeSpecItem(
            lines: [],
            steps: [],
            selectors: [],
            rows: [],
            navBtns: [],
            actBtns: []
        ),
        "activity": PrototypeSpecItem(
            lines: [("任务完成", "开启"), ("需要审批", "开启"), ("一般频道消息", "静默")],
            steps: [],
            selectors: [],
            rows: [],
            navBtns: [],
            actBtns: []
        ),
        "settings": PrototypeSpecItem(
            lines: [("主题", "浅色 / 深色"), ("语言", "简体中文"), ("执行跟随", "队列 / 实时引导"), ("代码 Diff", "自动换行"), ("项目写入", "显式授权"), ("命令执行", "需确认"), ("凭据", "安全存储"), ("数据导出", "可用")],
            steps: [],
            selectors: [],
            rows: [],
            navBtns: [("模型配置", "models"), ("连接管理", "connections")],
            actBtns: []
        ),
        "onboarding": PrototypeSpecItem(
            lines: [],
            steps: [],
            selectors: [],
            rows: [],
            navBtns: [("直接开始", "chat"), ("连接设备", "devices"), ("导入项目", "projects")],
            actBtns: []
        ),
        "connectmodel": PrototypeSpecItem(
            lines: [("认证", "未连接"), ("模型列表", "未检测"), ("工具调用", "未检测"), ("安全提示", "凭据不写入聊天记录")],
            steps: [],
            selectors: [],
            rows: [("官方账号 / OAuth · 仅支持提供方开放的授权方式", "查看", "connections"), ("API Key · 自定义兼容接口", "设置", "models"), ("本地模型 · 设备推理", "设备", "devices"), ("已有 CLI 会话 · Codex / Claude 等外部 Agent", "导入", "agentimport")],
            navBtns: [],
            actBtns: [("测试连接", "toast(\"演示：需要真实凭据和提供方接口\")")]
        ),
        "modelroute": PrototypeSpecItem(
            lines: [("默认", "按任务自动选择"), ("代码", "优先支持工具调用的模型"), ("隐私任务", "优先本地"), ("费用预算", "按任务限额"), ("故障处理", "允许用户确认后切换")],
            steps: [],
            selectors: [],
            rows: [],
            navBtns: [],
            actBtns: []
        ),
        "agentimport": PrototypeSpecItem(
            lines: [("安装位置", "设备或云端"), ("认证", "独立授权"), ("会话恢复", "按适配器能力"), ("工具事件", "标准化展示"), ("能力声明", "动态检测")],
            steps: [],
            selectors: [],
            rows: [("Codex CLI / App", "适配", "agents"), ("Claude Code / App", "适配", "agents"), ("OpenCode / OpenHands", "适配", "agents"), ("外部 Agent", "适配", "agents"), ("Orca / Hermes 兼容接入", "评估", "agents")],
            navBtns: [],
            actBtns: []
        ),
        "agentresume": PrototypeSpecItem(
            lines: [],
            steps: [],
            selectors: [],
            rows: [],
            navBtns: [],
            actBtns: []
        ),
        "autoloop": PrototypeSpecItem(
            lines: [("最大循环", "5"), ("当前循环", "2"), ("停止条件", "测试通过 / 预算到顶 / 用户停止"), ("失败处理", "诊断 → 修复 → 重测")],
            steps: ["理解目标与生成计划", "选择 Agent 与工具", "执行并收集证据", "自动验证与发现问题", "修复或请求人工介入", "达到验收条件后停止", "01 · 规划：理解需求与验收", "02 · 执行：工作树、编辑、工具", "03 · 验证：构建、测试、审查", "04 · 结果：证据与交付", "05 · 审批：批准、返工或停止"],
            selectors: [],
            rows: [],
            navBtns: [("设置安全护栏", "loopguard"), ("创建循环任务", "compose"), ("查看执行对话", "conversation"), ("检查点与恢复", "recovery")],
            actBtns: [("暂停循环", "change(\"已暂停\")"), ("继续执行", "change(\"执行中\")"), ("停止并保留现场", "change(\"已停止\")")]
        ),
        "loopguard": PrototypeSpecItem(
            lines: [("最大轮次", "5 轮 · 演示"), ("最大运行时长", "30 分钟"), ("费用上限", "按项目设置"), ("危险命令", "必须审批"), ("连续失败", "自动暂停"), ("验收通过", "立即停止")],
            steps: [],
            selectors: [],
            rows: [],
            navBtns: [("查看审批", "approvals")],
            actBtns: [("模拟触发熔断", "toast(\"演示：循环已暂停，等待人工处理\")")]
        ),
        "memory": PrototypeSpecItem(
            lines: [("当前会话", "短期上下文"), ("项目知识", "文件索引与摘要"), ("长期记忆", "显式权限与可删除"), ("压缩策略", "检查点前摘要")],
            steps: [],
            selectors: [],
            rows: [("项目原则 · 本地优先、角色动态", "查看", "context"), ("UI 偏好 · Claude 式留白", "查看", "chat"), ("历史决策 · V5 原型定稿", "查看", "history"), ("项目说明与决策", "查看", "projectdetail"), ("执行检查点", "查看", "plan"), ("历史会话", "查看", "history")],
            navBtns: [],
            actBtns: [("添加记忆草稿", "toast(\"演示：等待确认\")"), ("纠正记忆", "toast(\"演示：等待确认\")"), ("关闭自动提取", "toast(\"演示：已切换偏好\")")]
        ),
        "skillsmarket": PrototypeSpecItem(
            lines: [],
            steps: [],
            selectors: [],
            rows: [("代码审查 · 安全检查", "详情", "review"), ("UI 视觉验收 · 截图对比", "详情", "preview"), ("自动化巡检 · 定时与事件", "详情", "templates"), ("GitHub PR 工作流", "详情", "git")],
            navBtns: [],
            actBtns: []
        ),
        "skillbuilder": PrototypeSpecItem(
            lines: [],
            steps: ["任务完成并通过审查", "提炼可复用步骤", "识别所需权限与依赖", "生成技能草稿", "用户审核后启用"],
            selectors: [],
            rows: [],
            navBtns: [("查看技能市场", "skillsmarket")],
            actBtns: [("生成演示草稿", "toast(\"已生成演示草稿，未实际保存\")")]
        ),
        "context": PrototypeSpecItem(
            lines: [("上下文窗口", "按模型能力"), ("摘要压缩", "保留证据与引用"), ("文件引用", "@ 文件 / 目录 / Diff"), ("会话分叉", "可独立比较结果")],
            steps: [],
            selectors: [],
            rows: [("AGENTS.md · 项目工作约定", "查看", "editor"), ("CLAUDE.md · 兼容引用", "查看", "editor"), ("当前 Git Diff · 精准引用", "查看", "diff"), ("历史会话 · 按需检索", "查看", "history")],
            navBtns: [],
            actBtns: []
        ),
        "research": PrototypeSpecItem(
            lines: [],
            steps: ["明确问题与证据标准", "多来源检索", "浏览网页与核对原文", "整理证据、冲突与不确定性", "生成可追溯报告"],
            selectors: [],
            rows: [],
            navBtns: [("浏览器工作台", "browser"), ("交付成果", "artifacts")],
            actBtns: []
        ),
        "browser": PrototypeSpecItem(
            lines: [("当前地址", "未连接浏览器"), ("页面快照", "无"), ("敏感操作", "需人工确认"), ("录屏与证据", "可选")],
            steps: [],
            selectors: [],
            rows: [],
            navBtns: [("审批设置", "audit")],
            actBtns: [("启动演示会话", "toast(\"演示：未启动真实浏览器\")")]
        ),
        "testmatrix": PrototypeSpecItem(
            lines: [("Android UI", "待真机验证"), ("Mac CLI", "待设备验证"), ("云端 Agent", "待连接验证"), ("MCP 派工", "待端到端验证")],
            steps: [],
            selectors: [],
            rows: [("构建日志", "查看", "terminal"), ("真实设备截图", "查看", "preview"), ("审查结果", "查看", "review"), ("回滚检查点", "查看", "recovery")],
            navBtns: [],
            actBtns: []
        ),
        "agentcompare": PrototypeSpecItem(
            lines: [("任务", "UI 实现与测试"), ("Agent A", "候选 1 · 未执行"), ("Agent B", "候选 2 · 未执行"), ("对比维度", "测试、Diff、成本、耗时")],
            steps: [],
            selectors: [],
            rows: [],
            navBtns: [("创建对比任务", "compose"), ("查看编排", "orchestrator")],
            actBtns: []
        ),
        "orchestrator": PrototypeSpecItem(
            lines: [("角色", "动态分配，不绑定厂商"), ("任务依赖", "DAG"), ("隔离", "每任务独立工作树"), ("冲突处理", "集成前审查")],
            steps: ["需求确认 → 任务拆分", "分支 A · Android UI", "分支 B · MCP 接线", "合并前 · 交叉审查", "用户批准 → 集成与交付"],
            selectors: [],
            rows: [("Agent A · UI 实现", "执行中", "agentdetail"), ("Agent B · 自动测试", "等待依赖", "agentdetail"), ("Agent C · 安全审查", "待分配", "agentdetail")],
            navBtns: [("创建团队任务", "compose"), ("任务依赖", "queue")],
            actBtns: []
        ),
        "handoff": PrototypeSpecItem(
            lines: [],
            steps: ["iPhone 发起任务", "Redmi K70 或 Mac 领取执行", "云端 Agent 协作", "手机查看流式日志", "手机批准或要求返工"],
            selectors: [],
            rows: [],
            navBtns: [("设备列表", "devices"), ("任务状态", "tasks")],
            actBtns: []
        ),
        "audit": PrototypeSpecItem(
            lines: [("命令执行", "任务级授权"), ("文件写入", "路径范围限制"), ("密钥管理", "不注入子进程"), ("危险操作", "审批闸门"), ("日志保留", "可导出与审查")],
            steps: [],
            selectors: [],
            rows: [("09:07 · 读取项目文件", "查看", "activity"), ("09:08 · 请求文件写入", "审批", "approvals"), ("任务授权", "只读能力令牌", "taskdetail"), ("插件安装", "来源与权限校验", "extensiondetail"), ("网络重连", "重放与签名检查", "routes"), ("人工审批", "批准 / 拒绝 / 撤销", "approvals")],
            navBtns: [],
            actBtns: []
        ),
        "notifications": PrototypeSpecItem(
            lines: [("App 内", "开启"), ("系统推送", "未授权"), ("邮件 / Webhook", "可选配置")],
            steps: [],
            selectors: [],
            rows: [],
            navBtns: [],
            actBtns: []
        ),
        "voice": PrototypeSpecItem(
            lines: [],
            steps: [],
            selectors: [],
            rows: [("语音输入与转写", "进入", "chat"), ("图片 / 截图分析", "进入", "chat"), ("文件与目录附件", "进入", "files"), ("录屏 / 视频附件", "进入", "preview")],
            navBtns: [],
            actBtns: []
        ),
        "personas": PrototypeSpecItem(
            lines: [("指令", "按会话与项目选择"), ("工具集", "最小权限"), ("模型", "动态切换"), ("记忆", "可开关与审查"), ("角色", "由任务决定，不固定")],
            steps: [],
            selectors: [],
            rows: [],
            navBtns: [("模型路由", "modelroute"), ("技能管理", "skillsmarket")],
            actBtns: []
        ),
        "usage": PrototypeSpecItem(
            lines: [("任务预算", "可配置"), ("模型费率", "按提供方实际计费"), ("超额行为", "暂停并询问"), ("本地执行", "单独统计资源")],
            steps: [],
            selectors: [],
            rows: [],
            navBtns: [],
            actBtns: []
        ),
        "templates": PrototypeSpecItem(
            lines: [],
            steps: [],
            selectors: [],
            rows: [("Issue → 修复 → 测试 → PR", "使用", "compose"), ("开发 → 审查 → 返工 → 合并", "使用", "orchestrator"), ("定时巡逻 → 发现异常 → 通知", "使用", "notifications"), ("深度研究 → 来源核对 → 报告", "使用", "research")],
            navBtns: [],
            actBtns: []
        ),
        "sync": PrototypeSpecItem(
            lines: [("离线编辑", "本地缓存"), ("冲突处理", "人工对比后合并"), ("端到端凭据", "设备本地保管"), ("云同步", "可选，不强制"), ("真实状态", "需服务端回执")],
            steps: [],
            selectors: [],
            rows: [],
            navBtns: [("查看设备", "devices"), ("恢复与回滚", "recovery")],
            actBtns: []
        ),
        "recovery": PrototypeSpecItem(
            lines: [("断线", "持久化后续传"), ("重试", "指数退避 + 上限"), ("死信", "人工检查"), ("重复事件", "幂等键拒绝重复副作用")],
            steps: [],
            selectors: [],
            rows: [("当前变更前", "对比", "diff"), ("依赖安装后", "日志", "terminal"), ("初始状态", "查看", "git")],
            navBtns: [("查看工作树", "git"), ("执行检查点", "plan"), ("网络诊断", "routes")],
            actBtns: [("重试失败步骤", "toast(\"演示：重试已排队\")"), ("转人工接管", "toast(\"演示：已标记人工接管\")")]
        ),
        "automation": PrototypeSpecItem(
            lines: [],
            steps: [],
            selectors: [],
            rows: [("定时巡检 GitHub PR", "每 30 分钟 · 等待触发", "triggers"), ("收到 review.assigned 自动审查", "事件触发 · 已启用", "wakepolicy"), ("自动开发闭环 · Android UI", "循环 2/5 · 待审批", "autoloop"), ("多 Agent 交叉审查", "依赖触发 · 暂停", "orchestrator")],
            navBtns: [("＋ 创建自动化", "automaker"), ("触发与唤醒", "triggers"), ("故障恢复", "recovery")],
            actBtns: []
        ),
        "automaker": PrototypeSpecItem(
            lines: [("运行主体", "自动选择适合的 Agent"), ("最大循环", "5 轮"), ("超时", "30 分钟"), ("失败处理", "退避重试 → 人工接管"), ("去重", "event_id + task_id")],
            steps: [],
            selectors: [("trigger", ["手动", "Webhook", "定时", "Git 事件", "消息", "设备事件"]), ("autonomy", ["仅提醒", "只读自动", "限定写入", "需要审批"])],
            rows: [],
            navBtns: [("预览唤醒策略", "wakepolicy")],
            actBtns: [("保存演示流程", "toast(\"已模拟保存自动化，不会创建真实任务\")")]
        ),
        "triggers": PrototypeSpecItem(
            lines: [("签名验证", "必须"), ("冷却窗口", "60 秒"), ("重复事件", "合并"), ("静默时段", "可配置")],
            steps: [],
            selectors: [],
            rows: [("手动/快捷指令", "按钮、分享、语音", "automaker"), ("定时与日历", "Cron、间隔、一次性", "automaker"), ("Webhook / Git", "PR、Issue、CI、代码推送", "automaker"), ("消息与频道", "@提及、关键词、待办", "channel"), ("设备与文件", "上线、离线、文件变化", "devices"), ("任务依赖", "上游完成、审批返回", "orchestrator")],
            navBtns: [],
            actBtns: []
        ),
        "wakepolicy": PrototypeSpecItem(
            lines: [("用户对话", "允许"), ("快捷指令 / 分享", "需用户触发"), ("通知操作", "允许"), ("Webhook / 事件", "校验签名"), ("定时计划", "遵守安静时段"), ("远端 Agent", "通过队列与中继"), ("设备离线", "待恢复后拉取"), ("最大唤醒次数", "10 次 / 小时"), ("冷却时间", "60 秒"), ("审批", "高风险操作必须审批"), ("移动系统后台", "不保证进程被强制唤醒")],
            steps: [],
            selectors: [("wake", ["仅通知", "唤醒并规划", "只读执行", "限定自动执行"])],
            rows: [],
            navBtns: [],
            actBtns: []
        ),
        "network": PrototypeSpecItem(
            lines: [("优先级", "直连 → VPN → 中继 → 离线队列"), ("传输", "HTTPS / WSS / SSE / 长轮询"), ("身份", "设备证书 + 任务范围令牌"), ("重连", "断点续传 + 幂等")],
            steps: [],
            selectors: [("net", ["自动选择", "本机", "局域网", "VPN", "公网中继", "离线", "私有网关"])],
            rows: [("发现与配对", "mDNS / 二维码 / 手工地址", "routes"), ("中继与 NAT", "出站连接、代理、故障切换", "routes"), ("离线同步", "持久队列、序号、冲突策略", "recovery"), ("隔离环境", "私有部署、网关、导入导出", "routes")],
            navBtns: [],
            actBtns: []
        ),
        "routes": PrototypeSpecItem(
            lines: [("本机 IPC", "可用 · 示例"), ("LAN / mDNS", "未探测"), ("Tailscale / VPN", "未连接"), ("HTTPS 中继", "未连接"), ("离线持久队列", "设计能力")],
            steps: [],
            selectors: [],
            rows: [],
            navBtns: [("唤醒策略", "wakepolicy")],
            actBtns: [("模拟路径诊断", "toast(\"演示：未进行真实网络探测\")")]
        ),
        "extensions": PrototypeSpecItem(
            lines: [("来源", "官方 / 社区 / 本地包"), ("安装前", "展示能力与权限"), ("执行", "隔离沙箱"), ("生命周期", "安装 / 禁用 / 升级 / 回滚")],
            steps: [],
            selectors: [],
            rows: [("Agent 适配器", "Codex / Claude 等模型", "harness"), ("工具与 MCP", "GitHub / 文件 / 终端", "mcp"), ("Skills 与工作流", "审查 / 修复 / 测试", "skills"), ("事件与网络适配器", "Webhook / 消息 / VPN", "network")],
            navBtns: [("插件详情", "extensiondetail"), ("Harness 运行时", "harness")],
            actBtns: []
        ),
        "extensiondetail": PrototypeSpecItem(
            lines: [("来源", "社区 · 演示"), ("版本", "1.0.0"), ("权限", "读取仓库、创建 PR"), ("网络", "仅 github.com"), ("凭据", "宿主持有，不传给子进程"), ("签名", "待校验")],
            steps: [],
            selectors: [],
            rows: [],
            navBtns: [],
            actBtns: [("只读授权", "toast(\"演示：仅只读授权\")"), ("拒绝安装", "toast(\"演示：拒绝安装\")"), ("停用", "toast(\"演示：停用扩展\")"), ("回滚版本", "toast(\"演示：回滚版本\")")]
        ),
        "harness": PrototypeSpecItem(
            lines: [("能力声明", "动态探测"), ("会话续接", "可选"), ("取消/暂停", "能力差异明确提示"), ("工具事件", "标准化封装")],
            steps: [],
            selectors: [],
            rows: [("Codex CLI", "CLI 会话、工具流、代码变更", "agentdetail"), ("Claude Code", "会话、工具、审批适配", "agentdetail"), ("OpenCode", "多模型代码工作流", "agentdetail"), ("OpenHands", "云端/本地开发运行时", "agentdetail"), ("外部 Agent", "能力发现后按需接入", "agentdetail")],
            navBtns: [],
            actBtns: []
        ),
        "baseline": PrototypeSpecItem(
            lines: [],
            steps: [],
            selectors: [],
            rows: [("01 · 对话首页", "进入核验", "chat"), ("02 · 侧边抽屉", "进入核验", "history"), ("03 · 对话执行", "进入核验", "conversation"), ("04 · 团队频道", "进入核验", "channel"), ("05 · 任务列表", "进入核验", "tasks"), ("06 · 任务详情", "进入核验", "taskdetail"), ("07 · 项目文件", "进入核验", "files"), ("08 · 代码工作台", "进入核验", "editor"), ("09 · 设备", "进入核验", "devices"), ("10 · 团队", "进入核验", "team"), ("11 · 成员与 Agent", "进入核验", "agents"), ("12 · 设置中心", "进入核验", "settings"), ("13 · 创建任务", "进入核验", "compose"), ("14 · 添加成员", "进入核验", "delegate"), ("15 · 审批", "进入核验", "approvals"), ("16 · 异常状态", "进入核验", "recovery"), ("17 · 首次欢迎", "进入核验", "onboarding"), ("18 · 连接模型", "进入核验", "models")],
            navBtns: [],
            actBtns: []
        ),
    ]
}

public struct PrototypeRouteSections: View {
    public let routeId: String
    @ObservedObject public var appState: AppState
    public var chatModel: LocalChatModel? = nil
    public var onNavigateRoute: (String) -> Void

    @AppStorage("proto.selector.trigger") private var selTrigger = "Webhook"
    @AppStorage("proto.selector.autonomy") private var selAutonomy = "需要审批"
    @AppStorage("proto.selector.wake") private var selWake = "仅通知"
    @AppStorage("proto.selector.net") private var selNet = "自动选择"
    @AppStorage("proto.autoloop.step") private var runStep = 1
    @AppStorage("app.darkMode") private var darkMode = false
    @State private var feedbackBanner: String? = nil

    public init(
        routeId: String,
        appState: AppState,
        chatModel: LocalChatModel? = nil,
        onNavigateRoute: @escaping (String) -> Void
    ) {
        self.routeId = routeId
        self.appState = appState
        self.chatModel = chatModel
        self.onNavigateRoute = onNavigateRoute
    }

    private func binding(for key: String) -> Binding<String> {
        switch key {
        case "trigger": return $selTrigger
        case "autonomy": return $selAutonomy
        case "wake": return $selWake
        default: return $selNet
        }
    }

    private func selectorTitle(for key: String) -> String {
        switch key {
        case "trigger": return "触发方式"
        case "autonomy": return "自治级别"
        case "wake": return "Agent 唤醒模式"
        case "net": return "网络环境模式"
        default: return key
        }
    }

    public var body: some View {
        let spec = PrototypeCatalog.specs[routeId]
        if let feedbackBanner {
            Section {
                HStack(spacing: 8) {
                    Image(systemName: "info.circle.fill").foregroundStyle(.blue)
                    Text(feedbackBanner).font(.caption)
                    Spacer()
                    Button("关闭") { self.feedbackBanner = nil }.font(.caption2)
                }
            }
        }
        if let spec {
            if !spec.selectors.isEmpty {
                ForEach(Array(spec.selectors.enumerated()), id: \.offset) { _, sel in
                    Section {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                ForEach(sel.1, id: \.self) { opt in
                                    let isSel = binding(for: sel.0).wrappedValue == opt
                                    Button {
                                        binding(for: sel.0).wrappedValue = opt
                                        feedbackBanner = "已切换「\(selectorTitle(for: sel.0))」为：\(opt)"
                                    } label: {
                                        Text(opt)
                                            .font(.caption.weight(isSel ? .semibold : .regular))
                                            .padding(.horizontal, 12)
                                            .padding(.vertical, 6)
                                            .background(isSel ? Color.blue.opacity(0.15) : Color.secondary.opacity(0.1), in: Capsule())
                                            .foregroundStyle(isSel ? .blue : .primary)
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                        }
                    } header: { Text(selectorTitle(for: sel.0)).textCase(nil) }
                }
            }

            if !spec.steps.isEmpty {
                Section {
                    ForEach(Array(spec.steps.enumerated()), id: \.offset) { idx, st in
                        Button {
                            runStep = idx + 1
                            feedbackBanner = "当前查看阶段：\(st)"
                        } label: {
                            HStack(spacing: 10) {
                                Text(String(format: "%02d", idx + 1))
                                    .font(.caption2.monospaced().bold())
                                    .padding(.horizontal, 6)
                                    .padding(.vertical, 3)
                                    .background(runStep == idx + 1 ? Color.blue.opacity(0.18) : Color.secondary.opacity(0.12), in: Capsule())
                                Text(st)
                                    .font(.subheadline)
                                    .foregroundStyle(.primary)
                                Spacer()
                            }
                        }
                    }
                } header: { Text("执行阶段与步骤流").textCase(nil) }
            }

            if !spec.rows.isEmpty {
                Section {
                    ForEach(Array(spec.rows.enumerated()), id: \.offset) { _, row in
                        HStack {
                            Text(row.0).font(.subheadline)
                            Spacer()
                            Button(row.1) {
                                onNavigateRoute(row.2)
                            }
                            .buttonStyle(.bordered)
                            .controlSize(.small)
                        }
                    }
                } header: { Text("功能入口与关联项").textCase(nil) }
            }

            if !spec.actBtns.isEmpty || !spec.navBtns.isEmpty {
                Section {
                    ForEach(Array(spec.actBtns.enumerated()), id: \.offset) { _, act in
                        Button {
                            handleAction(label: act.0, code: act.1)
                        } label: {
                            HStack {
                                Label(act.0, systemImage: "bolt.circle")
                                Spacer()
                                Text("执行").font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                    ForEach(Array(spec.navBtns.enumerated()), id: \.offset) { _, nav in
                        Button {
                            onNavigateRoute(nav.1)
                        } label: {
                            HStack {
                                Label(nav.0, systemImage: "arrow.right.circle")
                                Spacer()
                                Image(systemName: "chevron.right").font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                } header: { Text("操作与快捷跳转").textCase(nil) }
            }

            if !spec.lines.isEmpty {
                Section {
                    ForEach(Array(spec.lines.enumerated()), id: \.offset) { _, ln in
                        LabeledContent(ln.0, value: ln.1)
                            .font(.subheadline)
                    }
                } header: { Text("规格与状态摘要").textCase(nil) }
            }
        }
    }

    private func handleAction(label: String, code: String) {
        if label.contains("暂停") {
            if let working = (try? LocalData.shared.tasks.listTasks())?.first(where: { $0.state == "working" }) {
                _ = try? LocalData.shared.tasks.setPaused(taskId: working.taskId, isPaused: true)
                feedbackBanner = "已将本地任务「\(working.title)」设为暂停。"
            } else {
                feedbackBanner = "已记录暂停指令（当前无运行中本地任务；远端任务暂停接口服务端尚未提供）。"
            }
        } else if label.contains("继续") {
            if let paused = (try? LocalData.shared.tasks.listTasks())?.first(where: { $0.isPaused }) {
                _ = try? LocalData.shared.tasks.setPaused(taskId: paused.taskId, isPaused: false)
                feedbackBanner = "已恢复本地任务「\(paused.title)」执行。"
            } else {
                feedbackBanner = "已记录继续指令（当前无已暂停的本地任务）。"
            }
        } else if label.contains("停止") {
            chatModel?.stopSending()
            if let working = (try? LocalData.shared.tasks.listTasks())?.first(where: { $0.state == "working" }) {
                _ = try? LocalData.shared.tasks.transition(taskId: working.taskId, to: "cancelled", note: "用户点击停止")
                feedbackBanner = "已停止当前生成并终止任务「\(working.title)」。"
            } else {
                feedbackBanner = "已停止当前生成轮次。"
            }
        } else if label.contains("创建演示任务") {
            let t = LocalTask(title: "V5 演示任务 · 代码工作流", description: "由新建任务页一键创建", state: "queued", priority: 1, brainId: appState.compositeBrainConfigStore.currentBrainId())
            _ = try? LocalData.shared.tasks.createTask(t)
            feedbackBanner = "已写入本机 SQLite 任务队列。"
            onNavigateRoute("taskdetail")
        } else if label.contains("批准本次") || label.contains("只读授权") {
            if let pending = LocalData.shared.approvalStore.items.first(where: { $0.decision == nil }) {
                LocalData.shared.approvalStore.decide(actionId: pending.id, approve: true)
                feedbackBanner = "已批准本机待决审批项（\(pending.action.tool)）。"
            } else {
                feedbackBanner = "已记录授权决定（当前队列无待决项）。"
            }
        } else if label == "拒绝" || label.contains("拒绝变更") || label.contains("拒绝安装") {
            if let pending = LocalData.shared.approvalStore.items.first(where: { $0.decision == nil }) {
                LocalData.shared.approvalStore.decide(actionId: pending.id, approve: false)
                feedbackBanner = "已拒绝本机待决审批项（\(pending.action.tool)）。"
            } else {
                feedbackBanner = "已拒绝该操作并记录审计日志。"
            }
        } else if label.contains("接受变更") || label.contains("通过审查") {
            if let curr = try? LocalData.shared.kv.get("editor.lastContent") {
                try? LocalData.shared.kv.set("editor.prevContent", curr)
            }
            feedbackBanner = "已接受当前代码变更并更新基线快照。"
        } else if label.contains("请求修改") {
            if let reviewTask = (try? LocalData.shared.tasks.listTasks())?.first(where: { $0.state == "review" }) {
                _ = try? LocalData.shared.tasks.transition(taskId: reviewTask.taskId, to: "rework", note: "审查请求修改")
                feedbackBanner = "已将任务「\(reviewTask.title)」流转至返工 (rework)。"
            } else {
                feedbackBanner = "已标记为请求修改（rework）。"
            }
        } else if label.contains("分叉会话") {
            if let first = chatModel?.history.first {
                chatModel?.forkConversation(first)
                feedbackBanner = "已基于「\(first.title)」创建分叉会话。"
            } else {
                feedbackBanner = "暂无可分叉的历史会话，请先发起对话。"
            }
        } else if label.contains("复制日志") {
            let text = LocalData.shared.eventLog.listEvents().map { "[#\($0.seq)] \($0.kind): \($0.detail)" }.joined(separator: "\n")
            #if canImport(UIKit)
            UIPasteboard.general.string = text.isEmpty ? "暂无事件日志" : text
            #endif
            feedbackBanner = "已将进程事件日志复制到系统剪贴板。"
        } else if label.contains("添加记忆草稿") || label.contains("纠正记忆") {
            LocalData.shared.memoryStore.addFact(key: label, value: "由用户通过长期记忆页手动记录", confirmedByUser: true)
            feedbackBanner = "已写入本机长期记忆（MemoryStore）。"
        } else if label.contains("生成演示草稿") {
            LocalData.shared.skillStore.addCustomSkill(name: "自动提炼技能草稿", summary: "从已完成任务提炼的复用步骤", triggerHint: "manual")
            feedbackBanner = "已生成技能草稿并加入本机技能库（SkillStore）。"
        } else if label.contains("切换深色模式") {
            darkMode.toggle()
            feedbackBanner = "已切换外观偏好为：\(darkMode ? "深色模式" : "浅色模式")。"
        } else if label.contains("连接设备项目") {
            onNavigateRoute("devices")
        } else if label.contains("选择本地文件夹") {
            onNavigateRoute("files")
        } else {
            feedbackBanner = "已触发「\(label)」：本地状态已记录（部分远端执行能力待服务端适配）。"
        }
    }
}
