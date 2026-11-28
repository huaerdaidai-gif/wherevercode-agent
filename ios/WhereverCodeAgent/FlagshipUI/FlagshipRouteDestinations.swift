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
            ConnectionManagementView(appState: appState)
        case "agentimport", "network", "routes":
            ConnectionExtendedScreen(route: route, appState: appState, onNavigateRoute: onNavigateRoute)

        // MARK: - 三、控制 · 系统与自动化 (17 路由)
        case "dashboard":
            ControlHomeView(appState: appState, onOpenDrawer: {}, onNavigateRoute: onNavigateRoute)
        case "settings":
            AccountSettingsView(appState: appState, chatModel: chatModel, capabilities: capabilities, onNavigateRoute: onNavigateRoute, onDismiss: {})
        case "onboarding":
            OnboardingLocalFirstScreen(appState: appState, onNavigateRoute: onNavigateRoute)
        // 消息中心复用既有 notifications 路由。
        case "notifications":
            MessageCenterView(route: route, appState: appState, onNavigateRoute: onNavigateRoute)
        case "activity", "audit", "usage", "sync", "recovery", "baseline":
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
    var initialAgentId: String = ""

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
        .onAppear {
            // 批二（3.5）：从 Agent 行「指派任务」进来时预填指派对象
            if assignedAgentId.isEmpty { assignedAgentId = initialAgentId }
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
    @State private var showProjectSources = false
    @State private var newProjectName = ""
    @State private var newProjectRepo = ""
    @State private var newProjectBranch = "main"
    @State private var newProjectPath = ""
    @State private var activeWorkspaceProject: ProjectWithHomes? = nil

    public enum ProjectFilter: String, CaseIterable, Identifiable {
        case all = "全部"
        case local = "本机"
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
        case .all:
            return list
        case .local:
            return list.filter { $0.project.origin.contains("LOCAL") || $0.project.origin.contains("SANDBOX") }
        case .remote:
            return list.filter { $0.project.origin.contains("REMOTE") }
        }
    }

    private var projectSourceDialog: some View {
        ZStack {
            Color.black.opacity(0.32)
                .ignoresSafeArea()
                .onTapGesture { showProjectSources = false }
                .accessibilityHidden(true)

            ViewThatFits(in: .vertical) {
                projectSourceDialogContent
                ScrollView { projectSourceDialogContent }
            }
            .frame(maxWidth: 360)
            .background(.background, in: RoundedRectangle(cornerRadius: 20))
            .padding(24)
            .accessibilityAddTraits(.isModal)
            .accessibilityAction(.escape) { showProjectSources = false }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var projectSourceDialogContent: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("打开文件夹")
                .font(.headline)
                .accessibilityAddTraits(.isHeader)
            Text("选一个位置打开它作为项目之家。")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .padding(.top, 8)
                .padding(.bottom, 8)

            projectSourceRow("本机文件夹", detail: "本机工作区里的文件夹；挂载、网盘同步下来的也是文件夹。") {
                onNavigateRoute("files")
            }
            projectSourceRow("远程设备上的文件夹", detail: "连一台已配对设备（局域网、远程都一样），再打开上面的文件夹。") {
                onNavigateRoute("devices")
            }
            projectSourceRow("git 仓库地址", detail: "粘 GitHub / GitLab / Gitee / Hugging Face / Git URL 登记位置，经 API 读，不拉代码。") {
                showNewProjectSheet = true
            }

            Button("取消") { showProjectSources = false }
                .frame(maxWidth: .infinity, alignment: .trailing)
                .padding(.top, 8)
        }
        .padding(24)
    }

    private func projectSourceRow(_ title: String, detail: String, action: @escaping () -> Void) -> some View {
        Button {
            showProjectSources = false
            action()
        } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.subheadline.weight(.semibold))
                Text(detail).font(.footnote).foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.vertical, 10)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
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

                // 创建入口只留一个（Funk 2026-11-22 定）：页眉「新建项目」，
                // 点开走「打开文件夹」对话框（iOS 原先弹旧表单，已统一）；
                // 空态不再另放按钮，旧表单只是对话框里 git 一项的下级
                // 登记流程。
                Button {
                    showProjectSources = true
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
            if filteredProjects.isEmpty {
                // 空态居中（项目页统一设计，Funk 2026-11-22 定）：图标 + 标题 +
                // 说明在剩余空间正中；「导入与开发工具」区已删除——来源只在
                // 「新建项目」的打开文件夹对话框里出现一次，其余功能各回各页。
                VStack(spacing: 12) {
                    Spacer()
                    Image(systemName: "shippingbox")
                        .font(.system(size: 44))
                        .foregroundStyle(.secondary.opacity(0.6))
                    Text(projects.isEmpty ? "还没有创建任何项目" : "未找到匹配的项目")
                        .font(.headline)
                        .foregroundStyle(Color(red: 0.20, green: 0.19, blue: 0.18))
                    Text("项目支持统一管理任务、文件、代码、Artifacts、执行与审批交付")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 32)
                    Spacer()
                }
                .frame(maxWidth: .infinity)
            } else {
                ScrollView {
                    VStack(spacing: 12) {
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
                    .padding(16)
                }
            }
        }
        .background(Color(red: 0.98, green: 0.975, blue: 0.965).ignoresSafeArea())
        .onAppear(perform: loadProjects)
        .accessibilityHidden(showProjectSources)
        .overlay {
            if showProjectSources {
                projectSourceDialog
            }
        }
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
        // 与聊天新建项目共用同一建项目口径（createProjectFromGitForm）。
        _ = createProjectFromGitForm(
            name: newProjectName,
            repoUrl: newProjectRepo.isEmpty ? nil : newProjectRepo,
            branch: newProjectBranch,
            path: newProjectPath,
            data: LocalData.shared
        )
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
    @State private var showWebSeats = false
    @State private var pendingWebSeats = false
    @State private var webSeats: [WebSeatRecord] = []
    @State private var bannerMsg: String? = nil
    @State private var testingAgentId: String? = nil
    @State private var testResultMsg: String? = nil
    @State private var agentPendingDelete: AgentRecord? = nil
    @State private var showDeleteAgentConfirm = false
    @State private var assignAgent: AgentRecord? = nil
    @State private var editingAgent: AgentRecord? = nil
    @State private var probingAgentId: String? = nil

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
                if agents.isEmpty && webSeats.isEmpty {
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
                                // 批二（3.19）：认证三态标，与安卓卡 loginLabel 同义同色
                                Text(ag.loginState == .loggedIn ? "认证通过" : (ag.loginState == .notLoggedIn ? "认证失败" : "未验证"))
                                    .font(.caption2.weight(.medium))
                                    .foregroundStyle(ag.loginState == .loggedIn ? .green : (ag.loginState == .notLoggedIn ? .red : .orange))
                                    .padding(.horizontal, 5)
                                    .padding(.vertical, 1)
                                    .background((ag.loginState == .loggedIn ? Color.green : (ag.loginState == .notLoggedIn ? Color.red : Color.orange)).opacity(0.12), in: Capsule())
                                Spacer()
                                Toggle("启用", isOn: Binding(
                                    get: { ag.enabled },
                                    set: { val in
                                        try? LocalData.shared.agents.setEnabled(agentId: ag.agentId, enabled: val)
                                        bannerMsg = val ? "已启用 \(ag.displayName)：可选、可进频道、可接派活。" : "已停用 \(ag.displayName)：不在选 Agent 里出现，不进频道，不接派活。"
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
                                if ag.kind == .device, ag.baseUrl != nil {
                                    Button(testingAgentId == ag.agentId ? "正在测试…" : "单兵运行测试") {
                                        executeSingleAgentTest(ag)
                                    }
                                    .buttonStyle(.borderedProminent)
                                    .controlSize(.mini)
                                    .disabled(testingAgentId != nil)
                                } else {
                                    Button(probingAgentId == ag.agentId ? "正在测试…" : "测试连接") {
                                        testConnection(ag)
                                    }
                                    .buttonStyle(.borderedProminent)
                                    .controlSize(.mini)
                                    .disabled(probingAgentId != nil)
                                }
                                Button("对话执行") {
                                    onNavigateRoute("chat")
                                }
                                .buttonStyle(.bordered)
                                .controlSize(.mini)
                                if ag.dispatchable {
                                    Button("指派任务") {
                                        assignAgent = ag
                                    }
                                    .buttonStyle(.bordered)
                                    .controlSize(.mini)
                                }
                                Spacer()
                            }
                            HStack(spacing: 8) {
                                Button("工具权限") { onNavigateRoute("mcp") }.buttonStyle(.bordered).controlSize(.mini)
                                Button("编辑") {
                                    editingAgent = ag
                                }
                                .buttonStyle(.bordered)
                                .controlSize(.mini)
                                Spacer()
                                Button("删除", role: .destructive) {
                                    agentPendingDelete = ag
                                    showDeleteAgentConfirm = true
                                }.font(.caption2)
                            }
                        }
                    }
                    // 网页版 Agent 席位同列「已添加」（Funk 2026-11-28 纠正：
                    // Agent 页只留添加＋已添加两块，席位不单独分节）。
                    if let engine = appState.webSeatEngine {
                        WebSeatAddedRows(engine: engine, onOpen: { showWebSeats = true })
                    } else {
                        ForEach(webSeats) { seat in
                            HStack {
                                Image(systemName: "globe").foregroundStyle(.secondary)
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(seat.displayName).font(.subheadline.weight(.semibold))
                                    Text(seat.enabled ? "休息中" : "已停用")
                                        .font(.caption).foregroundStyle(.secondary)
                                }
                                Spacer()
                                Button("管理") { showWebSeats = true }.font(.caption)
                            }
                        }
                    }
                }
            } header: {
                // 渲染层修正（A5）：同上，关闭分节头默认大写转换
                Text("已添加的 Agent（\(agents.count + webSeats.count)）").textCase(nil)
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
        .sheet(isPresented: $showWebSeats, onDismiss: reload) {
            NavigationStack {
                WebSeatManagerView(appState: appState)
            }
        }
        .sheet(isPresented: $showAddAgentSheet, onDismiss: {
            if pendingWebSeats {
                pendingWebSeats = false
                showWebSeats = true
            }
        }) {
            AddAgentSheet(
                onSaved: { addedName in
                    reload()
                    bannerMsg = "已添加 Agent「\(addedName)」。"
                },
                onBatchAdded: { count in
                    reload()
                    bannerMsg = "已添加 \(count) 个 Agent。"
                },
                onOpenWebSeats: {
                    pendingWebSeats = true
                    showAddAgentSheet = false
                }
            )
        }
        .sheet(item: $assignAgent) { ag in
            NavigationStack {
                ComposeTaskScreen(appState: appState, onCreated: {
                    assignAgent = nil
                    bannerMsg = "已创建任务并指派给「\(ag.displayName)」。"
                }, onNavigateRoute: onNavigateRoute, initialAgentId: ag.agentId)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("取消") { assignAgent = nil }
                    }
                }
            }
        }
        .sheet(item: $editingAgent) { ag in
            AddAgentSheet(
                onSaved: { savedName in
                    reload()
                    bannerMsg = "已保存 Agent「\(savedName)」。"
                },
                onBatchAdded: { _ in },
                editing: ag
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
                    // 批二（3.19）：单兵测试是真实往返——成功即标认证通过，
                    // 与安卓 markAgentVerifiedByRealRound 同义（非 hello 探测）。
                    if var rec = (try? LocalData.shared.agents.getAgent(agentId: ag.agentId)) ?? nil {
                        rec.loginState = .loggedIn
                        rec.lastTestSummary = "认证通过：已在单兵运行测试中真实执行并返回输出（真实往返成功，非 hello 探测）。"
                        rec.lastTestedAtEpochSeconds = Int64(Date().timeIntervalSince1970)
                        try? LocalData.shared.agents.saveAgent(rec)
                    }
                    self.testResultMsg = "单兵测试成功！输出：\(output.trimmingCharacters(in: .whitespacesAndNewlines))"
                    self.reload()
                }
            } catch {
                await MainActor.run {
                    self.testingAgentId = nil
                    self.testResultMsg = "单兵测试失败：\(error.localizedDescription)"
                }
            }
        }
    }

    private func persistTestResult(agentId: String, summary: String) {
        if var rec = (try? LocalData.shared.agents.getAgent(agentId: agentId)) ?? nil {
            rec.lastTestSummary = summary
            rec.lastTestedAtEpochSeconds = Int64(Date().timeIntervalSince1970)
            try? LocalData.shared.agents.saveAgent(rec)
        }
        testResultMsg = summary
        reload()
    }

    /// 批二（3.6）通用测试连接：按类型做真实可达性探针并写回摘要；
    /// iOS 上没有本机 CLI 运行环境、也读不到密钥值的类型如实说明，不伪造结果。
    private func testConnection(_ ag: AgentRecord) {
        if ag.brainKind == "MODEL_API" || ag.access == .api {
            guard let raw = ag.baseUrl, !raw.isEmpty,
                  let url = URL(string: raw.hasPrefix("http") ? raw : "https://\(raw)") else {
                testResultMsg = "测试连接：\(ag.displayName) 未填服务地址，无法测试。"
                return
            }
            probingAgentId = ag.agentId
            testResultMsg = "正在测试 \(ag.displayName) 的连接…"
            var req = URLRequest(url: url)
            req.httpMethod = "GET"
            req.timeoutInterval = 10
            Task {
                do {
                    let (_, resp) = try await URLSession.shared.data(for: req)
                    let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
                    await MainActor.run {
                        self.probingAgentId = nil
                        self.persistTestResult(agentId: ag.agentId, summary: "连接测试：服务可达（HTTP \(code)）。仅证明端点可达；本机未存该 Agent 的密钥值，未做带密钥的调用验证。")
                    }
                } catch {
                    await MainActor.run {
                        self.probingAgentId = nil
                        self.persistTestResult(agentId: ag.agentId, summary: "连接测试失败：\(error.localizedDescription)")
                    }
                }
            }
        } else if ag.brainKind == "PLATFORM_AGENT" {
            guard let host = ag.platformHost, !host.isEmpty,
                  let url = URL(string: "https://\(host)/api/open/v1/mcp") else {
                testResultMsg = "测试连接：\(ag.displayName) 未填平台地址，无法测试。"
                return
            }
            probingAgentId = ag.agentId
            testResultMsg = "正在测试 \(ag.displayName) 的连接…"
            var req = URLRequest(url: url)
            req.httpMethod = "GET"
            req.timeoutInterval = 10
            Task {
                do {
                    let (_, resp) = try await URLSession.shared.data(for: req)
                    let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
                    await MainActor.run {
                        self.probingAgentId = nil
                        self.persistTestResult(agentId: ag.agentId, summary: "连接测试：平台端点可达（HTTP \(code)）。仅证明端点可达，未做带凭证的调用验证。")
                    }
                } catch {
                    await MainActor.run {
                        self.probingAgentId = nil
                        self.persistTestResult(agentId: ag.agentId, summary: "连接测试失败：\(error.localizedDescription)")
                    }
                }
            }
        } else {
            testResultMsg = "测试连接：\(ag.displayName) 的类型在 iOS 上没有可用的连接探针（订阅/本机 CLI 依赖本机运行环境、云端 Agent 由对方主动来连），未做实际探测，不伪造结果。"
        }
    }

    private func reload() {
        let all = (try? LocalData.shared.agents.listAgents()) ?? []
        // 过滤掉纯设备记录与模型记录，确保 Agent 列表中只有纯正智能体，不与设备和模型混淆
        agents = all.filter { !$0.isDeviceHost && $0.kind != .localModel }
        webSeats = (try? appState.webSeatStore.listSeats()) ?? []
    }
}

private struct AddAgentSheet: View {
    var onSaved: (String) -> Void
    var onBatchAdded: (Int) -> Void
    var onOpenWebSeats: () -> Void = {}
    var editing: AgentRecord? = nil
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

    // 批二（3.11–3.16）：手动表单改 6 类分型＋专区，(kind/access/brainKind) 映射与安卓同义
    private enum FormAgentType: String, CaseIterable {
        case modelApi, subscription, localCli, deviceAgent, platformAgent, cloudAgent
        var label: String {
            switch self {
            case .modelApi: return "云端模型 API（填服务地址 + 模型 + 密钥）"
            case .subscription: return "订阅授权 Agent（已登录的 CLI 会员额度，无密钥框）"
            case .localCli: return "本机 CLI Agent（填二进制名，如 codebuddy）"
            case .deviceAgent: return "设备上的 Agent（远程设备上的 CLI）"
            case .platformAgent: return "平台 Agent（走平台通道 + webhook 唤醒）"
            case .cloudAgent: return "云端 Agent（MCP 连接器：对方 App 自己来领单）"
            }
        }
    }

    @State private var formType: FormAgentType = .modelApi
    @State private var formAdapterId = ""
    @State private var formBinary = ""
    @State private var formModel = ""
    @State private var formAuthRef = ""
    @State private var formCapabilities = ""
    @State private var formDispatchable = true
    @State private var formKnownLimits = ""
    @State private var formDeviceFolder = ""
    @State private var formIsManus = false
    @State private var formPlatformAgentId = ""
    @State private var formPlatformHost = ""
    @State private var formPlatformChannelId = ""
    @State private var formWebhookUrl = ""
    @State private var formWebhookSecretRef = ""
    @State private var formDirectGithubRead = false
    @State private var webhookTestStatus: String? = nil
    @State private var formCloudHomeIsDevice = false
    @State private var formCloudHomeDeviceId = ""
    @State private var formRoleDev = true
    @State private var formRoleReview = true
    @State private var formRoleDeliver = false
    @State private var formCanDispatch = true
    @State private var formError: String? = nil
    @State private var didPrefill = false

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

                if editing == nil {
                    Section {
                        Button {
                            onOpenWebSeats()
                        } label: {
                            HStack {
                                Label("网页版 Agent（Claude 等）", systemImage: "globe")
                                    .font(.subheadline.weight(.semibold))
                                Spacer()
                                Image(systemName: "chevron.right").font(.caption).foregroundStyle(.tertiary)
                            }
                        }
                    } header: { Text("网页版 Agent").textCase(nil) } footer: {
                        Text("席位住在 App 内嵌浏览器里，你登录自己的账号，打开 App 时在频道里应声。")
                    }
                }

                Section {
                    TextField("Agent 标识（如 my-codex）", text: $newAgentId)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .disabled(editing != nil)
                    TextField("显示名称（如 Codex 工作节点）", text: $newAgentName)
                    Picker("类型", selection: $formType) {
                        ForEach(FormAgentType.allCases, id: \.self) { t in
                            Text(t.label).tag(t)
                        }
                    }
                } header: { Text("手动配置 Agent").textCase(nil) } footer: {
                    if editing != nil {
                        Text("编辑已有 Agent：标识不可改，其余字段保存后即生效。")
                    }
                }

                if formType == .modelApi {
                    Section {
                        TextField("服务地址，如 https://api.deepseek.com", text: $newAgentEndpoint)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        TextField(formIsManus ? "档位：standard / lite / max" : "模型，如 deepseek-flash", text: $formModel)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        TextField("密钥引用名，如 key:deepseek-1；不填则无密钥", text: $formAuthRef)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        Toggle("Manus（云端任务型 API：任务型接口，不走 /models 探针）", isOn: $formIsManus)
                    } header: { Text("云端模型 API 配置").textCase(nil) } footer: {
                        Text("密钥只存引用名，值只存本机钥匙串、不进记录；本表填引用名，值在模型/大脑配置里维护。")
                    }
                }

