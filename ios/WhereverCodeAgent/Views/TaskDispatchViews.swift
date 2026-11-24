// WhereverCode Agent — iOS
//
// 派单任务 UI（spec §7，W10/W11）：频道内的派单任务列表、任务卡、
// 详情（交付信息 + 最近动态 + 按权限矩阵出现的操作）、派单表单。
// 数据走 App 内嵌的本地任务服务（LocalPlatform 的 LocalChannelService
// 以本机 App 身份分发 owner 工具）；无权按钮不渲染，服务层再校验
// 一次。状态标签与 transitions.json 一致（服务返回 state_label）。

import SwiftUI

// MARK: - 数据模型（owner_view 形状）

public struct DispatchTaskEvent: Identifiable {
    public let id: Int64
    public let who: String
    public let kind: String
    public let text: String
    public let at: Double

    init(_ dict: [String: Any]) {
        id = (dict["seq"] as? NSNumber)?.int64Value ?? 0
        who = (dict["who"] as? String) ?? ""
        kind = (dict["kind"] as? String) ?? ""
        text = (dict["text"] as? String) ?? ""
        at = (dict["at"] as? NSNumber)?.doubleValue ?? 0
    }
}

public struct DispatchTask: Identifiable {
    public let id: String
    public let title: String
    public let goal: String
    public let state: String
    public let stateLabel: String
    public let round: Int
    public let maxRounds: Int
    public let version: Int
    public let epoch: Int
    public let assigneeId: String
    public let assigneeName: String
    public let reviewerId: String?
    public let reviewerName: String?
    public let claimedByName: String?
    public let summary: String?
    public let branch: String?
    public let commit: String?
    public let prUrl: String?
    public let verdict: String?
    public let feedback: String?
    public let decisionReason: String?
    public let channelId: String?
    public let createdBy: String?
    public let deletedAt: Double?
    public let updatedAt: Double
    public let events: [DispatchTaskEvent]

    public init(_ dict: [String: Any]) {
        id = (dict["task_id"] as? String) ?? ""
        title = (dict["title"] as? String) ?? ""
        goal = (dict["goal"] as? String) ?? ""
        state = (dict["state"] as? String) ?? ""
        stateLabel = (dict["state_label"] as? String) ?? state
        round = (dict["round"] as? NSNumber)?.intValue ?? 1
        maxRounds = (dict["max_rounds"] as? NSNumber)?.intValue ?? 3
        version = (dict["version"] as? NSNumber)?.intValue ?? 1
        epoch = (dict["epoch"] as? NSNumber)?.intValue ?? 1
        assigneeId = (dict["assignee_id"] as? String) ?? ""
        assigneeName = (dict["assignee_name"] as? String) ?? ""
        reviewerId = dict["reviewer_id"] as? String
        reviewerName = dict["reviewer_name"] as? String
        claimedByName = dict["claimed_by_name"] as? String
        summary = dict["summary"] as? String
        branch = dict["branch"] as? String
        commit = dict["commit"] as? String
        prUrl = dict["pr_url"] as? String
        verdict = dict["verdict"] as? String
        feedback = dict["feedback"] as? String
        decisionReason = dict["decision_reason"] as? String
        channelId = dict["channel_id"] as? String
        createdBy = dict["created_by"] as? String
        deletedAt = (dict["deleted_at"] as? NSNumber)?.doubleValue
        updatedAt = (dict["updated_at"] as? NSNumber)?.doubleValue ?? 0
        events = ((dict["events"] as? [[String: Any]]) ?? []).map(DispatchTaskEvent.init)
    }

    // spec §7 权限矩阵（owner 视角；UI 只渲染有权按钮）。
    public var isTerminal: Bool {
        state == "approved" || state == "done" || state == "cancelled"
    }
    public var canWithdraw: Bool {
        state == "queued" || state == "claimed" || state == "reviewing"
    }
    public var canEdit: Bool { state == "queued" || state == "claimed" }
    public var canDecide: Bool { state == "needs_decision" }
    public var canReopen: Bool { isTerminal }
    public var canDelete: Bool { isTerminal && deletedAt == nil }
}

// MARK: - 本地任务服务客户端（本机 App 身份 = owner 作用域）

