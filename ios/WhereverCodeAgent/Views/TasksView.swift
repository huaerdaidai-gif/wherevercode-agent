// WhereverCode Agent — iOS
//
// My tasks: the real `list_my_tasks` list plus a detail PAGE with
// the real lifecycle actions (claim / progress / heartbeat /
// submit / release), wired to the V5 MCP tools exactly as the
// server defines them. After every action the task is re-read from
// the server, so the status and version on screen are the server's,
// not a local guess. The reserved controls (approval / pause /
// resume / stop / retarget) have NO server tool: they are shown
// disabled and labelled "服务端无此接口，阻塞中" — never faked.

import SwiftUI

/// Pure task-list content: renders exactly the tasks it is given
/// (plus the load-error section). Split out from TasksView so the
/// layout is testable/renderable without a live platform.
struct TaskListContentView: View {
    let tasks: [AgentTask]
    let loadError: String?
    let onRetry: () -> Void
    let onRefresh: () -> Void

    var body: some View {
        List {
            if let loadError {
                Section("连接平台失败") {
                    Text(loadError)
                        .font(.subheadline)
                        .foregroundStyle(.red)
                        .textSelection(.enabled)
                    Button("重试") { onRetry() }
                }
            }
            Section("我的任务") {
                if tasks.isEmpty {
                    Text(loadError == nil
                         ? "当前没有分配给你的任务。"
                         : "任务加载失败，点上面的「重试」再试一次。")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(tasks) { task in
                        NavigationLink {
                            TaskDetailView(task: task)
                        } label: {
                            TaskRow(task: task)
                        }
                    }
                }
            }
            Section("说明") {
                Text("一次只领一个任务；领取后租约 2 小时，汇报进度或心跳可续租。平台没有创建 / 指派任务的接口，本页只做已分配任务的领取与交付。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .refreshable { onRefresh() }
    }
}

/// One task row in the list.
struct TaskRow: View {
    let task: AgentTask

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(task.displayTitle)
                .font(.headline)
            if !task.status.isEmpty {
                Text(task.status)
                    .font(.caption)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 2)
                    .background(Color.secondary.opacity(0.15))
                    .clipShape(Capsule())
            }
            Text(task.id)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }
}

struct TasksView: View {
    @EnvironmentObject private var appState: AppState
    @State private var tasks: [AgentTask] = []
    @State private var isLoading = false
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            Group {
                if !appState.isConfigured {
                    NotConfiguredView(
                        message: "任务列表来自平台分配。请先在「设置」里填写平台地址并保存 Token。",
                        onGoSettings: { appState.goToSettings() }
                    )
                } else if isLoading && tasks.isEmpty && errorMessage == nil {
                    ProgressView("正在加载任务…")
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    TaskListContentView(
                        tasks: tasks,
                        loadError: errorMessage,
                        onRetry: { Task { await loadTasks() } },
                        onRefresh: { Task { await loadTasks() } }
                    )
                }
            }
            .navigationTitle("任务")
            .toolbar {
                if appState.isConfigured {
                    Button("刷新") { Task { await loadTasks() } }
                        .disabled(isLoading)
                }
            }
            .task { await loadTasks() }
            #if compiler(>=6.0)
            .onChange(of: appState.isConfigured) { _, isNowConfigured in
                if isNowConfigured { Task { await loadTasks() } }
            }
            #else
            .onChange(of: appState.isConfigured) { isNowConfigured in
                if isNowConfigured { Task { await loadTasks() } }
            }
            #endif
        }
    }

    private func loadTasks() async {
        guard let service = appState.makeTaskService() else { return }
        isLoading = true
        defer { isLoading = false }
        do {
            tasks = try await service.listMyTasks()
            errorMessage = nil
        } catch {
            errorMessage = (error as? McpError)?.message ?? "加载任务失败。"
        }
    }
}

// MARK: - Task detail

struct TaskDetailView: View {
    @EnvironmentObject private var appState: AppState
    @State private var current: AgentTask
    @State private var taskVanished = false
    @State private var progressText = ""
    @State private var summaryText = ""
    @State private var branchText = ""
    @State private var commitText = ""
    @State private var prURLText = ""
    @State private var releaseReasonText = ""
    @State private var isBusy = false
    @State private var resultMessage: String?
    @State private var errorMessage: String?

