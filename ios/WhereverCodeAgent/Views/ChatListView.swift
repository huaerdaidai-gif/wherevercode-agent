import SwiftUI

public struct ChatListView: View {
    @ObservedObject public var appState: AppState

    public init(appState: AppState) {
        self.appState = appState
    }

    public var body: some View {
        List {
            Section("平台状态") {
                HStack {
                    Image(systemName: appState.isConfigured ? "checkmark.circle.fill" : "exclamationmark.triangle")
                        .foregroundStyle(appState.isConfigured ? .green : .orange)
                    Text(appState.connectionStatus.displayText)
                        .font(.subheadline)
                }
            }
            Section("团队频道列表 (agent_channel_list)") {
                if appState.isLoadingChannels {
                    HStack {
                        ProgressView()
                        Text("正在加载频道…")
                    }
                } else if let err = appState.channelListError {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("频道加载失败：\(err)")
                            .font(.footnote)
                            .foregroundStyle(.red)
                        Button("重试") {
                            Task { await appState.loadChannels() }
                        }
                    }
                } else if appState.availableChannels.isEmpty {
                    Text("暂无可见频道。请确认平台 Token 已配置且有权访问频道。")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(appState.availableChannels) { channel in
                        NavigationLink(value: channel) {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(channel.name).font(.headline)
                                Text("ID: \(channel.id)").font(.caption2).foregroundStyle(.secondary)
                                if let last = channel.lastMessageText {
                                    Text(last).font(.caption).lineLimit(1).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("团队频道")
        .navigationDestination(for: ChannelInfo.self) { ch in
            ChannelChatView(appState: appState, channel: ch)
        }
        .task {
            if appState.availableChannels.isEmpty {
                await appState.loadChannels()
            }
        }
    }
}
