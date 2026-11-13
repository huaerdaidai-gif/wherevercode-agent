// WhereverCode Agent — iOS MVP
//
// Conversation list: the home screen. Channels come from the platform
// (`agent_channel_list`); the two reserved conversation modes are
// shown as explicitly unimplemented rows, never as working chats.

import SwiftUI

struct ChatListView: View {
    @EnvironmentObject private var appState: AppState
    @State private var channels: [ChannelInfo] = []
    @State private var isLoading = false
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            Group {
                if !appState.isConfigured {
                    notConfiguredView
                } else if isLoading && channels.isEmpty {
                    ProgressView("正在加载频道…")
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    channelList
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
            Text("请先在「设置」里填写平台地址，并保存 Agent Token。地址默认为空，不会替你猜；Token 只存本机 Keychain。")
                .font(.body)
                .foregroundStyle(.secondary)
            NavigationLink("去设置") { SettingsView() }
                .buttonStyle(.borderedProminent)
            Spacer()
        }
        .padding()
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    private var channelList: some View {
        List {
            Section("团队频道") {
                if channels.isEmpty {
                    Text("没有可见的频道。请确认 Token 有权访问频道，或在设置里检查平台地址。")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(channels) { channel in
                        NavigationLink {
                            ChannelChatView(channel: channel)
                        } label: {
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
                }
            }
            Section("其他对话") {
                reservedRow(feature: .brainChat)
                reservedRow(feature: .singleAgentChat)
            }
        }
    }

    private func reservedRow(feature: ReservedFeature) -> some View {
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
