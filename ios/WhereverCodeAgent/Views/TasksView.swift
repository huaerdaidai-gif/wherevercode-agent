import SwiftUI

public struct TasksView: View {
    @ObservedObject public var appState: AppState
    @State private var showPlatform = false
    @State private var localTasks: [LocalTask] = []
    @State private var platformTasks: [AgentTask] = []
    @State private var isLoading = false
    @State private var errorMessage: String?

    public init(appState: AppState) {
        self.appState = appState
    }

    public var body: some View {
        VStack(spacing: 0) {
            Picker("任务来源", selection: $showPlatform) {
                Text("本地任务账本 (SQLite)").tag(false)
                Text("V5 平台分配任务 (MCP)").tag(true)
            }
            .pickerStyle(.segmented)
            .padding(12)

            List {
                if !showPlatform {
                    Section {
                        Text("本地任务执行器遵循统一 10 状态机（queued / assigned / working / blocked / review / rework / requeued / completed / failed / cancelled），pause 为独立布尔位。")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    if localTasks.isEmpty {
                        Text("暂无本地任务")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(localTasks) { task in
                            NavigationLink {
                                LocalTaskDetailScreen(task: task, onUpdated: loadLocalTasks)
                            } label: {
                                VStack(alignment: .leading, spacing: 4) {
                                    HStack {
                                        Text(task.title).font(.headline)
                                        Spacer()
                                        Text(TaskStates.displayLabel(for: task.state, isPaused: task.isPaused))
                                            .font(.caption2)
                                            .padding(.horizontal, 8)
                                            .padding(.vertical, 3)
                                            .background(Color.secondary.opacity(0.15), in: Capsule())
                                    }
                                    if let desc = task.description, !desc.isEmpty {
                                        Text(desc).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                                    }
                                    Text("版本 v\(task.version) · 目标版本 g\(task.goalVersion) · 授权纪元 e\(task.authEpoch)")
                                        .font(.caption2)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                } else {
                    Section {
                        Text("一次只领一个任务；领取后租约 2 小时，汇报进度或心跳可续租。平台没有创建 / 指派任务的接口，本页只做已分配任务的领取与交付。")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    if isLoading {
                        HStack {
                            ProgressView()
                            Text("正在加载任务…")
                        }
                    } else if let err = errorMessage {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(err).font(.footnote).foregroundStyle(.red)
                            Button("重试") { Task { await loadPlatformTasks() } }
                        }
                    } else if platformTasks.isEmpty {
                        Text("当前没有分配给你的任务。")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(platformTasks) { pTask in
                            NavigationLink {
                                TaskDetailView(appState: appState, task: pTask)
                            } label: {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(pTask.title).font(.headline)
                                    Text("状态：\(pTask.status) · 版本 v\(pTask.version)")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("任务中心")
        .onAppear {
            loadLocalTasks()
            if showPlatform {
                Task { await loadPlatformTasks() }
            }
        }
        .onChange(of: showPlatform) { _, isPlat in
            if isPlat {
                Task { await loadPlatformTasks() }
            } else {
                loadLocalTasks()
            }
        }
    }

    private func loadLocalTasks() {
        localTasks = (try? LocalData.shared.tasks.listTasks()) ?? []
    }

    private func loadPlatformTasks() async {
        guard let client = appState.makeMcpClient() else {
            errorMessage = "还没有连上平台：任务来自平台分配。连接平台后，这里会列出分配给你的任务。"
            return
        }
        isLoading = true
        defer { isLoading = false }
        do {
            platformTasks = try await TaskService(client: client).listMyTasks()
            errorMessage = nil
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

public struct LocalTaskDetailScreen: View {
    @State public var task: LocalTask
    public var onUpdated: () -> Void
    @State private var events: [TaskEvent] = []
    @State private var transitionNote: String = ""
    @State private var errorMsg: String?

    public var body: some View {
        List {
            Section("任务基本信息") {
                LabeledContent("标题", value: task.title)
                LabeledContent("当前状态", value: TaskStates.displayLabel(for: task.state, isPaused: task.isPaused))
                LabeledContent("独立暂停位 (isPaused)", value: task.isPaused ? "true (已暂停)" : "false (正常运行)")
                LabeledContent("指派 Agent", value: task.assignedAgentId ?? "未指派")
                LabeledContent("执行栅栏", value: "v\(task.version) / goalVersion=\(task.goalVersion) / authEpoch=\(task.authEpoch)")
                if let desc = task.description {
                    Text(desc).font(.footnote)
                }
            }

            Section("状态机合法流转与控制操作") {
                TextField("流转备注（可选）", text: $transitionNote)
                let allowed = Array(TaskStates.allowedTransitions[task.state] ?? []).sorted()
                if allowed.isEmpty {
                    Text("当前处于终态 (\(task.state))，不可直接流转。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(allowed, id: \.self) { nextState in
                        Button("流转至 -> \(TaskStates.displayLabel(for: nextState))") {
                            do {
                                task = try LocalData.shared.tasks.transition(
                                    taskId: task.taskId,
                                    to: nextState,
                                    note: transitionNote.isEmpty ? nil : transitionNote
                                )
                                transitionNote = ""
                                events = (try? LocalData.shared.tasks.listEvents(taskId: task.taskId)) ?? []
                                onUpdated()
                            } catch {
                                errorMsg = error.localizedDescription
                            }
                        }
                    }
                }
                Button(task.isPaused ? "恢复任务 (isPaused = false)" : "暂停任务 (isPaused = true，不改变主状态)") {
                    task.isPaused.toggle()
                    try? LocalData.shared.tasks.updateTask(task)
                    onUpdated()
                }
            }

            if let err = errorMsg {
                Section("错误提示") {
                    Text(err).foregroundStyle(.red).font(.caption)
                }
            }

            Section("任务事件审计流 (task_events)") {
                if events.isEmpty {
                    Text("暂无事件记录").foregroundStyle(.secondary)
                } else {
                    ForEach(events) { ev in
                        VStack(alignment: .leading, spacing: 3) {
                            Text("\(ev.kind): \(ev.fromState ?? "-") → \(ev.toState ?? "-")")
                                .font(.caption.bold())
                            if let note = ev.note {
                                Text(note).font(.caption2).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("本地任务详情")
        .onAppear {
            events = (try? LocalData.shared.tasks.listEvents(taskId: task.taskId)) ?? []
        }
    }
}

public struct TaskDetailView: View {
    @ObservedObject public var appState: AppState
    public let task: AgentTask

    @State private var progressText = ""
    @State private var summaryText = ""
    @State private var branchText = ""
    @State private var commitText = ""
    @State private var prURLText = ""
    @State private var releaseReasonText = ""
    @State private var isBusy = false
    @State private var resultMessage: String?

    public init(appState: AppState, task: AgentTask) {
        self.appState = appState
        self.task = task
    }

    public var body: some View {
        Form {
            Section("平台任务概览") {
                LabeledContent("任务 ID", value: task.id)
                LabeledContent("标题", value: task.title)
                LabeledContent("状态", value: task.status)
                LabeledContent("版本", value: "v\(task.version)")
                if !task.detail.isEmpty {
                    Text(task.detail).font(.footnote)
                }
            }

            Section("认领与续租 (MCP: agent_task_claim / progress / heartbeat)") {
                Button("认领任务 (claim)") {
                    Task { await runAction { try await $0.claim(taskID: task.id, expectedVersion: task.version) } }
                }
                .disabled(isBusy)

                TextField("进度说明", text: $progressText)
                Button("汇报进度（续租 2 小时）") {
                    Task { await runAction { try await $0.reportProgress(taskID: task.id, text: progressText, expectedVersion: task.version); return [:] } }
                }
                .disabled(isBusy || progressText.isEmpty)

                Button("心跳（只续租，不记进度）") {
                    Task { await runAction { try await $0.heartbeat(taskID: task.id, expectedVersion: task.version); return [:] } }
                }
                .disabled(isBusy)
            }

            Section("提交结果 (MCP: agent_task_submit)") {
                Text("开发任务交活时，分支、提交或 PR 链接至少要给一个，否则平台会拒绝。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                TextField("做了什么、怎么验证的…", text: $summaryText)
                TextField("分支（branch）", text: $branchText)
                TextField("提交 SHA（commit）", text: $commitText)
                TextField("PR 链接（pr_url）", text: $prURLText)
                Button("提交交付成果") {
                    Task {
                        await runAction {
                            try await $0.submitResult(
                                taskID: task.id,
                                summary: summaryText,
                                branch: branchText,
                                commit: commitText,
                                prURL: prURLText,
                                verdict: "PASS",
                                expectedVersion: task.version
                            )
                        }
                    }
                }
                .disabled(isBusy || summaryText.isEmpty)
            }

            Section("放手任务與服务端控制边界") {
                TextField("放手原因（可空）", text: $releaseReasonText)
                Button("放手任务 (agent_task_release)", role: .destructive) {
                    Task { await runAction { try await $0.release(taskID: task.id, reason: releaseReasonText); return [:] } }
                }
                .disabled(isBusy)

                Text(ReservedFeature.pauseTask.notImplementedReason)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                Text(ReservedFeature.stopTask.notImplementedReason)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            if let msg = resultMessage {
                Section("执行回执") {
                    Text(msg).font(.footnote)
                }
            }
        }
        .navigationTitle("平台任务详情")
    }

    private func runAction(_ block: (TaskService) async throws -> [String: Any]) async {
        guard let client = appState.makeMcpClient() else {
            resultMessage = "未配置平台地址或 Token。"
            return
        }
        isBusy = true
        defer { isBusy = false }
        do {
            _ = try await block(TaskService(client: client))
            resultMessage = "操作成功，状态已按平台返回刷新。"
        } catch {
            resultMessage = error.localizedDescription
        }
    }
}
