import SwiftUI

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
            HistoryConversationsScreen(chatModel: chatModel, onNavigateRoute: onNavigateRoute)
        case "search":
            GlobalSearchScreen(chatModel: chatModel, onNavigateRoute: onNavigateRoute)
        case "voice":
            VoiceMultimodalScreen(chatModel: chatModel)

        // MARK: - 二、工作台 · 任务 (8 路由)
        case "compose":
            ComposeTaskScreen(appState: appState, onCreated: { onNavigateRoute("tasks") })
        case "tasks":
            TasksView(appState: appState)
        case "taskdetail":
            TaskDetailRouterScreen(appState: appState)
        case "plan":
            PlanAndCheckpointsScreen()
        case "queue":
            QueueAndSchedulerScreen()
        case "approvals":
            ApprovalsCenterScreen()
        case "review":
            CodeReviewScreen()
        case "artifacts":
            ArtifactsDeliveryScreen()

        // MARK: - 二、项目 · 任务与开发 (10 路由)
        case "projects", "projectdetail":
            ProjectsHomeView(appState: appState, onOpenDrawer: {}, onNavigateRoute: onNavigateRoute)
        case "files":
            SandboxFileBrowserScreen(onOpenEditor: { onNavigateRoute("editor") })
        case "editor":
            SandboxCodeEditorScreen(onOpenDiff: { onNavigateRoute("diff") })
        case "diff":
            UnifiedDiffScreen()
        case "terminal":
            TerminalAndExecutionTargetScreen(appState: appState)
        case "git":
            GitWorktreeInspectorScreen()
        case "preview", "browser", "testmatrix":
            WorkbenchDevExtendedScreen(route: route)

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
            DevicesView()
        case "models", "connectmodel":
            BrainSettingsView(model: appState.brainSettingsModel)
        case "mcp":
            McpToolsInspectorScreen(appState: appState)
        case "skills":
            SkillsManagementScreen()
        case "connections":
            SettingsView(appState: appState)
        case "modelroute", "agentimport", "network", "routes":
            ConnectionExtendedScreen(route: route, appState: appState)

        // MARK: - 三、控制 · 系统与自动化 (17 路由)
        case "dashboard":
            ControlHomeView(appState: appState, onOpenDrawer: {}, onNavigateRoute: onNavigateRoute)
        case "settings":
            AccountSettingsView(appState: appState, chatModel: chatModel, capabilities: capabilities, onNavigateRoute: onNavigateRoute, onDismiss: {})
        case "onboarding":
            OnboardingLocalFirstScreen(onNavigateRoute: onNavigateRoute)
        case "activity", "notifications", "audit", "usage", "sync", "recovery", "baseline":
            ConsoleSystemSubScreen(route: route, appState: appState, onNavigateRoute: onNavigateRoute)
        case "autoloop", "loopguard", "templates", "automation", "automaker", "triggers", "wakepolicy":
            ConsoleAutomationSubScreen(route: route)

        // MARK: - 四、设置 · 智能与扩展 (9 路由)
        case "memory", "skillsmarket", "skillbuilder", "context", "research", "personas", "extensions", "extensiondetail", "harness":
            SettingsIntelligenceAndExtensionsScreen(route: route, appState: appState)

        default:
            SettingsView(appState: appState)
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
            Section("当前会话与连接状态") {
                LabeledContent("当前大脑", value: chatModel.selectedBrain.isEmpty ? "未选择" : chatModel.selectedBrain)
                LabeledContent("执行环境", value: chatModel.selectedExecutionTarget)
                LabeledContent("平台状态", value: appState.connectionStatus.displayText)
            }
            Section("消息与工具流 (\(chatModel.messages.count) 条)") {
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
            }
        }
    }
}

private struct HistoryConversationsScreen: View {
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
            Section("SQLite 本地会话历史 (\(filtered.count) 条)") {
                if filtered.isEmpty {
                    Text("暂无历史会话记录")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(filtered) { conv in
                        HStack {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(conv.title).font(.headline)
                                Text("模型：\(conv.brainId ?? "默认") · ID: \(conv.id.prefix(8))")
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
            }
        }
        .searchable(text: $query, prompt: "按标题或模型搜索会话")
        .onAppear { chatModel.reloadHistory() }
    }
}

private struct GlobalSearchScreen: View {
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
            Section("跨模块检索关键词") {
                TextField("搜索会话、本地任务、项目或 70 屏路由…", text: $keyword)
            }
            Section("匹配会话 (\(convs.count))") {
                ForEach(convs.prefix(5)) { c in
                    Button(c.title) {
                        chatModel.open(c)
                        onNavigateRoute("chat")
                    }
                }
            }
            Section("匹配任务 (\(tasks.count))") {
                ForEach(tasks.prefix(5)) { t in
                    Button("\(t.title) [\(t.state)]") {
                        onNavigateRoute("tasks")
                    }
                }
            }
            Section("匹配项目 (\(projects.count))") {
                ForEach(projects.prefix(5)) { p in
                    Button(p.project.name) {
                        onNavigateRoute("projects")
                    }
                }
            }
            Section("匹配功能路由 (\(routes.count))") {
                ForEach(routes.prefix(10)) { r in
                    Button("\(r.title) (\(r.id)) — \(r.parent.rawValue)/\(r.group)") {
                        onNavigateRoute(r.id)
                    }
                }
            }
        }
    }
}

private struct VoiceMultimodalScreen: View {
    @ObservedObject var chatModel: LocalChatModel
    @State private var attachName = ""
    @State private var voiceTranscript = ""

    var body: some View {
        Form {
            Section("iOS 多模态附件与语音权限边界") {
                Text("• 文件与代码导入：通过 iOS UIDocumentPickerViewController 及 Security-Scoped Bookmark 访问授权沙盒文件。\n• 语音输入：使用 iOS Speech / AVAudioSession 系统听写与语音转文字入口（需 Info.plist 声明 NSMicrophoneUsageDescription 与 NSSpeechRecognitionUsageDescription）。\n• 视觉能力校验：当所选大脑（如 DeepSeek 文本模型）不支持 vision 时，BrainEngine 自动注入诚实提示，绝不伪造看图结果。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Section("添加沙盒文件引用到当前会话") {
                TextField("文件名或相对路径（如 README.md）", text: $attachName)
                Button("挂载到当前输入栏") {
                    let clean = attachName.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !clean.isEmpty else { return }
                    chatModel.attachedFiles.append(clean)
                    attachName = ""
                }
            }
            Section("语音听写草稿转入") {
                TextField("使用系统键盘麦克风听写或输入语音内容…", text: $voiceTranscript, axis: .vertical)
                Button("填入对话首页输入框") {
                    guard !voiceTranscript.isEmpty else { return }
                    chatModel.draft = voiceTranscript
                    voiceTranscript = ""
                }
            }
        }
    }
}

// MARK: - 2. 工作台 · 任务与开发子模块实现
private struct ComposeTaskScreen: View {
    @ObservedObject var appState: AppState
    var onCreated: () -> Void

    @State private var title = ""
    @State private var description = ""
    @State private var assignedAgentId = ""
    @State private var priority = 1
    @State private var executionEnv = "iphone_local_sandbox"
    @State private var errorMsg: String?