public enum TaskDispatchClient {
    public static var available: Bool { LocalPlatform.serviceOrNull() != nil }

    private static func service() throws -> LocalChannelService {
        guard let service = LocalPlatform.serviceOrNull() else {
            throw LocalServiceError("本地任务服务未启动")
        }
        return service
    }

    public static func call(_ tool: String, _ args: [String: Any]) throws -> [String: Any] {
        try service().dispatch(
            tool: tool, args: args,
            callerId: LocalPlatform.appAgentID,
            callerName: LocalPlatform.appAgentName)
    }

    public static func listChannelTasks(channelId: String) throws -> [DispatchTask] {
        let result = try call("list_tasks", ["limit": 200])
        let tasks = ((result["tasks"] as? [[String: Any]]) ?? []).map(DispatchTask.init)
        return tasks.filter { $0.channelId == channelId }
    }

    public static func getTask(taskId: String) throws -> DispatchTask {
        let result = try call("get_task", ["task_id": taskId, "include_events": true])
        return DispatchTask((result["task"] as? [String: Any]) ?? [:])
    }

    public static func deleteTask(taskId: String) throws {
        _ = try service().uiDeleteTask(taskId: taskId)
    }
}

// MARK: - 任务卡

public struct DispatchTaskCardView: View {
    public let task: DispatchTask

    public var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(task.title)
                    .font(.system(size: 14, weight: .semibold))
                    .lineLimit(1)
                Spacer()
                Text(task.stateLabel)
                    .font(.caption2)
                    .fontWeight(.bold)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(Color.blue.opacity(0.12), in: Capsule())
            }
            Text(task.goal)
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
                .lineLimit(2)
            HStack {
                Text("执行：\(task.assigneeName)")
                if let reviewerName = task.reviewerName {
                    Text("· 审查：\(reviewerName)")
                }
                if task.maxRounds > 1 {
                    Text("· 第 \(task.round)/\(task.maxRounds) 轮")
                }
                Spacer()
            }
            .font(.caption2)
            .foregroundStyle(.secondary)
        }
        .padding(12)
        .background(Color.white, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12)
            .stroke(Color(red: 0.90, green: 0.89, blue: 0.87), lineWidth: 1))
    }
}

// MARK: - 频道派单任务区（嵌入频道页「任务」分段）

public struct ChannelDispatchTasksSection: View {
    public let channelId: String
    public let agentOptions: [(id: String, name: String)]

    @State private var tasks: [DispatchTask] = []
    @State private var loadError: String? = nil
    @State private var showCreateForm = false
    @State private var selectedTask: DispatchTask? = nil

