// WhereverCode Agent — iOS
//
// Team channel chat: message list + composer over the ChannelFeed
// (WebSocket push first, 15-second polling fallback, automatic
// backfill after a reconnect). The connection state and the REAL
// error text are shown in the header; nothing is shown as live when
// it is only polling, and a failed load offers an explicit reload.

import SwiftUI

/// Pure chat content: connection banner + message list + composer.
/// Renders exactly the state it is given; split out from
/// ChannelChatView so the layout is testable/renderable without a
/// live platform.
struct ChannelContentView: View {
    let messages: [ChannelMessage]
    let connectionState: ChannelConnectionState
    let connectionError: String?
    let isLoadingHistory: Bool
    @Binding var composerText: String
    let isSending: Bool
    let onSend: () -> Void
    let onRefresh: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            connectionBanner
            messageList
            composer
        }
    }

    // MARK: - Connection banner

    private var connectionBanner: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                Circle()
                    .fill(connectionColor)
                    .frame(width: 8, height: 8)
                Text(connectionState.label)
                    .font(.caption)
                    .fontWeight(.semibold)
                Spacer()
                Text("\(messages.count) 条")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            // The real error, in full: truncating it to one line hid
            // the server's actual reason in phase 1.
            if let error = connectionError, connectionState != .live {
                Text(error)
                    .font(.caption)
                    .foregroundStyle(.red)
                    .textSelection(.enabled)
            }
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
            List {
                if isLoadingHistory && messages.isEmpty {
                    HStack(spacing: 8) {
                        ProgressView()
                        Text("正在加载频道历史…")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                } else if messages.isEmpty {
                    Text("还没有消息。发一条消息，或下拉刷新。")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                ForEach(messages) { message in
                    ChannelMessageRow(message: message)
                        .id(message.id)
                }
            }
            .listStyle(.plain)
            .refreshable { onRefresh() }
            // iOS 17 deprecates the one-parameter onChange; Xcode 26
            // uses the two-parameter form, older toolchains the old
            // one. (compiler(), not swift(): swift() follows the
            // project's Swift 5 language mode and would pick the old
            // form.)
            #if compiler(>=6.0)
            .onChange(of: messages.count) { _, _ in
                if let last = messages.last {
                    withAnimation {
                        proxy.scrollTo(last.id, anchor: .bottom)
                    }
                }
            }
            #else
            .onChange(of: messages.count) { _ in
                if let last = messages.last {
                    withAnimation {
                        proxy.scrollTo(last.id, anchor: .bottom)
                    }
                }
            }
            #endif
        }
    }

    // MARK: - Composer

    private var composer: some View {
        HStack(spacing: 8) {
            TextField("发消息给频道…", text: $composerText)
                .textFieldStyle(.roundedBorder)
                .disabled(isSending)
            Button("发送") { onSend() }
                .buttonStyle(.borderedProminent)
                .disabled(composerText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || isSending)
        }
        .padding()
    }
}

/// One message in the channel history.
struct ChannelMessageRow: View {
    let message: ChannelMessage

    var body: some View {
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
    }
}

struct ChannelChatView: View {
    let channel: ChannelInfo

    @EnvironmentObject private var appState: AppState
    @State private var messages: [ChannelMessage] = []
    @State private var connectionState: ChannelConnectionState = .stopped
    @State private var connectionError: String?
    @State private var isLoadingHistory = true
    @State private var composerText = ""
    @State private var isSending = false
    @State private var sendError: String?
    @State private var feed: ChannelFeed?

    var body: some View {
        ChannelContentView(
            messages: messages,
            connectionState: connectionState,
            connectionError: connectionError,
            isLoadingHistory: isLoadingHistory,
            composerText: $composerText,
            isSending: isSending,
            onSend: { Task { await send() } },
            onRefresh: { reload() }
        )
        .navigationTitle(channel.displayName)
        .toolbar {
            Button("重新加载") { reload() }
        }
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

    // MARK: - Feed lifecycle

    private func startFeed() {
        guard feed == nil,
              let transport = appState.makeChannelTransport(),
              let wsURL = appState.settings.channelWebSocketURL(channelID: channel.id) else {
            isLoadingHistory = false
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
            isLoadingHistory = false
            messages.append(contentsOf: fresh)
            messages.sort { $0.createdAtSeconds < $1.createdAtSeconds }
        }
        newFeed.onStateChange = { state, error in
            connectionState = state
            if let error { connectionError = error }
        }
        newFeed.onInitialPoll = { error in
            isLoadingHistory = false
            if let error { connectionError = error }
        }
        feed = newFeed
        newFeed.start()
    }

    private func stopFeed() {
        feed?.stop()
        feed = nil
    }

    /// Full reload: drop the feed and the list, pull history again.
    private func reload() {
        stopFeed()
        messages = []
        connectionError = nil
        isLoadingHistory = true
        startFeed()
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
