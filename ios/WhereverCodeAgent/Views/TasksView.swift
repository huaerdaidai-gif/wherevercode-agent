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
                Section {
                    Text(loadError)
                        .font(.subheadline)
                        .foregroundStyle(.red)
                        .textSelection(.enabled)
                    Button("重试") { onRetry() }
                } header: { Text("连接平台失败").textCase(nil) }
            }
            Section {
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
            } header: { Text("我的任务").textCase(nil) }
            Section {
                Text("一次只领一个任务；领取后租约 2 小时，汇报进度或心跳可续租。平台没有创建 / 指派任务的接口，本页只做已分配任务的领取与交付。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } header: { Text("说明").textCase(nil) }
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

/// Pure tasks content for the platform-not-connected state: the
/// page still renders as a page — the shared 平台连接 card, an
/// honest hint in the task section, and the standing explanation.
/// Mirrors Android, where an unconfigured platform only ever
/// affects the platform-backed page itself (the team channel's
/// setup hint) and never turns the page into a dead end.
struct TasksNotConnectedContentView: View {
    let onGoSettings: () -> Void

    var body: some View {
        List {
            PlatformConnectionCardSection(onGoSettings: onGoSettings)
            Section {
                Text("还没有连上平台：任务来自平台分配。连接平台后，这里会列出分配给你的任务。")
                    .foregroundStyle(.secondary)
            } header: { Text("我的任务").textCase(nil) }
            Section {
                Text("一次只领一个任务；领取后租约 2 小时，汇报进度或心跳可续租。平台没有创建 / 指派任务的接口，本页只做已分配任务的领取与交付。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } header: { Text("说明").textCase(nil) }
        }
    }
}

struct TasksView: View {
    @ObservedObject private var appState: AppState
    var onNavigateRoute: (String) -> Void = { _ in }

    init() {
        self.appState = AppState()
    }
    init(appState: AppState, onNavigateRoute: @escaping (String) -> Void = { _ in }) {
        self.appState = appState
        self.onNavigateRoute = onNavigateRoute
    }
    @State private var showPlatform = false
    @State private var localTasks: [LocalTask] = []
    @State private var localError: String?
    @State private var tasks: [AgentTask] = []
    @State private var isLoading = false
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker("任务来源", selection: $showPlatform) {
                    Text("本地任务").tag(false)
                    Text("平台任务").tag(true)
                }.pickerStyle(.segmented).padding(.horizontal, 16)
                    .accessibilityIdentifier("tasks.scope")
                if !showPlatform {
                    List {
                        Section {
                            let workingC = localTasks.filter { $0.state == "working" }.count
                            let reviewC = localTasks.filter { $0.state == "review" || $0.state == "blocked" }.count
                            let queuedC = localTasks.filter { $0.state == "queued" || $0.state == "assigned" || $0.state == "requeued" }.count
                            let doneC = localTasks.filter { $0.state == "completed" }.count
                            HStack {
                                VStack { Text("\(workingC)").font(.headline); Text("执行中").font(.caption2).foregroundStyle(.secondary) }.frame(maxWidth: .infinity)
                                VStack { Text("\(reviewC)").font(.headline); Text("待审批").font(.caption2).foregroundStyle(.secondary) }.frame(maxWidth: .infinity)
                                VStack { Text("\(queuedC)").font(.headline); Text("排队中").font(.caption2).foregroundStyle(.secondary) }.frame(maxWidth: .infinity)
                                VStack { Text("\(doneC)").font(.headline); Text("已完成").font(.caption2).foregroundStyle(.secondary) }.frame(maxWidth: .infinity)
                            }
                            Button("＋ 新建任务") {
                                if let nav = appState.onSelectRoute { nav("compose") } else { onNavigateRoute("compose") }
                            }
                            .font(.subheadline.weight(.semibold))
                        } header: { Text("任务状态概览").textCase(nil) }
                        Section {
                            if let localError { Text(localError).foregroundStyle(.red) }
                            if localTasks.isEmpty { Text("暂无本地任务，可点击上方「＋ 新建任务」创建。").foregroundStyle(.secondary) }
                            ForEach(localTasks, id: \.taskId) { task in
                                NavigationLink {
                                    LocalTaskDetailScreen(task: task, onUpdated: {
                                        localTasks = (try? LocalData.shared.tasks.listTasks()) ?? []
                                    }, onNavigateRoute: { r in
                                        if let nav = appState.onSelectRoute { nav(r) } else { onNavigateRoute(r) }
                                    })
                                } label: {
                                    VStack(alignment: .leading, spacing: 6) {
                                        Text(task.title.isEmpty ? task.taskId : task.title)
                                            .font(.subheadline.weight(.medium))
                                        Text(TaskStates.displayLabel(for: task.state, isPaused: task.isPaused))
                                            .font(.caption).foregroundStyle(.secondary)
                                    }
                                }
                            }
                        } header: { Text("本地任务（\(localTasks.count)）").textCase(nil) }
                    }
                } else if !appState.isConfigured {
                    TasksNotConnectedContentView(
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
            .task {
                do { localTasks = try LocalData.shared.tasks.listTasks() }
                catch { localError = error.localizedDescription }
                await loadTasks()
            }
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
        // Not connected is a page state, not an error (see
        // TasksNotConnectedContentView): never attempt a load and
        // never surface a missing-credential error here.
        guard appState.isConfigured, let service = appState.makeTaskService() else { return }
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

    init(appState: AppState, task: AgentTask) {
        _current = State(initialValue: task)
    }

    private var hasEvidence: Bool {
        !branchText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            || !commitText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            || !prURLText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        List {
            Section {
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
            } header: { Text("任务").textCase(nil) }
            Section {
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
            } header: { Text("领取与进度").textCase(nil) }
            Section {
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
            } header: { Text("交活").textCase(nil) }
            Section {
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
            } header: { Text("服务端无此接口，阻塞中").textCase(nil) }
            if let resultMessage {
                Section {
                    Text(resultMessage)
                } header: { Text("结果").textCase(nil) }
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


private struct LocalTaskDetailView: View {
    let task: LocalTask
    @State private var events: [TaskEvent] = []
    @State private var loadError: String?
    var body: some View {
        List {
            Section {
                LabeledContent("编号", value: task.taskId)
                LabeledContent("状态", value: task.state)
                if let description = task.description { Text(description) }
                if let result = task.result { Text(result).textSelection(.enabled) }
                if let error = task.error { Text(error).foregroundStyle(.red) }
            } header: { Text("任务").textCase(nil) }
            Section {
                ForEach(["暂停", "继续", "停止", "修改目标"], id: \.self) { title in
                    Button(title) {}.disabled(true)
                }
                Text("iOS 尚未接入本地任务执行器，无法确认控制回执；以上操作不可用。")
                    .font(.caption).foregroundStyle(.secondary)
            } header: { Text("控制").textCase(nil) }
            Section {
                if let loadError { Text(loadError).foregroundStyle(.red) }
                if events.isEmpty { Text("暂无事件") }
                ForEach(events, id: \.id) { event in
                    VStack(alignment: .leading) {
                        Text(event.kind)
                        if let note = event.note { Text(note).font(.caption) }
                    }
                }
            } header: { Text("日志").textCase(nil) }
        }
        .navigationTitle(task.title.isEmpty ? "任务详情" : task.title)
        .task {
            do { events = try LocalData.shared.tasks.listEvents(taskId: task.taskId) }
            catch { loadError = error.localizedDescription }
        }
    }
}

public struct LocalTaskDetailScreen: View {
    @State public var task: LocalTask
    public var onUpdated: () -> Void
    public var onNavigateRoute: (String) -> Void = { _ in }
    @State private var events: [TaskEvent] = []
    @State private var transitionNote: String = ""
    @State private var errorMsg: String?

    public var body: some View {
        List {
            Section {
                LabeledContent("标题", value: task.title)
                LabeledContent("当前状态", value: TaskStates.displayLabel(for: task.state, isPaused: task.isPaused))
                LabeledContent("独立暂停位 (isPaused)", value: task.isPaused ? "true (已暂停)" : "false (正常运行)")
                LabeledContent("指派 Agent", value: task.assignedAgentId ?? "未指派")
                LabeledContent("执行栅栏", value: "v\(task.version) / goalVersion=\(task.goalVersion) / authEpoch=\(task.authEpoch)")
                if let desc = task.description {
                    Text(desc).font(.footnote)
                }
            } header: { Text("任务基本信息").textCase(nil) }

            Section {
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
                HStack(spacing: 8) {
                    Button("暂停") {
                        if let t = try? LocalData.shared.tasks.setPaused(taskId: task.taskId, isPaused: true) { task = t; onUpdated() }
                    }.buttonStyle(.bordered)
                    Button("继续") {
                        if let t = try? LocalData.shared.tasks.setPaused(taskId: task.taskId, isPaused: false) { task = t; onUpdated() }
                    }.buttonStyle(.bordered)
                    Button("停止", role: .destructive) {
                        if let t = try? LocalData.shared.tasks.transition(taskId: task.taskId, to: "cancelled", note: "用户手动停止") { task = t; onUpdated() }
                    }.buttonStyle(.bordered)
                }
                HStack(spacing: 8) {
                    Button("改目标") { onNavigateRoute("compose") }.buttonStyle(.bordered)
                    Button("查看执行流") { onNavigateRoute("conversation") }.buttonStyle(.bordered)
                    Button("交付物") { onNavigateRoute("artifacts") }.buttonStyle(.bordered)
                }
            } header: { Text("状态机合法流转与控制操作").textCase(nil) }

            if let err = errorMsg {
                Section {
                    Text(err).foregroundStyle(.red).font(.caption)
                } header: { Text("错误提示").textCase(nil) }
            }

            Section {
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
            } header: { Text("任务事件审计流 (task_events)").textCase(nil) }
        }
        .navigationTitle("本地任务详情")
        .onAppear {
            events = (try? LocalData.shared.tasks.listEvents(taskId: task.taskId)) ?? []
        }
    }
}