    public init(channelId: String, agentOptions: [(id: String, name: String)]) {
        self.channelId = channelId
        self.agentOptions = agentOptions
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("派单任务 (\(tasks.count))")
                    .font(.system(size: 14, weight: .semibold))
                Spacer()
                Button {
                    showCreateForm = true
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "plus")
                        Text("派单")
                    }
                    .font(.caption)
                }
            }
            if let loadError {
                Text(loadError)
                    .font(.caption)
                    .foregroundStyle(.red)
                    .textSelection(.enabled)
            }
            if tasks.isEmpty && loadError == nil {
                Text("本频道还没有派单任务。点「派单」把活派给一位 Agent，进度和交付会回到本频道。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
            } else {
                ForEach(tasks) { task in
                    Button {
                        selectedTask = task
                    } label: {
                        DispatchTaskCardView(task: task)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .onAppear { reload() }
        .sheet(isPresented: $showCreateForm) {
            CreateDispatchTaskView(channelId: channelId, agentOptions: agentOptions) {
                reload()
            }
        }
        .sheet(item: $selectedTask) { task in
            DispatchTaskDetailView(taskId: task.id, agentOptions: agentOptions) {
                reload()
            }
        }
    }

    private func reload() {
        do {
            tasks = try TaskDispatchClient.listChannelTasks(channelId: channelId)
            loadError = nil
        } catch {
            loadError = (error as? LocalServiceError)?.message ?? "\(error)"
        }
    }
}

// MARK: - 派单表单（spec §7：选 Agent 必选、审查人可选、goal、绑当前频道）

public struct CreateDispatchTaskView: View {
    public let channelId: String
    public let agentOptions: [(id: String, name: String)]
    public let onCreated: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var agentId: String = ""
    @State private var reviewerId: String = ""
    @State private var projectId: String = ""
    @State private var goal: String = ""
    @State private var errorText: String? = nil
    @State private var submitting = false

    public var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("执行 Agent", selection: $agentId) {
                        Text("请选择").tag("")
                        ForEach(agentOptions, id: \.id) { option in
                            Text(option.name).tag(option.id)
                        }
                    }
                    Picker("审查人（可选）", selection: $reviewerId) {
                        Text("不指定").tag("")
                        ForEach(agentOptions, id: \.id) { option in
                            Text(option.name).tag(option.id)
                        }
                    }
                    TextField("项目标识", text: $projectId)
                } header: { Text("派给谁").textCase(nil) }
                Section {
                    TextEditor(text: $goal)
                        .frame(minHeight: 110)
                } header: { Text("任务目标").textCase(nil) }
                if let errorText {
                    Section {
                        Text(errorText)
                            .foregroundStyle(.red)
                            .textSelection(.enabled)
                    }
                }
            }
            .navigationTitle("派单")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("派出") { submit() }
                        .disabled(submitting || agentId.isEmpty ||
                                  goal.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
                                  projectId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }

    private func submit() {
        submitting = true
        defer { submitting = false }
        var args: [String: Any] = [
            "agent": agentId,
            "project_id": projectId.trimmingCharacters(in: .whitespacesAndNewlines),
            "goal": goal,
            "channel_id": channelId,
        ]
        if !reviewerId.isEmpty { args["reviewer"] = reviewerId }
        do {
            _ = try TaskDispatchClient.call("start_task", args)
            onCreated()
            dismiss()
        } catch {
            errorText = (error as? LocalServiceError)?.message ?? "\(error)"
        }
    }
}

// MARK: - 任务详情（交付信息 + 最近动态 + 权限矩阵操作）

public struct DispatchTaskDetailView: View {
    public let taskId: String
    public let agentOptions: [(id: String, name: String)]
    public let onChanged: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var task: DispatchTask? = nil
    @State private var loadError: String? = nil
    @State private var actionError: String? = nil
    @State private var reason: String = ""
    // 编辑表单状态
    @State private var editTitle: String = ""
    @State private var editGoal: String = ""
    @State private var editAgentId: String = ""
    @State private var editReviewerId: String = ""
    @State private var showDeleteConfirm = false

