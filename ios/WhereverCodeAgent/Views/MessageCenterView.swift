// WhereverCode Agent — iOS MVP
//
// P3-MSG 消息中心（2026-11-20，双端对齐批）：聚合频道里的 @我 / 未读 /
// 任务回执 三类条目，外加待落地任务区（9.19：本地账本里未绑定频道的
// 任务，点按选频道落地——消息中心唯一的写动作，不发送、不派任务、
// 不执行代码）。消息条目只读；数据经
// MessageCenterLoader 只用既有读接口（ChannelTransport.pollMessages）拉
// 回，全程 markRead=false（绝不推进服务端已读标记，频道页的未读状态不
// 会被消息中心误消费——语义见 MessageCenterRules.swift 注释与
// MessageCenterTests.swift 的桩传输测试，读法口径与 Android 端
// MessageCenterLoader 完全一致）。
//
// 条目呈现复用已摘入基线的共用对话组件 DialogMessageListView（LazyVStack
// 脚手架，与频道工作区/单聊同款），不重复造轮子。点条目跳到对应频道：
// 推回频道工作区路由（"channel"），消息就在该频道工作区的消息流/任务串里。
//
// 入口（可达可测，不翻注册表）：注册表 feature_message_center 保持 TODO
// （翻转留到组装步）；本视图挂在 FlagshipRoutes 既有的 "notifications"
// 路由上（抽屉 → 控制 → 系统分组 → 消息中心），并从频道工作区顶栏
// 「消息中心」按钮直达（onNavigateRoute("notifications")）。

import SwiftUI

struct MessageCenterView: View {
    var route: FlagshipRoute
    @ObservedObject var appState: AppState
    var onNavigateRoute: (String) -> Void

    @State private var snapshot: MessageCenterSnapshot?
    @State private var loading = false
    @State private var errorMessage: String?
    @State private var loadingChannels = false
    /// 待落地任务落地选择（9.19）：点条目选频道。
    @State private var landingTask: PendingTaskEntry?
    @State private var landingChannels: [LocalLandingChannel] = []

