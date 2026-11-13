// WhereverCode Agent — iOS MVP
//
// Team channel chat: message list + composer over the ChannelFeed
// (WebSocket push first, 15-second polling fallback). The connection
// state is shown honestly in the header; nothing is shown as live
// when it is only polling.

import SwiftUI

struct ChannelChatView: View {
    let channel: ChannelInfo

    @EnvironmentObject private var appState: AppState
    @State private var messages: [ChannelMessage] = []
    @State private var connectionState: ChannelConnectionState = .stopped
    @State private var connectionError: String?
    @State private var composerText = ""
    @State private var isSending = false
    @State private var sendError: String?
    @State private var feed: ChannelFeed?

    var body: some View {
        VStack(spacing: 0) {
            connectionBanner
            messageList
            composer
        }
        .navigationTitle(channel.displayName)
        .onAppear { startFeed() }
        .onDisappear { stopFeed() }
        .alert("发送失败", isPresented: Binding(
            get: { sendError != nil },
            set: { if !$0 { sendError = nil } }
        )) {
            Button("好", role: .cancel) {}
        } message: {
            Text(sendError ?? "")
        }
    }

    // MARK: - Connection banner

    private var connectionBanner: some View {
        HStack(spacing: 8) {
            Circle()
                .fill(connectionColor)
                .frame(width: 8, height: 8)
            Text(connectionState.label)
                .font(.caption)
            if let error = connectionError, connectionState != .live {
                Text(error)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer()
            Text("\(messages.count) 条")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding(.horizontal)
        .padding(.vertical, 6)
        .background(Color.secondary.opacity(0.08))
    }

    private var connectionColor: Color {
        switch connectionState {
        case .live: return .green
        case .polling: return .orange
        case .connecting: return .gray
        case .stopped: return .red
        }
    }

    // MARK: - Messages

    private var messageList: some View {
        ScrollViewReader { proxy in
            List(messages) { message in
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(message.displaySender)
                            .font(.caption)
                            .fontWeight(.semibold)
                        Text(message.createdAt, style: .time)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        Spacer()
                        Text(message.createdAt, style: .date)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Text(message.text)
                        .font(.body)
                        .textSelection(.enabled)
                    if let replyTo = message.replyTo, !replyTo.isEmpty {
                        Text("回复 \(String(replyTo.prefix(8)))…")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(.vertical, 2)
                .id(message.id)
            }
            .listStyle(.plain)
            .onChange(of: messages.count) { _ in
                if let last = messages.last {
                    withAnimation {
                        proxy.scrollTo(last.id, anchor: .bottom)
                    }
                }
            }
        }
    }

    // MARK: - Composer

    private var composer: some View {
        HStack(spacing: 8) {
            TextField("发消息给频道…", text: $composerText)
                .textFieldStyle(.roundedBorder)
                .disabled(isSending)
            Button("发送") { Task { await send() } }
                .buttonStyle(.borderedProminent)
                .disabled(composerText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || isSending)
        }
        .padding()
    }

    // MARK: - Feed lifecycle

    private func startFeed() {
        guard feed == nil,
              let transport = appState.makeChannelTransport(),
              let wsURL = appState.settings.channelWebSocketURL(channelID: channel.id) else {
            connectionError = "无法建立频道连接：请检查设置里的平台地址与 Token。"
            return
        }
        let newFeed = ChannelFeed(
            channelID: channel.id,
            transport: transport,
            webSocketURL: wsURL,
            tokenProvider: appState.tokenProvider
        )
        newFeed.onMessages = { fresh in
            messages.append(contentsOf: fresh)
            messages.sort { $0.createdAtSeconds < $1.createdAtSeconds }
        }
        newFeed.onStateChange = { state, error in
            connectionState = state
            connectionError = error
        }
        feed = newFeed
        newFeed.start()
    }

    private func stopFeed() {
        feed?.stop()
        feed = nil
    }

    private func send() async {
        guard let transport = appState.makeChannelTransport() else { return }
        let text = composerText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        isSending = true
        defer { isSending = false }
        do {
            _ = try await transport.postMessage(channelID: channel.id, text: text)
            composerText = ""
            sendError = nil
            // The server echoes the post back over WS/poll; no local
            // fake message is inserted — the list only shows what the
            // server actually returned.
        } catch {
            sendError = (error as? McpError)?.message ?? "发送失败。"
        }
    }
}