    public var body: some View {
        NavigationStack {
            List {
                if let loadError {
                    Section {
                        Text(loadError).foregroundStyle(.red).textSelection(.enabled)
                    }
                }
                if let task {
                    detailSections(task)
                }
            }
            .navigationTitle("任务详情")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
        .onAppear { reload() }
        .confirmationDialog("删除这个任务？删除后列表不再显示，账本保留墓碑。",
                            isPresented: $showDeleteConfirm, titleVisibility: .visible) {
            Button("删除", role: .destructive) { performDelete() }
            Button("取消", role: .cancel) {}
        }
    }

    @ViewBuilder
    private func detailSections(_ task: DispatchTask) -> some View {
        Section {
            LabeledContent("状态", value: task.stateLabel)
            LabeledContent("执行", value: task.assigneeName)
            if let reviewerName = task.reviewerName {
                LabeledContent("审查", value: reviewerName)
            }
            if let claimedByName = task.claimedByName {
                LabeledContent("当前持有人", value: claimedByName)
            }
            LabeledContent("轮次", value: "第 \(task.round)/\(task.maxRounds) 轮")
            Text(task.goal).textSelection(.enabled)
        } header: { Text(task.title).textCase(nil) }

        if task.summary != nil || task.branch != nil || task.commit != nil || task.prUrl != nil {
            Section {
                if let summary = task.summary { Text(summary).textSelection(.enabled) }
                if let branch = task.branch { LabeledContent("分支", value: branch) }
                if let commit = task.commit { LabeledContent("提交", value: commit) }
                if let prUrl = task.prUrl { LabeledContent("PR", value: prUrl) }
                if let verdict = task.verdict { LabeledContent("审查结论", value: verdict) }
                if let feedback = task.feedback { Text(feedback).textSelection(.enabled) }
            } header: { Text("交付").textCase(nil) }
        }

        if task.canDecide || task.canWithdraw {
            Section {
                TextField("理由（验收/取消必填，撤回可选）", text: $reason)
                if task.canDecide {
                    Button("验收通过") { decide(action: "done") }
                    Button("取消任务", role: .destructive) { decide(action: "cancel") }
                }
                if task.canWithdraw {
                    Button("撤回任务", role: .destructive) { withdraw() }
                }
            } header: { Text("决定").textCase(nil) }
        }

        if task.canEdit {
            Section {
                TextField("标题", text: $editTitle)
                TextEditor(text: $editGoal).frame(minHeight: 70)
                Picker("执行 Agent", selection: $editAgentId) {
                    ForEach(agentOptions, id: \.id) { option in
                        Text(option.name).tag(option.id)
                    }
                }
                Picker("审查人", selection: $editReviewerId) {
                    Text("不指定").tag("")
                    ForEach(agentOptions, id: \.id) { option in
                        Text(option.name).tag(option.id)
                    }
                }
                Button("保存修改") { saveEdit(task) }
            } header: { Text("编辑 / 改派").textCase(nil) }
        }

        if task.canReopen || task.canDelete {
            Section {
                if task.canReopen {
                    Button("重开任务") { reopen() }
                }
                if task.canDelete {
                    Button("删除任务", role: .destructive) { showDeleteConfirm = true }
                }
            } header: { Text("更多").textCase(nil) }
        }

        if let actionError {
            Section {
                Text(actionError).foregroundStyle(.red).textSelection(.enabled)
            }
        }

        if !task.events.isEmpty {
            Section {
                ForEach(task.events) { event in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(event.text)
                            .font(.system(size: 12))
                            .textSelection(.enabled)
                        Text("\(event.who) · \(Self.timeText(event.at))")
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
            } header: { Text("最近动态").textCase(nil) }
        }
    }

    private static func timeText(_ ts: Double) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "MM-dd HH:mm"
        return formatter.string(from: Date(timeIntervalSince1970: ts))
    }

    private func reload() {
        do {
            let fresh = try TaskDispatchClient.getTask(taskId: taskId)
            task = fresh
            editTitle = fresh.title
            editGoal = fresh.goal
            editAgentId = fresh.assigneeId
            editReviewerId = fresh.reviewerId ?? ""
            loadError = nil
        } catch {
            loadError = (error as? LocalServiceError)?.message ?? "\(error)"
        }
    }

    private func run(_ body: () throws -> Void) {
        do {
            try body()
            actionError = nil
            onChanged()
            reload()
        } catch {
            let message = (error as? LocalServiceError)?.message ?? "\(error)"
            actionError = message
            // W13 跨切面：版本冲突（任务已被别人改动）不静默失败——
            // 刷新到最新版本让用户核对后再操作，冲突原文留在上方。
            if message.contains("版本已变") {
                reload()
            }
        }
    }

    private func decide(action: String) {
        guard let task else { return }
        run {
            _ = try TaskDispatchClient.call("decide_task", [
                "task_id": task.id, "action": action, "reason": reason,
            ])
        }
    }

    private func withdraw() {
        guard let task else { return }
        run {
            var args: [String: Any] = ["task_id": task.id]
            if !reason.isEmpty { args["reason"] = reason }
            _ = try TaskDispatchClient.call("withdraw_task", args)
        }
    }

    private func reopen() {
        guard let task else { return }
        run {
            _ = try TaskDispatchClient.call("reopen_task", ["task_id": task.id])
        }
    }

    private func saveEdit(_ task: DispatchTask) {
        run {
            var args: [String: Any] = [
                "task_id": task.id, "expected_version": task.version,
            ]
            if editTitle != task.title { args["title"] = editTitle }
            if editGoal != task.goal { args["goal"] = editGoal }
            if editAgentId != task.assigneeId { args["agent"] = editAgentId }
            if editReviewerId != (task.reviewerId ?? "") {
                args["reviewer"] = editReviewerId.isEmpty ? NSNull() : editReviewerId
            }
            _ = try TaskDispatchClient.call("update_task", args)
        }
    }

    private func performDelete() {
        guard let task else { return }
        run {
            try TaskDispatchClient.deleteTask(taskId: task.id)
            dismiss()
        }
    }
}