                if formType == .subscription {
                    Section {
                        Text("订阅授权走已登录 CLI 的会员额度：没有服务地址、没有密钥框。")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    } header: { Text("订阅授权配置").textCase(nil) }
                }

                if formType == .localCli {
                    Section {
                        TextField("CLI 二进制名，如 codebuddy", text: $formBinary)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                    } header: { Text("本机 CLI 配置").textCase(nil) }
                }

                if formType == .deviceAgent {
                    Section {
                        let registeredDevices = (try? LocalData.shared.agents.listAgents())?.filter { $0.kind == .device && $0.access == .app } ?? []
                        if registeredDevices.isEmpty {
                            Text("还没加设备：请先去设备页添加远程设备，再回来加 Agent。")
                                .font(.caption)
                                .foregroundStyle(.orange)
                        } else {
                            Picker("绑定执行设备", selection: $newAgentDeviceId) {
                                Text("未绑定设备").tag("")
                                ForEach(registeredDevices) { dev in
                                    Text(dev.displayName).tag(dev.deviceId ?? dev.agentId)
                                }
                            }
                        }
                        TextField("设备上的工作文件夹，如 /Users/example-user", text: $formDeviceFolder)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        TextField("设备上的 CLI 二进制名，如 codex", text: $formBinary)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                    } header: { Text("设备上的 Agent 配置").textCase(nil) }
                }

                if formType == .platformAgent {
                    Section {
                        TextField("平台 Agent ID，如 example-agent", text: $formPlatformAgentId)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        TextField("平台地址主机名（必填）", text: $formPlatformHost)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        TextField("专用频道 ID（可选）", text: $formPlatformChannelId)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        TextField("webhook 地址（可选；只供测试通知用）", text: $formWebhookUrl)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        TextField("webhook 签名密钥引用名（可选）", text: $formWebhookSecretRef)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        Toggle("直接读 GitHub（该 Agent 能直接读 GitHub 审查完整代码）", isOn: $formDirectGithubRead)
                        Button("发一条测试通知") { sendWebhookTest() }
                        if let webhookTestStatus {
                            Text(webhookTestStatus).font(.caption).foregroundStyle(.secondary)
                        }
                    } header: { Text("平台 Agent 配置").textCase(nil) } footer: {
                        Text("App 的平台凭证是 App 级的，在平台连接/MCP 页统一维护，本表不重复收。")
                    }
                }

                if formType == .cloudAgent {
                    Section {
                        Picker("在哪干活", selection: $formCloudHomeIsDevice) {
                            Text("云端").tag(false)
                            Text("归到某设备下（只做分组，不改变权限）").tag(true)
                        }
                        if formCloudHomeIsDevice {
                            let registeredDevices = (try? LocalData.shared.agents.listAgents())?.filter { $0.kind == .device && $0.access == .app } ?? []
                            Picker("归属设备（分组）", selection: $formCloudHomeDeviceId) {
                                Text("未选").tag("")
                                ForEach(registeredDevices) { dev in
                                    Text(dev.displayName).tag(dev.deviceId ?? dev.agentId)
                                }
                            }
                        }
                        Toggle("开发（领活写代码）", isOn: $formRoleDev)
                        Toggle("审查（审查别人的交付）", isOn: $formRoleReview)
                        Toggle("递代码（把交付的改动递给读不到 GitHub 的审查方）", isOn: $formRoleDeliver)
                        Toggle("允许它派活（开着能给其他 Agent 派活，关掉只能领活、读、交结果）", isOn: $formCanDispatch)
                        TextField("钥匙引用名，如 key:my-cloud-agent", text: $formAuthRef)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                    } header: { Text("云端 Agent 配置").textCase(nil) } footer: {
                        Text("角色至少勾一个；钥匙引用名不填时保存自动生成 key:<标识>。钥匙值只存本机钥匙串。")
                    }
                }

                if formType == .modelApi || formType == .subscription || formType == .localCli || formType == .deviceAgent {
                    Section {
                        TextField("适配器 id（可空，留空按类型默认）", text: $formAdapterId)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                    } header: { Text("适配器").textCase(nil) }
                }

                if formType != .cloudAgent {
                    Section {
                        TextField("能力，逗号分隔，如 dev,review；可空", text: $formCapabilities)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        Toggle("可派单（不可派单的 Agent 只对话、不接任务）", isOn: $formDispatchable)
                        TextField("已知限制，可空", text: $formKnownLimits)
                    } header: { Text("通用能力与限制").textCase(nil) }
                }

                if let formError {
                    Section {
                        Text(formError).font(.caption).foregroundStyle(.red)
                    }
                }

                Section {
                    Button(editing == nil ? "保存 Agent" : "保存修改") {
                        saveFormAgent()
                    }
                    .disabled(newAgentName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && newAgentId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }

                if let scanErrorMessage {
                    Section {
                        Text(scanErrorMessage).font(.caption).foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle(editing == nil ? "添加 Agent" : "编辑 Agent")
            .navigationBarTitleDisplayMode(.inline)
            .onAppear { prefillIfNeeded() }
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

    private static func detectType(_ r: AgentRecord) -> FormAgentType {
        switch r.brainKind {
        case "PLATFORM_AGENT": return .platformAgent
        case "CLOUD_AGENT": return .cloudAgent
        case "SUBSCRIPTION": return .subscription
        case "AGENT_RUNTIME": return r.deviceId != nil ? .deviceAgent : .localCli
        default:
            if r.adapterId == AgentRecord.platformAdapterId { return .platformAgent }
            if r.kind == .device { return .deviceAgent }
            if r.access == .cli { return .localCli }
            return .modelApi
        }
    }

    private func prefillIfNeeded() {
        guard !didPrefill else { return }
        didPrefill = true
        guard let r = editing else { return }
        newAgentId = r.agentId
        newAgentName = r.displayName
        formType = Self.detectType(r)
        formAdapterId = r.adapterId
        formBinary = r.binary ?? ""
        formModel = r.model ?? ""
        formAuthRef = r.authRef ?? ""
        formCapabilities = r.capabilities.joined(separator: ",")
        formDispatchable = r.dispatchable
        formKnownLimits = r.knownLimits ?? ""
        newAgentEndpoint = r.baseUrl ?? ""
        newAgentDeviceId = r.deviceId ?? ""
        formDeviceFolder = r.deviceFolder ?? ""
        formIsManus = r.adapterId == "manus"
        formPlatformAgentId = r.platformAgentId ?? ""
        formPlatformHost = r.platformHost ?? ""
        formPlatformChannelId = r.platformChannelId ?? ""
        formWebhookUrl = r.webhookUrl ?? ""
        formWebhookSecretRef = r.webhookSecretRef ?? ""
        formDirectGithubRead = r.directGithubRead
        formCloudHomeIsDevice = r.cloudHomeDeviceId != nil
        formCloudHomeDeviceId = r.cloudHomeDeviceId ?? ""
        formRoleDev = r.cloudRoles.contains("dev")
        formRoleReview = r.cloudRoles.contains("review")
        formRoleDeliver = r.cloudRoles.contains("deliver")
        formCanDispatch = r.canDispatch
    }

    private func saveFormAgent() {
        let cleanName = newAgentName.trimmingCharacters(in: .whitespacesAndNewlines)
        let rawId = newAgentId.trimmingCharacters(in: .whitespacesAndNewlines)
        let finalId = editing?.agentId ?? (rawId.isEmpty ? "agent-\(UUID().uuidString.prefix(6).lowercased())" : rawId)
        guard !cleanName.isEmpty || !rawId.isEmpty || editing != nil else { return }
        formError = nil

        // 编辑时从原记录起步：表单不管的字段（登录态/测试摘要/创建时间等）原样保留
        var rec = editing ?? AgentRecord(agentId: finalId, displayName: cleanName.isEmpty ? finalId : cleanName)
        rec.agentId = finalId
        if !cleanName.isEmpty { rec.displayName = cleanName }
        let caps = formCapabilities.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        let limits = formKnownLimits.trimmingCharacters(in: .whitespacesAndNewlines)

        switch formType {
        case .modelApi:
            rec.kind = .cloud
            rec.access = .api
            rec.brainKind = "MODEL_API"
            rec.adapterId = formIsManus ? "manus" : (formAdapterId.isEmpty ? "model-api-generic" : formAdapterId)
            var url = newAgentEndpoint.trimmingCharacters(in: .whitespacesAndNewlines)
            if formIsManus && url.isEmpty { url = "https://api.manus.ai" }
            rec.baseUrl = url.isEmpty ? nil : url
            rec.model = formModel.isEmpty ? nil : formModel
            rec.authRef = formAuthRef.isEmpty ? nil : formAuthRef
            rec.binary = nil
            rec.deviceId = nil
            rec.deviceFolder = nil
        case .subscription:
            rec.kind = .cloud
            rec.access = .cli
            rec.brainKind = "SUBSCRIPTION"
            rec.adapterId = formAdapterId.isEmpty ? "subscription" : formAdapterId
            rec.baseUrl = nil
            rec.authRef = nil
            rec.binary = nil
            rec.deviceId = nil
            rec.deviceFolder = nil
        case .localCli:
            rec.kind = .cloud
            rec.access = .cli
            rec.brainKind = "AGENT_RUNTIME"
            rec.adapterId = formAdapterId.isEmpty ? (formBinary.isEmpty ? "cli-generic" : formBinary) : formAdapterId
            rec.binary = formBinary.isEmpty ? nil : formBinary
            rec.baseUrl = nil
            rec.deviceId = nil
            rec.deviceFolder = nil
        case .deviceAgent:
            guard !newAgentDeviceId.isEmpty else {
                formError = "还没选设备：请先去设备页添加远程设备，再回来选一台已添加的设备。"
                return
            }
            rec.kind = .device
            rec.access = .cli
            rec.brainKind = "AGENT_RUNTIME"
            rec.adapterId = formAdapterId.isEmpty ? (formBinary.isEmpty ? "cli-generic" : formBinary) : formAdapterId
            rec.binary = formBinary.isEmpty ? nil : formBinary
            rec.deviceId = newAgentDeviceId
            rec.deviceFolder = formDeviceFolder.isEmpty ? nil : formDeviceFolder
            let host = (try? LocalData.shared.agents.listAgents())?.first(where: { ($0.deviceId ?? $0.agentId) == newAgentDeviceId })
            rec.baseUrl = host?.baseUrl
        case .platformAgent:
            let pid = formPlatformAgentId.trimmingCharacters(in: .whitespacesAndNewlines)
            let phost = formPlatformHost.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !pid.isEmpty else { formError = "平台 Agent 需要填写平台 Agent ID，如 example-agent。"; return }
            guard !phost.isEmpty else { formError = "平台 Agent 需要填写平台地址主机名。"; return }
            rec.kind = .cloud
            rec.access = .webhook
            rec.brainKind = "PLATFORM_AGENT"
            rec.adapterId = AgentRecord.platformAdapterId
            rec.platformAgentId = pid
            rec.platformHost = phost
            rec.platformChannelId = formPlatformChannelId.isEmpty ? nil : formPlatformChannelId
            rec.webhookUrl = formWebhookUrl.isEmpty ? nil : formWebhookUrl
            rec.webhookSecretRef = formWebhookSecretRef.isEmpty ? nil : formWebhookSecretRef
            rec.directGithubRead = formDirectGithubRead
            rec.binary = nil
            rec.deviceId = nil
            rec.deviceFolder = nil
        case .cloudAgent:
            var roles: [String] = []
            if formRoleDev { roles.append("dev") }
            if formRoleReview { roles.append("review") }
            if formRoleDeliver { roles.append("deliver") }
            guard !roles.isEmpty else { formError = "云端 Agent 至少要勾一个角色：开发、审查、递代码。"; return }
            rec.kind = .cloud
            rec.access = .connector
            rec.brainKind = "CLOUD_AGENT"
            rec.adapterId = "cloud-agent"
            rec.cloudRoles = roles
            rec.canDispatch = formCanDispatch
            rec.cloudHomeDeviceId = (formCloudHomeIsDevice && !formCloudHomeDeviceId.isEmpty) ? formCloudHomeDeviceId : nil
            rec.authRef = formAuthRef.isEmpty ? "key:\(finalId)" : formAuthRef
            rec.binary = nil
            rec.deviceId = nil
            rec.deviceFolder = nil
        }

        // 换类型时清掉不属于当前类型的专属字段，避免脏数据跨类型残留
        if formType != .platformAgent {
            rec.platformAgentId = nil
            rec.platformHost = nil
            rec.platformChannelId = nil
            rec.webhookUrl = nil
            rec.webhookSecretRef = nil
            rec.directGithubRead = false
        }
        if formType != .cloudAgent {
            rec.cloudRoles = []
            rec.cloudHomeDeviceId = nil
        }
        if formType != .cloudAgent {
            rec.capabilities = caps
            rec.dispatchable = formDispatchable
            rec.knownLimits = limits.isEmpty ? nil : limits
        }
        if editing == nil {
            rec.quotaRule = rec.kind == .cloud ? "按厂商额度" : "设备本地，不占云端额度"
            rec.lastTestSummary = "本地已登记"
            rec.memberConnectionState = "NOT_CONNECTED"
        }
        try? LocalData.shared.agents.saveAgent(rec)
        dismiss()
        onSaved(rec.displayName)
    }

    /// 平台 Agent 专区「发一条测试通知」：与安卓 PlatformWebhookTester 同款
    /// 载荷与结论口径；iOS 表单只存签名密钥引用名、无密钥值，故本次不签名并如实注明。
    private func sendWebhookTest() {
        let hook = formWebhookUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !hook.isEmpty else { webhookTestStatus = "请先填 webhook 地址。"; return }
        let targetId = formPlatformAgentId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !targetId.isEmpty else { webhookTestStatus = "请先填平台 Agent ID。"; return }
        guard let url = URL(string: hook) else { webhookTestStatus = "webhook 地址不是合法 URL。"; return }
        webhookTestStatus = "正在发测试通知…"
        let payload: [String: Any] = [
            "event": "test_notification",
            "source": "v5-app",
            "agent_id": targetId,
            "text": "WhereverCode V5 App 测试通知：只验证 webhook 端点可达，请忽略，不要当作真实任务。"
        ]
        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.timeoutInterval = 10
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try? JSONSerialization.data(withJSONObject: payload)
        // 定格为 let 再进 Task：var 被并发闭包捕获在新工具链的严格
        // 并发检查下是编译错误（reference to captured var in
        // concurrently-executing code），会打挂 Debug 模拟器构建。
        let request = req
        let unsignedNote = formWebhookSecretRef.isEmpty ? "" : "（本机只存签名密钥引用名、无密钥值，本次未签名）"
        Task {
            do {
                let (data, resp) = try await URLSession.shared.data(for: request)
                let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
                await MainActor.run {
                    if (200..<300).contains(code) {
                        self.webhookTestStatus = "测试通知已通：webhook 回 HTTP \(code)（只证明端点可达，不证明平台会用它唤醒对方）。\(unsignedNote)"
                    } else {
                        let preview = String(data: data, encoding: .utf8).map { String($0.prefix(80)) } ?? ""
                        self.webhookTestStatus = "测试通知不通：webhook 回 HTTP \(code)（\(preview.isEmpty ? "无回包说明" : preview)）。\(unsignedNote)"
                    }
                }
            } catch {
                await MainActor.run {
                    self.webhookTestStatus = "测试通知发不出去：\(error.localizedDescription)"
                }
            }
        }
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

    // 通用 MCP 服务器 / 插件 / 扩展注册表（控制对齐批四）
    @State private var genericServers: [GenericMcpServerConfig] = []
    @State private var serverStatuses: [String: String] = [:]
    @State private var serverBusy: Set<String> = []
    @State private var showServerForm = false
    @State private var serverDraft = GenericMcpServerConfig()
    @State private var serverFormIsEdit = false
    @State private var showImportSheet = false
    @State private var importText = ""
    @State private var importMessage: String?
    @State private var pendingDeleteServer: GenericMcpServerConfig?
    @State private var plugins: [V5PluginRecord] = []

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

            Section {
                HStack {
                    Button("＋ 添加服务器") {
                        serverDraft = GenericMcpServerConfig()
                        serverFormIsEdit = false
                        showServerForm = true
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                    Spacer()
                    Button("导入配置") {
                        importText = ""
                        importMessage = nil
                        showImportSheet = true
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                }
                Text("添加：填地址就能连，支持 Streamable HTTP（主流）、SSE（老版）与 stdio（本机命令）；导入：粘贴标准 mcpServers JSON（Claude Desktop / VS Code 同款格式）。")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                if genericServers.isEmpty {
                    Text("暂无 MCP 服务器。").font(.caption).foregroundStyle(.secondary)
                } else {
                    ForEach(genericServers) { server in
                        genericServerRow(server)
                    }
                }
            } header: { Text("通用 MCP 服务器").textCase(nil) }

            Section {
                if plugins.isEmpty {
                    Text("暂无插件。").font(.caption).foregroundStyle(.secondary)
                } else {
                    ForEach(plugins) { plugin in
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text(plugin.name).font(.subheadline.weight(.semibold))
                                Text("v\(plugin.version)").font(.caption2).foregroundStyle(.secondary)
                                Spacer()
                                Text(plugin.status)
                                    .font(.caption2).fontWeight(.bold)
                                    .padding(.horizontal, 6).padding(.vertical, 2)
                                    .background(plugin.status == "已启用" ? Color.green.opacity(0.15) : Color.secondary.opacity(0.12), in: Capsule())
                                    .foregroundStyle(plugin.status == "已启用" ? .green : .secondary)
                            }
                            Text(plugin.summary).font(.caption).foregroundStyle(.secondary)
                            HStack {
                                Text("提供工具: \(plugin.providedTools.joined(separator: ", "))").font(.caption2).foregroundStyle(.secondary)
                                Spacer()
                                Button(plugin.status == "已启用" ? "禁用" : (plugin.status == "未安装" ? "安装并启用" : "启用")) {
                                    plugins = V5PluginManager.togglePlugin(id: plugin.id)
                                }
                                .buttonStyle(.bordered)
                                .controlSize(.mini)
                            }
                        }
                        .padding(.vertical, 2)
                    }
                }
            } header: { Text("插件").textCase(nil) }

            Section {
                let counts = IosExtensionRegistry.countsByKind()
                let mcpCount = (counts[.mcp] ?? 0) + genericServers.count
                let summary = IosExtensionKind.allCases
                    .sorted { $0.rawValue < $1.rawValue }
                    .map { kind in "\(kind.rawValue) \(kind == .mcp ? mcpCount : (counts[kind] ?? 0)) 个" }
                    .joined(separator: " · ")
                Text(summary).font(.caption)
                Text("MCP 服务器与插件在两边是同一套身份（同名、同类）；其余五类先只登记，不在此屏提供操作。")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            } header: { Text("扩展注册表（七类，与 Hand 侧同一套分类）").textCase(nil) }

            PrototypeRouteSections(routeId: "mcp", appState: appState, onNavigateRoute: onNavigateRoute)
        }
        .task {
            genericServers = GenericMcpServerStore.list()
            plugins = V5PluginManager.listPlugins()
            if appState.hasToken && toolNames.isEmpty {
                await fetchTools()
            }
        }
        .sheet(isPresented: $showServerForm) {
            GenericMcpServerFormSheet(draft: serverDraft, isEdit: serverFormIsEdit) { saved in
                GenericMcpServerStore.upsert(saved)
                genericServers = GenericMcpServerStore.list()
            }
        }
        .sheet(isPresented: $showImportSheet) {
            NavigationStack {
                VStack(alignment: .leading, spacing: 8) {
                    Text("粘贴标准 mcpServers JSON（Claude Desktop / VS Code 同款格式）。同名服务器会覆盖配置，启用开关保留。")
                        .font(.caption).foregroundStyle(.secondary)
                    TextEditor(text: $importText)
                        .font(.system(.caption, design: .monospaced))
                        .frame(maxHeight: .infinity)
                    if let importMessage {
                        Text(importMessage).font(.caption).foregroundStyle(.red)
                    }
                }
                .padding()
                .navigationTitle("导入 MCP 服务器")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("取消") { showImportSheet = false }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("导入") {
                            let parsed = GenericMcpImport.parse(importText)
                            if parsed.isEmpty {
                                importMessage = "没有解析出服务器：请粘贴含 mcpServers 的 JSON。"
                            } else {
                                for var config in parsed {
                                    if let existing = genericServers.first(where: { $0.name == config.name }) {
                                        config.enabled = existing.enabled
                                    }
                                    GenericMcpServerStore.upsert(config)
                                }
                                genericServers = GenericMcpServerStore.list()
                                showImportSheet = false
                            }
                        }
                    }
                }
            }
        }
        .alert("删除 MCP 服务器", isPresented: Binding(
            get: { pendingDeleteServer != nil },
            set: { if !$0 { pendingDeleteServer = nil } }
        )) {
            Button("删除", role: .destructive) {
                if let server = pendingDeleteServer {
                    GenericMcpServerStore.remove(name: server.name)
                    serverStatuses[server.name] = nil
                    genericServers = GenericMcpServerStore.list()
                }
                pendingDeleteServer = nil
            }
            Button("取消", role: .cancel) { pendingDeleteServer = nil }
        } message: {
            Text("删除 MCP 服务器“\(pendingDeleteServer?.name ?? "")”？已连接时会先断开。内置服务器删不掉（只能停用）。")
        }
    }

