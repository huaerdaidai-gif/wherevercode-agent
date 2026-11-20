// WhereverCode Agent — iOS MVP
//
// P3-MSG 消息中心（2026-11-20，双端对齐批）：聚合频道里的 @我 / 未读 /
// 任务回执 三类条目。只读视图——不发送、不派任务、不执行代码；数据经
// MessageCenterLoader 只用既有读接口（ChannelTransport.pollMessages）拉
// 回，全程 markRead=false（绝不推进服务端已读标记，频道页的未读状态不
// 会被消息中心误消费——语义见 MessageCenterRules.swift 注释与桩传输测试）。
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
                    scrollTrigger: 0,
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
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("聚合频道里的 @我、未读与任务回执；只读，点条目回到对应频道。")
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
    }

    private func section(
        _ section: MessageCenterSection,
        title: String,
        entries: [MessageCenterEntry],
        subtitle: (MessageCenterEntry) -> String,
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
        }
    }

    private func entryRow(_ entry: MessageCenterEntry, subtitle: String) -> some View {
        Button {
            // 点条目跳到对应频道（频道工作区路由）；跳转语义见文件头注释。
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
            let channels = try await appState.loadChannels()
            await MainActor.run { loadingChannels = false }
            guard !channels.isEmpty else {
                snapshot = MessageCenterSnapshot()
                return
            }
            let transport = ChannelTransportMessageCenterAdapter(transport: appState.makeChannelTransport())
            let sources = channels.filter { !$0.isArchived }.map {
                MessageCenterChannelSource(channelId: $0.id, channelName: $0.displayName)
            }
            let loaded = try await MessageCenterLoader(transport: transport)
                .fetch(channels: sources, selfNames: selfNames)
            await MainActor.run { snapshot = loaded }
        } catch {
            await MainActor.run {
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
}
