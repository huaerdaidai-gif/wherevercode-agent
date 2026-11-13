// WhereverCode Agent — iOS
//
// Conversation list: the home screen. Channels come from the platform
// (`agent_channel_list`); the two reserved conversation modes are
// shown as explicitly unimplemented rows, never as working chats.
// Load failures are shown inline with the server's real error text
// and a retry button — never swallowed, never replaced by fake data.

import SwiftUI

/// Shared "not connected yet" empty state. The action switches to
/// the Settings TAB via AppState — it must never push a Settings
/// screen onto the current tab's navigation stack (that was the
/// phase-1 bug: the tab bar said 对话 while the page showed 设置).
struct NotConfiguredView: View {
    let message: String
    let onGoSettings: () -> Void

    var body: some View {
        ContentUnavailableView {
            Label("还没有连上平台", systemImage: "wifi.slash")
        } description: {
            Text(message)
        } actions: {
            Button("去设置") { onGoSettings() }
                .buttonStyle(.borderedProminent)
        }
    }
}

/// Pure channel-list content: renders exactly the channels it is
/// given (plus the load-error section and the reserved rows). Split
/// out from ChatListView so the layout is testable/renderable without
/// a live platform.
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
            Group {
                if !appState.isConfigured {
                    NotConfiguredView(
                        message: "请先在「设置」里填写平台地址，并保存 Agent Token。地址默认为空，不会替你猜；Token 只存本机 Keychain。",
                        onGoSettings: { appState.goToSettings() }
                    )
                } else if isLoading && channels.isEmpty && errorMessage == nil {
                    ProgressView("正在加载频道…")
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    ChannelListContentView(
                        channels: channels,
                        loadError: errorMessage,
                        onRetry: { Task { await loadChannels() } },
                        onRefresh: { Task { await loadChannels() } }
                    )
                }
            }
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
                // show real data, not the stale empty state.
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
        guard let transport = appState.makeChannelTransport() else { return }
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
