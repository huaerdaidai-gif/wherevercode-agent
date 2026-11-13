// WhereverCode Agent — iOS
//
// Conversation home: the app's home screen, shaped like the
// Android app's chat home — a real page in its own right, NOT a
// platform remote. The platform connection is one item inside the
// app (a Settings entry), never a gate in front of every page:
// - Not connected to the platform yet: the page still renders as
//   the home screen — a 平台连接 card (status + a "去设置" action),
//   the 团队频道 section carrying the same setup hint Android
//   shows ("团队频道还没法连：先去…" — Android: MainActivity's
//   teamChannelEndpoint / buildTeamChannelSnapshot), and the
//   reserved conversation rows. Nothing is a dead end.
// - Connected: channels come from the platform
//   (`agent_channel_list`); load failures are shown inline with
//   the server's real error text and a retry button — never
//   swallowed, never replaced by fake data.
// The two reserved conversation modes are shown as explicitly
// unimplemented rows, never as working chats (see the capability
// gap list in the delivery notes: brain chat and single-agent
// chat are Android capabilities this iOS build does not have yet).

import SwiftUI

/// The "platform not connected" card section, shared by the
/// conversation home and the tasks page. On Android the same
/// state is a setup hint inside the affected page only (the team
/// channel page), while the rest of the app keeps working; here
/// it is one card in the page, and its action switches to the
/// Settings TAB via AppState — it must never push a Settings
/// screen onto the current tab's navigation stack (that was the
/// phase-1 bug: the tab bar said 对话 while the page showed 设置).
struct PlatformConnectionCardSection: View {
    let onGoSettings: () -> Void

    var body: some View {
        Section("平台连接") {
            VStack(alignment: .leading, spacing: 8) {
                Label("平台未连接", systemImage: "wifi.slash")
                    .font(.headline)
                Text("团队频道与平台任务需要先连接平台。平台只是本 App 的一个连接项，在「设置」里完成：地址已预填；平台凭证由你的平台管理员签发后填入，或用已有 owner 凭证在设置里生成。")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Button("去设置") { onGoSettings() }
                    .buttonStyle(.borderedProminent)
            }
            .padding(.vertical, 4)
        }
    }
}

/// Pure conversation-home content: renders exactly what it is
/// given — the connection card when the platform is not connected,
/// the real channel list when it is, plus the load-error section
/// and the reserved rows. Split out from ChatListView so the
/// layout is testable/renderable without a live platform.
struct ConversationHomeContentView: View {
    let platformConnected: Bool
    let channels: [ChannelInfo]
    let loadError: String?
    let isLoading: Bool
    let onRetry: () -> Void
    let onRefresh: () -> Void
    let onGoSettings: () -> Void

    var body: some View {
        List {
            if !platformConnected {
                PlatformConnectionCardSection(onGoSettings: onGoSettings)
            }
            if platformConnected, let loadError {
                Section("连接平台失败") {
                    Text(loadError)
                        .font(.subheadline)
                        .foregroundStyle(.red)
                        .textSelection(.enabled)
                    Button("重试") { onRetry() }
                }
            }
            Section("团队频道") {
                if !platformConnected {
                    // Android's setupHint for the same state
                    // (buildTeamChannelSnapshot, configured=false).
                    Text("团队频道还没法连：先在设置里连接平台——在「平台连接」里填入 App 的平台凭证（由你的平台管理员签发），或先用已有凭证生成。连上后频道会列在这里，点一下即可进入。")
                        .foregroundStyle(.secondary)
                } else if channels.isEmpty {
                    Text(loadError == nil
                         ? (isLoading ? "正在加载频道…" : "没有可见的频道。请确认 Token 有权访问频道，或在设置里检查平台地址。")
                         : "频道加载失败，点上面的「重试」再试一次。")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(channels) { channel in
                        NavigationLink {
                            ChannelChatView(channel: channel)
                        } label: {
                            ChannelRow(channel: channel)
                        }
                    }
                }
            }
            Section("其他对话") {
                ReservedFeatureRow(feature: .brainChat)
                ReservedFeatureRow(feature: .singleAgentChat)
            }
        }
        .refreshable { onRefresh() }
    }
}

/// Pure channel-list content (connected state): renders exactly
/// the channels it is given, plus the load-error section and the
/// reserved rows. Kept as its own view for the screenshot renders.
struct ChannelListContentView: View {
    let channels: [ChannelInfo]
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
            Section("团队频道") {
                if channels.isEmpty {
                    Text(loadError == nil
                         ? "没有可见的频道。请确认 Token 有权访问频道，或在设置里检查平台地址。"
                         : "频道加载失败，点上面的「重试」再试一次。")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(channels) { channel in
                        NavigationLink {
                            ChannelChatView(channel: channel)
                        } label: {
                            ChannelRow(channel: channel)
                        }
                    }
                }
            }
            Section("其他对话") {
                ReservedFeatureRow(feature: .brainChat)
                ReservedFeatureRow(feature: .singleAgentChat)
            }
        }
        .refreshable { onRefresh() }
    }
}

/// One channel row in the list.
struct ChannelRow: View {
    let channel: ChannelInfo

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(channel.displayName)
                .font(.headline)
            if let last = channel.lastMessageText, !last.isEmpty {
                Text(last)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
            if !channel.participants.isEmpty {
                Text(channel.participants.joined(separator: "、"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }
}

/// A reserved feature shown as a labelled, non-working row.
struct ReservedFeatureRow: View {
    let feature: ReservedFeature

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text(feature.label)
                    .font(.headline)
                Text(feature.note)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Text("未实现")
                .font(.caption)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(Color.secondary.opacity(0.15))
                .clipShape(Capsule())
        }
        .opacity(0.7)
    }
}

struct ChatListView: View {
    @EnvironmentObject private var appState: AppState
    @State private var channels: [ChannelInfo] = []
    @State private var isLoading = false
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            ConversationHomeContentView(
                platformConnected: appState.isConfigured,
                channels: channels,
                loadError: errorMessage,
                isLoading: isLoading,
                onRetry: { Task { await loadChannels() } },
                onRefresh: { Task { await loadChannels() } },
                onGoSettings: { appState.goToSettings() }
            )
            .navigationTitle("对话")
            .toolbar {
                if appState.isConfigured {
                    Button("刷新") { Task { await loadChannels() } }
                        .disabled(isLoading)
                }
            }
            .task { await loadChannels() }
            #if compiler(>=6.0)
            .onChange(of: appState.isConfigured) { _, isNowConfigured in
                // Returning from Settings with a finished setup must
                // show real data, not the stale unconnected card.
                if isNowConfigured { Task { await loadChannels() } }
            }
            #else
            .onChange(of: appState.isConfigured) { isNowConfigured in
                if isNowConfigured { Task { await loadChannels() } }
            }
            #endif
        }
    }

    private func loadChannels() async {
        // Not connected is a home state, not an error: the page
        // shows the connection card and never attempts a load (and
        // never surfaces a "missing credential" error as if the
        // platform had failed).
        guard appState.isConfigured, let transport = appState.makeChannelTransport() else { return }
        isLoading = true
        defer { isLoading = false }
        do {
            channels = try await transport.listChannels()
            errorMessage = nil
        } catch {
            errorMessage = (error as? McpError)?.message ?? "加载频道失败。"
        }
    }
}