    var body: some View {
        Form {
            Section("新建任务（写入本地 SQLite 任务账本 wherevercode-tasks.db）") {
                TextField("任务目标标题", text: $title)
                TextField("验收标准与上下文说明", text: $description, axis: .vertical)
                    .lineLimit(3...6)
                TextField("指派 Agent ID（可选）", text: $assignedAgentId)
                Stepper("优先级：P\(priority)", value: $priority, in: 0...3)
            }
            Section("执行环境与权限声明") {
                Picker("目标执行环境", selection: $executionEnv) {
                    Text("iPhone 本机大脑与沙盒").tag("iphone_local_sandbox")
                    Text("远端配对设备 (Hand)").tag("remote_device_hand")
                    Text("V5 平台云端 Agent (MCP)").tag("cloud_agent_mcp")
                }
                Text("注：V5 平台 MCP 当前不提供客户端直接建单接口，新建任务将持久化至本机 SQLite 任务表（初始状态 queued），支持完整 10 状态流转与事件审计。")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
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
                        brainId: appState.brainSettingsModel.currentBrainId
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
        }
    }
}

private struct TaskDetailRouterScreen: View {
    @ObservedObject var appState: AppState

    var body: some View {
        let tasks = (try? LocalData.shared.tasks.listTasks()) ?? []
        if let first = tasks.last {
            LocalTaskDetailScreen(task: first, onUpdated: {})
        } else {
            TasksView(appState: appState)
        }
    }
}

private struct PlanAndCheckpointsScreen: View {
    @State private var goalText = "完成 iOS 70 屏路由装配与 SQLite 状态机校验"
    @State private var stepTool = "list_files"
    @State private var taskId = "task-demo-01"
    @State private var validationResult: String?

    var body: some View {
        Form {
            Section("执行计划与幂等键校验器 (Planner.validate)") {
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
            }
            if let res = validationResult {
                Section("校验结果") {
                    Text(res).font(.footnote)
                }
            }
        }
    }
}

private struct QueueAndSchedulerScreen: View {
    var body: some View {
        let allTasks = (try? LocalData.shared.tasks.listTasks()) ?? []
        let queued = allTasks.filter { $0.state == "queued" || $0.state == "requeued" || $0.state == "assigned" }
        let working = allTasks.filter { $0.state == "working" || $0.state == "blocked" }
        List {
            Section("排队与待调度任务 (\(queued.count))") {
                if queued.isEmpty {
                    Text("队列为空").foregroundStyle(.secondary)
                } else {
                    ForEach(queued) { t in
                        LabeledContent("\(t.title) (P\(t.priority))", value: TaskStates.displayLabel(for: t.state, isPaused: t.isPaused))
                    }
                }
            }
            Section("并发执行中 / 阻塞中 (\(working.count))") {
                if working.isEmpty {
                    Text("当前无并发执行任务").foregroundStyle(.secondary)
                } else {
                    ForEach(working) { t in
                        LabeledContent(t.title, value: TaskStates.displayLabel(for: t.state, isPaused: t.isPaused))
                    }
                }
            }
        }
    }
}

private struct ApprovalsCenterScreen: View {
    @ObservedObject var store: InteractiveApprovalStore = LocalData.shared.approvalStore
    @State private var sampleTool = "write_file"
    @State private var samplePath = "ios/WhereverCodeAgent/App/WhereverCodeAgentApp.swift"