    var body: some View {
        VStack(spacing: 0) {
            header
            statusLine
            if loading && snapshot == nil {
                Spacer()
                Text("正在读取频道消息…")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Spacer()
            } else {
                DialogMessageListView(
                    lazy: true,
                    spacing: 0,
                    horizontalPadding: 16,
                    topPadding: 12,
                    bottomPadding: 20,
                    scrollTrigger: 0
                ) {
                    sections
                } trailing: {
                    EmptyView()
                }
            }
        }
        .background(Color(red: 1, green: 1, blue: 1))
        .navigationTitle("消息中心")
        .navigationBarTitleDisplayMode(.inline)
        .task { await refresh() }
        .refreshable { await refresh() }
        .confirmationDialog(
            "落地到频道",
            isPresented: Binding(
                get: { landingTask != nil },
                set: { if !$0 { landingTask = nil } }),
            presenting: landingTask
        ) { task in
            ForEach(landingChannels, id: \.id) { channel in
                Button(channel.name) { land(task, to: channel) }
            }
            Button("取消", role: .cancel) { landingTask = nil }
        } message: { task in
            Text("把「\(task.title)」落地到哪个频道？")
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("聚合频道里的 @我、未读与任务回执；点条目回到对应频道，待落地任务点按选择频道落地。")
                .font(.caption)
                .foregroundStyle(.secondary)
            HStack {
                Spacer()
                Button {
                    Task { await refresh() }
                } label: {
                    Label("刷新", systemImage: "arrow.clockwise")
                        .font(.footnote.weight(.semibold))
                }
                .buttonStyle(.borderless)
                .disabled(loading)
                .accessibilityIdentifier("messageCenter.action.refresh")
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 8)
    }

    @ViewBuilder
    private var statusLine: some View {
        if let errorMessage {
            Text(errorMessage)
                .font(.caption)
                .foregroundStyle(.red)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.top, 4)
        } else if let snapshot, loadingChannels {
            Text("频道列表刷新中…")
                .font(.caption2)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 16)
                .padding(.top, 4)
        }
    }

    @ViewBuilder
    private var sections: some View {
        section(.mention, title: "＠我", entries: snapshot?.mentions ?? []) { entry in
            "被 @\(entry.matchedAlias ?? "?") · 来自 \(entry.senderName)"
        }
        section(.unread, title: "未读", entries: snapshot?.unread ?? []) { entry in
            "来自 \(entry.senderName)"
        }
        section(.taskReceipt, title: "任务回执", entries: snapshot?.taskReceipts ?? []) { entry in
            if let title = entry.taskTitle, !title.isEmpty {
                "任务 \(entry.taskId ?? "") · \(title)"
            } else {
                "任务 \(entry.taskId ?? "")"
            }
        }
        pendingSection
    }

    /// 待落地任务区（9.19）：点条目选频道落地，落地后任务进该频道任务页。
    @ViewBuilder
    private var pendingSection: some View {
        let tasks = snapshot?.pendingTasks ?? []
        VStack(alignment: .leading, spacing: 8) {
            Text("待落地任务 · \(tasks.count)")
                .font(.subheadline.weight(.semibold))
                .padding(.top, 12)
            if tasks.isEmpty {
                Text("暂无待落地任务。派单未指定频道且本地不止一个频道时，任务会先挂在这里。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .padding(.vertical, 6)
            } else {
                ForEach(tasks) { task in
                    pendingRow(task)
                }
            }
        }
    }

    private func pendingRow(_ task: PendingTaskEntry) -> some View {
        Button {
            landingChannels = Self.localLandingChannels()
            landingTask = task
        } label: {
            VStack(alignment: .leading, spacing: 4) {
                Text(task.title.isEmpty ? "（无标题）" : task.title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                    .multilineTextAlignment(.leading)
                if !task.goal.isEmpty {
                    Text(String(task.goal.prefix(300)))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.leading)
                }
                Text("执行人 \(task.assigneeName.isEmpty ? "未指定" : task.assigneeName) · \(task.stateLabel) · \(Self.timeText(task.createdAtSeconds)) · 点按选择频道落地")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .background(Color(red: 0.96, green: 0.95, blue: 0.94), in: RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("messageCenter.pendingTask.\(task.taskId)")
    }

    private func section(
        _ section: MessageCenterSection,
        title: String,
        entries: [MessageCenterEntry],
        subtitle: @escaping (MessageCenterEntry) -> String
    ) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("\(title) · \(entries.count)")
                .font(.subheadline.weight(.semibold))
                .padding(.top, 12)
            if entries.isEmpty {
                Text(emptyHint(section))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .padding(.vertical, 6)
            } else {
                ForEach(entries) { entry in
                    entryRow(entry, subtitle: subtitle(entry))
                }
            }
        }
    }

    private func emptyHint(_ section: MessageCenterSection) -> String {
        switch section {
        case .mention: return "暂无 @我 的消息。"
        case .unread: return "暂无未读。已读状态由频道页自己推进，这里不会替你消费。"
        case .taskReceipt: return "暂无带任务归属的消息。"
        case .pendingTask: return "暂无待落地任务。"
        }
    }

    private func entryRow(_ entry: MessageCenterEntry, subtitle: String) -> some View {
        Button {
            appState.selectChannel(entry.channelId)
            onNavigateRoute("channel")
        } label: {
            VStack(alignment: .leading, spacing: 4) {
                Text(subtitle)
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.secondary)
                Text(entry.text.isEmpty ? "（无正文）" : String(entry.text.prefix(300)))
                    .font(.subheadline)
                    .foregroundStyle(.primary)
                    .multilineTextAlignment(.leading)
                Text("\(entry.channelName) · \(Self.timeText(entry.createdAtSeconds)) · 点按回到频道")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .background(Color(red: 0.96, green: 0.95, blue: 0.94), in: RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("messageCenter.entry.\(entry.section.rawValue).\(entry.messageId)")
    }

    /// 拉取并渲染（只读）。频道列表与消息读取失败都如实提示，不假装成功。
    private func refresh() async {
        guard !loading else { return }
        loading = true
        errorMessage = nil
        loadingChannels = true
        defer { loading = false }
        do {
            // 本机身份别名（@我 的判定集合）：我 / 本机 App / 协调者，加
            // 注册表里启用的本机 Agent（@ 到自己的 Agent 也算 @我，与
            // 频道 @ 寻址口径同族）。读注册表失败不阻断（别名兜底够用）。
            var selfNames = ["我", "本机 App", "协调者"]
            for agent in (try? LocalData.shared.agents.listAgents()) ?? [] where agent.enabled {
                selfNames.append(agent.agentId)
                selfNames.append(agent.displayName)
            }
            // 既有读路径：AppState.loadChannels()（内部走 ChannelTransport.listChannels），
            // 结果发布在 availableChannels；列表读失败（channelListError）如实上抛。
            guard let transport = appState.makeChannelTransport() else {
                let pending = Self.loadPendingTasks()
                await MainActor.run {
                    loadingChannels = false
                    errorMessage = "平台地址无效，读不到频道列表。"
                    // 待落地任务是本地账本数据，平台读不到时也要呈现（9.19）。
                    snapshot = MessageCenterSnapshot(pendingTasks: pending)
                }
                return
            }
            await appState.loadChannels()
            let channels = await MainActor.run { appState.availableChannels }
            let listError = await MainActor.run { appState.channelListError }
            await MainActor.run { loadingChannels = false }
            if let listError {
                await MainActor.run { errorMessage = "消息中心刷新失败：\(listError)" }
                return
            }
            let sources = channels.filter { !$0.isArchived }.map {
                MessageCenterChannelSource(channelId: $0.id, channelName: $0.displayName)
            }
            guard !sources.isEmpty else {
                let pending = Self.loadPendingTasks()
                await MainActor.run { snapshot = MessageCenterSnapshot(pendingTasks: pending) }
                return
            }
            let adapter = ChannelTransportMessageCenterAdapter(transport: transport)
            var loaded = try await MessageCenterLoader(transport: adapter)
                .fetch(channels: sources, selfNames: selfNames)
            loaded.pendingTasks = Self.loadPendingTasks()
            await MainActor.run { snapshot = loaded }
        } catch {
            await MainActor.run {
                loadingChannels = false
                errorMessage = "消息中心刷新失败：\(error.localizedDescription)"
            }
        }
    }

    private static func timeText(_ seconds: Double) -> String {
        guard seconds > 0 else { return "时间未知" }
        let date = Date(timeIntervalSince1970: seconds)
        let formatter = DateFormatter()
        formatter.dateFormat = "MM-dd HH:mm"
        return formatter.string(from: date)
    }

    /// 待落地任务（9.19）：本地任务账本里未绑定频道、未终结的任务。
    private static func loadPendingTasks() -> [PendingTaskEntry] {
        guard let service = LocalPlatform.serviceOrNull() else { return [] }
        do {
            let result = try service.dispatch(
                tool: "list_tasks", args: ["limit": 200],
                callerId: LocalPlatform.appAgentID,
                callerName: LocalPlatform.appAgentName)
            let rows = result["tasks"] as? [[String: Any]] ?? []
            let terminal: Set<String> = ["approved", "done", "cancelled"]
            return rows.compactMap { row in
                let channelId = row["channel_id"]
                if channelId != nil && !(channelId is NSNull) { return nil }
                let state = row["state"] as? String ?? ""
                if terminal.contains(state) { return nil }
                guard let taskId = row["task_id"] as? String, !taskId.isEmpty else { return nil }
                return PendingTaskEntry(
                    taskId: taskId,
                    title: row["title"] as? String ?? "",
                    goal: row["goal"] as? String ?? "",
                    assigneeName: row["assignee_name"] as? String ?? "",
                    stateLabel: row["state_label"] as? String ?? state,
                    createdAtSeconds: (row["created_at"] as? NSNumber)?.doubleValue ?? 0)
            }
        } catch {
            return []
        }
    }

    /// 可落地的本地频道（非私聊、未归档）。
    private static func localLandingChannels() -> [LocalLandingChannel] {
        guard let service = LocalPlatform.serviceOrNull() else { return [] }
        do {
            let result = try service.dispatch(
                tool: "agent_channel_list", args: [:],
                callerId: LocalPlatform.appAgentID,
                callerName: LocalPlatform.appAgentName)
            let rows = result["channels"] as? [[String: Any]] ?? []
            return rows.compactMap { row in
                guard let id = row["id"] as? String, !id.isEmpty else { return nil }
                if (row["kind"] as? String) == "dm" { return nil }
                if (row["archived"] as? Bool) == true { return nil }
                return LocalLandingChannel(id: id, name: (row["name"] as? String) ?? id)
            }
        } catch {
            return []
        }
    }

    /// 执行落地（9.19）：绑定频道后刷新，失败如实提示。
    private func land(_ task: PendingTaskEntry, to channel: LocalLandingChannel) {
        landingTask = nil
        guard let service = LocalPlatform.serviceOrNull() else {
            errorMessage = "本地服务未就绪，无法落地。"
            return
        }
        Task {
            let failure: String? = await Task.detached(priority: .userInitiated) {
                do {
                    _ = try service.uiLandTask(taskId: task.taskId, channelId: channel.id)
                    return nil
                } catch {
                    return error.localizedDescription
                }
            }.value
            if let failure {
                errorMessage = "落地失败：\(failure)"
            } else {
                await refresh()
            }
        }
    }
}

/// 落地目标频道（本地频道列表的一行）。
private struct LocalLandingChannel {
    let id: String
    let name: String
}
