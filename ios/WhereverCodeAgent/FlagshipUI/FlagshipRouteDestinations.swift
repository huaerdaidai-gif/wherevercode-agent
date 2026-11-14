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
            routeStatusBanner
            Divider()
            routeContent
        }
        .navigationTitle("\(route.title) (\(route.id))")
        .navigationBarTitleDisplayMode(.inline)
    }

    @ViewBuilder
    private var routeStatusBanner: some View {
        let status = capabilities.status(for: route.id)
        HStack(alignment: .center, spacing: 10) {
            Text("父级：\(route.parent.rawValue) / \(route.group)")
                .font(.caption.weight(.semibold))
            Text("·")
            Text(status.state.rawValue)
                .font(.caption2.bold())
                .padding(.horizontal, 8)
                .padding(.vertical, 3)
                .background(badgeColor(for: status.state).opacity(0.18), in: Capsule())
                .foregroundStyle(badgeColor(for: status.state))
            Spacer()
            Text(route.p0 ? "P0 核心" : "旗舰扩展")
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(Color(red: 0.96, green: 0.95, blue: 0.93))
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

        // MARK: - 二、工作台 · 开发 (10 路由)
        case "projects", "projectdetail":
            ProjectsWorkbenchScreen(showDetailByDefault: route.id == "projectdetail", onNavigateRoute: onNavigateRoute)
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
            ChatListView(appState: appState)
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

        // MARK: - 三、全局控制台 · 系统与自动化 (17 路由)
        case "dashboard":
            GlobalConsoleDashboardScreen(appState: appState, onNavigateRoute: onNavigateRoute)
        case "settings":
            SettingsView(appState: appState)
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
                        idempotencyKey: Planner.idempotencyKey(taskId: taskId, seq: 1)
                    )
                    let plan = Plan(goal: goalText, steps: [action], risks: ["写入类操作需经过 ApprovalGate 审批"])
                    let errs = Planner.validate(plan, registeredTools: DefaultiOSToolRegistry().registeredToolNames, taskId: taskId)
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

private struct ProjectsWorkbenchScreen: View {
    let showDetailByDefault: Bool
    var onNavigateRoute: (String) -> Void
    @State private var projects: [ProjectWithHomes] = []
    @State private var newName = ""
    @State private var newRepo = ""
    @State private var newBranch = "main"
    @State private var newPath = ""

    var body: some View {
        List {
            Section("新建或登记项目工作区 (SQLite: projects + project_locations)") {
                TextField("项目名称（如 wherevercode-agent）", text: $newName)
                TextField("Git 仓库地址（可选）", text: $newRepo)
                TextField("默认分支", text: $newBranch)
                TextField("工作区路径（如 Documents/workspace）", text: $newPath)
                Button("保存项目与位置") {
                    let clean = newName.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !clean.isEmpty else { return }
                    let proj = Project(name: clean, repoUrl: newRepo.isEmpty ? nil : newRepo, defaultBranch: newBranch, origin: "LOCAL_SANDBOX")
                    try? LocalData.shared.projects.saveProject(proj)
                    let loc = ProjectLocation(
                        projectId: proj.projectId,
                        hostId: "iphone-local",
                        workspacePath: newPath.isEmpty ? "Documents/\(clean)" : newPath
                    )
                    _ = try? LocalData.shared.projects.addLocation(loc)
                    newName = ""
                    newRepo = ""
                    newPath = ""
                    reload()
                }
                .buttonStyle(.borderedProminent)
            }

            Section("已登记项目列表 (\(projects.count))") {
                if projects.isEmpty {
                    Text("暂无已保存项目").foregroundStyle(.secondary)
                } else {
                    ForEach(projects) { item in
                        VStack(alignment: .leading, spacing: 6) {
                            HStack {
                                Text(item.project.name).font(.headline)
                                Spacer()
                                Text(item.project.defaultBranch)
                                    .font(.caption2)
                                    .padding(.horizontal, 6)
                                    .padding(.vertical, 2)
                                    .background(Color.secondary.opacity(0.15), in: Capsule())
                            }
                            if let repo = item.project.repoUrl {
                                Text(repo).font(.caption).foregroundStyle(.secondary)
                            }
                            ForEach(item.homes) { loc in
                                Text("位置 [\(loc.hostId)]：\(loc.workspacePath) (\(loc.availability))")
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                            HStack {
                                Button("浏览沙盒文件 (files)") { onNavigateRoute("files") }
                                    .font(.caption)
                                Button("打开编辑器 (editor)") { onNavigateRoute("editor") }
                                    .font(.caption)
                                Button("Git 检查 (git)") { onNavigateRoute("git") }
                                    .font(.caption)
                            }
                        }
                        .padding(.vertical, 4)
                    }
                }
            }
        }
        .onAppear(perform: reload)
    }

    private func reload() {
        projects = (try? LocalData.shared.projects.listProjects()) ?? []
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

// MARK: - 4. 全局控制台子模块实现
private struct GlobalConsoleDashboardScreen: View {
    @ObservedObject var appState: AppState
    var onNavigateRoute: (String) -> Void

    var body: some View {
        let tasks = (try? LocalData.shared.tasks.listTasks()) ?? []
        let workingCount = tasks.filter { $0.state == "working" }.count
        let queuedCount = tasks.filter { $0.state == "queued" || $0.state == "requeued" || $0.state == "assigned" }.count
        let attentionCount = tasks.filter { $0.needsAttention || $0.state == "blocked" || $0.state == "review" || $0.state == "rework" }.count
        let agentsCount = ((try? LocalData.shared.agents.listAgents()) ?? []).count
        let events = LocalData.shared.eventLog.listEvents()
        let pendingApprovals = LocalData.shared.approvalStore.items.filter { $0.decision == nil }.count

        List {
            Section("运行总览与待处理中心（全部来自本机 SQLite / 内存真实状态）") {
                LabeledContent("平台连接状态", value: appState.connectionStatus.displayText)
                LabeledContent("当前活跃大脑", value: appState.brainSettingsModel.currentBrainId ?? "未配置")
                LabeledContent("执行中任务 (working)", value: "\(workingCount)")
                LabeledContent("排队/待领任务 (queued/assigned)", value: "\(queuedCount)")
                LabeledContent("阻塞/待审/返工 (needsAttention)", value: "\(attentionCount)")
                LabeledContent("待决高危审批 (ApprovalGate)", value: "\(pendingApprovals)")
                LabeledContent("已登记协作 Agent", value: "\(agentsCount)")
                LabeledContent("当前进程实时事件数", value: "\(events.count)")
            }

            Section("控制台核心入口直达") {
                Button("任务、队列、依赖与派单 (tasks / queue)") { onNavigateRoute("tasks") }
                Button("设备与 Agent 管理 (devices / agents)") { onNavigateRoute("devices") }
                Button("审批、审查、返工与交付 (approvals / review / artifacts)") { onNavigateRoute("approvals") }
                Button("实时事件、日志与异常 (activity / notifications)") { onNavigateRoute("activity") }
                Button("恢复、回滚与人工接管 (recovery)") { onNavigateRoute("recovery") }
                Button("工作流、自动化与护栏 (loopguard / automation)") { onNavigateRoute("loopguard") }
                Button("模型、运行时、连接器与 MCP (models / mcp)") { onNavigateRoute("mcp") }
                Button("网络、同步、离线与回执 (network / sync)") { onNavigateRoute("sync") }
                Button("用量、成本、资源与安全审计 (usage / audit)") { onNavigateRoute("audit") }
                Button("Claude 18 屏与 70 屏基线验收核对 (baseline)") { onNavigateRoute("baseline") }
            }
        }
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
                let usageEvents = events.filter { $0.kind == "USAGE" }
                Section("Token 用量与成本统计 (来自真实流事件)") {
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
                    LabeledContent("一级抽屉父项", value: FlagshipDrawerParent.allCases.map(\.rawValue).joined(separator: "、"))
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
                let entries = LocalData.shared.memoryStore.listEntries()
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
                Section("已存记忆 (\(entries.count))") {
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
                                searchHits = try await WebSearchClient().search(query: searchQuery)
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