    var body: some View {
        List {
            Section("审批门禁说明 (ApprovalGate & MCP 边界)") {
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
            }
            Section("本机待审批动作队列 (\(store.items.count))") {
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
            }
        }
    }
}

private struct CodeReviewScreen: View {
    var body: some View {
        let tasks = ((try? LocalData.shared.tasks.listTasks()) ?? []).filter { $0.state == "review" || $0.state == "rework" }
        List {
            Section("审查门禁与机械检查原则") {
                Text("严格遵循统一契约：机械检查默认状态为 NOT_RUN，未经真实自动化测试不得宣称 PASS。支持从 review 流转至 completed 或打回 rework。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Section("待审查 / 需返工任务 (\(tasks.count))") {
                if tasks.isEmpty {
                    Text("当前没有处于 review 或 rework 状态的任务").foregroundStyle(.secondary)
                } else {
                    ForEach(tasks) { t in
                        NavigationLink(t.title) {
                            LocalTaskDetailScreen(task: t, onUpdated: {})
                        }
                    }
                }
            }
        }
    }
}

private struct ArtifactsDeliveryScreen: View {
    var body: some View {
        let completed = ((try? LocalData.shared.tasks.listTasks()) ?? []).filter { $0.state == "completed" || $0.result != nil }
        List {
            Section("交付成果聚合说明") {
                Text("平台任务交付通过 TaskService.submitResult 提交 branch / commit / pr_url；本地任务交付记录在 SQLite tasks.result 字段。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Section("已登记交付物 (\(completed.count))") {
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
            }
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
                }
                .padding(16)
            }
        }
        .background(Color(red: 0.98, green: 0.975, blue: 0.965).ignoresSafeArea())
        .onAppear(perform: loadProjects)
        .sheet(isPresented: $showNewProjectSheet) {
            NavigationStack {
                Form {
                    Section("项目基本信息") {
                        TextField("项目名称（如 wherevercode-agent）", text: $newProjectName)
                        TextField("Git 仓库地址（可选）", text: $newProjectRepo)
                        TextField("默认分支", text: $newProjectBranch)
                        TextField("工作区路径（如 Documents/workspace）", text: $newProjectPath)
                    }
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
        var list = (try? LocalData.shared.projects.listProjects()) ?? []
        if list.isEmpty {
            let defaultProj = Project(
                name: "WhereverCode Agent",
                repoUrl: "https://github.com/huaerdaidai-gif/wherevercode-agent.git",
                defaultBranch: "main",
                origin: "LOCAL_SANDBOX"
            )
            try? LocalData.shared.projects.saveProject(defaultProj)
            let loc = ProjectLocation(
                projectId: defaultProj.projectId,
                hostId: "iphone-local",
                workspacePath: "Documents/wherevercode-agent"
            )
            _ = try? LocalData.shared.projects.addLocation(loc)
            list = (try? LocalData.shared.projects.listProjects()) ?? []
        }
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
                        Section("项目信息") {
                            LabeledContent("项目名称", value: projectItem.project.name)
                            LabeledContent("默认分支", value: projectItem.project.defaultBranch)
                            if let repo = projectItem.project.repoUrl {
                                LabeledContent("仓库地址", value: repo)
                            }
                            LabeledContent("来源属性", value: projectItem.project.origin)
                        }
                        Section("工作区位置") {
                            ForEach(projectItem.homes) { loc in
                                LabeledContent(loc.hostId, value: loc.workspacePath)
                            }
                        }
                        Section("快捷操作") {
                            Button("新建此项目任务 (compose)") { onNavigateRoute("compose") }
                            Button("查看项目任务中心 (tasks)") { onNavigateRoute("tasks") }
                            Button("浏览沙盒文件 (files)") { onNavigateRoute("files") }
                            Button("打开代码编辑器 (editor)") { onNavigateRoute("editor") }
                            Button("执行环境与终端 (terminal)") { onNavigateRoute("terminal") }
                        }
                    }
                case .tasks:
                    TasksView(appState: appState)
                case .files:
                    SandboxFileBrowserScreen(onOpenEditor: { onNavigateRoute("editor") })
                case .editor:
                    SandboxCodeEditorScreen(onOpenDiff: { onNavigateRoute("diff") })
                case .diff:
                    UnifiedDiffScreen()
                case .artifacts:
                    ArtifactsDeliveryScreen()
                case .terminal:
                    TerminalAndExecutionTargetScreen(appState: appState)
                case .git:
                    GitWorktreeInspectorScreen()
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
    var onOpenEditor: () -> Void
    @State private var files: [String] = []
    @State private var newFileName = "notes.md"

    private var docsURL: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first ?? URL(fileURLWithPath: NSTemporaryDirectory())
    }

    var body: some View {
        List {
            Section("iOS 沙盒文件权限边界") {
                Text("iOS 采用应用沙盒机制：本页直接读写 App Documents 容器目录，跨应用文件通过 UIDocumentPickerViewController 安全书签导入。")
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
            }
            Section("App 沙盒 Documents 目录文件 (\(files.count))") {
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
        }
        .onAppear(perform: reload)
    }

    private func reload() {
        files = ((try? FileManager.default.contentsOfDirectory(atPath: docsURL.path)) ?? []).sorted()
    }
}

private struct SandboxCodeEditorScreen: View {
    var onOpenDiff: () -> Void
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
            HStack {
                TextField("文件名", text: $fileName)
                    .textFieldStyle(.roundedBorder)
                Button("读取") {
                    if let loaded = try? String(contentsOf: fileURL, encoding: .utf8) {
                        content = loaded
                        savedSnapshot = loaded
                        statusMsg = "已从沙盒读取 \(fileName)"
                    }
                }
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
                Button("对比 Diff") {
                    try? LocalData.shared.kv.set("editor.lastContent", content)
                    if savedSnapshot.isEmpty { savedSnapshot = content }
                    try? LocalData.shared.kv.set("editor.prevContent", savedSnapshot)
                    onOpenDiff()
                }
                .buttonStyle(.bordered)
            }
            .padding(.horizontal, 16)
            .padding(.top, 10)

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
    var body: some View {
        let prev = (try? LocalData.shared.kv.get("editor.prevContent")) ?? "// 原始版本\nfunc greet() {}"
        let curr = (try? LocalData.shared.kv.get("editor.lastContent")) ?? "// 修改后版本\nfunc greet() -> String { return \"V5\" }"
        let prevLines = prev.components(separatedBy: "\n")
        let currLines = curr.components(separatedBy: "\n")

        List {
            Section("逐行变更对比 (Line Diff)") {
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
        }
    }
}

/// 工作台核心要求：明确区分 iPhone 本地真实执行、远端设备执行、云端 Agent 执行、当前平台不支持（严禁将远端执行伪装成本地执行）
private struct TerminalAndExecutionTargetScreen: View {
    @ObservedObject var appState: AppState
    @ObservedObject var eventLog: InMemoryEventLog = LocalData.shared.eventLog
    @State private var selectedTarget: ExecutionTargetKind = .unsupportedOnPlatform

    var body: some View {
        List {
            Section("执行环境四态严格区分（反伪造门禁）") {
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
                        Text("本机真实可执行范围：SQLite WAL 事务（wherevercode-tasks.db）、Keychain 凭据存取、沙盒 Documents 文件读写、统一 Diff 计算、BrainEngine 多轮推理与流解析。")
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
            }

            Section("本机进程真实 EngineEvent 日志流 (\(eventLog.listEvents().count) 条)") {
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
            }
        }
    }
}

private struct GitWorktreeInspectorScreen: View {
    var body: some View {
        let projects = (try? LocalData.shared.projects.listProjects()) ?? []
        List {
            Section("Git 协作与分支元数据边界") {
                Text("iOS 本机不包含 git CLI 子进程；Git 分支（defaultBranch）、canonicalCommit 与 PR 链接通过 ProjectStore、GitHub API 工具或远端 Hand 协议的 repos 字段（git rev-parse 12位短哈希）真实同步。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Section("已登记项目的仓库与分支 (\(projects.count))") {
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
            }
        }
    }
}

private struct WorkbenchDevExtendedScreen: View {
    let route: FlagshipRoute

    var body: some View {
        List {
            Section("\(route.title) (\(route.id)) — 真实状态与平台边界") {
                switch route.id {
                case "preview":
                    Text("运行预览 (preview)：当前为规划契约页（DESIGN_ONLY）。iOS 端不伪造网页截图或假预览，产物验收截图须来自真实模拟器或真机运行。")
                case "browser":
                    Text("浏览器工作台 (browser)：当前为规划契约页（DESIGN_ONLY）。联网检索已通过 WebSearchClient 接入，受控浏览器录制/快照尚未接入服务端。")
                case "testmatrix":
                    Text("测试矩阵 (testmatrix)：展示双端 70 屏路由契约、10 状态机转换、SQLite 存储与 MCP 工具映射校验结果；机械检查默认标注 NOT_RUN 直至真机 CI 跑通。")
                default:
                    Text(route.title)
                }
            }
        }
    }
}

// MARK: - 3. 工作台 · 协作与连接子模块实现
private struct CollaborationAndAgentsScreen: View {
    let route: FlagshipRoute
    @ObservedObject var appState: AppState
    var onNavigateRoute: (String) -> Void

    @State private var agents: [AgentRecord] = []
    @State private var newAgentId = ""
    @State private var newAgentName = ""
    @State private var newAgentKind: AgentKind = .cloud

    var body: some View {
        List {
            Section("协作路由说明：\(route.title) (\(route.id))") {
                Text("本地与远端 Agent 登记存储于 SQLite kv 表 (agents.records.v1)。注意：外部 Agent 进程直接唤醒与跨端会话动态迁移在 V5 服务端尚无接口，当前可使用团队频道 (channels) 与本地/MCP 任务派单。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Section("登记新协作 Agent (AgentRegistryStore)") {
                TextField("Agent ID（如 codex-worker）", text: $newAgentId)
                    .textInputAutocapitalization(.never)
                TextField("显示名称（如 Codex Worker）", text: $newAgentName)
                Picker("类型", selection: $newAgentKind) {
                    ForEach(AgentKind.allCases, id: \.self) { k in
                        Text(k.rawValue).tag(k)
                    }
                }
                Button("保存到本地 Agent 注册表") {
                    let cleanId = newAgentId.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !cleanId.isEmpty else { return }
                    let rec = AgentRecord(
                        agentId: cleanId,
                        displayName: newAgentName.isEmpty ? cleanId : newAgentName,
                        kind: newAgentKind
                    )
                    try? LocalData.shared.agents.saveAgent(rec)
                    newAgentId = ""
                    newAgentName = ""
                    reload()
                }
            }

            Section("已登记 Agent 列表 (\(agents.count))") {
                if agents.isEmpty {
                    Text("暂无已登记 Agent").foregroundStyle(.secondary)
                } else {
                    ForEach(agents) { ag in
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text(ag.displayName).font(.headline)
                                Spacer()
                                Text(ag.memberConnectionState)
                                    .font(.caption2)
                                    .padding(.horizontal, 6)
                                    .padding(.vertical, 2)
                                    .background(Color.secondary.opacity(0.15), in: Capsule())
                            }
                            Text("ID: \(ag.agentId) · 类型: \(ag.kind.rawValue) · 接入: \(ag.access.rawValue)")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }
        }
        .onAppear(perform: reload)
    }

    private func reload() {
        agents = (try? LocalData.shared.agents.listAgents()) ?? []
    }
}

private struct McpToolsInspectorScreen: View {
    @ObservedObject var appState: AppState
    @State private var toolNames: [String] = []
    @State private var isLoading = false
    @State private var errorMsg: String?

    var body: some View {
        List {
            Section("V5 平台 MCP 端点") {
                LabeledContent("Endpoint", value: appState.settings.platformBaseURLString)
                LabeledContent("凭证状态", value: appState.isConfigured ? "Keychain 已配置" : "未配置 Token")
                Button(isLoading ? "正在探测 tools/list…" : "探测远端 MCP 工具清单 (listToolNames)") {
                    Task { await fetchTools() }
                }
                .disabled(isLoading)
            }
            if let err = errorMsg {
                Section("探测结果 / 错误") {
                    Text(err).font(.caption).foregroundStyle(.red)
                }
            }
            Section("已发现远端 MCP 工具 (\(toolNames.count))") {
                if toolNames.isEmpty {
                    Text("尚未拉取或未连接平台。已知标准 V5 工具包括：issue_agent_token、agent_channel_list、agent_channel_join、agent_channel_post、agent_channel_messages、agent_my_tasks、agent_task_claim、agent_task_progress、agent_task_heartbeat、agent_task_submit、agent_task_release。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(toolNames, id: \.self) { name in
                        Text(name).font(.system(.subheadline, design: .monospaced))
                    }
                }
            }
        }
    }

    private func fetchTools() async {
        guard let client = appState.makeMcpClient() else {
            errorMsg = "未配置平台 Agent 凭证：请先在设置里保存 Token。"
            return
        }
        isLoading = true
        defer { isLoading = false }
        do {
            toolNames = try await client.listToolNames()
            errorMsg = nil
        } catch {
            errorMsg = error.localizedDescription
        }
    }
}

private struct SkillsManagementScreen: View {
    @ObservedObject var skillStore: SkillStore = LocalData.shared.skillStore
    @State private var newName = ""
    @State private var newSummary = ""
    @State private var newTrigger = ""

    var body: some View {
        List {
            Section("已注册技能列表 (\(skillStore.skills.count))") {
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
            }
            Section("提炼并添加新技能") {
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
            }
        }
    }
}

private struct ConnectionExtendedScreen: View {
    let route: FlagshipRoute
    @ObservedObject var appState: AppState

    var body: some View {
        List {
            Section("\(route.title) (\(route.id))") {
                switch route.id {
                case "modelroute":
                    Text("智能模型路由与故障切换 (FailoverPolicy)：当前主大脑 \(appState.brainSettingsModel.currentBrainId ?? "未设置")，支持同大脑重试与 fallbackBrainIds 链式切换。")
                case "agentimport":
                    Text("导入现有 Agent (agentimport)：支持登记 Codex、Claude Code、OpenCode、DeepSeek Harness 等外部 Agent 元数据至 AgentRegistryStore。")
                case "network", "routes":
                    Text("通信网络与路径详情 (\(route.id))：iOS 端仅使用系统标准 URLSession HTTPS (强制 ATS TLS) 与 WebSocket，不内置私有 VPNService；跨端投递状态严格区分 PERSISTED / DELIVERED / CLAIMED / READ，断线缓存在本地 SQLite 绝不伪装已送达。")
                default:
                    Text(route.title)
                }
            }
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
    @State private var showCreateChannelSheet = false
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
            partitionPicker
            searchField

            if selectedPartition == .channels {
                channelsListView
            } else {
                directMessagesListView
            }
        }
        .background(Color(red: 0.969, green: 0.969, blue: 0.961).ignoresSafeArea())
        .onAppear(perform: loadData)
        .sheet(isPresented: $showCreateChannelSheet) {
            CreateChannelSheet(appState: appState) { newChannel in
                channels.insert(newChannel, at: 0)
            }
        }
        .sheet(isPresented: $showCreateDMSheet) {
            CreateDirectMessageSheet(appState: appState) { newDM in
                directMessages.insert(newDM, at: 0)
            }
        }
        .alert("删除频道", isPresented: $showDeleteConfirmation, presenting: channelToDelete) { ch in
            Button("取消", role: .cancel) {}
            Button("删除", role: .destructive) {
                channels.removeAll { $0.id == ch.id }
            }
        } message: { ch in
            Text("确定要删除「#\(ch.name)」吗？删除后该频道的消息记录将对所有成员不可见，此操作需要平台授权。")
        }
        .alert("安全规范提示", isPresented: $showPolicyAlert) {
            Button("我知道了", role: .cancel) {}
        } message: {
            Text(policyAlertMessage)
        }
        .navigationDestination(item: $activeWorkspaceChannel) { ch in
            ChannelDetailWorkspaceView(
                appState: appState,
                channelItem: ch,
                dmItem: nil,
                onNavigateRoute: onNavigateRoute
            )
        }
        .navigationDestination(item: $activeWorkspaceDM) { dm in
            ChannelDetailWorkspaceView(
                appState: appState,
                channelItem: nil,
                dmItem: dm,
                onNavigateRoute: onNavigateRoute
            )
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

            Text("频道与协作")
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))

            Spacer()

            Menu {
                Button {
                    showCreateChannelSheet = true
                } label: {
                    Label("新建频道", systemImage: "number")
                }
                .accessibilityIdentifier("channels.action.createChannel")

                Button {
                    showCreateDMSheet = true
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
            TextField(selectedPartition == .channels ? "搜索频道名称、简介或消息…" : "搜索私聊成员、Agent 或对话…", text: $searchQuery)
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
                    VStack(spacing: 8) {
                        Image(systemName: "bubble.left.and.bubble.right")
                            .font(.system(size: 40))
                            .foregroundStyle(.secondary.opacity(0.6))
                        Text(searchQuery.isEmpty ? "暂无私聊会话" : "未找到匹配的私聊")
                            .font(.headline)
                            .foregroundStyle(Color(red: 0.20, green: 0.19, blue: 0.18))
                        Text("点击右上角「＋」可发起与成员或 Agent 的 1:1 私聊\n（平台规范：严格禁止 Agent ↔ Agent 直连私聊）")
                            .font(.caption)
                            .multilineTextAlignment(.center)
                            .foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 40)
                } else {
                    ForEach(filteredDMs) { dm in
                        Button {
                            activeWorkspaceDM = dm
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
        if channels.isEmpty {
            channels = [
                V5ChannelItem(
                    id: "ch-general",
                    name: "全员协作",
                    topic: "WhereverCode 全平台项目总线与 Agent 协同讨论",
                    isPrivate: false,
                    associatedProjectId: "proj-wherevercode",
                    associatedProjectName: "WhereverCode V5",
                    memberCount: 5,
                    agentCount: 3,
                    lastMessageText: "@Codex 请确认当前 iOS 端 7 处核心 UI 截图与编译状态",
                    lastMessageTime: "12:45",
                    unreadCount: 2
                ),
                V5ChannelItem(
                    id: "ch-frontend",
                    name: "前端开发",
                    topic: "SwiftUI 与 Compose 原生端对齐及路由一致性审查",
                    isPrivate: false,
                    associatedProjectId: "proj-ios",
                    associatedProjectName: "iOS Native",
                    memberCount: 4,
                    agentCount: 2,
                    lastMessageText: "已完成左侧抽屉严格 4 菜单改造，正在运行 XCTest",
                    lastMessageTime: "12:30",
                    unreadCount: 0
                ),
                V5ChannelItem(
                    id: "ch-arch",
                    name: "架构设计",
                    topic: "BrainEngine、17 模型适配器、Task 10 状态机边界",
                    isPrivate: true,
                    associatedProjectId: "proj-brain",
                    associatedProjectName: "Brain Engine",
                    memberCount: 3,
                    agentCount: 1,
                    lastMessageText: "已合入 PR #13 安全修复，ATS 与 Keychain 策略严格保留",
                    lastMessageTime: "11:15",
                    unreadCount: 0
                ),
                V5ChannelItem(
                    id: "ch-qa",
                    name: "QA 验证",
                    topic: "自动化 CI、真机端到端验收与截图交付审查",
                    isPrivate: false,
                    associatedProjectId: nil,
                    associatedProjectName: nil,
                    memberCount: 6,
                    agentCount: 2,
                    lastMessageText: "模拟器与真机运行产物均已打标并归档",
                    lastMessageTime: "10:20",
                    unreadCount: 0
                )
            ]
        }

        if directMessages.isEmpty {
            directMessages = [
                V5DirectMessageItem(
                    id: "dm-codex",
                    targetId: "agent-codex",
                    targetName: "Codex",
                    isAgent: true,
                    targetRole: "设备 Agent · 代码实现与构建",
                    lastMessageText: "收到任务，正在执行 70 屏契约校验与截图导出。",
                    lastMessageTime: "12:44",
                    unreadCount: 1
                ),
                V5DirectMessageItem(
                    id: "dm-claude",
                    targetId: "agent-claude",
                    targetName: "Claude Code",
                    isAgent: true,
                    targetRole: "云端 Agent · 架构规划与重构",
                    lastMessageText: "建议将频道分为消息、文件、任务三个独立内部分区。",
                    lastMessageTime: "12:10",
                    unreadCount: 0
                ),
                V5DirectMessageItem(
                    id: "dm-user-lead",
                    targetId: "user-lead",
                    targetName: "项目主管",
                    isAgent: false,
                    targetRole: "平台管理员 · 审批中心",
                    lastMessageText: "验收标准以最终定稿为准，严禁自行合并 main 分支。",
                    lastMessageTime: "09:30",
                    unreadCount: 0
                )
            ]
        }
    }
}

/// 频道与私聊内部多功能工作台
public struct ChannelDetailWorkspaceView: View {
    @ObservedObject public var appState: AppState
    public var channelItem: V5ChannelItem?
    public var dmItem: V5DirectMessageItem?
    public var onNavigateRoute: (String) -> Void

    @State private var selectedTab: WorkspaceTab = .messages
    @State private var composerText = ""
    @State private var isSending = false
    @State private var messages: [V5ChannelMessage] = []
    @State private var files: [V5ChannelFile] = []
    @State private var activeTasks: [LocalTask] = []

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
        onNavigateRoute: @escaping (String) -> Void = { _ in }
    ) {
        self.appState = appState
        self.channelItem = channelItem
        self.dmItem = dmItem
        self.onNavigateRoute = onNavigateRoute
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
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
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
        .sheet(item: $selectedAgentProfile) { msg in
            AgentProfileSheet(message: msg)
        }
        .sheet(isPresented: $showChannelSettingsSheet) {
            ChannelSettingsSheet(channel: channelItem, dm: dmItem)
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
    }

    // MARK: - 工作台顶部
    private var topWorkspaceHeader: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(displayName)
                        .font(.system(size: 18, weight: .bold))
                        .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                    if let ch = channelItem, ch.isPrivate {
                        Image(systemName: "lock.fill")
                            .font(.system(size: 11))
                            .foregroundStyle(.secondary)
                    }
                }
                HStack(spacing: 6) {
                    Circle()
                        .fill(Color.green)
                        .frame(width: 7, height: 7)
                    Text("实时连接 · WebSocket live")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
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

                    // 关联任务卡片
                    if let tId = msg.linkedTaskId, let tTitle = msg.linkedTaskTitle {
                        HStack(spacing: 8) {
                            Image(systemName: "checklist")
                                .font(.system(size: 14))
                                .foregroundStyle(.blue)
                            VStack(alignment: .leading, spacing: 1) {
                                Text("关联任务: \(tTitle)")
                                    .font(.system(size: 12, weight: .medium))
                                Text("状态: \(msg.linkedTaskState ?? "working") · ID: \(String(tId.prefix(8)))")
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                        }
                        .padding(8)
                        .background(Color.blue.opacity(0.08), in: RoundedRectangle(cornerRadius: 8))
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

                TextField("发消息，长按头像 @ Agent…", text: $composerText)
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
                .disabled(composerText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && selectedAttachment == nil)
                .accessibilityIdentifier("channel.composer.send")
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(Color(red: 0.95, green: 0.94, blue: 0.92))
        }
    }

    private func sendMessage() {
        let text = composerText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty || selectedAttachment != nil else { return }

        let newMsg = V5ChannelMessage(
            id: UUID().uuidString,
            senderId: "user-me",
            senderName: "我",
            isAgent: false,
            text: text.isEmpty ? "分享了附件" : text,
            timestamp: "12:46",
            fullDateTime: "2026-11-15 12:46:00",
            replyToId: replyingTo?.id,
            replyToText: replyingTo?.text,
            attachmentName: selectedAttachment?.fileName,
            attachmentType: selectedAttachment?.fileType,
            attachmentSize: selectedAttachment?.fileSize
        )
        messages.append(newMsg)
        composerText = ""
        replyingTo = nil
        selectedAttachment = nil
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

    private func channelTaskCard(_ t: LocalTask) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(t.title)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                Spacer()
                Text(t.state)
                    .font(.caption2)
                    .fontWeight(.bold)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(Color.orange.opacity(0.15), in: Capsule())
                    .foregroundStyle(.orange)
            }

            Text((t.description?.isEmpty ?? true) ? "无详细任务描述" : (t.description ?? ""))
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
                .lineLimit(2)

            HStack {
                Text("优先级: P\(t.priority) · ID: \(String(t.id.prefix(8)))")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
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

    private func loadWorkspaceData() {
        if messages.isEmpty {
            messages = [
                V5ChannelMessage(
                    id: "msg-1",
                    senderId: "agent-codex",
                    senderName: "Codex",
                    isAgent: true,
                    text: "已完成 WhereverCode V5 iOS 导航重构基线，目前左侧抽屉严格包含「对话」「项目」「频道」「控制」四项一级入口。",
                    timestamp: "12:40",
                    fullDateTime: "2026-11-15 12:40:15",
                    linkedTaskId: "task-nav-1",
                    linkedTaskTitle: "iOS V5 导航整改",
                    linkedTaskState: "working"
                ),
                V5ChannelMessage(
                    id: "msg-2",
                    senderId: "user-lead",
                    senderName: "项目主管",
                    isAgent: false,
                    text: "@Codex 请确认频道内输入框是否严格保留单一「＋」入口并提供附件、网盘与任务关联？",
                    timestamp: "12:42",
                    fullDateTime: "2026-11-15 12:42:01",
                    replyToId: "msg-1",
                    replyToText: "已完成 WhereverCode V5 iOS 导航重构基线…"
                ),
                V5ChannelMessage(
                    id: "msg-3",
                    senderId: "agent-codex",
                    senderName: "Codex",
                    isAgent: true,
                    text: "确认完毕！输入框左侧为单一「＋」按钮，支持展开：上传附件、从项目选择、从网盘选择、关联任务。同时已在服务端与客户端强制校验 Agent ↔ Agent 私聊拦截。",
                    timestamp: "12:45",
                    fullDateTime: "2026-11-15 12:45:30",
                    attachmentName: "FlagshipApp.swift",
                    attachmentType: "code",
                    attachmentSize: "28 KB"
                )
            ]
        }

        if files.isEmpty {
            files = [
                V5ChannelFile(
                    id: "file-1",
                    fileName: "WhereverCodeAgent-unsigned.ipa",
                    fileSize: "18.4 MB",
                    fileType: "zip",
                    uploaderName: "GitHub Actions",
                    uploadTime: "11-15 12:45",
                    associatedProjectName: "WhereverCode V5",
                    sourceKind: "project"
                ),
                V5ChannelFile(
                    id: "file-2",
                    fileName: "routes_wiring.json",
                    fileSize: "42 KB",
                    fileType: "code",
                    uploaderName: "Codex",
                    uploadTime: "11-15 12:30",
                    associatedProjectName: "Contracts",
                    sourceKind: "project"
                ),
                V5ChannelFile(
                    id: "file-3",
                    fileName: "design_spec_claude_ui.pdf",
                    fileSize: "1.2 MB",
                    fileType: "pdf",
                    uploaderName: "主管",
                    uploadTime: "11-15 10:00",
                    sourceKind: "google_drive"
                )
            ]
        }

        activeTasks = (try? LocalData.shared.tasks.listTasks()) ?? []
    }
}

// MARK: - 辅助弹窗与选择器

public struct CreateChannelSheet: View {
    @ObservedObject public var appState: AppState
    public var onCreated: (V5ChannelItem) -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var name = ""
    @State private var topic = ""
    @State private var isPrivate = false
    @State private var selectedProject = ""

    public var body: some View {
        NavigationStack {
            Form {
                Section("频道信息") {
                    TextField("频道名称（例如：移动端重构）", text: $name)
                    TextField("讨论主题与简介", text: $topic)
                    Toggle("设为私有频道", isOn: $isPrivate)
                }

                Section("关联项目（可选）") {
                    Picker("项目", selection: $selectedProject) {
                        Text("不关联项目").tag("")
                        Text("WhereverCode V5").tag("proj-wherevercode")
                        Text("iOS Native").tag("proj-ios")
                    }
                }

                Section("安全规范提示") {
                    Text("频道成员可包含团队用户与受信任的 Agent。频道所有消息与执行操作受统一审计保护。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("新建频道")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("创建") {
                        let newCh = V5ChannelItem(
                            id: "ch-\(UUID().uuidString.prefix(6))",
                            name: name.isEmpty ? "未命名频道" : name,
                            topic: topic,
                            isPrivate: isPrivate,
                            associatedProjectId: selectedProject.isEmpty ? nil : selectedProject,
                            associatedProjectName: selectedProject.isEmpty ? nil : "关联项目",
                            memberCount: 2,
                            agentCount: 1,
                            lastMessageText: "频道已创建",
                            lastMessageTime: "刚刚"
                        )
                        onCreated(newCh)
                        dismiss()
                    }
                    .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }
}

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
                Section("私聊对象") {
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
                }

                Section("平台安全规则校验") {
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
                }

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
                    Section("基础信息") {
                        LabeledContent("唯一标识", value: message.senderId)
                        LabeledContent("身份类型", value: message.isAgent ? "Autonomous Agent" : "Human User")
                        LabeledContent("安全级别", value: "Verified · ATS TLS 强制")
                    }

                    Section("协作规范") {
                        Text("长按该 Agent 头像可在频道消息输入框快速插入 @ 召唤该 Agent 参与协作。")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
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
    @Environment(\.dismiss) private var dismiss

    public var body: some View {
        NavigationStack {
            Form {
                if let ch = channel {
                    Section("频道信息") {
                        LabeledContent("频道名称", value: "# \(ch.name)")
                        LabeledContent("频道性质", value: ch.isPrivate ? "私有频道" : "公开频道")
                        LabeledContent("讨论主题", value: ch.topic.isEmpty ? "无主题" : ch.topic)
                        LabeledContent("成员总数", value: "\(ch.memberCount) 人")
                        LabeledContent("已入驻 Agent", value: "\(ch.agentCount) 位")
                    }
                } else if let d = dm {
                    Section("私聊信息") {
                        LabeledContent("对象姓名", value: d.targetName)
                        LabeledContent("身份类型", value: d.isAgent ? "平台 Agent" : "团队成员")
                        LabeledContent("归属角色", value: d.targetRole)
                    }
                }

                Section("通知与权限") {
                    Toggle("消息免打扰", isOn: .constant(false))
                    Toggle("忽略未读提示", isOn: .constant(false))
                }
            }
            .navigationTitle("会话设置")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
    }
}

public struct ProjectFilePickerSheet: View {
    public var onSelect: (V5ChannelFile) -> Void
    @Environment(\.dismiss) private var dismiss

    private let sampleProjectFiles = [
        V5ChannelFile(id: "pf-1", fileName: "FlagshipApp.swift", fileSize: "28 KB", fileType: "code", uploaderName: "Git", associatedProjectName: "WhereverCode"),
        V5ChannelFile(id: "pf-2", fileName: "routes_wiring.json", fileSize: "42 KB", fileType: "code", uploaderName: "Git", associatedProjectName: "Contracts"),
        V5ChannelFile(id: "pf-3", fileName: "architecture.png", fileSize: "1.8 MB", fileType: "image", uploaderName: "Git", associatedProjectName: "Docs")
    ]

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

    private let sampleCloudFiles = [
        V5ChannelFile(id: "cf-1", fileName: "spec-v5-navigation.pdf", fileSize: "1.4 MB", fileType: "pdf", uploaderName: "Google Drive", sourceKind: "google_drive"),
        V5ChannelFile(id: "cf-2", fileName: "release_notes_build6.md", fileSize: "18 KB", fileType: "markdown", uploaderName: "OneDrive", sourceKind: "onedrive"),
        V5ChannelFile(id: "cf-3", fileName: "test_matrix_backup.zip", fileSize: "8.6 MB", fileType: "zip", uploaderName: "WebDAV", sourceKind: "webdav")
    ]

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
        let tasks = (try? LocalData.shared.tasks.listTasks()) ?? []
        let workingCount = tasks.filter { $0.state == "working" }.count
        let queuedCount = tasks.filter { $0.state == "queued" || $0.state == "assigned" }.count
        let attentionCount = tasks.filter { $0.needsAttention || $0.state == "blocked" || $0.state == "review" }.count
        let agentsCount = ((try? LocalData.shared.agents.listAgents()) ?? []).count
        let pendingApprovals = LocalData.shared.approvalStore.items.filter { $0.decision == nil }.count

        VStack(spacing: 0) {
            topNavBar

            ScrollView {
                VStack(spacing: 16) {
                    statusOverviewCard(
                        workingCount: workingCount,
                        queuedCount: queuedCount,
                        attentionCount: attentionCount,
                        pendingApprovals: pendingApprovals,
                        agentsCount: agentsCount
                    )

                    controlModulesList
                }
                .padding(16)
            }
        }
        .background(Color(red: 0.98, green: 0.975, blue: 0.965).ignoresSafeArea())
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
                    .fill(Color.green)
                    .frame(width: 8, height: 8)
                Text("平台就绪")
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
                    Text("平台连接与运行状态")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                    Text(appState.settings.platformBaseURLString.isEmpty ? "https://v5.cococolo.xyz" : appState.settings.platformBaseURLString)
                        .font(.system(size: 15, weight: .bold, design: .monospaced))
                        .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
                }
                Spacer()
                Text("本地优先")
                    .font(.caption2.weight(.medium))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(Color.green.opacity(0.12), in: Capsule())
                    .foregroundStyle(.green)
            }

            Divider()
                .padding(.vertical, 2)

            HStack(spacing: 20) {
                statusStatItem("活跃 Agent", value: "\(max(1, agentsCount))")
                statusStatItem("执行中任务", value: "\(workingCount)")
                statusStatItem("排队/待决", value: "\(queuedCount + attentionCount)")
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
    private var controlModulesList: some View {
        VStack(spacing: 12) {
            controlCard(
                title: "设备与 Hand 节点管理",
                subtitle: "本机 iPhone 本地沙盒 · 远端 Hand 节点互联互通",
                icon: "iphone.gen3",
                badge: "1 在线",
                routeId: "devices"
            )

            controlCard(
                title: "Agent 协作与编排",
                subtitle: "BrainEngine 智能调度 · 多 Agent 对比与协作编排",
                icon: "person.2.badge.gearshape",
                badge: "活跃",
                routeId: "agents"
            )

            controlCard(
                title: "模型提供方与智能路由",
                subtitle: "Claude, DeepSeek, OpenAI, Grok, Cloudflare 统一调度",
                icon: "cpu",
                badge: appState.brainSettingsModel.currentBrainId ?? "就绪",
                routeId: "models"
            )

            controlCard(
                title: "连接器与 MCP 工具池",
                subtitle: "23 个生产 MCP 工具 · 终端写入白名单与执行护栏",
                icon: "cable.connector.horizontal",
                badge: "23 工具",
                routeId: "mcp"
            )

            controlCard(
                title: "调度与任务队列",
                subtitle: "10 状态机合法流转 · 独立 isPaused 暂停/恢复",
                icon: "list.bullet.rectangle.portrait",
                badge: "队列",
                routeId: "queue"
            )

            controlCard(
                title: "实时事件与消息中心",
                subtitle: "进程实时事件日志 · 异常捕获与调度回执",
                icon: "bell.badge",
                badge: "事件流",
                routeId: "activity"
            )

            controlCard(
                title: "运行监控与用量",
                subtitle: "Token 消耗、响应延迟、SQLite 存储与性能监控",
                icon: "chart.xyaxis.line",
                badge: "健康",
                routeId: "dashboard"
            )

            controlCard(
                title: "安全审计与合规权限",
                subtitle: "Keychain 隔离 · ATS 强制 HTTPS · 敏感凭据自动脱敏",
                icon: "shield.lefthalf.filled",
                badge: "合规",
                routeId: "audit"
            )
        }
    }

    @ViewBuilder
    private func statusStatItem(_ title: String, value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value)
                .font(.system(size: 16, weight: .bold, design: .serif))
                .foregroundStyle(Color(red: 0.16, green: 0.15, blue: 0.14))
            Text(title)
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private func controlCard(title: String, subtitle: String, icon: String, badge: String, routeId: String) -> some View {
        Button {
            onNavigateRoute(routeId)
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
                            Text("WhereverCode 开发者")
                                .font(.system(size: 17, weight: .semibold))
                            Text("Pro")
                                .font(.caption2.bold())
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(Color.orange.opacity(0.15), in: Capsule())
                                .foregroundStyle(.orange)
                        }
                        Text("developer@wherevercode.local")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Text("本地优先 · 沙盒完全隔离")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(.vertical, 6)
            }

            // 分组一：账户 (Account)
            Section("账户") {
                NavigationLink {
                    ProfileEditScreen()
                } label: {
                    Label("个人资料", systemImage: "person.circle")
                }

                NavigationLink {
                    PrivacyAndSecurityScreen()
                } label: {
                    Label("隐私与安全 (Keychain / ATS)", systemImage: "lock.shield")
                }

                Button {
                    onNavigateRoute("sync")
                } label: {
                    Label("跨端同步 (Sync)", systemImage: "arrow.triangle.2.circlepath")
                }
                .foregroundStyle(.primary)

                Button {
                    onNavigateRoute("recovery")
                } label: {
                    Label("数据备份与恢复 (Recovery)", systemImage: "arrow.counterclockwise")
                }
                .foregroundStyle(.primary)
            }

            // 分组二：应用 (Application)
            Section("应用") {
                NavigationLink {
                    BrainSettingsView(model: appState.brainSettingsModel)
                } label: {
                    Label("AI 模型与推理配置 (Brain Engine)", systemImage: "cpu")
                }

                NavigationLink {
                    PlatformConnectionFormScreen(appState: appState)
                } label: {
                    Label("平台连接与通道凭证 (Platform)", systemImage: "network")
                }

                Button {
                    onNavigateRoute("mcp")
                } label: {
                    Label("连接器与 MCP 插件 (Connectors)", systemImage: "cable.connector")
                }
                .foregroundStyle(.primary)

                Button {
                    onNavigateRoute("voice")
                } label: {
                    Label("语音与多模态设置 (Voice)", systemImage: "waveform")
                }
                .foregroundStyle(.primary)

                Button {
                    onNavigateRoute("notifications")
                } label: {
                    Label("消息与系统通知 (Notifications)", systemImage: "bell")
                }
                .foregroundStyle(.primary)

                Button {
                    onNavigateRoute("memory")
                } label: {
                    Label("长期记忆与上下文 (Memory)", systemImage: "brain")
                }
                .foregroundStyle(.primary)

                Button {
                    onNavigateRoute("personas")
                } label: {
                    Label("Agent 个性与配置 (Personas)", systemImage: "theatermasks")
                }
                .foregroundStyle(.primary)

                Button {
                    onNavigateRoute("onboarding")
                } label: {
                    Label("首次使用指南 (Onboarding)", systemImage: "sparkles")
                }
                .foregroundStyle(.primary)
            }

            // 分组三：开发者诊断 (Developer Diagnostics & 70 Routes Catalog)
            Section("开发者诊断与 70 屏全量矩阵") {
                NavigationLink {
                    DeveloperDiagnosticsScreen(
                        capabilities: capabilities,
                        onNavigateRoute: onNavigateRoute
                    )
                } label: {
                    HStack {
                        Label("70 屏全量路由调试矩阵", systemImage: "square.grid.3x3.topleft.filled")
                        Spacer()
                        Text("70 路由")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .accessibilityIdentifier("settings.developerDiagnostics")

                LabeledContent("Keychain 安全级别", value: "WhenUnlockedThisDeviceOnly")
                LabeledContent("ATS 网络边界", value: "强制 HTTPS (无明文)")
                LabeledContent("SQLite 状态", value: "WAL 模式 · 正常")
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
    @State private var nickname = "WhereverCode 开发者"
    @State private var email = "developer@wherevercode.local"

    public var body: some View {
        Form {
            Section("基本信息") {
                TextField("昵称", text: $nickname)
                TextField("邮箱", text: $email)
            }
            Section("设备沙盒信息") {
                LabeledContent("平台模式", value: "iOS 本地优先沙盒")
                LabeledContent("Keychain 隔离", value: "独立钥匙串群组")
            }
        }
        .navigationTitle("个人资料")
        .navigationBarTitleDisplayMode(.inline)
    }
}

public struct PrivacyAndSecurityScreen: View {
    public var body: some View {
        List {
            Section("数据存储与钥匙串保护") {
                Text("所有 API Key、平台 Token 与敏感凭据均写入 iOS 本地钥匙串，采用 kSecAttrAccessibleWhenUnlockedThisDeviceOnly 保护，禁止 iCloud 跨设备自动外泄。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Section("网络通信安全边界 (ATS)") {
                Text("App 强制启用 App Transport Security (ATS)，全局禁止明文 HTTP 传输，仅允许加密 HTTPS 连接。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Section("敏感数据脱敏 (SecretRedactor)") {
                Text("控制台日志与会话记录中若检测到类似 sk-、Bearer 等敏感格式凭据，将在渲染前自动掩码处理。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
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
            Section("70 屏功能路由全景 (\(filtered.count)/70)") {
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
            }
        }
        .navigationTitle("70 屏路由矩阵")
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
    var onNavigateRoute: (String) -> Void

    var body: some View {
        List {
            Section("首次使用（本地优先，无需登录平台即可开始）") {
                Text("1. 本地对话与任务：配置任意模型 API Key（仅存本机 Keychain），即可在对话首页与本地 SQLite 会话/任务账本工作。\n2. 团队频道与平台任务：可选在「设置」中填入 V5 平台 HTTPS 地址与平台凭证。\n3. 跨端与沙盒边界：iOS 端严格遵守系统沙盒，不伪造本地终端。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Button("第 1 步：配置推理模型 (models)") { onNavigateRoute("models") }
                Button("第 2 步：可选配置 V5 平台连接 (settings)") { onNavigateRoute("settings") }
                Button("第 3 步：返回极简对话首页 (chat)") { onNavigateRoute("chat") }
            }
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
                Section("实时事件与消息回执流 (\(events.count) 条)") {
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
                }
            case "audit":
                Section("安全审计与密钥隔离 (Keychain + SecretRedactor)") {
                    LabeledContent("Keychain 隔离级别", value: "kSecAttrAccessibleWhenUnlockedThisDeviceOnly")
                    LabeledContent("日志脱敏规则", value: "自动掩码 sk-* / Bearer / v5_* / PEM 私钥")
                }
                Section("审批与控制审计记录 (\(auditTrail.count) 条)") {
                    if auditTrail.isEmpty {
                        Text("暂无高危审批操作记录").foregroundStyle(.secondary)
                    } else {
                        ForEach(auditTrail, id: \.self) { line in
                            Text(line).font(.caption2)
                        }
                    }
                }
            case "usage":
                Section("Token 用量与成本统计 (来自真实流事件)") {
                    let usageEvents = events.filter { $0.kind == "USAGE" }
                    LabeledContent("已记录模型调用计费事件数", value: "\(usageEvents.count)")
                    ForEach(usageEvents) { u in
                        Text(u.detail).font(.caption)
                    }
                }
            case "sync":
                Section("跨端同步与投递四态语义") {
                    Text("严格区分：\n• PERSISTED：仅写入本机 SQLite 数据库\n• DELIVERED：远端通道已确认接收\n• CLAIMED：目标 Agent 已认领并持有租约\n• READ：终端用户或审查人已读\n断线期间本地操作仅标为 PERSISTED，绝不伪装已送达。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            case "recovery":
                Section("执行暂停、恢复、停止及人工接管") {
                    Text("• 本地大脑轮次：支持随时停止当前流生成并写入 STOPPED 事件。\n• 本地 SQLite 任务：支持独立切换 isPaused 布尔位，或流转至 requeued / cancelled。\n• 远端 V5 平台任务：\(ReservedFeature.stopTask.notImplementedReason)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Button("前往任务中心执行状态恢复或放手") { onNavigateRoute("tasks") }
                }
            case "baseline":
                Section("70 屏双端 UI 组装基线核验") {
                    LabeledContent("总路由数", value: "\(FlagshipRoutes.all.count) / 70")
                    LabeledContent("一级抽屉主项", value: FlagshipDrawerMenu.allCases.map(\.rawValue).joined(separator: "、"))
                    LabeledContent("已接线真实功能路由", value: "\(AppFlagshipCapabilities.implementedRoutes.count) 屏")
                    LabeledContent("iOS 平台受限路由", value: "\(AppFlagshipCapabilities.platformRestrictedRoutes.count) 屏 (terminal)")
                }
            default:
                Text(route.title)
            }
        }
    }
}

private struct ConsoleAutomationSubScreen: View {
    let route: FlagshipRoute

    var body: some View {
        List {
            Section("\(route.title) (\(route.id)) — 真实护栏参数与 iOS 后台限制") {
                LabeledContent("单轮最大工具调用轮数 (maxToolRounds)", value: "6 轮")
                LabeledContent("单轮最大工具调用次数 (maxToolCallsPerTurn)", value: "12 次")
                LabeledContent("单轮超时上限 (maxTurnMillis)", value: "180,000 ms")
                LabeledContent("上下文压缩预算 (budgetEstimatedTokens)", value: "\(LocalData.shared.contextManager.budgetEstimatedTokens) tokens")
            }
            Section("iOS 平台后台与自动化触发限制说明") {
                Text("iOS 系统严格限制后台常驻进程与任意定时唤醒（BGTaskScheduler 仅由系统按电量与使用习惯调度，不保证准点触发）。自动化触发器 (triggers / wakepolicy / automation) 在服务端规则账本未接入前保持 DESIGN_ONLY / ADAPTER_PENDING 状态，不伪造后台常驻定时执行。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

// MARK: - 5. 设置 · 智能与扩展子模块实现
private struct SettingsIntelligenceAndExtensionsScreen: View {
    let route: FlagshipRoute
    @ObservedObject var appState: AppState
    @State private var newMemKey = ""
    @State private var newMemVal = ""
    @State private var searchQuery = ""
    @State private var searchHits: [WebSearchHit] = []
    @State private var searchError: String?

    var body: some View {
        List {
            switch route.id {
            case "memory":
                Section("新增长期记忆条目 (MemoryStore)") {
                    TextField("记忆键（如代码风格偏好）", text: $newMemKey)
                    TextField("记忆内容（自动经 SecretRedactor 脱敏）", text: $newMemVal)
                    Button("保存记忆") {
                        guard !newMemKey.isEmpty, !newMemVal.isEmpty else { return }
                        LocalData.shared.memoryStore.addFact(key: newMemKey, value: newMemVal, confirmedByUser: true)
                        newMemKey = ""
                        newMemVal = ""
                    }
                }
                Section("已存记忆 (\(LocalData.shared.memoryStore.listEntries().count))") {
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
                }
            case "skillsmarket", "skillbuilder":
                SkillsManagementScreen()
            case "context":
                Section("上下文预算与自动压缩配置 (ContextManager)") {
                    LabeledContent("估算预算上限", value: "\(LocalData.shared.contextManager.budgetEstimatedTokens) tokens")
                    LabeledContent("字符/Token 换算比", value: "\(LocalData.shared.contextManager.charsPerToken) chars/token")
                    Text("当历史消息超出预算时，自动压缩最旧对话轮次并注入 [上下文压缩] 摘要标记。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            case "research":
                Section("联网深度研究 (WebSearchClient)") {
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
                }
            default:
                Section("\(route.title) (\(route.id))") {
                    Text("扩展中心、插件权限与 Harness 运行时：iOS 端由于系统禁止动态加载未签名二进制与外部 CLI，本地仅支持元数据校验与远端 Hand / 云端 MCP 桥接调用。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
    }
}
