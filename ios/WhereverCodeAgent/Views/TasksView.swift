// WhereverCode Agent — iOS MVP
//
// My tasks: the real `list_my_tasks` list plus the real lifecycle
// actions (claim / progress / heartbeat / submit / release). The
// reserved controls (approval / pause / resume / stop / retarget)
// are shown disabled and labelled — never as working buttons.

import SwiftUI

struct TasksView: View {
    @EnvironmentObject private var appState: AppState
    @State private var tasks: [AgentTask] = []
    @State private var isLoading = false
    @State private var errorMessage: String?
    @State private var selectedTask: AgentTask?

    var body: some View {
        NavigationStack {
            Group {
                if !appState.isConfigured {
                    notConfiguredView
                } else if isLoading && tasks.isEmpty {
                    ProgressView("正在加载任务…")
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    taskList
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
            .sheet(item: $selectedTask) { task in
                TaskDetailView(task: task)
                    .environmentObject(appState)
            }
            .alert("加载失败", isPresented: Binding(
                get: { errorMessage != nil },
                set: { if !$0 { errorMessage = nil } }
            )) {
                Button("好", role: .cancel) {}
            } message: {
                Text(errorMessage ?? "")
            }
        }
    }

    private var notConfiguredView: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("还没有连上平台")
                .font(.headline)
            Text("任务列表来自平台分配。请先在「设置」里填写平台地址并保存 Token。")
                .font(.body)
                .foregroundStyle(.secondary)
            NavigationLink("去设置") { SettingsView() }
                .buttonStyle(.borderedProminent)
            Spacer()
        }
        .padding()
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    private var taskList: some View {
        List {
            if tasks.isEmpty {
                Text("当前没有分配给你的任务。")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(tasks) { task in
                    Button {
                        selectedTask = task
                    } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(task.displayTitle)
                                .font(.headline)
                                .foregroundStyle(.primary)
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
            }
            Section("说明") {
                Text("一次只领一个任务；领取后租约 2 小时，汇报进度或心跳可续租。平台没有创建 / 指派任务的接口，本页只做已分配任务的领取与交付。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
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
    let task: AgentTask

    @EnvironmentObject private var appState: AppState
    @Environment(\.dismiss) private var dismiss
    @State private var progressText = ""
    @State private var summaryText = ""
    @State private var branchText = ""
    @State private var commitText = ""
    @State private var isBusy = false
    @State private var resultMessage: String?
    @State private var errorMessage: String?

    private var hasEvidence: Bool {
        !branchText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            || !commitText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        NavigationStack {
            List {
                Section("任务") {
                    Text(task.displayTitle).font(.headline)
                    if !task.detail.isEmpty {
                        Text(task.detail)
                    }
                    LabeledContent("编号", value: task.id)
                    if !task.status.isEmpty {
                        LabeledContent("状态", value: task.status)
                    }
                    if let version = task.version {
                        LabeledContent("版本", value: "\(version)")
                    }
                }
                Section("领取与进度") {
                    Button("领取任务") { Task { await run { svc in _ = try await svc.claim(taskID: task.id, expectedVersion: task.version) } } }
                        .disabled(isBusy)
                    TextField("进度说明…", text: $progressText)
                    Button("汇报进度（续租 2 小时）") {
                        Task { await run { svc in try await svc.reportProgress(taskID: task.id, text: progressText, expectedVersion: task.version) } }
                    }
                    .disabled(isBusy || progressText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    Button("心跳（只续租，不记进度）") {
                        Task { await run { svc in try await svc.heartbeat(taskID: task.id, expectedVersion: task.version) } }
                    }
                    .disabled(isBusy)
                    Button("放手任务", role: .destructive) {
                        Task { await run { svc in try await svc.release(taskID: task.id, reason: "在 iOS 端放手") } }
                    }
                    .disabled(isBusy)
                }
                Section("交活") {
                    TextField("做了什么、怎么验证的…", text: $summaryText)
                    TextField("分支（branch）", text: $branchText)
                    TextField("提交 SHA（commit）", text: $commitText)
                    Text("开发任务交活时，分支、提交或 PR 链接至少要给一个，否则平台会拒绝；本页只收分支与提交，PR 链接请走后续版本。")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Button("提交结果") {
                        Task {
                            await run { svc in
                                _ = try await svc.submitResult(
                                    taskID: task.id,
                                    summary: summaryText,
                                    branch: branchText,
                                    commit: commitText,
                                    expectedVersion: task.version
                                )
                            }
                        }
                    }
                    .disabled(isBusy || summaryText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !hasEvidence)
                }
                Section("未实现") {
                    ForEach([ReservedFeature.approval, .pauseTask, .resumeTask, .stopTask, .retargetTask], id: \.self) { feature in
                        VStack(alignment: .leading, spacing: 2) {
                            Text("\(feature.label)：未实现")
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
            .toolbar {
                Button("关闭") { dismiss() }
            }
            .alert("操作失败", isPresented: Binding(
                get: { errorMessage != nil },
                set: { if !$0 { errorMessage = nil } }
            )) {
                Button("好", role: .cancel) {}
            } message: {
                Text(errorMessage ?? "")
            }
        }
    }

    private func service() throws -> TaskService {
        guard let service = appState.makeTaskService() else {
            throw McpError.notConfigured("未配置平台地址或 Token。")
        }
        return service
    }

    /// Run one lifecycle call, surfacing success/failure honestly.
    /// The service is resolved inside, so the escaping closure never
    /// captures the view struct itself.
    private func run(_ operation: @escaping (TaskService) async throws -> Void) async {
        isBusy = true
        defer { isBusy = false }
        do {
            let svc = try service()
            try await operation(svc)
            resultMessage = "操作成功（以平台返回为准）。"
            errorMessage = nil
        } catch {
            errorMessage = (error as? McpError)?.message ?? "操作失败。"
        }
    }
}