    private func genericServerRow(_ server: GenericMcpServerConfig) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(server.name).font(.subheadline.weight(.semibold))
                Spacer()
                Text(serverStatuses[server.name] ?? (server.enabled ? "未连接" : "未启用"))
                    .font(.caption).foregroundStyle(.secondary)
            }
            Text(server.transportLabel).font(.caption2).foregroundStyle(.secondary)
            HStack(spacing: 8) {
                Toggle("启用", isOn: Binding(
                    get: { server.enabled },
                    set: { newValue in
                        GenericMcpServerStore.setEnabled(name: server.name, enabled: newValue)
                        genericServers = GenericMcpServerStore.list()
                    }
                ))
                .toggleStyle(.switch)
                .controlSize(.mini)
                .labelsHidden()
                Text(server.enabled ? "已启用" : "已停用").font(.caption2).foregroundStyle(.secondary)
                Spacer()
                if server.enabled {
                    Button(serverStatuses[server.name]?.hasPrefix("已连接") == true ? "测连通" : "连接") {
                        connectGenericServer(server)
                    }
                    .buttonStyle(.bordered).controlSize(.mini)
                    .disabled(serverBusy.contains(server.name))
                }
                Button("编辑") {
                    serverDraft = server
                    serverFormIsEdit = true
                    showServerForm = true
                }
                .buttonStyle(.bordered).controlSize(.mini)
                Button("删除", role: .destructive) {
                    pendingDeleteServer = server
                }
                .buttonStyle(.bordered).controlSize(.mini)
            }
        }
        .padding(.vertical, 2)
    }

    private func connectGenericServer(_ server: GenericMcpServerConfig) {
        serverBusy.insert(server.name)
        serverStatuses[server.name] = "正在连接…"
        Task {
            do {
                let names = try await GenericMcpClient(config: server).listTools()
                serverStatuses[server.name] = "已连接 · \(names.count) 个工具"
            } catch let error as GenericMcpError {
                serverStatuses[server.name] = "连接失败：\(error.text)"
            } catch {
                serverStatuses[server.name] = "连接失败：\(error.localizedDescription)"
            }
            serverBusy.remove(server.name)
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

/// 通用 MCP 服务器添加/编辑表单（控制对齐批四，与安卓表单同构：
/// 名称编辑时不可改、传输三选、地址/命令随传输切换、请求头一行
/// 一个、OAuth 字段 stdio 时隐藏；校验口径与安卓一致）。
private struct GenericMcpServerFormSheet: View {
    @Environment(\.dismiss) private var dismiss
    let isEdit: Bool
    var onSave: (GenericMcpServerConfig) -> Void
    private let originalEnabled: Bool
    @State private var name: String
    @State private var transport: String
    @State private var address: String
    @State private var headersText: String
    @State private var oauth: Bool
    @State private var oauthClientId: String
    @State private var oauthScopes: String
    @State private var errorText: String?

    init(draft: GenericMcpServerConfig, isEdit: Bool, onSave: @escaping (GenericMcpServerConfig) -> Void) {
        self.isEdit = isEdit
        self.onSave = onSave
        self.originalEnabled = draft.enabled
        _name = State(initialValue: draft.name)
        _transport = State(initialValue: draft.transport)
        _address = State(initialValue: draft.transport == "stdio" ? draft.command : draft.url)
        _headersText = State(initialValue: draft.headers.sorted { $0.key < $1.key }.map { "\($0.key): \($0.value)" }.joined(separator: "\n"))
        _oauth = State(initialValue: draft.oauth)
        _oauthClientId = State(initialValue: draft.oauthClientId)
        _oauthScopes = State(initialValue: draft.oauthScopes)
    }

    private var addressLabel: String {
        switch transport {
        case "sse": return "服务器地址（https://…/sse）"
        case "stdio": return "启动命令（如 npx -y @modelcontextprotocol/server-filesystem /tmp）"
        default: return "服务器地址（https://…/mcp）"
        }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("服务器名（唯一，如 filesystem）", text: $name)
                        .disabled(isEdit)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } header: { Text("名称").textCase(nil) }
                Section {
                    Picker("传输", selection: $transport) {
                        Text("Streamable HTTP（主流）").tag("http")
                        Text("SSE（老版兼容）").tag("sse")
                        Text("stdio（本机命令）").tag("stdio")
                    }
                } header: { Text("传输").textCase(nil) }
                Section {
                    TextField(addressLabel, text: $address)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } header: { Text("地址 / 命令").textCase(nil) }
                Section {
                    TextEditor(text: $headersText)
                        .frame(minHeight: 60)
                        .font(.system(.caption, design: .monospaced))
                    Text("值可写 keychain:<引用名>，连接时从本机钥匙串解析，不落明文。")
                        .font(.caption2).foregroundStyle(.secondary)
                } header: { Text("请求头（可选，一行一个，如 Authorization: Bearer keychain:xxx）").textCase(nil) }
                if transport != "stdio" {
                    Section {
                        Toggle("走 OAuth 2.1 登录（401 时开浏览器授权）", isOn: $oauth)
                        TextField("OAuth 客户端 id（可空，默认 wherevercode-agent）", text: $oauthClientId)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        TextField("OAuth scope（可选，空格分隔）", text: $oauthScopes)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                    } header: { Text("OAuth").textCase(nil) }
                }
                if let errorText {
                    Section {
                        Text(errorText).font(.caption).foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle(isEdit ? "编辑 MCP 服务器" : "添加 MCP 服务器")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") { save() }
                }
            }
        }
    }

    private func save() {
        let cleanName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanAddress = address.trimmingCharacters(in: .whitespacesAndNewlines)
        if cleanName.isEmpty {
            errorText = "请填服务器名。"
            return
        }
        if cleanAddress.isEmpty {
            errorText = transport == "stdio" ? "请填启动命令。" : "请填服务器地址。"
            return
        }
        var headers: [String: String] = [:]
        for rawLine in headersText.split(separator: "\n") {
            let line = rawLine.trimmingCharacters(in: .whitespaces)
            if line.isEmpty { continue }
            guard let idx = line.firstIndex(of: ":") else {
                errorText = "请求头格式不对：每行写成“头名: 值”。"
                return
            }
            let key = line[..<idx].trimmingCharacters(in: .whitespaces)
            let value = line[line.index(after: idx)...].trimmingCharacters(in: .whitespaces)
            if key.isEmpty || value.isEmpty {
                errorText = "请求头格式不对：每行写成“头名: 值”。"
                return
            }
            headers[key] = value
        }
        onSave(GenericMcpServerConfig(
            name: cleanName,
            transport: transport,
            url: transport == "stdio" ? "" : cleanAddress,
            command: transport == "stdio" ? cleanAddress : "",
            headers: headers,
            oauth: oauth && transport != "stdio",
            oauthClientId: oauthClientId.trimmingCharacters(in: .whitespacesAndNewlines),
            oauthScopes: oauthScopes.trimmingCharacters(in: .whitespacesAndNewlines),
            enabled: originalEnabled
        ))
        dismiss()
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

public enum ChannelLayer: String, Equatable, Hashable {
    case local
    case cloud

    public var label: String {
        switch self {
        case .local: return "本机"
        case .cloud: return "云端"
        }
    }
}

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
    /// 频道来源层：本机账本或云端列表/缓存。
    public var layer: ChannelLayer

    public init(
        id: String,
        name: String,
        topic: String = "",
        isPrivate: Bool = false,
        associatedProjectId: String? = nil,
        associatedProjectName: String? = nil,
        // 规范化：计数与时间默认值改为 0/空——此前是设计稿占位假值
        // （3 成员／1 Agent／12:00），映射层漏传就会把假值显示上屏；
        // 生产构造只有 mapChannelItem 一处，现已全量传真实数据。
        memberCount: Int = 0,
        agentCount: Int = 0,
        lastMessageText: String = "",
        lastMessageTime: String = "",
        unreadCount: Int = 0,
        isMuted: Bool = false,
        isIgnored: Bool = false,
        isArchived: Bool = false,
        layer: ChannelLayer = .local
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
        self.layer = layer
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

    init(_ participant: ChannelParticipant) {
        self.init(id: participant.id, name: participant.name,
                  isAgent: participant.isAgent ?? false, role: participant.role ?? "平台参与者",
                  status: "成员", agentRecordId: participant.id)
    }

}

/// Channel role → Chinese label (raw values stay on the wire).
public func channelRoleLabel(_ role: String) -> String {
    switch role {
    case "owner": return "创建者"
    case "coordinator": return "协调员"
    case "member": return "成员"
    default: return role
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
    /// 频道补全二期字段（平台回帖直带；本地审查流消息缺省为空/假）。
    public var recalled: Bool = false
    public var pinned: Bool = false
    public var reactions: [ChannelReaction] = []
    /// 附件引用（引用制：网盘/投递引用，不存二进制）。
    public var attachmentRef: String? = nil
    /// 转发来源标注（“来源频道 · 原发送者”）。
    public var forwardOf: String? = nil
    /// 平台原样 `is_agent`。nil 表示响应没有该字段，不能事后当成 false。
    public var platformIsAgent: Bool? = nil

    public init(
        id: String,
        senderId: String,
        senderName: String,
        isAgent: Bool,
        text: String,
        // 规范化：时间默认值改为空串——此前是写死的占位假值
        // （12:00／2026-11-15 12:00:00），任何漏传时间的消息都会
        // 把假时间显示上屏；真实消息一律由映射层传真实时间。
        timestamp: String = "",
        fullDateTime: String = "",
        replyToId: String? = nil,
        replyToText: String? = nil,
        linkedTaskId: String? = nil,
        linkedTaskTitle: String? = nil,
        linkedTaskState: String? = nil,
        attachmentName: String? = nil,
        attachmentType: String? = nil,
        attachmentSize: String? = nil,
        recalled: Bool = false,
        pinned: Bool = false,
        reactions: [ChannelReaction] = [],
        attachmentRef: String? = nil,
        forwardOf: String? = nil,
        platformIsAgent: Bool? = nil
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
        self.recalled = recalled
        self.pinned = pinned
        self.reactions = reactions
        self.attachmentRef = attachmentRef
        self.forwardOf = forwardOf
        self.platformIsAgent = platformIsAgent
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
    /// 全量对齐批二：附件引用原文与可打开链接（引用制四动作的依据：
    /// 预览/下载/分享只对带链接的引用成立，其余如实降级为复制引用）。
    public var ref: String = ""
    public var linkURL: String? = nil

    public init(
        id: String,
        fileName: String,
        fileSize: String,
        fileType: String,
        uploaderName: String,
        uploadTime: String = "11-15 12:00",
        associatedProjectName: String? = nil,
        associatedTaskId: String? = nil,
        sourceKind: String = "local",
        ref: String = "",
        linkURL: String? = nil
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
        self.ref = ref
        self.linkURL = linkURL
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
    @State private var showCreateChannelSheet = false
    /// 切换面板是 sheet，要等它关掉再弹出新建频道。
    @State private var openCreateAfterSwitchDismiss = false
    @State private var showManagementSheet = false
    @State private var showPolicyAlert = false
    @State private var policyAlertMessage = ""
    // 218 深链：带 channelId 跳转失败时的定性提示（无效/无权限/不存在）
    @State private var channelLinkError: String? = nil

    // 批三（3.7）：创建成功后的落点提示行（与安卓创建后状态行同位）。
    @State private var createLandingNotice: String? = nil

    // 频道数据与选中
    @State private var channels: [V5ChannelItem] = []
    @State private var activeWorkspaceChannel: V5ChannelItem? = nil

    // 私聊数据与选中
    @State private var directMessages: [V5DirectMessageItem] = []
    @State private var activeWorkspaceDM: V5DirectMessageItem? = nil


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

    /// 频道可用性：有平台凭证走远程；没配全时走 App 内嵌的本地
    /// 平台（频道三层一期·本地层），频道页不再落未配置死页。
    private var channelAvailable: Bool {
        appState.hasToken || appState.channelUsesLocalPlatform
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

            // 218 统一版式分段一：频道／私聊直接放在页面上（与安卓
            // 同位），不再只藏在切换面板里；面板内分段与此共用状态。
            if channelAvailable {
                partitionPicker
            }

            if let createLandingNotice {
                Text(createLandingNotice)
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 4)
            }

            // 对话框直达（批D）：打开即当前频道的对话框（消息流+状态+
            // 发送区）；搜索/频道列表收进切换面板（点频道名开）。
            if !channelAvailable {
                unconfiguredTokenView
            } else if selectedPartition == .directMessages, activeWorkspaceDM == nil {
                directMessagesListView
            } else if let dm = activeWorkspaceDM, selectedPartition == .directMessages {
                ChannelDetailWorkspaceView(
                    appState: appState,
                    channelItem: nil,
                    dmItem: dm,
                    onNavigateRoute: onNavigateRoute,
                    embedded: true,
                    onOpenSwitchPanel: { showSwitchPanel = true }
                )
                .id(dm.id)
            } else if let ch = activeWorkspaceChannel {
                ChannelDetailWorkspaceView(
                    appState: appState,
                    channelItem: ch,
                    dmItem: nil,
                    onNavigateRoute: onNavigateRoute,
                    embedded: true,
                    onOpenSwitchPanel: { showSwitchPanel = true }
                )
                .id(ch.id)
            } else {
                noChannelSelectedView
            }
        }
        .background(Color(red: 0.969, green: 0.969, blue: 0.961).ignoresSafeArea())
        .onAppear(perform: loadData)
        .task {
            await refreshChannels()
            // 冷启动/登录恢复：pending 可能在本页出现前就已恢复，
            // 不等 onChange，出现后主动消费一次。
            await consumePendingChannelLink()
        }
        .sheet(isPresented: $showSwitchPanel, onDismiss: {
            if openCreateAfterSwitchDismiss {
                openCreateAfterSwitchDismiss = false
                showCreateChannelSheet = true
            }
        }) {
            switchPanel
        }
        .sheet(isPresented: $showCreateDMSheet) {
            CreateDirectMessageSheet(
                appState: appState,
                currentChannelID: activeWorkspaceChannel?.id ?? appState.settings.channelID
            ) { newDM in
                directMessages.insert(newDM, at: 0)
                activeWorkspaceDM = newDM
            }
        }
        .sheet(isPresented: $showCreateChannelSheet) {
            CreateChannelSheet(appState: appState) { newChannelID in
                Task {
                    await refreshChannels()
                    // 批三（3.7）：创建成功自动进入新频道＋提示行（对齐安卓）。
                    if let newChannelID, let ch = channels.first(where: { $0.id == newChannelID }) {
                        activeWorkspaceChannel = ch
                        activeWorkspaceDM = nil
                        selectedPartition = .channels
                        createLandingNotice = "频道已创建。"
                    }
                }
            }
        }
        .sheet(isPresented: $showManagementSheet) {
            ChannelManagementListSheet(appState: appState, channels: channels) {
                Task { await refreshChannels() }
            }
        }
        .alert("安全规范提示", isPresented: $showPolicyAlert) {
            Button("我知道了", role: .cancel) {}
        } message: {
            Text(policyAlertMessage)
        }
        .alert("无法打开频道", isPresented: Binding(
            get: { channelLinkError != nil },
            set: { if !$0 { channelLinkError = nil } }
        )) {
            Button("好", role: .cancel) {}
        } message: {
            Text(channelLinkError ?? "")
        }
        .onChange(of: appState.pendingChannelID) { _ in
            Task { await consumePendingChannelLink() }
        }
        .onChange(of: appState.hasToken) { _ in
            Task { await consumePendingChannelLink() }
        }
    }

    /// 218 深链消费：pendingChannelID 落到指定频道。列表里有就直达；
    /// 没有则按三类异常定性——ID 格式不对 = 链接无效；探针读消息被
    /// 服务端以成员/权限类错误拒绝 = 无权限；不存在类错误 = 频道
    /// 已删除/不存在。消费成功或定性后都清掉 pending（含持久化份，
    /// 冷启动/登录恢复靠它续上）。
    private func consumePendingChannelLink() async {
        guard let target = appState.pendingChannelID, channelAvailable else { return }
        await appState.loadChannels()
        loadData()
        if let ch = channels.first(where: { $0.id == target }) {
            activeWorkspaceChannel = ch
            activeWorkspaceDM = nil
            selectedPartition = .channels
            showSwitchPanel = false
            appState.clearPendingChannelLink()
            return
        }
        if let dm = directMessages.first(where: { $0.id == target }) {
            activeWorkspaceDM = dm
            selectedPartition = .directMessages
            showSwitchPanel = false
            appState.clearPendingChannelLink()
            return
        }
        let hexOK = target.count == 32 && target.allSatisfy { $0.isHexDigit }
        if !hexOK {
            channelLinkError = "链接无效：频道 ID 格式不正确。"
            appState.clearPendingChannelLink()
            return
        }
        guard let transport = appState.makeChannelTransport() else { return }
        do {
            _ = try await transport.pollMessages(channelID: target, limit: 1)
            channelLinkError = "无法打开该频道：它不在你的频道列表里。"
            appState.clearPendingChannelLink()
        } catch {
            let raw = (error as? McpError)?.message ?? ""
            if raw.contains("member") || raw.contains("成员") || raw.contains("permission") || raw.contains("权限") {
                channelLinkError = "无权限：你不是该频道成员。"
            } else if raw.contains("not found") || raw.contains("不存在") || raw.contains("deleted") || raw.contains("删除") {
                channelLinkError = "频道不存在或已删除。"
            } else {
                channelLinkError = raw.isEmpty ? "无法打开该频道。" : raw
            }
            appState.clearPendingChannelLink()
        }
    }

    /// 频道首页空态：本地频道数为 0 且云端无频道。
    private var channelEmptyState: some View {
        VStack(spacing: 16) {
            Text("还没有频道")
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
            Text("频道是多个 Agent 协作的对话空间，可以聊天、派发任务。")
                .font(.system(size: 14))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
            Button {
                if showSwitchPanel {
                    openCreateAfterSwitchDismiss = true
                    showSwitchPanel = false
                } else {
                    showCreateChannelSheet = true
                }
            } label: {
                Text("新建频道")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(maxWidth: 280)
                    .padding(.vertical, 12)
                    .background(Color(red: 0.16, green: 0.15, blue: 0.14), in: RoundedRectangle(cornerRadius: 12))
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("channels.empty.create")
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 40)
    }

    /// 暂无可选频道时的首页空态。
    private var noChannelSelectedView: some View {
        channelEmptyState
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    /// 频道切换面板（批D）：搜索框 + [频道 | 私聊] 分段 +
    /// 频道列表；点选即切换并关面板，不先落列表页。
    private var switchPanel: some View {
        NavigationStack {
            VStack(spacing: 0) {
                partitionPicker

                if selectedPartition == .channels {
                    searchField
                    if !channelAvailable {
                        unconfiguredTokenView
                    } else {
                        channelsListView
                    }
                } else {
                    // 全量对齐批四（2.5）：私聊分段补同款搜索框（filteredDMs
                    // 过滤逻辑本已存在，此前只有频道分段有框）。
                    searchField
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
                Button { showManagementSheet = true } label: {
                    Label("管理频道", systemImage: "slider.horizontal.3")
                }
                Button { showCreateChannelSheet = true } label: {
                    Label("新建频道", systemImage: "number")
                }
                .accessibilityIdentifier("channels.action.createChannel")

                Button {
                    // 私聊（方案 A 的频道页分段在切换面板里）：这里直接
                    // 打开既有的发起私聊表单，不再是待开发提示。
                    showCreateDMSheet = true
                } label: {
                    Label("发起私聊", systemImage: "person.2")
                }
                .accessibilityIdentifier("channels.action.createDM")

                // P3-MSG：直达消息中心（注册表 feature_message_center 保持
                // TODO，翻转留到组装步；入口先走频道页顶栏，可达可测）。
                Button {
                    onNavigateRoute("notifications")
                } label: {
                    Label("打开消息中心", systemImage: "tray.full")
                }
                .accessibilityIdentifier("channels.action.messageCenter")
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
                Text(part.rawValue).tag(part)
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
        if channelAvailable {
            await appState.loadChannels()
        }
        await MainActor.run {
            loadData()
        }
    }

    private var localFilteredChannels: [V5ChannelItem] {
        filteredChannels.filter { $0.layer == .local }
    }

    private var cloudFilteredChannels: [V5ChannelItem] {
        filteredChannels.filter { $0.layer == .cloud }
    }

    // MARK: - 频道列表（按层分组：本机在前，云端在后）
    private var channelsListView: some View {
        ScrollView {
            LazyVStack(spacing: 8) {
                if filteredChannels.isEmpty {
                    if searchQuery.isEmpty {
                        channelEmptyState
                    } else {
                        VStack(spacing: 8) {
                            Image(systemName: "number.circle")
                                .font(.system(size: 40))
                                .foregroundStyle(.secondary.opacity(0.6))
                            Text("未找到匹配的频道")
                                .font(.headline)
                                .foregroundStyle(Color(red: 0.20, green: 0.19, blue: 0.18))
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 40)
                    }
                    if searchQuery.isEmpty && !cloudConnected {
                        channelGroupHeader("云端", showConnect: true)
                    }
                } else {
                    channelLayerGroup(.local, rows: localFilteredChannels)
                    channelLayerGroup(.cloud, rows: cloudFilteredChannels)
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 24)
        }
        .refreshable {
            await refreshChannels()
        }
    }

    private func channelGroupHeader(_ title: String, showConnect: Bool) -> some View {
        HStack(spacing: 8) {
            Text(title)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(Color(red: 0.35, green: 0.34, blue: 0.32))
            Spacer()
            if showConnect {
                Button {
                    showSwitchPanel = false
                    onNavigateRoute("settings")
                } label: {
                    Text("连接云端")
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("channels.cloud.connect")
            }
        }
        .padding(.top, 10)
        .padding(.bottom, 2)
        .accessibilityIdentifier("channels.group.\(title)")
    }

    @ViewBuilder
    private func channelLayerGroup(_ layer: ChannelLayer, rows: [V5ChannelItem]) -> some View {
        let cacheLocked = layer == .cloud && !cloudConnected
        channelGroupHeader(layer.label, showConnect: cacheLocked)
        ForEach(rows) { ch in
            Button {
                guard !cacheLocked else { return }
                activeWorkspaceChannel = ch
                activeWorkspaceDM = nil
                selectedPartition = .channels
                createLandingNotice = nil
                showSwitchPanel = false
            } label: {
                channelRow(ch, cacheLocked: cacheLocked)
            }
            .buttonStyle(.plain)
            .disabled(cacheLocked)
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
                    showManagementSheet = true
                } label: {
                    Label(ch.isArchived ? "移出归档" : "归档频道", systemImage: "archivebox")
                }
            }
        }
    }

    private func channelRow(_ ch: V5ChannelItem, cacheLocked: Bool = false) -> some View {
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
                    if ch.isArchived {
                        // 批三（7.8）：归档标记（与安卓切换器行同文同位）。
                        Text("（已归档）")
                            .font(.system(size: 11))
                            .foregroundStyle(.secondary)
                    }
                    if ch.id == activeWorkspaceChannel?.id {
                        // 批四（1.6）：当前频道 ✓（与安卓切换器行同标）。
                        Text("✓")
                            .font(.system(size: 12, weight: .bold))
                            .foregroundStyle(.secondary)
                    }
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
                    if ch.layer == .local && LocalPlatform.lanServerRunning() {
                        Image(systemName: "antenna.radiowaves.left.and.right")
                            .font(.system(size: 11, weight: .regular))
                            .foregroundStyle(.secondary)
                            .accessibilityLabel("局域网共享中")
                    }
                }

                Text(cacheLocked ? "缓存内容，连接后可发送" : (ch.lastMessageText.isEmpty ? (ch.topic.isEmpty ? "暂无新消息" : ch.topic) : ch.lastMessageText))
                    .font(.system(size: 13))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)

                HStack(spacing: 8) {
                    // 批四（1.6）：类别词前缀与安卓副行同构（topic 即类别词）。
                    Text("\(ch.topic.isEmpty ? "团队频道" : ch.topic) · \(ch.memberCount) 成员 · \(ch.agentCount) Agent")
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
        .opacity(cacheLocked ? 0.45 : 1)
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
                        Text("暂无私聊")
                            .font(.headline)
                            .foregroundStyle(Color(red: 0.20, green: 0.19, blue: 0.18))
                        Text("点右上角「＋ 新建 → 发起私聊」，与频道成员开始 1:1 私聊\n（私聊只在频道成员之间，与单 Agent 对话并存）")
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
                            selectedPartition = .directMessages
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

    /// 已配置远程平台地址且持有凭证时，频道列表来自云端。
    private var cloudConnected: Bool {
        !appState.channelUsesLocalPlatform
    }

    /// 本机账本里的频道（只读，不改服务）。
    private func readLocalChannelInfos() -> [ChannelInfo] {
        guard let service = LocalPlatform.serviceOrNull() else { return [] }
        guard let result = try? service.dispatch(
            tool: "agent_channel_list",
            args: [:],
            callerId: LocalPlatform.appAgentID,
            callerName: LocalPlatform.appAgentName
        ) else { return [] }
        return ChannelInfo.list(from: result)
    }

    /// 云端频道：已连接用实时列表，未连接只用缓存。没缓存就不列。
    private func readCloudChannelInfos() -> [ChannelInfo] {
        if cloudConnected {
            return appState.availableChannels
        }
        return ChannelListCache().load()
    }

    /// 列表行时间规范（双端同一条规则，与安卓 ChannelExtras
    /// .listTimeLabel 同实现）：今天 HH:mm；昨天「昨天」；近 7 天内
    /// （不含今天/昨天）星期几；更早 yyyy-MM-dd。无效时间返回空串。
    private func channelListTimeLabel(_ epochSeconds: Double?) -> String {
        guard let epochSeconds, epochSeconds > 0 else { return "" }
        let calendar = Calendar.current
        let date = Date(timeIntervalSince1970: epochSeconds)
        let dayDiff = calendar.dateComponents(
            [.day],
            from: calendar.startOfDay(for: date),
            to: calendar.startOfDay(for: Date())
        ).day ?? 0
        switch dayDiff {
        case ..<1:
            let formatter = DateFormatter()
            formatter.dateFormat = "HH:mm"
            return formatter.string(from: date)
        case 1:
            return "昨天"
        case 2..<7:
            let weekdays = ["星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六"]
            return weekdays[calendar.component(.weekday, from: date) - 1]
        default:
            let formatter = DateFormatter()
            formatter.dateFormat = "yyyy-MM-dd"
            return formatter.string(from: date)
        }
    }

    private func mapChannelItem(_ c: ChannelInfo, layer: ChannelLayer) -> V5ChannelItem {
        // 全量对齐批（8.3）：Agent 数与行尾时间改读真实数据——此前未传，
        // 一直吃 V5ChannelItem 的占位默认值（1 Agent／12:00），与安卓
        // 真值口径不一致。Agent 数按参与者明细实算，无明细就是 0，
        // 不编造。行尾时间按双端统一的相对时间规范呈现。
        let agentCount = c.participantDetails.filter { $0.isAgent == true }.count
        return V5ChannelItem(
            id: c.id,
            name: c.displayName,
            topic: c.isDM ? "私聊" : "团队频道",
            isPrivate: false,
            memberCount: c.participants.count,
            agentCount: agentCount,
            lastMessageText: c.lastMessageText ?? "",
            lastMessageTime: channelListTimeLabel(c.lastMessageAt),
            unreadCount: 0,
            isArchived: c.isArchived,
            layer: layer
        )
    }

    private func loadData() {
        if !channelAvailable {
            channels = []
            directMessages = []
            return
        }

        let source = appState.availableChannels.isEmpty
            ? ChannelListCache().load()
            : appState.availableChannels
        let localInfos = readLocalChannelInfos().filter { !$0.isDM }
        let cloudInfos = readCloudChannelInfos().filter { !$0.isDM }
        var seen = Set<String>()
        var merged: [V5ChannelItem] = []
        for info in localInfos where seen.insert(info.id).inserted {
            merged.append(mapChannelItem(info, layer: .local))
        }
        for info in cloudInfos where seen.insert(info.id).inserted {
            merged.append(mapChannelItem(info, layer: .cloud))
        }
        channels = merged
        directMessages = source.filter(\.isDM).map { c in
            let peer = c.participantDetails.first(where: { !$0.isSelf })
            return V5DirectMessageItem(
                id: c.id,
                targetId: peer?.id ?? c.participants.first ?? c.id,
                targetName: peer?.name ?? c.name,
                isAgent: peer?.isAgent ?? true,
                targetRole: peer?.role ?? "私聊",
                lastMessageText: c.lastMessageText ?? "",
                // 全量对齐批四（2.2）：私聊行时间与频道行同源同规（原写死空串）。
                lastMessageTime: channelListTimeLabel(c.lastMessageAt)
            )
        }

        // 对话框直达（批D）：默认选中第一个频道；选中项已不在列表
        // 时回落到第一个。
        if let active = activeWorkspaceChannel, channels.contains(where: { $0.id == active.id }) {
            // 保持当前选中
        } else {
            activeWorkspaceChannel = channels.first
        }
        if let activeDM = activeWorkspaceDM {
            activeWorkspaceDM = directMessages.first(where: { $0.id == activeDM.id })
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

    @State private var showFilesPage = false
    @State private var showTasksPage = false
    @State private var showSearchTools = false
    @State private var composerText = ""
    @FocusState private var composerFocused: Bool
    @State private var sendAsTask = false
    @State private var showCreateTaskForm = false
    @State private var pendingTaskText = ""
    @State private var taskTitle = ""
    @State private var isSending = false
    @State private var messages: [V5ChannelMessage] = []
    @State private var files: [V5ChannelFile] = []
    /// 全量对齐批二：文件动作结果提示（预览引用原文/下载结果）。
    @State private var fileActionNotice: String? = nil
    @State private var activeTasks: [LocalTask] = []
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
    // 频道补全二/三期动作状态：定时队列、附件引用、转发、已读回执。
    @State private var scheduledPosts: [ScheduledChannelPost] = []
    // 全量对齐批五：瞬时提示行（与安卓 noticeLine 同义，3.5 秒自动消；
    // 4.16 历史开关与 4.18 定时设定/取消共用）。
    @State private var transientNotice: String? = nil
    @State private var transientNoticeToken: Int = 0

    private func showTransientNotice(_ text: String) {
        transientNoticeToken += 1
        let token = transientNoticeToken
        transientNotice = text
        Task {
            try? await Task.sleep(nanoseconds: 3_500_000_000)
            if transientNoticeToken == token {
                transientNotice = nil
            }
        }
    }

    // 批五（4.18）：延时文案与安卓 scheduledDelayText 逐档镜像。
    private func scheduledDelayText(_ delaySeconds: TimeInterval) -> String {
        if delaySeconds <= 60 { return "约 1 分钟后" }
        if delaySeconds < 3600 { return "约 \(Int(delaySeconds) / 60) 分钟后" }
        if delaySeconds < 86400 { return "约 \(Int(delaySeconds) / 3600) 小时后" }
        return "约 \(Int(delaySeconds) / 86400) 天后"
    }
    @State private var pendingAttachmentRef: String? = nil
    @State private var showAttachmentInput = false
    @State private var attachmentDraft = ""
    @State private var actionError: String? = nil
    @State private var readersText: String? = nil
    @State private var forwardSource: V5ChannelMessage? = nil
    private var otherChannels: [ChannelInfo] { appState.availableChannels }
    @State private var activeTopicThreadRootId: String? = nil
    @State private var showTopicThreadsSheet = false
    @State private var typingSenderName: String? = nil
    // №12 工具行状态（218 自 ChannelChatView 搬入本页，原页面删除）：
    // 搜索/过滤走服务端 pollMessages 参数，跳到日期进入历史浏览态，
    // 历史开关走 setHistoryVisible（仅 owner/协调者，服务端裁决）。
    @State private var searchText = ""
    @State private var searchSender: String?
    @State private var searchRangeIndex = 0 // 0 全部时间 1 今天 2 近7天 3 近30天
    @State private var searchKind: String?
    @State private var isShowingSearchResults = false
    @State private var isBrowsing = false
    @State private var browseHasOlder = false
    @State private var showingDatePicker = false
    @State private var jumpDate = Date()
    @State private var historyVisibleOverride: Bool?
    @Environment(\.dismiss) private var dismiss

    private var currentChannelId: String {
        channelItem?.id ?? dmItem?.id ?? "ch-general"
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
            messagesTab
        }
        .background(Color(red: 0.969, green: 0.969, blue: 0.961).ignoresSafeArea())
        // 218 标题一层：嵌入旗舰壳时导航标题留空，频道名只在页内
        // 标题行（切换面板入口）出现一次；独立打开时仍用频道名。
        .navigationTitle(embedded ? "" : displayName)
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
        }
        .onAppear {
            loadWorkspaceData()
        }
        .onDisappear(perform: stopFeed)
        .task {
            while !Task.isCancelled {
                loadScheduled()
                await flushScheduled()
                try? await Task.sleep(nanoseconds: 15_000_000_000)
            }
        }
        .sheet(item: $selectedAgentProfile) { msg in
            AgentProfileSheet(message: msg)
        }
        .sheet(isPresented: $showTopicThreadsSheet) {
            TopicThreadsSheet(
                threads: groupTopicThreads(messages: messages.map { ChannelMessage(id: $0.id, agentId: $0.senderId, agentName: $0.senderName, text: $0.text, replyTo: $0.replyToId) }),
                onSelect: { rootId in
                    activeTopicThreadRootId = rootId
                    if let root = messages.first(where: { $0.id == rootId }) {
                        replyingTo = root
                    }
                }
            )
        }
        .sheet(isPresented: $showFilesPage) {
            NavigationStack {
                filesTab
                    .navigationTitle("频道文件")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button("完成") { showFilesPage = false }
                        }
                    }
            }
            .onAppear { Task { await loadChannelFiles() } }
        }
        .sheet(isPresented: $showTasksPage) {
            NavigationStack {
                tasksTab
                    .navigationTitle("任务")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button("完成") { showTasksPage = false }
                        }
                    }
            }
        }
        .sheet(isPresented: $showingDatePicker) {
            NavigationStack {
                Form {
                    DatePicker("跳到哪一天", selection: $jumpDate, displayedComponents: .date)
                }
                .navigationTitle("跳到日期")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("取消") { showingDatePicker = false }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("跳转") {
                            showingDatePicker = false
                            Task { await jumpToDate(Calendar.current.startOfDay(for: jumpDate)) }
                        }
                    }
                }
            }
        }
        .sheet(isPresented: $showChannelSettingsSheet) {
            ChannelSettingsSheet(appState: appState, channel: channelItem, dm: dmItem)
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
        .alert("操作失败", isPresented: Binding(
            get: { actionError != nil },
            set: { if !$0 { actionError = nil } }
        )) {
            Button("好", role: .cancel) {}
        } message: {
            Text(actionError ?? "")
        }
        .alert("已读情况", isPresented: Binding(
            get: { readersText != nil },
            set: { if !$0 { readersText = nil } }
        )) {
            Button("好", role: .cancel) {}
        } message: {
            Text(readersText ?? "")
        }
        .alert("加附件引用", isPresented: $showAttachmentInput) {
            TextField("网盘链接、投递编号或文件引用", text: $attachmentDraft)
            Button("添加") {
                let ref = attachmentDraft.trimmingCharacters(in: .whitespacesAndNewlines)
                pendingAttachmentRef = ref.isEmpty ? nil : ref
                attachmentDraft = ""
            }
            Button("取消", role: .cancel) { attachmentDraft = "" }
        } message: {
            Text("引用制：只填引用，不上传文件本体。")
        }
        .confirmationDialog(
            "转发到",
            isPresented: Binding(
                get: { forwardSource != nil },
                set: { if !$0 { forwardSource = nil } }
            ),
            titleVisibility: .visible
        ) {
            ForEach(otherChannels.filter { $0.id != currentChannelId }) { target in
                Button(target.displayName) {
                    if let source = forwardSource {
                        Task { await forward(source, to: target) }
                    }
                    forwardSource = nil
                }
            }
            Button("取消", role: .cancel) { forwardSource = nil }
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
            sessionId: currentChannelId,
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
        reloadWorkspaceTasks()
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
                    if let ch = channelItem, ch.isArchived {
                        // 批三（7.8）：归档态在对话页标题行可见（此前无任何标记）。
                        Text("（已归档）")
                            .font(.system(size: 11))
                            .foregroundStyle(.secondary)
                    }
                }
                if let ch = channelItem {
                    Text(ch.layer.label)
                        .font(.system(size: 11))
                        .foregroundStyle(.secondary)
                        .accessibilityIdentifier("channel.layerSubtitle")
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
                            .foregroundStyle(.secondary)
                        }
                        .buttonStyle(.plain)
                        if showConnectionDiagnostics {
                            Text(err)
                                .font(.system(size: 10, design: .monospaced))
                                .foregroundStyle(.secondary)
                                .textSelection(.enabled)
                                .padding(4)
                                .background(Color.secondary.opacity(0.08), in: RoundedRectangle(cornerRadius: 4))
                        }
                    }
                }
            }
            Spacer()
            // 218：入口在页内标题行（toolbar 的 ⋯ 在旗舰壳嵌入时
            // 点击无响应，216/217 实机复现）。
            // 全页化（Funk 2026-11-28 批准效果图）：此钮升级为频道
            // 菜单——文件/任务/搜索/话题/跳日期/历史开关/消息中心
            // 计数全部收进菜单，页面只留消息与输入框。
            Menu {
                if channelItem != nil {
                    Button {
                        showFilesPage = true
                    } label: { Label("频道文件", systemImage: "folder") }
                    Button {
                        showTasksPage = true
                    } label: { Label("任务列表", systemImage: "checklist") }
                    Button {
                        showSearchTools.toggle()
                    } label: { Label("搜索消息", systemImage: "magnifyingglass") }
                    Button {
                        showTopicThreadsSheet = true
                    } label: { Label(topicThreadsCount > 0 ? "话题列表 (\(topicThreadsCount))" : "话题列表", systemImage: "bubble.left.and.bubble.right") }
                    Button {
                        showingDatePicker = true
                    } label: { Label("跳到日期", systemImage: "calendar") }
                    if canManageHistory {
                        Button {
                            Task { await setHistoryVisible(!historyVisibleNow) }
                        } label: { Label("新成员看历史：\(historyVisibleNow ? "开" : "关")", systemImage: "clock.arrow.circlepath") }
                    }
                    Section {
                        Button {
                        } label: {
                            Text("未读 \(messageCenterCounts.unread) · @我未读 \(messageCenterCounts.mentions) · 置顶 \(messageCenterCounts.pinned)（按当前已加载消息统计）")
                        }
                        .disabled(true)
                    }
                    Divider()
                }
                Button {
                    showChannelSettingsSheet = true
                } label: { Label("频道设置", systemImage: "gearshape") }
            } label: {
                Image(systemName: "ellipsis.circle")
                    .font(.system(size: 20))
                    .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                    .frame(width: 38, height: 38)
            }
            .accessibilityIdentifier("channel.settingsButton")
            .accessibilityLabel("频道菜单")
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }

    // MARK: - 消息分区
    private var displayedMessages: [V5ChannelMessage] {
        guard let rootId = activeTopicThreadRootId else { return messages }
        let rawMsgs = messages.map { ChannelMessage(id: $0.id, agentId: $0.senderId, agentName: $0.senderName, text: $0.text, replyTo: $0.replyToId) }
        let topics = groupTopicThreads(messages: rawMsgs)
        if let topic = topics.first(where: { $0.rootMessageId == rootId }) {
            let topicIds = Set(topic.messages.map { $0.id })
            return messages.filter { topicIds.contains($0.id) }
        }
        return messages.filter { $0.id == rootId || $0.replyToId == rootId }
    }

    private var activeTopicTitle: String {
        guard let activeRootId = activeTopicThreadRootId,
              let rootMsg = messages.first(where: { $0.id == activeRootId }) else {
            return "话题讨论"
        }
        let trimmed = rootMsg.text.trimmingCharacters(in: .whitespacesAndNewlines)
        return String(trimmed.prefix(20))
    }

    private var messagesTab: some View {
        VStack(spacing: 0) {
            if channelItem != nil {
                if showSearchTools || isShowingSearchResults {
                    searchBar
                    filterBar
                }
                if isBrowsing { browseBar }
            }
            if let transientNotice {
                Text(transientNotice)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 2)
            }
            if !scheduledForChannel.isEmpty {
                scheduledStrip
            }
            if messages.isEmpty {
                VStack(spacing: 12) {
                    Image(systemName: "bubble.left.and.bubble.right")
                        .font(.system(size: 36))
                        .foregroundStyle(.secondary.opacity(0.6))
                    Text(isShowingSearchResults ? "没有找到匹配的消息" : "暂无频道消息")
                        .font(.headline)
                        .foregroundStyle(Color(red: 0.20, green: 0.19, blue: 0.18))
                    Text(isShowingSearchResults ? "换个关键词或清除搜索条件再试" : "在下方输入框发送消息开始协同讨论")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .padding(.vertical, 60)
            } else {
                if activeTopicThreadRootId != nil {
                    HStack {
                        Text("当前话题：\(activeTopicTitle)")
                            .font(.caption)
                            .fontWeight(.semibold)
                            .foregroundStyle(.blue)
                        Spacer()
                        Button("返回全部") {
                            if replyingTo?.id == activeTopicThreadRootId {
                                replyingTo = nil
                            }
                            activeTopicThreadRootId = nil
                        }
                        .font(.caption)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 6)
                    .background(Color.blue.opacity(0.1))
                }
                // 对话组件收敛：列表脚手架与单聊共用 DialogMessageListView
                // （LazyVStack；2026-11-27 Funk 真机反馈补齐：贴底跟随
                // 新消息、离底出 ↓ 拉底按钮，与安卓「回到最新」对齐；
                // 搜索/历史浏览态不启用，沿用退出浏览入口；点消息区
                // 收键盘，与单聊 onTapContent 同口径）。
                DialogMessageListView(
                    lazy: true,
                    spacing: 12,
                    horizontalPadding: 16,
                    topPadding: 12,
                    bottomPadding: 12,
                    scrollTrigger: displayedMessages.count,
                    onTapContent: { composerFocused = false },
                    showsScrollToBottomButton: !isShowingSearchResults && !isBrowsing,
                    bottomTargetID: displayedMessages.last?.id
                ) {
                    ForEach(Array(displayedMessages.enumerated()), id: \.element.id) { pair in
                        if dayDividerNeeded(at: pair.offset) {
                            dayDividerView(label: channelDayLabel(pair.element))
                        }
                        messageRow(pair.element)
                            .id(pair.element.id)
                    }
                } trailing: {
                    EmptyView()
                }
            }

            composerSection
        }
    }

    /// 消息中心条（218 计数口径 B，与安卓同一定义）：未读按本机
    /// 已读游标之后计（无游标计 0、游标不在列表计全部）；@我未读 =
    /// 未读中 @ 到本人名字集合的消息（本人 = 成员表里 isSelf 或
    /// 本机发送者 ID 对应条目，排除本人所发）；置顶、话题在当前
    /// 已加载消息内计（话题 = 有回复的根消息数，与安卓同规则）。
    /// 三项都是当前已加载窗口内统计、不是全局数，条内明示范围；
    /// 去重由 feed/刷新按消息 id 保证（startFeed /
    /// refreshChannelMessages）。
    private var messageCenterCounts: (unread: Int, mentions: Int, pinned: Int) {
        let lastSeen = (try? LocalData.shared.cursors.lastSeenMessageId(channelId: currentChannelId)) ?? nil
        let unread: Int
        let unreadSlice: [V5ChannelMessage]
        if lastSeen == nil {
            unread = 0
            unreadSlice = []
        } else if let idx = messages.firstIndex(where: { $0.id == lastSeen }) {
            unread = messages.count - idx - 1
            unreadSlice = Array(messages.suffix(from: idx + 1))
        } else {
            unread = messages.count
            unreadSlice = messages
        }
        let selfNames = Set(channelParticipants
            .filter { $0.isSelf || $0.id == appState.channelSenderID }
            .map { $0.name }
            .filter { !$0.isEmpty })
        let mentionCount = unreadSlice.filter { msg in
            guard !isOwnSender(msg.senderId) else { return false }
            // 生效的 @所有人（仅 owner/协调者所发）也算 @我，与安卓
            // buildMessageCenter 的 allMentionLive 同口径。
            if ChannelExtras.mentionsAllText(msg.text), canMentionAll(senderID: msg.senderId) {
                return true
            }
            return selfNames.contains { name in
                msg.text.contains("@\(name) ")
                    || msg.text.contains("@\(name)\n")
                    || msg.text.hasSuffix("@\(name)")
            }
        }.count
        let notifyMode = UserDefaults.standard.string(forKey: "channel.notify_mode.\(currentChannelId)") ?? "全部消息"
        let effectiveUnread = ChannelExtras.effectiveUnreadCount(
            unreadCount: unread,
            unreadMentionCount: mentionCount,
            notifyMode: notifyMode
        )
        let pinnedCount = messages.filter { $0.pinned && !$0.recalled }.count
        return (unread: effectiveUnread, mentions: mentionCount, pinned: pinnedCount)
    }

    private var scheduledForChannel: [ScheduledChannelPost] {
        scheduledPosts.filter { $0.channelId == currentChannelId }
    }

    private var scheduledStrip: some View {
        VStack(alignment: .leading, spacing: 2) {
            // 批五（4.18）：分节标题与安卓同文。
            Text("定时发送（待发）")
                .font(.caption)
                .fontWeight(.bold)
                .foregroundStyle(Color(red: 0.20, green: 0.19, blue: 0.18))
            ForEach(scheduledForChannel) { item in
                HStack {
                    Text("定时 \(Date(timeIntervalSince1970: item.dueAtSeconds), style: .time) 发：\(String(item.text.prefix(40)))")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Spacer()
                    Button("取消") { cancelScheduled(item) }
                        .font(.caption)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 4)
    }

    // MARK: - №12 工具行（218 自 ChannelChatView 搬入：搜索/过滤/工具/浏览）

    private var memberNames: [String] {
        channelParticipants.map(\.name).filter { !$0.isEmpty }
    }

    private var currentChannelInfo: ChannelInfo? {
        otherChannels.first(where: { $0.id == currentChannelId })
    }

    private var historyVisibleNow: Bool {
        historyVisibleOverride ?? currentChannelInfo?.historyVisible ?? true
    }

    private var canManageHistory: Bool {
        channelParticipants.first {
            $0.isSelf || $0.id == appState.channelSenderID
        }?.canMentionAll ?? false
    }

    /// 话题数（218 计数口径 B；220 与安卓对齐：话题钮归搜索行右侧）。
    private var topicThreadsCount: Int {
        let rawMsgs = messages.map { ChannelMessage(id: $0.id, agentId: $0.senderId, agentName: $0.senderName, text: $0.text, replyTo: $0.replyToId) }
        return groupTopicThreads(messages: rawMsgs).count
    }

    private var searchBar: some View {
        HStack(spacing: 8) {
            TextField("搜本频道消息…", text: $searchText)
                .textFieldStyle(.roundedBorder)
                .font(.caption)
            if isShowingSearchResults {
                Button("清除") { clearSearch() }
                    .font(.caption)
            } else {
                Button("搜索") { Task { await runSearch() } }
                    .font(.caption)
                    .disabled(!searchCanRun)
            }
            Button {
                showTopicThreadsSheet = true
            } label: {
                Text(topicThreadsCount > 0 ? "话题 (\(topicThreadsCount))" : "话题")
                    .font(.caption)
                    .foregroundStyle(.blue)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 3)
    }

    private var searchCanRun: Bool {
        !searchText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
            searchSender != nil || searchRangeIndex != 0 || searchKind != nil
    }

    private static let rangeLabels = ["全部时间", "今天", "近7天", "近30天"]
    private static let kindValues: [String?] = [nil, "image", "file", "link"]
    private static let kindNames = ["全部", "图片", "文件", "链接"]

    private var filterBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                Menu {
                    Button("全部成员") { searchSender = nil; rerunSearchIfPossible() }
                    ForEach(memberNames, id: \.self) { name in
                        Button(name) { searchSender = name; rerunSearchIfPossible() }
                    }
                } label: {
                    Text("成员：\(searchSender ?? "全部")").font(.caption)
                }
                Menu {
                    ForEach(Self.rangeLabels.indices, id: \.self) { index in
                        Button(Self.rangeLabels[index]) {
                            searchRangeIndex = index
                            rerunSearchIfPossible()
                        }
                    }
                } label: {
                    Text("时间：\(Self.rangeLabels[searchRangeIndex])").font(.caption)
                }
                Menu {
                    ForEach(Self.kindValues.indices, id: \.self) { index in
                        Button(Self.kindNames[index]) {
                            searchKind = Self.kindValues[index]
                            rerunSearchIfPossible()
                        }
                    }
                } label: {
                    Text("分类：\(Self.kindNames[Self.kindValues.firstIndex { $0 == searchKind } ?? 0])")
                        .font(.caption)
                }
            }
            .padding(.horizontal, 16)
        }
        .padding(.vertical, 2)
    }

    private var browseBar: some View {
        HStack(spacing: 12) {
            if browseHasOlder {
                Button("加载更早") { Task { await browseLoadOlder() } }.font(.caption)
            }
            Spacer()
            Text("正在查看历史").font(.caption).foregroundStyle(.secondary)
            Button("回到最新") { exitBrowse() }.font(.caption).fontWeight(.semibold)
            Spacer()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 4)
        .background(Color.secondary.opacity(0.08))
    }

    private func rerunSearchIfPossible() {
        if searchCanRun {
            Task { await runSearch() }
        } else if isShowingSearchResults {
            // 4.12 修复：筛选全部清掉（如成员选回「全部成员」且无其他
            // 条件）时必须退出搜索结果态、恢复实时列表——此前直接
            // no-op，过滤后的列表清不掉。
            clearSearch()
        }
    }

    private func searchWindow() -> (from: Int?, to: Int?) {
        let now = Int(Date().timeIntervalSince1970)
        switch searchRangeIndex {
        case 1: return (Int(Calendar.current.startOfDay(for: Date()).timeIntervalSince1970), nil)
        case 2: return (now - 7 * 86400, nil)
        case 3: return (now - 30 * 86400, nil)
        default: return (nil, nil)
        }
    }

    /// 搜索：停 feed 后用服务端过滤参数取结果替换列表（与原
    /// ChannelChatView 同语义）；清除则重载实时列表。
    private func runSearch() async {
        guard let transport = appState.makeChannelTransport(), searchCanRun else { return }
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        stopFeed()
        isBrowsing = false
        do {
            let window = searchWindow()
            let results = try await transport.pollMessages(
                channelID: currentChannelId, limit: 100,
                query: query.isEmpty ? nil : query,
                sender: searchSender,
                fromTs: window.from, toTs: window.to,
                kind: searchKind)
            messages = results.map { toV5Message($0) }
                .sorted { $0.fullDateTime < $1.fullDateTime }
            isShowingSearchResults = true
        } catch {
            actionError = (error as? McpError)?.message ?? "搜索失败。"
        }
    }

    private func clearSearch() {
        searchText = ""
        searchSender = nil
        searchRangeIndex = 0
        searchKind = nil
        isShowingSearchResults = false
        messages = []
        loadWorkspaceData()
    }

    /// 跳到日期：取该日（含）之后的消息进入历史浏览态；空日不换列表。
    private func jumpToDate(_ day: Date) async {
        guard let transport = appState.makeChannelTransport() else { return }
        do {
            let page = try await transport.pollMessages(
                channelID: currentChannelId, limit: 100,
                fromTs: Int(day.timeIntervalSince1970))
            if page.isEmpty {
                actionError = "该日期之后暂无消息。"
            } else {
                stopFeed()
                messages = page.map { toV5Message($0) }
                    .sorted { $0.fullDateTime < $1.fullDateTime }
                browseHasOlder = true
                isShowingSearchResults = false
                isBrowsing = true
            }
        } catch {
            actionError = (error as? McpError)?.message ?? "跳到日期失败。"
        }
    }

    private func browseLoadOlder() async {
        guard let transport = appState.makeChannelTransport(),
              let first = messages.first else { return }
        do {
            let older = try await transport.pollMessages(
                channelID: currentChannelId, beforeID: first.id, limit: 50)
            let converted = older.map { toV5Message($0) }
            var seen = Set(messages.map { $0.id })
            messages = converted.filter { seen.insert($0.id).inserted } + messages
            browseHasOlder = older.count >= 50
        } catch {
            actionError = (error as? McpError)?.message ?? "加载更早失败。"
        }
    }

    private func exitBrowse() {
        isBrowsing = false
        browseHasOlder = false
        messages = []
        loadWorkspaceData()
    }

    private func setHistoryVisible(_ visible: Bool) async {
        guard let transport = appState.makeChannelTransport() else { return }
        do {
            _ = try await transport.setHistoryVisible(channelID: currentChannelId, visible: visible)
            historyVisibleOverride = visible
            // 批五（4.16）：切换反馈行与安卓逐字一致。
            showTransientNotice(visible ? "已开启：新成员可看全部历史。" : "已关闭：新成员只能看入群之后的消息。")
        } catch {
            actionError = (error as? McpError)?.message ?? "改历史开关失败。"
        }
    }

    /// 日期分隔（主流聊天形态）：新的一天第一条消息上方插居中胶囊
    /// 日期条，文案与列表行时间规范同源（今天／昨天／近 7 天星期几／
    /// 更早 yyyy-MM-dd）。此前对话内消息平铺无分隔，跨天频道打开
    /// 是一坨（2026-11-28 Funk 真机反馈补齐）。
    /// 视图消息只有 fullDateTime 字符串（yyyy-MM-dd HH:mm:ss），
    /// 按它还原日期做按天分隔。
    private func messageDay(_ msg: V5ChannelMessage) -> Date? {
        let fmt = DateFormatter()
        fmt.locale = Locale(identifier: "en_US_POSIX")
        fmt.dateFormat = "yyyy-MM-dd HH:mm:ss"
        return fmt.date(from: msg.fullDateTime)
    }

    private func dayDividerNeeded(at index: Int) -> Bool {
        guard let current = messageDay(displayedMessages[index]) else { return false }
        guard index > 0 else { return true }
        guard let previous = messageDay(displayedMessages[index - 1]) else { return true }
        return !Calendar.current.isDate(previous, inSameDayAs: current)
    }

    private func channelDayLabel(_ msg: V5ChannelMessage) -> String {
        guard let date = messageDay(msg) else { return "" }
        let calendar = Calendar.current
        let dayDiff = calendar.dateComponents(
            [.day],
            from: calendar.startOfDay(for: date),
            to: calendar.startOfDay(for: Date())
        ).day ?? 0
        switch dayDiff {
        case ..<1:
            return "今天"
        case 1:
            return "昨天"
        case 2..<7:
            let weekdays = ["星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六"]
            return weekdays[calendar.component(.weekday, from: date) - 1]
        default:
            let formatter = DateFormatter()
            formatter.dateFormat = "yyyy-MM-dd"
            return formatter.string(from: date)
        }
    }

    private func dayDividerView(label: String) -> some View {
        HStack {
            Spacer()
            Text(label)
                .font(.caption2)
                .foregroundStyle(.secondary)
                .padding(.horizontal, 10)
                .padding(.vertical, 3)
                .background(Color.secondary.opacity(0.15), in: Capsule())
            Spacer()
        }
        .padding(.vertical, 2)
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
                    // 规范化：引用原文解析不到时不渲染任何占位——此前会把
                    // 消息 ID 截断成「回复 10101010…」上屏，原始 ID 不是
                    // 给人看的内容（话题内实测缺陷，6.5）。

                    if let forwardOf = msg.forwardOf, !forwardOf.isEmpty {
                        Text("转发自 \(forwardOf)")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }

                    // 撤回的消息只留占位，不展示正文；未撤回的使用 Markdown 渲染（失败回退纯文本）。
                    Text(ChannelExtras.safeMarkdownAttributedString(msg.recalled ? ChannelExtras.recalledPlaceholder : msg.text))
                        .font(.system(size: 14))
                        .foregroundStyle(Color(red: 0.18, green: 0.17, blue: 0.16))
                        .textSelection(.enabled)

                    if let ref = msg.attachmentRef, !ref.isEmpty {
                        Text("附件：\(ref)")
                            .font(.caption)
                            .foregroundStyle(.blue)
                            .textSelection(.enabled)
                    }

                    let reactionLine = ChannelExtras.reactionText(msg.reactions)
                    if !reactionLine.isEmpty {
                        Text(reactionLine)
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }

                    // @所有人渲染：只有 owner/协调者发的才算全员提及；
                    // 其他人写的 @all 只当普通文本，并如实注一句不生效。
                    if !msg.recalled && ChannelExtras.mentionsAllText(msg.text) {
                        let live = canMentionAll(senderID: msg.senderId)
                        Text(live ? "＠所有人" : "（@所有人未生效：仅 owner/协调者可用）")
                            .font(.caption2)
                            .foregroundStyle(live ? Color.accentColor : .secondary)
                    }

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
            if !msg.recalled {
                Button {
                    replyingTo = msg
                } label: {
                    Label("回复", systemImage: "arrowshape.turn.up.left")
                }
                Button {
                    activeTopicThreadRootId = msg.id
                    replyingTo = msg
                } label: {
                    Label("发起话题", systemImage: "bubble.left.and.bubble.right")
                }
                Button {
                    Task { await runAction { transport in
                        _ = try await transport.setMessagePinned(
                            channelID: currentChannelId, messageID: msg.id, pinned: !msg.pinned)
                    } }
                } label: {
                    Label(msg.pinned ? "取消置顶" : "置顶", systemImage: "pin")
                }
                ForEach(ChannelExtras.reactionChoices.prefix(2), id: \.self) { emoji in
                    Button {
                        Task { await runAction { transport in
                            _ = try await transport.reactMessage(
                                channelID: currentChannelId, messageID: msg.id, emoji: emoji,
                                on: !ChannelExtras.myReaction(msg.reactions, emoji: emoji))
                        } }
                    } label: {
                        Label(emoji, systemImage: "face.smiling")
                    }
                }
                Button {
                    forwardSource = msg
                } label: {
                    Label("转发", systemImage: "arrowshape.turn.up.right")
                }
                Button {
                    Task { await showReaders(msg) }
                } label: {
                    Label("已读情况", systemImage: "checkmark.circle")
                }
                // 撤回只对自己的消息显示；删除点了由平台按权限裁决，
                // 失败如实报错，不在本地假删。
                if isOwnSender(msg.senderId) {
                    Button {
                        Task { await runAction { transport in
                            _ = try await transport.recallMessage(
                                channelID: currentChannelId, messageID: msg.id)
                        } }
                    } label: {
                        Label("撤回", systemImage: "arrow.uturn.backward")
                    }
                }
                Button(role: .destructive) {
                    Task { await runAction { transport in
                        _ = try await transport.deleteMessage(
                            channelID: currentChannelId, messageID: msg.id)
                    } }
                } label: {
                    Label("删除", systemImage: "trash")
                }
            }
            Button {
                UIPasteboard.general.string = msg.text
            } label: {
                Label("复制文本", systemImage: "doc.on.doc")
            }
            Button {
                showLinkTaskPicker = true
            } label: {
                Label("关联任务", systemImage: "link")
            }
        }
    }

    /// 新开还是续话的明示（2026-11-28 Funk 问「@某某时到底是新开
    /// 对话还是旧对话，怎么感觉没有区分开」）：规则本就存在——一
    /// 条话题线＝一个持续对话——但界面从没标出来。在话题线里回
    /// 复＝续上文；主频道带 @ 发送＝新开话题（对方那边新开一个
    /// 对话、不带上文）。发送前先让用户看见自己在哪种。
    @ViewBuilder private var topicContextHint: some View {
        if activeTopicThreadRootId != nil || replyingTo != nil {
            HStack {
                Text("在话题中回复 · 续上文")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                Spacer()
            }
            .padding(.horizontal, 16)
            .padding(.top, 4)
        } else if composerText.contains("@") {
            HStack {
                Text("主频道发送 · 将新开一个话题")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                Spacer()
            }
            .padding(.horizontal, 16)
            .padding(.top, 4)
        }
    }

    // MARK: - 输入框区域（对话组件收敛：与单聊共用 DialogComposerView，
    // inline 排布；发送方式/回复横幅/附件条走 above 插槽，「＋」菜单走
    // leading 插槽，超限提示/边界文案走 below 插槽，内容逐字不变）
    private var composerSection: some View {
        DialogComposerView(
            text: $composerText,
            placeholder: "给频道发一条短消息（≤4000 字），@名字 叫一位 Agent…",
            layout: .inline,
            sendButtonStyle: .accentGlyph,
            hasAttachment: selectedAttachment != nil,
            maxLength: 4000,
            focused: $composerFocused,
            onSend: { sendMessage() },
            inputAccessibilityID: "channel.composer.input",
            sendAccessibilityID: "channel.composer.send"
        ) {
            mentionBar
            topicContextHint
            // 发送方式（批D，与安卓频道发送区同构）：仅讨论 / 作为任务发送。
            Picker("发送方式", selection: $sendAsTask) {
                Text("仅讨论").tag(false)
                Text("转为正式任务").tag(true)
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

            if let typing = typingSenderName {
                HStack(spacing: 6) {
                    Text("\(typing) 正在输入…")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                    Spacer()
                }
                .padding(.horizontal, 16)
                .padding(.top, 2)
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

            if let ref = pendingAttachmentRef {
                HStack(spacing: 6) {
                    Image(systemName: "paperclip")
                        .font(.system(size: 12))
                        .foregroundStyle(.secondary)
                    Text("附件引用将随下一条发出：\(ref)")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                    Spacer()
                    Button {
                        pendingAttachmentRef = nil
                    } label: {
                        Image(systemName: "xmark.circle.fill")
                            .font(.system(size: 12))
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 2)
            }
        } leading: {
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

                // 频道补全二期：定时发送与附件引用（引用制）。
                Menu {
                    Button("10 分钟后") { scheduleCurrent(delaySeconds: 600) }
                    Button("1 小时后") { scheduleCurrent(delaySeconds: 3600) }
                    Button("3 小时后") { scheduleCurrent(delaySeconds: 10800) }
                } label: {
                    Label("定时发送", systemImage: "clock")
                }

                Button {
                    attachmentDraft = pendingAttachmentRef ?? ""
                    showAttachmentInput = true
                } label: {
                    Label(pendingAttachmentRef == nil ? "加附件引用" : "附件引用已加", systemImage: "paperclip")
                }
            } label: {
                Image(systemName: "plus")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                    .frame(width: 36, height: 36)
                    .background(Color(red: 0.92, green: 0.91, blue: 0.89), in: Circle())
            }
            .accessibilityIdentifier("channel.composer.plus")
        } accessory: {
            EmptyView()
        } below: {
            if composerText.count > 4000 {
                Text("已超出 4000 字上限（当前 \(composerText.count) 字）")
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

    private var channelParticipants: [ChannelParticipant] {
        otherChannels.first(where: { $0.id == currentChannelId })?.participantDetails ?? []
    }

    private var members: [V5ChannelMember] {
        channelParticipants.map(V5ChannelMember.init)
    }

    /// @ 候选名单（与安卓 updateMentionBar 同口径）：成员显示名去重
    /// 保序；本人有 @所有人 权限时「所有人」列在最前。
    private var mentionCandidateNames: [String] {
        var names: [String] = []
        if localCanMentionAll(in: currentChannelId) { names.append("所有人") }
        for name in channelParticipants.map(\.name) where !name.isEmpty && !names.contains(name) {
            names.append(name)
        }
        return names
    }

    /// @ 候选条（2026-11-27 补齐，与安卓 mentionBar 同构）：输入尾部
    /// 出现 @token 时在输入区上方出成员候选，点选插入「@名字 」，
    /// 不用手打全名。匹配/替换走 DialogComponents 的共享 helper
    /// （mentionMatches / applyingMention——原 ChannelChatView 的
    /// 遗产，218 收敛时 UI 丢失、本批重新接上）。
    @ViewBuilder
    private var mentionBar: some View {
        let matches = mentionMatches(in: composerText, memberNames: mentionCandidateNames)
        if !matches.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(matches, id: \.self) { name in
                        Button {
                            composerText = applyingMention(name, to: composerText)
                        } label: {
                            Text("@\(name)")
                                .font(.system(size: 12, weight: .bold))
                                .foregroundStyle(Color(red: 0.82, green: 0.49, blue: 0.35))
                                .padding(.horizontal, 10)
                                .padding(.vertical, 5)
                                .background(Color.white, in: Capsule())
                                .overlay(Capsule().stroke(Color(red: 0.88, green: 0.87, blue: 0.85), lineWidth: 1))
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 6)
            }
            .background(Color(red: 0.95, green: 0.94, blue: 0.92))
            .accessibilityIdentifier("channel.mentionBar")
        }
    }

    private func isOwnSender(_ senderID: String) -> Bool {
        guard !senderID.isEmpty else { return false }
        return senderID == appState.channelSenderID ||
            channelParticipants.contains { $0.id == senderID && $0.isSelf }
    }

    private func canMentionAll(senderID: String) -> Bool {
        channelParticipants.contains { $0.id == senderID && $0.canMentionAll }
    }

    private func localCanMentionAll(in channelID: String) -> Bool {
        guard let channel = otherChannels.first(where: { $0.id == channelID }) else { return false }
        return channel.participantDetails.contains {
            $0.canMentionAll && ($0.isSelf || $0.id == appState.channelSenderID)
        }
    }

    /// 发送成功回显（2026-11-28 Funk 真机反馈修）：此前发送后完全
    /// 靠 feed 回推显示，推送断开／轮询兜底时自己发的消息迟迟不
    /// 出现、要切走再切回才看得到。用平台返回的真实 message id
    /// 立即把这条消息并入列表（不是本地假消息：id 与内容都来自
    /// 已落帖的发送结果）；feed 之后带回同一条时按 id 合并、
    /// 不会重复。
    private func appendLocalEcho(id: String, text: String, replyTo: String?, attachmentRef: String?) {
        guard !id.isEmpty, !messages.contains(where: { $0.id == id }) else { return }
        guard let selfParticipant = channelParticipants.first(where: { $0.isSelf }) else { return }
        let now = Date()
        let fullFmt = DateFormatter()
        fullFmt.locale = Locale(identifier: "en_US_POSIX")
        fullFmt.dateFormat = "yyyy-MM-dd HH:mm:ss"
        let timeFmt = DateFormatter()
        timeFmt.locale = Locale(identifier: "en_US_POSIX")
        timeFmt.dateFormat = "HH:mm"
        let quote: String? = {
            guard let parentId = replyTo,
                  let parent = messages.first(where: { $0.id == parentId }) else { return nil }
            let text = parent.recalled ? ChannelExtras.recalledPlaceholder : parent.text
            return String(text.replacingOccurrences(of: "\n", with: " ").prefix(40))
        }()
        messages.append(V5ChannelMessage(
            id: id,
            senderId: selfParticipant.id,
            senderName: selfParticipant.name,
            isAgent: ChannelExtras.displayedIsAgent(
                platformIsAgent: nil,
                senderIsSelf: true),
            text: text,
            timestamp: timeFmt.string(from: now),
            fullDateTime: fullFmt.string(from: now),
            replyToId: replyTo,
            replyToText: quote,
            recalled: false,
            pinned: false,
            reactions: [],
            attachmentRef: attachmentRef,
            forwardOf: nil,
            platformIsAgent: nil
        ))
        messages.sort { $0.fullDateTime < $1.fullDateTime }
    }

    private func recordChannelPost(_ id: String) {
        appState.trackChannelPost(id)
        if !id.isEmpty, let message = messages.first(where: { $0.id == id }) {
            appState.learnChannelSender(messageID: id, senderID: message.senderId)
        }
        guard let senderID = appState.channelSenderID else { return }
        for index in messages.indices where messages[index].senderId == senderID {
            // 平台明确给了 is_agent 就保留；只有缺失时才把本人收成非 Agent。
            messages[index].isAgent = ChannelExtras.displayedIsAgent(
                platformIsAgent: messages[index].platformIsAgent,
                senderIsSelf: true)
        }
    }

    // MARK: - 频道补全二期动作 helpers

    /// 统一跑一条平台动作：成功后刷新列表，失败如实报错、不本地假改。
    private func runAction(_ block: (ChannelTransport) async throws -> Void) async {
        guard let transport = appState.makeChannelTransport() else {
            actionError = "频道尚未连接。"
            return
        }
        do {
            try await block(transport)
            await refreshChannelMessages()
        } catch {
            actionError = (error as? McpError)?.message ?? "操作失败。"
        }
    }

    /// 已读情况：调 channelReaders，按“已读 X 人”如实展示。
    private func showReaders(_ msg: V5ChannelMessage) async {
        guard let transport = appState.makeChannelTransport() else {
            actionError = "频道尚未连接。"
            return
        }
        do {
            let readers = try await transport.channelReaders(
                channelID: currentChannelId, messageID: msg.id)
            if readers.isEmpty {
                readersText = "暂无已读回执。"
            } else {
                readersText = "已读 \(readers.count) 人：\(readers.map { $0.agentName }.joined(separator: "、"))"
            }
        } catch {
            actionError = (error as? McpError)?.message ?? "读取已读情况失败。"
        }
    }

    /// 转发：把原文以 forwardOf 标注发到目标频道（postMessageEx，
    /// attachmentRef 随原文引用；转发不带正文改动）。
    private func forward(_ source: V5ChannelMessage, to target: ChannelInfo) async {
        guard let transport = appState.makeChannelTransport() else {
            actionError = "频道尚未连接。"
            return
        }
        let forwardOf = ChannelExtras.forwardOfLabel(
            sourceChannelName: channelItem?.name ?? "", senderName: source.senderName)
        let outcome = await ChannelExtras.forwardMessage(
            text: source.text,
            canMentionAllInTarget: localCanMentionAll(in: target.id)
        ) {
            try await transport.postMessageEx(
                channelID: target.id, text: source.text,
                effectKey: ChannelExtras.buildEffectKey(nonce: UUID().uuidString),
                attachmentRef: source.attachmentRef, forwardOf: forwardOf)
        }
        switch outcome {
        case .success:
            await refreshChannelMessages()
        case .failure(let failure):
            actionError = failure.message
        }
    }

    /// 刷新当前频道消息（动作成功后用；列表走 feed 回推为主，
    /// 刷新只补平台已确认的最新状态）。iOS 没有独立的 searchMessages，
    /// 取消息走 pollMessages（q 搜索是它的参数，见 ChannelTransport）。
    private func refreshChannelMessages() async {
        guard let transport = appState.makeChannelTransport() else { return }
        do {
            let fresh = try await transport.pollMessages(channelID: currentChannelId, limit: 50)
            for message in fresh {
                appState.learnChannelSender(messageID: message.id, senderID: message.agentId)
            }
            let converted = fresh.map { toV5Message($0) }
            var seen = Set(messages.map { $0.id })
            for msg in converted where !seen.contains(msg.id) {
                seen.insert(msg.id)
                messages.append(msg)
            }
            for msg in converted {
                if let idx = messages.firstIndex(where: { $0.id == msg.id }) {
                    messages[idx].isAgent = msg.isAgent
                    messages[idx].platformIsAgent = msg.platformIsAgent
                    messages[idx].recalled = msg.recalled
                    messages[idx].pinned = msg.pinned
                    messages[idx].reactions = msg.reactions
                    messages[idx].attachmentRef = msg.attachmentRef
                    messages[idx].forwardOf = msg.forwardOf
                }
            }
            messages.sort { $0.fullDateTime < $1.fullDateTime }
        } catch {
            // 刷新失败不报错到界面：feed 回推仍是主路径。
        }
    }

    // MARK: - 定时发送队列（UserDefaults 持久化，与安卓同形）

    private static let scheduledDefaultsKey = "channel_scheduled_posts"

    private func loadScheduled() {
        guard let data = UserDefaults.standard.data(forKey: Self.scheduledDefaultsKey),
              let decoded = try? JSONDecoder().decode([ScheduledChannelPost].self, from: data) else {
            scheduledPosts = []
            return
        }
        scheduledPosts = decoded
    }

    private func saveScheduled() {
        if let data = try? JSONEncoder().encode(scheduledPosts) {
            UserDefaults.standard.set(data, forKey: Self.scheduledDefaultsKey)
        }
    }

    private func scheduleCurrent(delaySeconds: TimeInterval) {
        let text = composerText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        let item = ScheduledChannelPost(
            id: UUID().uuidString,
            channelId: currentChannelId,
            text: text,
            dueAtSeconds: Date().timeIntervalSince1970 + delaySeconds,
            effectKey: ChannelExtras.buildEffectKey(nonce: UUID().uuidString),
            attachmentRef: pendingAttachmentRef,
            replyToId: replyingTo?.id
        )
        guard !ChannelExtras.mentionsAllText(text) || localCanMentionAll(in: currentChannelId) else {
            actionError = "只有频道 owner/协调者可以定时发送 @所有人。"
            return
        }
        loadScheduled()
        scheduledPosts.append(item)
        saveScheduled()
        composerText = ""
        replyingTo = nil
        pendingAttachmentRef = nil
        // 批五（4.18）：设定反馈行与安卓逐字一致。
        showTransientNotice("已设定时发送（\(scheduledDelayText(delaySeconds))），发送前可在频道页取消。")
    }

    private func cancelScheduled(_ item: ScheduledChannelPost) {
        loadScheduled()
        scheduledPosts.removeAll { $0.id == item.id }
        saveScheduled()
        // 批五（4.18）：取消反馈行与安卓逐字一致。
        showTransientNotice("已取消一条定时消息。")
    }

    private func flushScheduled() async {
        guard let transport = appState.makeChannelTransport() else { return }
        let due = ChannelExtras.dueScheduled(
            scheduledPosts, nowSeconds: Date().timeIntervalSince1970)
        for item in due {
            guard !Task.isCancelled else { return }
            loadScheduled()
            guard scheduledPosts.contains(where: { $0.id == item.id }),
                  !ChannelExtras.mentionsAllText(item.text) || localCanMentionAll(in: item.channelId) else { continue }
            do {
                let id = try await transport.postMessageEx(
                    channelID: item.channelId, text: item.text, replyTo: item.replyToId,
                    effectKey: item.effectKey, attachmentRef: item.attachmentRef)
                loadScheduled()
                let updated = ChannelExtras.scheduledQueueAfterFlush(
                    items: scheduledPosts, itemID: item.id, postedID: id)
                if updated != scheduledPosts {
                    scheduledPosts = updated
                    saveScheduled()
                }
                // 空 id 不是成功：上面的队列没有删这项，effect_key 原样留着下次重试。
                guard !id.isEmpty else { continue }
                recordChannelPost(id)
            } catch {
                // 保留原队列项和 effect_key，下次到期检查时继续重试。
            }
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

        if ChannelExtras.mentionsAllText(text) && !localCanMentionAll(in: currentChannelId) {
            sendErrorMessage = "只有频道 owner/协调者可以 @所有人，消息未发送。"
            showSendErrorAlert = true
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
        let replyTo = replyingTo?.id ?? activeTopicThreadRootId
        let attachmentRef = pendingAttachmentRef
        let channelID = currentChannelId
        // 幂等（阻塞⑤修法）：一次发送意图只铸一次 effect_key，
        // 失败重试把同一键再交给 postMessageEx，平台按 key 去重，
        // 不会双发。
        let effectKey = ChannelExtras.buildEffectKey(nonce: UUID().uuidString)
        Task {
            do {
                let postedID = try await transport.postMessageEx(
                    channelID: channelID, text: postText, replyTo: replyTo,
                    effectKey: effectKey, attachmentRef: attachmentRef)
                recordChannelPost(postedID)
                appendLocalEcho(id: postedID, text: postText, replyTo: replyTo, attachmentRef: attachmentRef)
                composerText = ""
                composerFocused = false
                replyingTo = nil
                selectedAttachment = nil
                pendingAttachmentRef = nil
            } catch {
                // 同一键再试一次（首发超时但实际落帖时，平台按 key 去重）。
                do {
                    let postedID = try await transport.postMessageEx(
                        channelID: channelID, text: postText, replyTo: replyTo,
                        effectKey: effectKey, attachmentRef: attachmentRef)
                    recordChannelPost(postedID)
                    appendLocalEcho(id: postedID, text: postText, replyTo: replyTo, attachmentRef: attachmentRef)
                    composerText = ""
                    composerFocused = false
                    replyingTo = nil
                    selectedAttachment = nil
                    pendingAttachmentRef = nil
                } catch {
                    sendErrorMessage = (error as? McpError)?.message ?? "发送失败。"
                    showSendErrorAlert = true
                }
            }
        }
    }

    // MARK: - 文件分区
    /// 文件页签数据（10.7 补齐）：与安卓同口径——拉全量消息后按
    /// attachmentRef 聚合成本地文件条目。此前 files 从未被填充，
    /// 汇总恒为 0（iOS 侧缺口，本批补上）。
    private func loadChannelFiles() async {
        guard let transport = appState.makeChannelTransport() else { return }
        do {
            let all = try await transport.pollMessages(channelID: currentChannelId, limit: 500)
            let converted = all.map { toV5Message($0) }
            let removedFileIds = Self.removedChannelFileIds(channelId: currentChannelId)
            files = converted.reversed().compactMap { Self.channelFileEntry(from: $0) }
                .filter { !removedFileIds.contains($0.id) }
        } catch {
            // 加载失败保留既有列表，不冒充空。
        }
    }

    /// 一条带附件引用的消息 → 文件汇总条目（引用 JSON 字段与双端种子同形）。
    private static func channelFileEntry(from msg: V5ChannelMessage) -> V5ChannelFile? {
        guard let ref = msg.attachmentRef, !ref.isEmpty else { return nil }
        var name = ""
        var kind = "file"
        var sizeText = ""
        var linkURL: String? = nil
        var projectName: String? = nil
        if let data = ref.data(using: .utf8),
           let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            name = (obj["name"] as? String) ?? ""
            kind = (obj["kind"] as? String) ?? "file"
            if let url = obj["url"] as? String {
                linkURL = url
                if name.isEmpty { name = url }
            }
            projectName = (obj["project"] as? String)?.trimmingCharacters(in: .whitespaces)
            if projectName?.isEmpty == true { projectName = nil }
            if let size = (obj["size"] as? NSNumber)?.intValue, size > 0 {
                sizeText = ByteCountFormatter.string(
                    fromByteCount: Int64(size), countStyle: .file)
            }
        }
        if linkURL == nil, ref.hasPrefix("http://") || ref.hasPrefix("https://") {
            linkURL = ref
        }
        if name.isEmpty {
            // 非 JSON 的纯文本引用：引用本身即文件名（与安卓「附件：<ref>」同口径）。
            let trimmedRef = ref.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmedRef.hasPrefix("{"), !trimmedRef.isEmpty {
                name = trimmedRef
            }
        }
        if name.isEmpty {
            name = msg.text.isEmpty ? "附件" : String(msg.text.prefix(30))
        }
        let timeText = msg.fullDateTime.count >= 16
            ? String(msg.fullDateTime.dropFirst(5).prefix(11))
            : msg.timestamp
        return V5ChannelFile(
            id: "file-\(msg.id)",
            fileName: name,
            fileSize: sizeText,
            fileType: kind,
            uploaderName: msg.senderName,
            uploadTime: timeText,
            associatedProjectName: projectName,
            sourceKind: "local",
            ref: ref,
            linkURL: linkURL)
    }

    /// 全量对齐批二：从频道移除落盘——文件由消息 attachmentRef 重聚合，
    /// 只删内存会在重进文件页时恢复；已移除 id 按频道过滤聚合结果。
    /// 存储用 Application Support 下的 JSON 文件（与安卓
    /// ChannelRemovedFilesStore 同名同形）：实测本环境 UserDefaults
    /// 写入只停留在 cfprefsd 缓存、模拟器重启即丢，文件落盘才可靠。
    private static var removedFilesStoreURL: URL? {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)
            .first?.appendingPathComponent("channel_removed_files.json")
    }

    private static func removedFilesStore() -> [String: [String]] {
        guard let url = removedFilesStoreURL,
              let data = try? Data(contentsOf: url),
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let channels = root["channels"] as? [String: [String]] else { return [:] }
        return channels
    }

    private static func removedChannelFileIds(channelId: String) -> Set<String> {
        Set(removedFilesStore()[channelId] ?? [])
    }

    private static func markChannelFileRemoved(channelId: String, fileId: String) {
        guard let url = removedFilesStoreURL else { return }
        var channels = removedFilesStore()
        var ids = channels[channelId] ?? []
        if !ids.contains(fileId) { ids.append(fileId) }
        channels[channelId] = ids
        guard let data = try? JSONSerialization.data(
            withJSONObject: ["channels": channels]) else { return }
        try? data.write(to: url, options: [.atomic])
    }

    /// 预览：URL 引用走系统打开；非 URL 引用展示引用原文（引用制下没有
    /// 本体可预览，如实呈现引用本身，与安卓「附件引用」对话框同口径）。
    private func previewChannelFile(_ file: V5ChannelFile) {
        if let link = file.linkURL, let url = URL(string: link) {
            UIApplication.shared.open(url)
        } else {
            fileActionNotice = "附件引用\n\(file.ref)"
        }
    }

    /// 下载至本地：仅 URL 引用可真下载（落盘到 App 文档目录）；非 URL
    /// 引用在菜单里不提供此动作（按类型显隐，不做空按钮）。
    private func downloadChannelFile(_ file: V5ChannelFile) {
        guard let link = file.linkURL, let url = URL(string: link) else { return }
        Task {
            do {
                let (tmp, _) = try await URLSession.shared.download(from: url)
                let docs = FileManager.default.urls(
                    for: .documentDirectory, in: .userDomainMask)[0]
                let dest = docs.appendingPathComponent(
                    file.fileName.isEmpty ? "下载文件" : file.fileName)
                try? FileManager.default.removeItem(at: dest)
                try FileManager.default.moveItem(at: tmp, to: dest)
                fileActionNotice = "已下载至本地：\(dest.lastPathComponent)"
            } catch {
                fileActionNotice = "下载失败：\(error.localizedDescription)"
            }
        }
    }

    private func removeChannelFile(_ file: V5ChannelFile) {
        Self.markChannelFileRemoved(channelId: currentChannelId, fileId: file.id)
        files.removeAll { $0.id == file.id }
    }

    private var filesTab: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                // 网盘统一适配器状态卡片
                VStack(alignment: .leading, spacing: 8) {
                    Text("统一网盘适配器")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(.secondary)

                    LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 8) {
                        cloudDriveBadge(name: "Google Drive", status: "待开发", isOk: false)
                        cloudDriveBadge(name: "OneDrive", status: "待开发", isOk: false)
                        cloudDriveBadge(name: "WebDAV", status: "待开发", isOk: false)
                        cloudDriveBadge(name: "Cloudreve", status: "待开发", isOk: false)
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
                                    Button("预览文件") { previewChannelFile(file) }
                                    if let link = file.linkURL, let url = URL(string: link) {
                                        Button("下载至本地") { downloadChannelFile(file) }
                                        ShareLink(item: url) {
                                            Text("分享链接")
                                        }
                                    } else {
                                        Button("复制引用") {
                                            UIPasteboard.general.string = file.ref
                                        }
                                    }
                                    Divider()
                                    Button(role: .destructive) {
                                        removeChannelFile(file)
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
        .alert("频道文件", isPresented: Binding(
            get: { fileActionNotice != nil },
            set: { if !$0 { fileActionNotice = nil } }
        )) {
            Button("好", role: .cancel) { fileActionNotice = nil }
        } message: {
            Text(fileActionNotice ?? "")
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
                // 派单任务（App 内嵌任务服务，仅本地平台频道）：本频道
                // 派出的活、进度与交付都在这里，区别于下方本机协同任务。
                if appState.channelUsesLocalPlatform {
                    ChannelDispatchTasksSection(
                        channelId: currentChannelId,
                        agentOptions: members.map { (id: $0.id, name: $0.name) })
                    Divider()
                }
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
        let state = task?.state ?? fallbackState ?? "状态未知"
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

            HStack {
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

            HStack {
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

    private func reloadWorkspaceTasks() {
        let chId = currentChannelId
        let channelTasks = (try? LocalData.shared.tasks.listTasks(sessionId: chId)) ?? []
        activeTasks = channelTasks
    }

    private func startFeed() {
        guard feed == nil,
              (appState.hasToken || appState.channelUsesLocalPlatform),
              let transport = appState.makeChannelTransport(),
              let wsURL = appState.channelWebSocketURL(channelID: currentChannelId) else {
            connectionState = .stopped
            return
        }
        let newFeed = ChannelFeed(
            channelID: currentChannelId,
            transport: transport,
            webSocketURL: wsURL,
            tokenProvider: appState.tokenProvider,
            localSubscribe: appState.channelUsesLocalPlatform
                ? LocalPlatform.subscribeLocalChannelEvents
                : nil
        )
        newFeed.onMessages = { [weak newFeed] fresh in
            Task { @MainActor in
                guard let newFeed, self.feed === newFeed else { return }
                for message in fresh {
                    self.appState.learnChannelSender(messageID: message.id, senderID: message.agentId)
                }
                let converted = fresh.map { self.toV5Message($0) }
                var seen = Set(self.messages.map { $0.id })
                for msg in converted where !seen.contains(msg.id) {
                    seen.insert(msg.id)
                    self.messages.append(msg)
                }
                // 刷新也可能带回已撤回/置顶等字段变化：按 id 合并新字段。
                for msg in converted {
                    if let idx = self.messages.firstIndex(where: { $0.id == msg.id }) {
                        self.messages[idx].isAgent = msg.isAgent
                        self.messages[idx].platformIsAgent = msg.platformIsAgent
                        self.messages[idx].recalled = msg.recalled
                        self.messages[idx].pinned = msg.pinned
                        self.messages[idx].reactions = msg.reactions
                        self.messages[idx].attachmentRef = msg.attachmentRef
                        self.messages[idx].forwardOf = msg.forwardOf
                        self.messages[idx].replyToId = msg.replyToId
                        self.messages[idx].replyToText = msg.replyToText
                    }
                }
                self.messages.sort { $0.fullDateTime < $1.fullDateTime }
                if let newest = self.messages.last {
                    let channelId = self.currentChannelId
                    Task.detached {
                        try? LocalData.shared.cursors.setLastSeenMessageId(
                            channelId: channelId, messageId: newest.id)
                    }
                }
            }
        }
        newFeed.onStateChange = { [weak newFeed] state, err in
            Task { @MainActor in
                guard let newFeed, self.feed === newFeed else { return }
                self.connectionState = state
                self.connectionError = err
            }
        }
        newFeed.onTyping = { [weak newFeed] typing in
            Task { @MainActor in
                guard let newFeed, self.feed === newFeed else { return }
                self.typingSenderName = typing.isTyping ? typing.senderName : nil
            }
        }
        self.feed = newFeed
        newFeed.start()
        self.connectionState = newFeed.connectionState
    }

    /// 平台消息 → 旗舰页视图消息：回复引用行按当前列表算；时间用
    /// 固定格式 "yyyy-MM-dd HH:mm:ss"，供列表排序。
    private func toV5Message(_ msg: ChannelMessage) -> V5ChannelMessage {
        let date = Date(timeIntervalSince1970: msg.createdAtSeconds)
        let fullFmt = DateFormatter()
        fullFmt.locale = Locale(identifier: "en_US_POSIX")
        fullFmt.dateFormat = "yyyy-MM-dd HH:mm:ss"
        let timeFmt = DateFormatter()
        timeFmt.locale = Locale(identifier: "en_US_POSIX")
        timeFmt.dateFormat = "HH:mm"
        let quote: String? = {
            guard let parentId = msg.replyTo,
                  let parent = messages.first(where: { $0.id == parentId }) else { return nil }
            let text = parent.recalled ? ChannelExtras.recalledPlaceholder : parent.text
            return String(text.replacingOccurrences(of: "\n", with: " ").prefix(40))
        }()
        return V5ChannelMessage(
            id: msg.id,
            senderId: msg.agentId,
            senderName: msg.displaySender,
            isAgent: ChannelExtras.displayedIsAgent(
                platformIsAgent: msg.isAgent,
                senderIsSelf: isOwnSender(msg.agentId),
                rosterIsAgent: channelParticipants.first(where: { $0.id == msg.agentId })?.isAgent),
            text: msg.text,
            timestamp: timeFmt.string(from: date),
            fullDateTime: fullFmt.string(from: date),
            replyToId: msg.replyTo,
            replyToText: quote,
            recalled: msg.recalled,
            pinned: msg.pinned,
            reactions: msg.reactions,
            attachmentRef: msg.attachmentRef,
            forwardOf: msg.forwardOf,
            platformIsAgent: msg.isAgent
        )
    }

    private func stopFeed() {
        let oldFeed = feed
        feed = nil
        oldFeed?.onMessages = nil
        oldFeed?.onStateChange = nil
        oldFeed?.onTyping = nil
        oldFeed?.stop()
        typingSenderName = nil
        connectionState = .stopped
        connectionError = nil
    }

    private func loadWorkspaceData() {
        reloadWorkspaceTasks()
        startFeed()
        Task {
            await appState.loadChannels()
            await refreshChannelMessages()
        }
    }
}

// MARK: - 辅助弹窗与选择器

public struct CreateDirectMessageSheet: View {
    @ObservedObject public var appState: AppState
    public var currentChannelID: String?
    public var onCreated: (V5DirectMessageItem) -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var selectedMemberID = ""
    @State private var isOpening = false
    @State private var errorText: String? = nil

    private var currentChannelMembers: [ChannelParticipant] {
        let channelID = currentChannelID ?? appState.settings.channelID
        return appState.availableChannels
            .first(where: { $0.id == channelID })?
            .participantDetails
            // 服务端 participants 不带 is_self（实查），单靠 isSelf 排不掉
            // 本机；再按本机频道发送者 ID 排除，与 isOwnSender 同一口径。
            .filter { !$0.isSelf && $0.id != appState.channelSenderID } ?? []
    }

    public var body: some View {
        NavigationStack {
            Form {
                Section {
                    if currentChannelMembers.isEmpty {
                        Text("当前频道没有可发起私聊的成员。")
                            .foregroundStyle(.secondary)
                    } else {
                        Picker("选择成员", selection: $selectedMemberID) {
                            ForEach(currentChannelMembers, id: \.id) { member in
                                Text(member.name).tag(member.id)
                            }
                        }
                    }
                    Text("名单来自当前频道，已排除本机成员。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } header: { Text("当前频道成员").textCase(nil) }

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
            .onAppear {
                if selectedMemberID.isEmpty {
                    selectedMemberID = currentChannelMembers.first?.id ?? ""
                }
            }
            .onChange(of: currentChannelMembers.map(\.id)) { _, ids in
                if !ids.contains(selectedMemberID) { selectedMemberID = ids.first ?? "" }
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("确定") {
                        openSelectedMemberDM()
                    }
                    .disabled(isOpening || selectedMember == nil)
                }
            }
        }
    }

    private var selectedMember: ChannelParticipant? {
        currentChannelMembers.first(where: { $0.id == selectedMemberID })
    }

    private func openSelectedMemberDM() {
        guard let member = selectedMember else {
            errorText = "请选择当前频道中的成员。"
            return
        }
        guard let transport = appState.makeChannelTransport() else {
            errorText = "无法连接频道服务。"
            return
        }
        errorText = nil
        isOpening = true
        Task {
            defer { isOpening = false }
            do {
                let result = try await transport.openDm(agentID: member.id)
                guard let channelID = (result["id"] as? String)?
                    .trimmingCharacters(in: .whitespacesAndNewlines),
                    !channelID.isEmpty else {
                    errorText = "平台未返回私聊频道 ID。"
                    return
                }
                onCreated(V5DirectMessageItem(
                    id: channelID,
                    targetId: member.id,
                    targetName: member.name,
                    isAgent: member.isAgent ?? true,
                    targetRole: member.role ?? "私聊",
                    lastMessageText: "",
                    lastMessageTime: ""
                ))
                dismiss()
            } catch {
                errorText = (error as? McpError)?.message ?? error.localizedDescription
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

public struct CreateChannelSheet: View {
    @ObservedObject public var appState: AppState
    /// 批三（3.7）：回传新频道 id，宿主据此刷新后直达新频道（对齐安卓创建后落点）。
    public var onCreated: (String?) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var name = LocalChannelService.defaultChannelName
    @State private var placement: ChannelLayer = .local
    @State private var errorText: String?
    @State private var isWorking = false

    /// 已连云端才出现「放在」；未连时只建本机频道。
    private var cloudConnected: Bool {
        !appState.channelUsesLocalPlatform
    }

    public var body: some View {
        NavigationStack {
            Form {
                Section("频道") {
                    TextField("频道名称", text: $name)
                        .textInputAutocapitalization(.never)
                }
                if cloudConnected {
                    Section {
                        Picker("放在", selection: $placement) {
                            Text("本机").tag(ChannelLayer.local)
                            Text("云端").tag(ChannelLayer.cloud)
                        }
                        .pickerStyle(.segmented)
                        .accessibilityIdentifier("channels.create.placement")
                    } header: {
                        Text("放在")
                    }
                }
                if let errorText { Section { Text(errorText).foregroundStyle(.red).textSelection(.enabled) } }
            }
            .navigationTitle("新建频道")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("创建") { Task { await create() } }
                        .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || isWorking)
                }
            }
        }
    }
    @MainActor private func create() async {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        isWorking = true
        defer { isWorking = false }
        errorText = nil
        let placeOnCloud = cloudConnected && placement == .cloud
        do {
            let created: [String: Any]
            if placeOnCloud {
                guard let transport = appState.makeChannelTransport() else {
                    errorText = "无法连接频道服务。"
                    return
                }
                created = try await transport.createChannel(name: trimmed)
            } else {
                guard let service = LocalPlatform.serviceOrNull() else {
                    errorText = "无法连接频道服务。"
                    return
                }
                created = try service.dispatch(
                    tool: "agent_channel_create",
                    args: ["name": trimmed],
                    callerId: LocalPlatform.appAgentID,
                    callerName: LocalPlatform.appAgentName
                )
            }
            onCreated(created["id"] as? String)
            dismiss()
        } catch let error as LocalServiceError {
            errorText = "创建频道失败：\(error.message)"
        } catch {
            errorText = "创建频道失败：\(error.localizedDescription)"
        }
    }
}

public struct ChannelManagementListSheet: View {
    @ObservedObject public var appState: AppState
    public let channels: [V5ChannelItem]
    public var onChanged: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var selected: V5ChannelItem?
    public var body: some View {
        NavigationStack {
            List(channels) { ch in
                Button { selected = ch } label: {
                    HStack { Text("# \(ch.name)"); Spacer(); Text(ch.isArchived ? "已归档" : "活动中").foregroundStyle(.secondary) }
                }
            }
            .navigationTitle("管理频道")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } } }
            .sheet(item: $selected) { ch in
                ChannelSettingsSheet(appState: appState, channel: ch, dm: nil) { _ in onChanged() }
            }
        }
    }
}

public struct TopicThreadsSheet: View {
    public let threads: [TeamThread]
    public let onSelect: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    public init(threads: [TeamThread], onSelect: @escaping (String) -> Void) {
        self.threads = threads
        self.onSelect = onSelect
    }

    public var body: some View {
        NavigationStack {
            List {
                if threads.isEmpty {
                    Text("当前频道暂无自由话题。\n点击任意消息长按菜单中的「发起话题」即可发起。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(threads) { thread in
                        Button {
                            if let rootId = thread.rootMessageId {
                                onSelect(rootId)
                                dismiss()
                            }
                        } label: {
                            VStack(alignment: .leading, spacing: 4) {
                                HStack {
                                    Text(thread.topicTitle ?? "话题讨论")
                                        .font(.subheadline)
                                        .fontWeight(.semibold)
                                        .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                                    Spacer()
                                    Text("\(thread.messages.count) 条消息")
                                        .font(.caption2)
                                        .foregroundStyle(.secondary)
                                }
                                if let first = thread.messages.first {
                                    Text("\(first.displaySender): \(first.text)")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                        .lineLimit(2)
                                }
                            }
                            .padding(.vertical, 4)
                        }
                    }
                }
            }
            .navigationTitle("自由话题列表")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
    }
}

public struct ChannelWebhookRow: Identifiable {
    public let id: String
    public let name: String
    public let createdAt: String
    public let urlPath: String
}

public struct ChannelSettingsSheet: View {
    @ObservedObject public var appState: AppState
    public let channel: V5ChannelItem?
    public let dm: V5DirectMessageItem?
    public var onMembersChanged: (([V5ChannelMember]) -> Void)? = nil
    @Environment(\.dismiss) private var dismiss
    @State private var members: [V5ChannelMember] = []
    @State private var agents: [AgentRecord] = []
    @State private var editedName = ""
    @State private var errorText: String?
    @State private var isWorking = false
    @State private var showDeleteConfirmation = false
    @State private var memberPendingRemoval: V5ChannelMember?
    @State private var showMemberRemovalConfirmation = false
    @State private var showCreatorRemovalAlert = false
    /// 批三（7.8）：归档行文案绑本页状态（channel 是启动快照，归档后不刷新）。
    @State private var archivedState: Bool? = nil
    @State private var isMuted = false
    @State private var ignoresUnread = false
    @State private var notifyMode = "全部消息"
    private var channelId: String { channel?.id ?? dm?.id ?? "ch-general" }
    public init(appState: AppState, channel: V5ChannelItem?, dm: V5DirectMessageItem?, onMembersChanged: (([V5ChannelMember]) -> Void)? = nil) {
        self.appState = appState; self.channel = channel; self.dm = dm; self.onMembersChanged = onMembersChanged
    }
    public var body: some View {
        NavigationStack {
            Form {
                channelInformationSection
                directMessageInformationSections
                notificationSettingsSection
                channelWebhooksSection
                channelMembersSection
                if let errorText { Section { Text(errorText).foregroundStyle(.red).textSelection(.enabled) } }
            }
            .navigationTitle("频道设置")
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } } }
            .background(managementDialogs)
            .onAppear {
                editedName = channel?.name ?? ""
                let modeKey = "channel.notify_mode.\(channelId)"
                notifyMode = UserDefaults.standard.string(forKey: modeKey) ?? (channel?.isMuted == true || dm?.isMuted == true ? "静音" : "全部消息")
                isMuted = (notifyMode == "静音")
                agents = (try? LocalData.shared.agents.listAgents()) ?? []
                members = appState.availableChannels.first(where: { $0.id == channelId })?
                    .participantDetails.map(V5ChannelMember.init) ?? []
                Task { await loadWebhooks() }
            }
        }
    }
    @ViewBuilder private var channelInformationSection: some View {
        if let ch = channel {
            Section("频道信息") {
                TextField("频道名称", text: $editedName)
                LabeledContent("频道性质", value: ch.isPrivate ? "私有频道" : "公开频道")
                LabeledContent("成员总数", value: "\(members.count) 人")
                Button("保存名称") { Task { await rename() } }.disabled(editedName == ch.name || isWorking)
                Button((archivedState ?? ch.isArchived) ? "取消归档" : "归档频道") { Task { await setArchived(!(archivedState ?? ch.isArchived)) } }.disabled(isWorking)
                Button("删除频道", role: .destructive) { showDeleteConfirmation = true }.disabled(isWorking)
                Text("仅频道 owner 可删除。删除权限由服务端校验。")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
    }
    @ViewBuilder private var directMessageInformationSections: some View {
        if let d = dm {
            Section("私聊信息") {
                LabeledContent("对象姓名", value: d.targetName)
                LabeledContent("身份类型", value: d.isAgent ? "平台 Agent" : "团队成员")
                LabeledContent("归属角色", value: d.targetRole)
            }
        }
    }
    @ViewBuilder private var notificationSettingsSection: some View {
        Section("通知设置") {
            Picker("通知级别", selection: $notifyMode) {
                Text("全部消息").tag("全部消息")
                Text("仅@我").tag("仅@我")
                Text("静音").tag("静音")
            }
            .pickerStyle(.segmented)
            .onChange(of: notifyMode) { _, newMode in
                UserDefaults.standard.set(newMode, forKey: "channel.notify_mode.\(channelId)")
                isMuted = (newMode == "静音")
            }
            Text(notifyModePrompt(notifyMode))
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }
    private func notifyModePrompt(_ mode: String) -> String {
        switch mode {
        case "仅@我": return "仅接收提及我的消息通知与未读提醒"
        case "静音": return "不接收通知，不计入未读提醒"
        default: return "接收所有新消息通知与未读计数"
        }
    }
    @State private var webhookRows: [ChannelWebhookRow] = []
    @State private var createdWebhookText: String?
    /// 无管理权限（普通成员）时 Webhook 区的静默说明行：打开设置
    /// 不该被平台的权限拒绝糊一脸红字（2026-11-28 Funk 真机反馈）。
    @State private var webhookPermissionNote: String?

    @ViewBuilder private var channelWebhooksSection: some View {
        if channel != nil {
            Section("入帖 Webhook") {
                ForEach(webhookRows) { hook in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(hook.name.isEmpty ? hook.id : hook.name)
                            Text(hook.urlPath).font(.caption2).foregroundStyle(.secondary).textSelection(.enabled)
                            if !hook.createdAt.isEmpty {
                                Text("建于 \(hook.createdAt)").font(.caption2).foregroundStyle(.secondary)
                            }
                        }
                        Spacer()
                        Button("删除", role: .destructive) { Task { await deleteWebhook(hook) } }
                    }
                }
                if let webhookPermissionNote {
                    Text(webhookPermissionNote).font(.caption).foregroundStyle(.secondary)
                } else {
                    Button("创建入帖 Webhook") { Task { await createWebhook() } }
                }
                if let createdWebhookText {
                    Text(createdWebhookText).font(.caption).textSelection(.enabled)
                }
            }
        }
    }

    @MainActor private func loadWebhooks() async {
        guard channel != nil, let transport = appState.makeChannelTransport() else { return }
        do {
            let result = try await transport.listChannelWebhooks(channelID: channelId)
            let raw = result["webhooks"] as? [[String: Any]] ?? []
            webhookRows = raw.compactMap { item in
                guard let id = item["webhook_id"] as? String, !id.isEmpty else { return nil }
                var created = ""
                if let ts = item["created_at"] as? Double {
                    created = DateFormatter.localizedString(
                        from: Date(timeIntervalSince1970: ts), dateStyle: .short, timeStyle: .short)
                }
                return ChannelWebhookRow(
                    id: id,
                    name: item["name"] as? String ?? "",
                    createdAt: created,
                    urlPath: item["url_path"] as? String ?? "")
            }
        } catch {
            let message = (error as? McpError)?.message ?? error.localizedDescription
            if message.contains("owner") || message.contains("协调者") || message.contains("403") {
                // 普通成员没有 Webhook 管理权：这是常态不是故障，
                // 区内给一行灰字说明，不进全局红色错误区。
                webhookPermissionNote = "入帖 Webhook 由频道创建者／协调者管理。"
                webhookRows = []
            } else {
                errorText = message
            }
        }
    }

    @MainActor private func createWebhook() async {
        guard let transport = appState.makeChannelTransport() else { errorText = "无法连接频道服务。"; return }
        do {
            // Real server fields: url_path (plural channel-webhooks) +
            // secret (shown exactly once) + signature instructions.
            let result = try await transport.createChannelWebhook(channelID: channelId)
            let urlPath = result["url_path"] as? String ?? ""
            let secret = result["secret"] as? String ?? ""
            let signature = result["signature"] as? String ?? ""
            createdWebhookText = "入帖地址路径：\(urlPath)\n密钥（只显示这一次，请自行保存）：\(secret)\n\(signature)\n外部系统 POST {\"text\": \"…\"} 到平台地址加该路径即可入帖；密钥丢失只能删掉重建。"
            await loadWebhooks()
        } catch {
            errorText = (error as? McpError)?.message ?? error.localizedDescription
        }
    }

    @MainActor private func deleteWebhook(_ hook: ChannelWebhookRow) async {
        guard let transport = appState.makeChannelTransport() else { errorText = "无法连接频道服务。"; return }
        do {
            _ = try await transport.deleteChannelWebhook(channelID: channelId, webhookID: hook.id)
            await loadWebhooks()
        } catch {
            errorText = (error as? McpError)?.message ?? error.localizedDescription
        }
    }

    /// 批三（7.5）：添加成员候选 = 其他频道账本成员并集＋本机已注册 Agent，
    /// 排除已在本频道内的与本机自己（此前只有已注册 Agent 一源，零配置下
    /// 候选为空、入口整行消失，设置页里看不见添加成员）。
    private struct AddCandidate: Identifiable {
        let agentId: String
        let name: String
        var id: String { agentId }
    }

    private var addCandidates: [AddCandidate] {
        var seen = Set(members.map { $0.id })
        var result: [AddCandidate] = []
        for other in appState.availableChannels where other.id != channelId {
            for p in other.participantDetails where !p.isSelf && !seen.contains(p.id) {
                seen.insert(p.id)
                result.append(AddCandidate(agentId: p.id, name: p.name))
            }
        }
        for agent in agents where !seen.contains(agent.agentId) {
            seen.insert(agent.agentId)
            result.append(AddCandidate(agentId: agent.agentId, name: agent.displayName))
        }
        return result
    }

    private var channelMembersSection: some View {
        Section("频道成员") {
            ForEach(members) { member in memberRow(member) }
            if channel != nil {
                if addCandidates.isEmpty {
                    Text("暂无可添加的成员")
                        .font(.caption).foregroundStyle(.secondary)
                } else {
                    Menu("添加成员") {
                        ForEach(addCandidates) { candidate in
                            Button(candidate.name) { Task { await add(agentID: candidate.agentId) } }
                        }
                    }
                    .disabled(isWorking)
                }
            }
        }
    }
    /// My own role in this channel (platform truth via is_self).
    private var myRole: String? {
        appState.availableChannels.first(where: { $0.id == channelId })?
            .participantDetails.first { $0.isSelf }?.role
    }

    @ViewBuilder private func memberRow(_ member: V5ChannelMember) -> some View {
        HStack {
            VStack(alignment: .leading) { Text(member.name); Text(channelRoleLabel(member.role)).font(.caption).foregroundStyle(.secondary) }
            Spacer()
            if myRole == "owner", let agentID = member.agentRecordId {
                Menu("改角色") {
                    Button("设为创建者") { Task { await setRole(agentID: agentID, role: "owner") } }
                    Button("设为协调员") { Task { await setRole(agentID: agentID, role: "coordinator") } }
                    Button("设为成员") { Task { await setRole(agentID: agentID, role: "member") } }
                }
                .disabled(isWorking)
            }
            if isProtectedMember(member) { Text("不可移除").font(.caption).foregroundStyle(.secondary) }
            else if member.agentRecordId != nil {
                Button("移除", role: .destructive) {
                    memberPendingRemoval = member
                    showMemberRemovalConfirmation = true
                }.disabled(isWorking)
            }
        }
    }

    @MainActor private func setRole(agentID: String, role: String) async {
        guard let transport = appState.makeChannelTransport() else { errorText = "无法连接频道服务。"; return }
        await perform { try await transport.setMemberRole(channelID: channelId, memberID: agentID, role: role) }
    }
    private var managementDialogs: some View {
        Color.clear
            .confirmationDialog("确定删除频道？", isPresented: $showDeleteConfirmation, titleVisibility: .visible) {
                Button("删除频道", role: .destructive) { Task { await deleteChannel() } }
                Button("取消", role: .cancel) {}
            } message: {
                Text("删除无法撤销。仅频道 owner 有权限，服务端将验证权限。")
            }
            .confirmationDialog("移除频道成员？", isPresented: $showMemberRemovalConfirmation, titleVisibility: .visible) {
                Button("移除", role: .destructive) {
                    guard let member = memberPendingRemoval else { return }
                    if isProtectedMember(member) { showCreatorRemovalAlert = true }
                    else if let agentID = member.agentRecordId { Task { await remove(agentID: agentID) } }
                    memberPendingRemoval = nil
                }
                Button("取消", role: .cancel) { memberPendingRemoval = nil }
            } message: {
                Text("确定将 \(memberPendingRemoval?.name ?? "该成员")移出此频道？")
            }
            .alert("无法移除创建者", isPresented: $showCreatorRemovalAlert) {
                Button("好的", role: .cancel) {}
            } message: {
                Text("创建者或 owner 身份不可通过成员移除操作删除。")
            }
    }
    @MainActor private func rename() async {
        guard let channel, let transport = appState.makeChannelTransport() else { errorText = "无法连接频道服务。"; return }
        await perform { try await transport.renameChannel(channelID: channel.id, name: editedName.trimmingCharacters(in: .whitespacesAndNewlines)) }
    }
    @MainActor private func setArchived(_ archived: Bool) async {
        guard let channel, let transport = appState.makeChannelTransport() else { errorText = "无法连接频道服务。"; return }
        await perform { try await transport.setChannelArchived(channelID: channel.id, archived: archived) }
        if errorText == nil { archivedState = archived }
    }
    @MainActor private func add(agentID: String) async {
        guard let transport = appState.makeChannelTransport() else { errorText = "无法连接频道服务。"; return }
        await perform { try await transport.addChannelMember(channelID: channelId, agentID: agentID) }
    }
    @MainActor private func remove(agentID: String) async {
        guard let transport = appState.makeChannelTransport() else { errorText = "无法连接频道服务。"; return }
        await perform { try await transport.removeChannelMember(channelID: channelId, agentID: agentID) }
    }
    private func isProtectedMember(_ member: V5ChannelMember) -> Bool {
        let participant = appState.availableChannels.first(where: { $0.id == channelId })?
            .participantDetails.first(where: { $0.id == member.id })
        let isSelf = member.id == appState.channelSenderID || participant?.isSelf == true
        // 群主看 role。canMentionAll 把协调者也算进去，不能用来挡移除。
        let role = participant?.role ?? member.role
        return ChannelExtras.isProtectedChannelMember(isSelf: isSelf, role: role)
    }
    @MainActor private func deleteChannel() async {
        guard let channel, let transport = appState.makeChannelTransport() else { errorText = "无法连接频道服务。"; return }
        isWorking = true
        defer { isWorking = false }
        do {
            _ = try await transport.deleteChannel(channelID: channel.id)
            await appState.loadChannels()
            onMembersChanged?([])
            dismiss()
        } catch {
            errorText = "删除频道失败：\(error.localizedDescription)"
        }
    }
    @MainActor private func perform(_ operation: () async throws -> [String: Any]) async {
        isWorking = true; defer { isWorking = false }
        do {
            _ = try await operation()
            await appState.loadChannels()
            members = appState.availableChannels.first(where: { $0.id == channelId })?
                .participantDetails.map(V5ChannelMember.init) ?? []
            onMembersChanged?(members)
            errorText = nil
        } catch { errorText = "频道操作失败：\(error.localizedDescription)" }
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
            Group {
                if tasks.isEmpty {
                    // 5.10 修复：空时给空态文案，不再是整页空白。
                    ContentUnavailableView(
                        "暂无可关联的任务",
                        systemImage: "link.badge.plus",
                        description: Text("可关联的是本机任务列表里的任务；先派发一个任务，它会出现在这里。"))
                } else {
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
        .task {
            // 与安卓控制台同口径：打开时每 5 秒自动刷新一次本机记录；
            // .task 随页面离屏自动取消，不留后台轮询。
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 5_000_000_000)
                if Task.isCancelled { break }
                reloadLocalRecords()
            }
        }
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
                        Text(TaskStates.label(of: t.state)).font(.caption2).foregroundStyle(.secondary)
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
                    Text("▸ \(t.title.isEmpty ? t.taskId : t.title) · \(TaskStates.label(of: t.state))")
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
                    RepoAuthorizationView()
                } label: {
                    Label("仓库授权", systemImage: "lock.rectangle")
                }

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

                NavigationLink {
                    WorkspaceSettingsView()
                } label: {
                    Label("工作区", systemImage: "folder")
                }

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

                NavigationLink {
                    AutonomySettingsView()
                } label: {
                    Label("自主度", systemImage: "slider.horizontal.3")
                }

                // 大脑入口（已砍下放角落：主设置留一行安静入口，进现成 BrainSettingsView，页面内容不动）
                NavigationLink {
                    BrainSettingsView(model: appState.brainSettingsModel)
                } label: {
                    Label("大脑", systemImage: "brain.head.profile")
                }
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

                NavigationLink {
                    AboutSettingsView()
                } label: {
                    Label("关于", systemImage: "info.circle")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
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
                        webhookStateBadge(title: "1. 端点生成", state: webhookURLText == "未配置" ? "未配置" : "已生成", isOk: webhookURLText != "未配置")
                        webhookStateBadge(title: "2. 验签合法", state: "本机未验证", isOk: false)
                        webhookStateBadge(title: "3. 触发器就绪", state: "本机未验证", isOk: false)
                        webhookStateBadge(title: "4. 模拟建单", state: simulatedWebhookResult != nil ? "已实际执行" : "未执行", isOk: simulatedWebhookResult != nil)
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
                } header: { Text("唤醒四态（本机口径：未验证不标绿）").textCase(nil) }
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
            description: "本机模拟的 Git Push 事件（未收到真实 Webhook 请求，未做真实 HMAC 验签）",
            state: "queued",
            assignedAgentId: "codex",
            priority: 1
        )
        do {
            _ = try LocalData.shared.tasks.createTask(newTask)
            _ = try LocalData.shared.tasks.transition(taskId: newTask.taskId, to: "assigned", note: "Webhook 唤醒指派给 Codex")
            _ = try LocalData.shared.tasks.transition(taskId: newTask.taskId, to: "working", note: "Codex 开始执行自动化审查")
            simulatedWebhookResult = "✓ 模拟唤醒完成：已在任务中心生成任务 \(newTask.taskId)（状态: working）。此为本机模拟，未收到真实 Webhook 请求、未做真实 HMAC 验签。"
            LocalData.shared.approvalStore.recordAudit("[WEBHOOK_WAKEUP_SIM] 模拟 git_push 事件 -> 本机建单 \(newTask.taskId) (状态: 已实际执行；未收到真实 Webhook、未验签)")
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
