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
    // Phase-2/3 message actions (optional: preview/test call sites
    // that pass none keep the read-only rendering).
    var onReply: ((ChannelMessage) -> Void)? = nil
    var onTogglePin: ((ChannelMessage) -> Void)? = nil
    var onReact: ((ChannelMessage, String) -> Void)? = nil
    var onForward: ((ChannelMessage) -> Void)? = nil
    var onShowReaders: ((ChannelMessage) -> Void)? = nil
    var onRecall: ((ChannelMessage) -> Void)? = nil
    var onDelete: ((ChannelMessage) -> Void)? = nil

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
                let pinned = ChannelExtras.pinnedMessages(messages)
                if !pinned.isEmpty {
                    Section("置顶消息") {
                        ForEach(pinned) { message in
                            Text("\(message.displaySender)：\(ChannelExtras.displayText(message))")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(2)
                        }
                    }
                }
                ForEach(messages) { message in
                    ChannelMessageRow(
                        message: message,
                        quoteText: ChannelExtras.replyQuote(in: messages, for: message),
                        onReply: onReply,
                        onTogglePin: onTogglePin,
                        onReact: onReact,
                        onForward: onForward,
                        onShowReaders: onShowReaders,
                        onRecall: onRecall,
                        onDelete: onDelete
                    )
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
    var quoteText: String? = nil
    var onReply: ((ChannelMessage) -> Void)? = nil
    var onTogglePin: ((ChannelMessage) -> Void)? = nil
    var onReact: ((ChannelMessage, String) -> Void)? = nil
    var onForward: ((ChannelMessage) -> Void)? = nil
    var onShowReaders: ((ChannelMessage) -> Void)? = nil
    var onRecall: ((ChannelMessage) -> Void)? = nil
    var onDelete: ((ChannelMessage) -> Void)? = nil

    private func promoteToTask(_ msg: ChannelMessage) {
        let newTask = LocalTask(
            title: String(msg.text.prefix(40)),
            description: "来自团队频道 (@\(msg.displaySender))：\(msg.text)",
            state: "queued"
        )
        _ = try? LocalData.shared.tasks.createTask(newTask)
    }

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
            if let quoteText {
                Text(quoteText)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else if let replyTo = message.replyTo, !replyTo.isEmpty {
                Text("回复 \(String(replyTo.prefix(8)))…")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            if let forwardOf = message.forwardOf, !forwardOf.isEmpty {
                Text("转发自 \(forwardOf)")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            Text(ChannelExtras.displayText(message))
                .font(.body)
                .textSelection(.enabled)
            if let ref = message.attachmentRef, !ref.isEmpty {
                Text("附件：\(ref)")
                    .font(.caption)
                    .foregroundStyle(.blue)
                    .textSelection(.enabled)
            }
            let reactionLine = ChannelExtras.reactionText(message.reactions)
            if !reactionLine.isEmpty {
                Text(reactionLine)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            HStack {
                Spacer()
                if !message.recalled, onReply != nil || onTogglePin != nil || onReact != nil
                    || onForward != nil || onShowReaders != nil || onRecall != nil || onDelete != nil {
                    Menu {
                        if let onReply {
                            Button("回复") { onReply(message) }
                        }
                        if let onTogglePin {
                            Button(message.pinned ? "取消置顶" : "置顶") { onTogglePin(message) }
                        }
                        if let onReact {
                            ForEach(ChannelExtras.reactionChoices.prefix(2), id: \.self) { emoji in
                                Button(emoji) { onReact(message, emoji) }
                            }
                        }
                        if let onForward {
                            Button("转发") { onForward(message) }
                        }
                        if let onShowReaders {
                            Button("已读情况") { onShowReaders(message) }
                        }
                        if let onRecall {
                            Button("撤回") { onRecall(message) }
                        }
                        if let onDelete {
                            Button("删除", role: .destructive) { onDelete(message) }
                        }
                    } label: {
                        Label("操作", systemImage: "ellipsis.circle")
                            .font(.caption2)
                    }
                    .controlSize(.mini)
                }
                Button {
                    promoteToTask(message)
                } label: {
                    Label("转为正式任务", systemImage: "arrow.triangle.branch")
                        .font(.caption2)
                }
                .buttonStyle(.bordered)
                .controlSize(.mini)
            }
        }
        .padding(.vertical, 2)
    }
}

struct ChannelChatView: View {
    let channel: ChannelInfo

    @EnvironmentObject private var appState: AppState

    init(channel: ChannelInfo) {
        self.channel = channel
    }

    init(appState: AppState, channel: ChannelInfo) {
        self.channel = channel
    }
    @State private var messages: [ChannelMessage] = []
    @State private var connectionState: ChannelConnectionState = .stopped
    @State private var connectionError: String?
    @State private var isLoadingHistory = true
    @State private var composerText = ""
    @State private var isSending = false
    @State private var sendError: String?
    @State private var feed: ChannelFeed?
    @State private var searchText = ""
    @State private var isShowingSearchResults = false
    // Phase-2/3 state: reply target, pending attachment reference,
    // scheduled posts, action plumbing.
    @State private var replyingTo: ChannelMessage?
    @State private var pendingAttachmentRef: String?
    @State private var scheduledPosts: [ScheduledChannelPost] = []
    @State private var actionError: String?
    @State private var readersText: String?
    @State private var forwardSource: ChannelMessage?
    @State private var otherChannels: [ChannelInfo] = []
    // @我 identity (phase-3 strip completion, 2026-11-20): ids of
    // messages THIS view posted, and the sender names learned from
    // their server echoes — the same learn-from-own-posts approach
    // as Android (ChannelSenderPolicy). No settings screen stores
    // this token's channel display name, so the echo is the only
    // reliable source; before the first own post echoes back, the
    // strip can only match the literal "@本机 App".
    @State private var ownPostIds: Set<String> = []
    @State private var selfNames: [String] = []
    @State private var showAttachmentInput = false
    @State private var attachmentDraft = ""

    var body: some View {
        VStack(spacing: 0) {
            searchBar
            messageCenterStrip
            if let replyingTo {
                HStack {
                    Text("正在回复 \(replyingTo.displaySender)：\(String(replyingTo.text.prefix(30)))")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Spacer()
                    Button("取消") { self.replyingTo = nil }
                        .font(.caption)
                }
                .padding(.horizontal)
                .padding(.vertical, 4)
            }
            if !scheduledForChannel.isEmpty {
                scheduledStrip
            }
            ChannelContentView(
                messages: messages,
                connectionState: connectionState,
                connectionError: connectionError,
                isLoadingHistory: isLoadingHistory,
                composerText: $composerText,
                isSending: isSending,
                onSend: { Task { await send() } },
                onRefresh: { reload() },
                onReply: { replyingTo = $0 },
                onTogglePin: { msg in
                    Task { await runAction { transport in
                        _ = try await transport.setMessagePinned(
                            channelID: channel.id, messageID: msg.id, pinned: !msg.pinned)
                    } }
                },
                onReact: { msg, emoji in
                    Task { await runAction { transport in
                        _ = try await transport.reactMessage(
                            channelID: channel.id, messageID: msg.id, emoji: emoji,
                            on: !ChannelExtras.myReaction(msg.reactions, emoji: emoji))
                    } }
                },
                onForward: { forwardSource = $0 },
                onShowReaders: { msg in
                    Task { await showReaders(msg) }
                },
                onRecall: { msg in
                    Task { await runAction { transport in
                        _ = try await transport.recallMessage(channelID: channel.id, messageID: msg.id)
                    } }
                },
                onDelete: { msg in
                    Task { await runAction { transport in
                        _ = try await transport.deleteMessage(channelID: channel.id, messageID: msg.id)
                    } }
                }
            )
            composerTools
        }
        .navigationTitle(channel.displayName)
        .toolbar {
            Button("重新加载") { reload() }
        }
        .onAppear {
            startFeed()
            scheduledPosts = Self.loadScheduled()
            Task {
                await flushScheduled()
                if let transport = appState.makeChannelTransport() {
                    otherChannels = (try? await transport.listChannels()) ?? []
                }
            }
        }
        .onDisappear { stopFeed() }
        .alert("发送失败", isPresented: Binding(
            get: { sendError != nil },
            set: { if !$0 { sendError = nil } }
        )) {
            Button("好", role: .cancel) {}
        } message: {
            Text(sendError ?? "")
        }
        .alert("操作失败", isPresented: Binding(
            get: { actionError != nil },
            set: { if !$0 { actionError = nil } }
        )) {
            Button("好", role: .cancel) {}
        } message: {
            Text(actionError ?? "")
        }
        .alert("已读情况", isPresented: Binding(
            get: { readersText != nil },
            set: { if !$0 { readersText = nil } }
        )) {
            Button("好", role: .cancel) {}
        } message: {
            Text(readersText ?? "")
        }
        .alert("加附件引用", isPresented: $showAttachmentInput) {
            TextField("网盘链接、投递编号或文件引用", text: $attachmentDraft)
            Button("添加") {
                let ref = attachmentDraft.trimmingCharacters(in: .whitespacesAndNewlines)
                pendingAttachmentRef = ref.isEmpty ? nil : ref
                attachmentDraft = ""
            }
            Button("取消", role: .cancel) { attachmentDraft = "" }
        } message: {
            Text("引用制：只填引用，不上传文件本体。")
        }
        .confirmationDialog(
            "转发到",
            isPresented: Binding(
                get: { forwardSource != nil },
                set: { if !$0 { forwardSource = nil } }
            ),
            titleVisibility: .visible
        ) {
            ForEach(otherChannels.filter { $0.id != channel.id }) { target in
                Button(target.displayName) {
                    if let source = forwardSource {
                        Task { await forward(source, to: target) }
                    }
                    forwardSource = nil
                }
            }
            Button("取消", role: .cancel) { forwardSource = nil }
        }
    }

    // MARK: - Phase-2/3 strips and tools

    /// Message center strip: unread (vs the local read cursor),
    /// @我 unread and pinned counts for this channel — same line as
    /// Android's message-center section. @我 matches the names
    /// learned from own-post echoes plus the literal "本机 App"
    /// (Android's local-app member name); with no learned name yet
    /// the count covers only "@本机 App" mentions, never a guess.
    private var messageCenterStrip: some View {
        let lastSeen = (try? LocalData.shared.cursors.lastSeenMessageId(channelId: channel.id)) ?? nil
        let center = ChannelExtras.buildMessageCenter(
            messages: messages, myNames: selfNames + ["本机 App"], lastReadId: lastSeen)
        let pinnedCount = ChannelExtras.pinnedMessages(messages).count
        return HStack(spacing: 12) {
            Text("消息中心")
                .font(.caption)
                .fontWeight(.semibold)
            Text("未读 \(center.unreadCount)")
                .font(.caption)
                .foregroundStyle(.secondary)
            Text("@我未读 \(center.unreadMentionCount)")
                .font(.caption)
                .foregroundStyle(.secondary)
            Text("置顶 \(pinnedCount)")
                .font(.caption)
                .foregroundStyle(.secondary)
            Spacer()
        }
        .padding(.horizontal)
        .padding(.vertical, 4)
        .background(Color.secondary.opacity(0.05))
    }

    private var scheduledForChannel: [ScheduledChannelPost] {
        scheduledPosts.filter { $0.channelId == channel.id }
    }

    private var scheduledStrip: some View {
        VStack(alignment: .leading, spacing: 2) {
            ForEach(scheduledForChannel) { item in
                HStack {
                    Text("定时 \(Date(timeIntervalSince1970: item.dueAtSeconds), style: .time) 发：\(String(item.text.prefix(40)))")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Spacer()
                    Button("取消") { cancelScheduled(item) }
                        .font(.caption)
                }
            }
        }
        .padding(.horizontal)
        .padding(.vertical, 4)
    }

    /// Composer tools row: attachment reference + scheduled send.
    private var composerTools: some View {
        HStack(spacing: 12) {
            Button {
                attachmentDraft = pendingAttachmentRef ?? ""
                showAttachmentInput = true
            } label: {
                Label(pendingAttachmentRef == nil ? "加附件引用" : "附件引用已加", systemImage: "paperclip")
                    .font(.caption)
            }
            Menu {
                ForEach([600.0, 3600.0, 10800.0], id: \.self) { delay in
                    Button(delay == 600 ? "10 分钟后" : (delay == 3600 ? "1 小时后" : "3 小时后")) {
                        scheduleCurrent(delaySeconds: delay)
                    }
                }
            } label: {
                Label("定时发送", systemImage: "clock")
                    .font(.caption)
            }
            Spacer()
        }
        .padding(.horizontal)
        .padding(.bottom, 6)
    }

    // MARK: - Phase-2/3 actions

    /// Run one transport action; on success reload, on failure show
    /// the server's real error — never fake success.
    private func runAction(_ block: (ChannelTransport) async throws -> Void) async {
        guard let transport = appState.makeChannelTransport() else { return }
        do {
            try await block(transport)
            reload()
        } catch {
            actionError = (error as? McpError)?.message ?? "操作失败。"
        }
    }

    private func showReaders(_ msg: ChannelMessage) async {
        guard let transport = appState.makeChannelTransport() else { return }
        do {
            let readers = try await transport.channelReaders(channelID: channel.id, messageID: msg.id)
            readersText = ChannelExtras.readersText(readers)
        } catch {
            actionError = (error as? McpError)?.message ?? "已读名单拉取失败。"
        }
    }

    private func forward(_ source: ChannelMessage, to target: ChannelInfo) async {
        guard let transport = appState.makeChannelTransport() else { return }
        do {
            _ = try await transport.postMessageEx(
                channelID: target.id,
                text: source.text,
                effectKey: ChannelExtras.buildEffectKey(nonce: UUID().uuidString),
                forwardOf: ChannelExtras.forwardOfLabel(
                    sourceChannelName: channel.displayName, senderName: source.displaySender)
            )
        } catch {
            actionError = (error as? McpError)?.message ?? "转发失败。"
        }
    }

    // MARK: - Scheduled send (phase-2, on-device queue)

    private static let scheduledKey = "channel_scheduled_posts"

    private static func loadScheduled() -> [ScheduledChannelPost] {
        guard let data = UserDefaults.standard.data(forKey: scheduledKey),
              let items = try? JSONDecoder().decode([ScheduledChannelPost].self, from: data)
        else { return [] }
        return items
    }

    private func saveScheduled() {
        if let data = try? JSONEncoder().encode(scheduledPosts) {
            UserDefaults.standard.set(data, forKey: Self.scheduledKey)
        }
    }

    private func scheduleCurrent(delaySeconds: Double) {
        let text = composerText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        scheduledPosts.append(ScheduledChannelPost(
            id: UUID().uuidString,
            channelId: channel.id,
            text: text,
            dueAtSeconds: Date().timeIntervalSince1970 + delaySeconds,
            effectKey: ChannelExtras.buildEffectKey(nonce: UUID().uuidString),
            attachmentRef: pendingAttachmentRef
        ))
        saveScheduled()
        composerText = ""
        pendingAttachmentRef = nil
    }

    private func cancelScheduled(_ item: ScheduledChannelPost) {
        scheduledPosts.removeAll { $0.id == item.id }
        saveScheduled()
    }

    /// Send due scheduled posts (on appear; stored effect keys make
    /// replays safe). Failures stay queued for the next appearance.
    private func flushScheduled() async {
        guard let transport = appState.makeChannelTransport() else { return }
        let due = ChannelExtras.dueScheduled(scheduledPosts, nowSeconds: Date().timeIntervalSince1970)
        if due.isEmpty { return }
        for item in due {
            do {
                let postedId = try await transport.postMessageEx(
                    channelID: item.channelId, text: item.text,
                    effectKey: item.effectKey, attachmentRef: item.attachmentRef)
                if !postedId.isEmpty { ownPostIds.insert(postedId) }
                scheduledPosts.removeAll { $0.id == item.id }
            } catch {
                // Keep queued; the effect key prevents double-posting.
            }
        }
        saveScheduled()
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
            // Learn this token's own channel identity from the
            // echoes of messages posted here (see ownPostIds).
            selfNames = ChannelExtras.learnedSelfNames(
                from: fresh, ownPostIds: ownPostIds, existing: selfNames)
            messages.append(contentsOf: fresh)
            messages.sort { $0.createdAtSeconds < $1.createdAtSeconds }
            // Reply cursor (data layer): remember the newest seen
            // message per channel, best-effort and off the UI path.
            if let newest = messages.last {
                let channelId = channel.id
                Task.detached {
                    try? LocalData.shared.cursors.setLastSeenMessageId(
                        channelId: channelId, messageId: newest.id)
                }
            }
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

    // MARK: - In-channel search (phase-1: search parity)
    //
    // Server-side keyword search via the `q` argument of
    // agent_channel_messages. While results are shown the live feed
    // is stopped, so the list is exactly what the server returned;
    // clearing the search reloads the full history and restarts it.

    private var searchBar: some View {
        HStack(spacing: 8) {
            TextField("搜索频道内消息…", text: $searchText)
                .textFieldStyle(.roundedBorder)
            if isShowingSearchResults {
                Button("清除") { clearSearch() }
            } else {
                Button("搜索") { Task { await runSearch() } }
                    .disabled(searchText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .padding(.horizontal)
        .padding(.vertical, 6)
    }

    private func runSearch() async {
        guard let transport = appState.makeChannelTransport() else { return }
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return }
        stopFeed()
        isLoadingHistory = true
        do {
            let results = try await transport.pollMessages(
                channelID: channel.id, limit: 100, query: query)
            messages = results
            isShowingSearchResults = true
            connectionError = nil
        } catch {
            connectionError = (error as? McpError)?.message ?? "搜索失败。"
        }
        isLoadingHistory = false
    }

    private func clearSearch() {
        searchText = ""
        isShowingSearchResults = false
        reload()
    }

    private func send() async {
        guard let transport = appState.makeChannelTransport() else { return }
        let text = composerText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        isSending = true
        defer { isSending = false }
        do {
            let postedId = try await transport.postMessageEx(
                channelID: channel.id,
                text: text,
                replyTo: replyingTo?.id,
                effectKey: ChannelExtras.buildEffectKey(nonce: UUID().uuidString),
                attachmentRef: pendingAttachmentRef
            )
            // Remember the id so the echo teaches the strip our own
            // channel name (see ownPostIds / selfNames).
            if !postedId.isEmpty { ownPostIds.insert(postedId) }
            composerText = ""
            replyingTo = nil
            pendingAttachmentRef = nil
            sendError = nil
            // The server echoes the post back over WS/poll; no local
            // fake message is inserted — the list only shows what the
            // server actually returned.
        } catch {
            sendError = (error as? McpError)?.message ?? "发送失败。"
        }
    }
}