    init(task: AgentTask) {
        _current = State(initialValue: task)
    }

    private var hasEvidence: Bool {
        !branchText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            || !commitText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            || !prURLText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        List {
            Section("任务") {
                Text(current.displayTitle).font(.headline)
                if !current.detail.isEmpty {
                    Text(current.detail)
                }
                LabeledContent("编号", value: current.id)
                if !current.status.isEmpty {
                    LabeledContent("状态", value: current.status)
                }
                if let version = current.version {
                    LabeledContent("版本", value: "\(version)")
                }
                if taskVanished {
                    Text("平台的任务列表里已找不到这个任务（可能已完成、已交回或被收回），下面显示的是最后已知状态。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            Section("领取与进度") {
                Button("领取任务") { Task { await run { svc in _ = try await svc.claim(taskID: current.id, expectedVersion: current.version) } } }
                    .disabled(isBusy)
                TextField("进度说明…", text: $progressText)
                Button("汇报进度（续租 2 小时）") {
                    Task { await run { svc in try await svc.reportProgress(taskID: current.id, text: progressText, expectedVersion: current.version) } }
                }
                .disabled(isBusy || progressText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                Button("心跳（只续租，不记进度）") {
                    Task { await run { svc in try await svc.heartbeat(taskID: current.id, expectedVersion: current.version) } }
                }
                .disabled(isBusy)
                TextField("放手原因（可空）", text: $releaseReasonText)
                Button("放手任务", role: .destructive) {
                    Task {
                        await run { svc in
                            let reason = releaseReasonText.trimmingCharacters(in: .whitespacesAndNewlines)
                            try await svc.release(taskID: current.id, reason: reason.isEmpty ? nil : reason)
                        }
                    }
                }
                .disabled(isBusy)
            }
            Section("交活") {
                TextField("做了什么、怎么验证的…", text: $summaryText)
                TextField("分支（branch）", text: $branchText)
                TextField("提交 SHA（commit）", text: $commitText)
                TextField("PR 链接（pr_url）", text: $prURLText)
                Text("开发任务交活时，分支、提交或 PR 链接至少要给一个，否则平台会拒绝。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Button("提交结果") {
                    Task {
                        await run { svc in
                            _ = try await svc.submitResult(
                                taskID: current.id,
                                summary: summaryText,
                                branch: branchText,
                                commit: commitText,
                                prURL: prURLText,
                                expectedVersion: current.version
                            )
                        }
                    }
                }
                .disabled(isBusy || summaryText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !hasEvidence)
            }
            Section("服务端无此接口，阻塞中") {
                ForEach([ReservedFeature.approval, .pauseTask, .resumeTask, .stopTask, .retargetTask], id: \.self) { feature in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(feature.label)
                            .font(.subheadline)
                        Text(feature.note)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    .opacity(0.7)
                }
            }
            if let resultMessage {
                Section("结果") {
                    Text(resultMessage)
                }
            }
        }
        .navigationTitle("任务详情")
        .task { await refreshCurrent() }
        .alert("操作失败", isPresented: Binding(
            get: { errorMessage != nil },
            set: { if !$0 { errorMessage = nil } }
        )) {
            Button("好", role: .cancel) {}
        } message: {
            Text(errorMessage ?? "")
        }
    }

    private func service() throws -> TaskService {
        guard let service = appState.makeTaskService() else {
            throw McpError.notConfigured("未配置平台地址或 Token。")
        }
        return service
    }

    /// Re-read this task from the server so status/version on screen
    /// are the server's current truth after an action.
    private func refreshCurrent() async {
        guard let service = appState.makeTaskService() else { return }
        if let list = try? await service.listMyTasks() {
            if let fresh = list.first(where: { $0.id == current.id }) {
                current = fresh
                taskVanished = false
            } else {
                taskVanished = true
            }
        }
    }

    /// Run one lifecycle call, surfacing success/failure honestly.
    /// On success the task is re-read from the server before the
    /// result is shown.
    private func run(_ operation: @escaping (TaskService) async throws -> Void) async {
        isBusy = true
        defer { isBusy = false }
        do {
            let svc = try service()
            try await operation(svc)
            await refreshCurrent()
            resultMessage = "操作成功，状态已按平台返回刷新。"
            errorMessage = nil
        } catch {
            errorMessage = (error as? McpError)?.message ?? "操作失败。"
        }
    }
}
