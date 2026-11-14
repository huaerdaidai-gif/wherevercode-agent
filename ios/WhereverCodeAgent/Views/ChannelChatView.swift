import SwiftUI

public struct ChannelChatView: View {
    @ObservedObject public var appState: AppState
    public let channel: ChannelInfo

    @State private var messages: [ChannelMessage] = []
    @State private var connectionState: ChannelConnectionState = .stopped
    @State private var connectionError: String?
    @State private var isLoadingHistory = false
    @State private var composerText = ""
    @State private var isSending = false
    @State private var sendError: String?
    @State private var taskCreatedBanner: String?

    public init(appState: AppState, channel: ChannelInfo) {
        self.appState = appState
        self.channel = channel
    }

    public var body: some View {
        VStack(spacing: 0) {
            HStack {
                Circle()
                    .fill(connectionState == .live ? Color.green : Color.orange)
                    .frame(width: 8, height: 8)
                Text("频道：\(channel.name) · 状态：\(connectionState.rawValue)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
                Button("刷新") {
                    Task { await refreshHistory() }
                }
                .font(.caption)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(Color(.secondarySystemBackground))

            if let banner = taskCreatedBanner {
                Text(banner)
                    .font(.caption)
                    .foregroundStyle(.green)
                    .padding(8)
            }

            List {
                if isLoadingHistory {
                    HStack {
                        ProgressView()
                        Text("正在加载频道历史…")
                    }
                } else if messages.isEmpty {
                    Text("还没有消息。发一条消息，或下拉刷新。")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(messages) { msg in
                        VStack(alignment: .leading, spacing: 6) {
                            HStack {
                                Text(msg.agentName).font(.caption.bold())
                                Spacer()
                                Button("转为正式任务") {
                                    promoteToTask(msg)
                                }
                                .font(.caption2)
                                .buttonStyle(.bordered)
                            }
                            Text(msg.text)
                                .font(.body)
                                .textSelection(.enabled)
                        }
                        .padding(.vertical, 4)
                    }
                }
            }

            if let err = sendError ?? connectionError {
                Text(err)
                    .font(.caption)
                    .foregroundStyle(.red)
                    .padding(.horizontal, 16)
            }

            HStack(spacing: 10) {
                TextField("发消息给频道…", text: $composerText)
                    .textFieldStyle(.roundedBorder)
                Button(isSending ? "发送中" : "发送") {
                    Task { await sendMessage() }
                }
                .disabled(isSending || composerText.trimmingCharacters(in: .whitespaces).isEmpty)
                .buttonStyle(.borderedProminent)
            }
            .padding(12)
        }
        .navigationTitle(channel.name)
        .task {
            await refreshHistory()
        }
    }

    private func promoteToTask(_ msg: ChannelMessage) {
        let newTask = LocalTask(
            title: String(msg.text.prefix(40)),
            description: "来自团队频道 [\(channel.name)] @\(msg.agentName)：\n\(msg.text)",
            state: "queued",
            assignedAgentId: msg.agentId
        )
        _ = try? LocalData.shared.tasks.createTask(newTask)
        taskCreatedBanner = "已将消息转为本地任务队列 (ID: \(newTask.taskId.prefix(8)))"
    }

    private func refreshHistory() async {
        guard let client = appState.makeMcpClient() else {
            connectionError = "无法建立频道连接：请检查设置里的平台地址与 Token。"
            return
        }
        isLoadingHistory = true
        defer { isLoadingHistory = false }
        let transport = ChannelTransport(client: client)
        do {
            try? await transport.joinChannel(channelID: channel.id)
            messages = try await transport.pollMessages(channelID: channel.id)
            connectionState = .polling
            connectionError = nil
        } catch {
            connectionError = error.localizedDescription
        }
    }

    private func sendMessage() async {
        let text = composerText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, let client = appState.makeMcpClient() else { return }
        isSending = true
        defer { isSending = false }
        let transport = ChannelTransport(client: client)
        do {
            _ = try await transport.postMessage(channelID: channel.id, text: text)
            composerText = ""
            sendError = nil
            await refreshHistory()
        } catch {
            sendError = error.localizedDescription
        }
    }
}
