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
    /// №12: row to highlight (search jump target) and scroll to.
    var highlightID: String? = nil
    var scrollTargetID: String? = nil
    /// №12: set in search mode — tapping a result jumps to its context.
    var onTapMessage: ((ChannelMessage) -> Void)? = nil
    /// №12: member names for the @ completion strip.
    var memberNames: [String] = []

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
            // the server's actual reason in phase 1. №12: while the
            // polling fallback is running the error is diagnostic,
            // not an alarm — it shows in secondary, never red.
            if let error = connectionError, connectionState != .live {
                Text(error)
                    .font(.caption)
                    .foregroundStyle(connectionState == .polling ? Color.secondary : Color.red)
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
                    ChannelMessageRow(message: message, isHighlighted: message.id == highlightID)
                        .id(message.id)
                        .contentShape(Rectangle())
                        .onTapGesture { onTapMessage?(message) }
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
                scrollAfterChange(proxy)
            }
            .onChange(of: scrollTargetID) { _, target in
                if let target {
                    withAnimation { proxy.scrollTo(target, anchor: .center) }
                }
            }
            #else
            .onChange(of: messages.count) { _ in
                scrollAfterChange(proxy)
            }
            .onChange(of: scrollTargetID) { target in
                if let target {
                    withAnimation { proxy.scrollTo(target, anchor: .center) }
                }
            }
            #endif
        }
    }

    /// Live mode follows the newest message; browse mode (a scroll
    /// target is set) leaves the position where the user is looking.
    private func scrollAfterChange(_ proxy: ScrollViewProxy) {
        guard scrollTargetID == nil, let last = messages.last else { return }
        withAnimation {
            proxy.scrollTo(last.id, anchor: .bottom)
        }
    }

    // MARK: - Composer

    private var composer: some View {
        VStack(spacing: 6) {
            mentionStrip
            HStack(spacing: 8) {
                TextField("发消息给频道…", text: $composerText)
                    .textFieldStyle(.roundedBorder)
                    .disabled(isSending)
                Button("发送") { onSend() }
                    .buttonStyle(.borderedProminent)
                    .disabled(composerText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || isSending)
            }
        }
        .padding()
    }

    /// №12 @ completion: while the text ends with an @token, offer the
    /// matching members above the composer; tapping one completes it.
    @ViewBuilder
    private var mentionStrip: some View {
        let matches = mentionMatches(in: composerText, memberNames: memberNames)
        if !matches.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(matches, id: \.self) { name in
                        Button("@\(name)") {
                            composerText = applyingMention(name, to: composerText)
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.small)
                    }
                }
            }
        }
    }
}

/// The trailing @token of `text` when the @ starts a word (start of
/// text or after whitespace) and the token has no whitespace yet;
/// nil otherwise (e.g. an email address, or the mention is done).
func mentionToken(in text: String) -> String? {
    guard let at = text.lastIndex(of: "@") else { return nil }
    let before = text[..<at]
    if let last = before.last, last != " ", last != "\n" { return nil }
    let token = text[text.index(after: at)...]
    if token.contains(" ") || token.contains("\n") { return nil }
    return String(token)
}

/// Members matching the trailing @token (at most 8).
func mentionMatches(in text: String, memberNames: [String]) -> [String] {
    guard let token = mentionToken(in: text) else { return [] }
    return Array(
        memberNames.filter {
            $0.localizedCaseInsensitiveContains(token) &&
                $0.caseInsensitiveCompare(token) != .orderedSame
        }.prefix(8)
    )
}

/// Replace the trailing @token with `@<name> `.
func applyingMention(_ name: String, to text: String) -> String {
    guard let at = text.lastIndex(of: "@"), mentionToken(in: text) != nil else { return text }
    return String(text[..<at]) + "@" + name + " "
}

/// One message in the channel history.
struct ChannelMessageRow: View {
    let message: ChannelMessage
    /// №12: highlight (search jump target).
    var isHighlighted: Bool = false
    @State private var showingTaskForm = false
    @State private var taskTitle = ""
    @State private var taskError: String?
    private func promoteToTask(_ msg: ChannelMessage) {
        taskTitle = String(msg.text.prefix(40))
        showingTaskForm = true
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(message.displaySender).font(.caption).fontWeight(.semibold)
                Text(message.createdAt, style: .time).font(.caption).foregroundStyle(.secondary)
                Spacer()
                Text(message.createdAt, style: .date).font(.caption).foregroundStyle(.secondary)
            }
            Text(message.text).font(.body).textSelection(.enabled)
            if let replyTo = message.replyTo, !replyTo.isEmpty {
                Text("回复 \(String(replyTo.prefix(8)))…").font(.caption).foregroundStyle(.secondary)
            }
            HStack {
                Spacer()
                Button { promoteToTask(message) } label: {
                    Label("转为正式任务", systemImage: "arrow.triangle.branch").font(.caption2)
                }
                .buttonStyle(.bordered).controlSize(.mini)
            }
        }
        .padding(.vertical, 2)
        .background(isHighlighted ? Color.accentColor.opacity(0.15) : Color.clear)
        .sheet(isPresented: $showingTaskForm) {
            NavigationStack {
                Form {
                    Section("任务标题") { TextField("任务标题", text: $taskTitle) }
                    Section("任务说明") {
                        Text("来自团队频道 (@\(message.displaySender))：\(message.text)")
                    }
                    Section { Text("确认后进入任务列表（状态：排队）。") }
                }
                .navigationTitle("创建任务")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("取消") { showingTaskForm = false } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("创建") {
                            do {
                                try LocalData.shared.tasks.createTask(LocalTask(title: taskTitle.trimmingCharacters(in: .whitespacesAndNewlines), description: "来自团队频道 (@\(message.displaySender))：\(message.text)", state: "queued"))
                                showingTaskForm = false
                            } catch { taskError = "创建任务失败：\(error.localizedDescription)" }
                        }.disabled(taskTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                }
                .alert("创建失败", isPresented: Binding(get: { taskError != nil }, set: { if !$0 { taskError = nil } })) {
                    Button("好", role: .cancel) { taskError = nil }
                } message: { Text(taskError ?? "") }
            }
        }
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
    // №12 search filters / browse window / history switch state.
    @State private var searchSender: String?
    @State private var searchRangeIndex = 0 // 0 全部时间 1 今天 2 近7天 3 近30天
    @State private var searchKind: String?
    @State private var isBrowsing = false
    @State private var browseHighlightID: String?
    @State private var browseHasOlder = false
    @State private var browseHasNewer = false
    @State private var showingDatePicker = false
    @State private var jumpDate = Date()
    @State private var historyVisibleOverride: Bool?
    @Environment(\.scenePhase) private var scenePhase

    private var memberNames: [String] {
        let names = channel.participantDetails.map(\.name).filter { !$0.isEmpty }
        return names.isEmpty ? channel.participants : names
    }

    private var canManageHistory: Bool {
        channel.participantDetails.first { $0.isSelf }?.canMentionAll ?? false
    }

    private var historyVisibleNow: Bool {
        historyVisibleOverride ?? channel.historyVisible
    }

    var body: some View {
        VStack(spacing: 0) {
            searchBar
            filterBar
            toolsBar
            if isBrowsing { browseBar }
            ChannelContentView(
                messages: messages,
                connectionState: connectionState,
                connectionError: connectionError,
                isLoadingHistory: isLoadingHistory,
                composerText: $composerText,
                isSending: isSending,
                onSend: { Task { await send() } },
                onRefresh: { reload() },
                highlightID: browseHighlightID,
                scrollTargetID: browseHighlightID,
                onTapMessage: isShowingSearchResults ? { message in
                    Task { await jumpToMessage(message) }
                } : nil,
                memberNames: memberNames
            )
        }
        .navigationTitle(channel.displayName)
        .toolbar {
            Button("重新加载") { reload() }
        }
        .onAppear { startFeed() }
        .onDisappear { stopFeed() }
        #if compiler(>=6.0)
        .onChange(of: scenePhase) { _, phase in
            // №12: back in the foreground — retry the WebSocket now
            // instead of waiting out the polling fallback.
            if phase == .active { feed?.reconnectNow() }
        }
        #else
        .onChange(of: scenePhase) { phase in
            if phase == .active { feed?.reconnectNow() }
        }
        #endif
        .sheet(isPresented: $showingDatePicker) {
            NavigationStack {
                Form {
                    DatePicker("跳到哪一天", selection: $jumpDate, displayedComponents: .date)
                }
                .navigationTitle("跳到日期")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("取消") { showingDatePicker = false }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("跳转") {
                            showingDatePicker = false
                            Task { await jumpToDate(Calendar.current.startOfDay(for: jumpDate)) }
                        }
                    }
                }
            }
        }
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
                    .disabled(!searchCanRun)
            }
        }
        .padding(.horizontal)
        .padding(.vertical, 6)
    }

    /// Search runs with a keyword OR any filter on its own (the
    /// category index is a kind-only search).
    private var searchCanRun: Bool {
        !searchText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
            searchSender != nil || searchRangeIndex != 0 || searchKind != nil
    }

    private static let rangeLabels = ["全部时间", "今天", "近7天", "近30天"]
    private static let kindValues: [String?] = [nil, "image", "file", "link"]
    private static let kindNames = ["全部", "图片", "文件", "链接"]

    /// №12 filter row: member / time window / category menus. Picking
    /// a filter re-runs the search right away when it can run.
    private var filterBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                Menu {
                    Button("全部成员") { searchSender = nil; rerunSearchIfPossible() }
                    ForEach(memberNames, id: \.self) { name in
                        Button(name) { searchSender = name; rerunSearchIfPossible() }
                    }
                } label: {
                    Text("成员：\(searchSender ?? "全部")").font(.caption)
                }
                Menu {
                    ForEach(Self.rangeLabels.indices, id: \.self) { index in
                        Button(Self.rangeLabels[index]) {
                            searchRangeIndex = index
                            rerunSearchIfPossible()
                        }
                    }
                } label: {
                    Text("时间：\(Self.rangeLabels[searchRangeIndex])").font(.caption)
                }
                Menu {
                    ForEach(Self.kindValues.indices, id: \.self) { index in
                        Button(Self.kindNames[index]) {
                            searchKind = Self.kindValues[index]
                            rerunSearchIfPossible()
                        }
                    }
                } label: {
                    Text("分类：\(Self.kindNames[Self.kindValues.firstIndex { $0 == searchKind } ?? 0])")
                        .font(.caption)
                }
            }
            .padding(.horizontal)
        }
        .padding(.vertical, 2)
    }

    /// №12 tools row: date jump + the new-member history switch
    /// (group channels only; only owner/coordinator can flip it).
    private var toolsBar: some View {
        HStack(spacing: 12) {
            Button("跳到日期") { showingDatePicker = true }
                .font(.caption)
            Spacer()
            if !channel.isDM {
                if canManageHistory {
                    Button("新成员看历史：\(historyVisibleNow ? "开" : "关")") {
                        Task { await setHistoryVisible(!historyVisibleNow) }
                    }
                    .font(.caption)
                } else {
                    Text("新成员看历史：\(historyVisibleNow ? "开" : "关")")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .padding(.horizontal)
        .padding(.vertical, 2)
    }

    /// №12 browse bar: paging for the history window + back to live.
    private var browseBar: some View {
        HStack(spacing: 12) {
            if browseHasOlder {
                Button("加载更早") { Task { await browseLoadOlder() } }.font(.caption)
            }
            Spacer()
            Text("正在查看历史").font(.caption).foregroundStyle(.secondary)
            Button("回到最新") { exitBrowse() }.font(.caption).fontWeight(.semibold)
            Spacer()
            if browseHasNewer {
                Button("加载更新") { Task { await browseLoadNewer() } }.font(.caption)
            }
        }
        .padding(.horizontal)
        .padding(.vertical, 4)
        .background(Color.secondary.opacity(0.08))
    }

    private func rerunSearchIfPossible() {
        if searchCanRun { Task { await runSearch() } }
    }

    private func searchWindow() -> (from: Int?, to: Int?) {
        let now = Int(Date().timeIntervalSince1970)
        switch searchRangeIndex {
        case 1: return (Int(Calendar.current.startOfDay(for: Date()).timeIntervalSince1970), nil)
        case 2: return (now - 7 * 86400, nil)
        case 3: return (now - 30 * 86400, nil)
        default: return (nil, nil)
        }
    }

    private func runSearch() async {
        guard let transport = appState.makeChannelTransport() else { return }
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        guard searchCanRun else { return }
        stopFeed()
        isBrowsing = false
        browseHighlightID = nil
        isLoadingHistory = true
        do {
            let window = searchWindow()
            let results = try await transport.pollMessages(
                channelID: channel.id, limit: 100,
                query: query.isEmpty ? nil : query,
                sender: searchSender,
                fromTs: window.from, toTs: window.to,
                kind: searchKind)
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
        searchSender = nil
        searchRangeIndex = 0
        searchKind = nil
        isShowingSearchResults = false
        reload()
    }

    // MARK: - №12 jump / browse / history switch

    /// Jump to a search result's context: 25 messages either side,
    /// the target highlighted and scrolled to.
    private func jumpToMessage(_ target: ChannelMessage) async {
        guard let transport = appState.makeChannelTransport() else { return }
        isLoadingHistory = true
        do {
            let before = try await transport.pollMessages(
                channelID: channel.id, beforeID: target.id, limit: 25)
            let after = try await transport.pollMessages(
                channelID: channel.id, afterID: target.id, limit: 25)
            messages = before + [target] + after
            browseHighlightID = target.id
            browseHasOlder = before.count >= 25
            browseHasNewer = after.count >= 25
            isShowingSearchResults = false
            isBrowsing = true
            connectionError = nil
        } catch {
            connectionError = (error as? McpError)?.message ?? "跳到原文失败。"
        }
        isLoadingHistory = false
    }

    /// Jump to the first message on/after the picked day. The fetch
    /// happens before the feed stops, so an empty day changes nothing.
    private func jumpToDate(_ day: Date) async {
        guard let transport = appState.makeChannelTransport() else { return }
        isLoadingHistory = true
        do {
            let page = try await transport.pollMessages(
                channelID: channel.id, limit: 100,
                fromTs: Int(day.timeIntervalSince1970))
            if page.isEmpty {
                connectionError = "该日期之后暂无消息。"
            } else {
                stopFeed()
                messages = page
                browseHighlightID = page.first?.id
                browseHasOlder = true
                browseHasNewer = page.count >= 100
                isShowingSearchResults = false
                isBrowsing = true
                connectionError = nil
            }
        } catch {
            connectionError = (error as? McpError)?.message ?? "跳到日期失败。"
        }
        isLoadingHistory = false
    }

    private func browseLoadOlder() async {
        guard let transport = appState.makeChannelTransport(),
              let first = messages.first else { return }
        do {
            let older = try await transport.pollMessages(
                channelID: channel.id, beforeID: first.id, limit: 50)
            messages = older + messages
            browseHasOlder = older.count >= 50
        } catch {
            connectionError = (error as? McpError)?.message ?? "加载更早失败。"
        }
    }

    private func browseLoadNewer() async {
        guard let transport = appState.makeChannelTransport(),
              let last = messages.last else { return }
        do {
            let newer = try await transport.pollMessages(
                channelID: channel.id, afterID: last.id, limit: 50)
            messages = messages + newer
            browseHasNewer = newer.count >= 50
        } catch {
            connectionError = (error as? McpError)?.message ?? "加载更新失败。"
        }
    }

    private func exitBrowse() {
        isBrowsing = false
        browseHighlightID = nil
        reload()
    }

    private func setHistoryVisible(_ visible: Bool) async {
        guard let transport = appState.makeChannelTransport() else { return }
        do {
            _ = try await transport.setHistoryVisible(channelID: channel.id, visible: visible)
            historyVisibleOverride = visible
            connectionError = nil
        } catch {
            connectionError = (error as? McpError)?.message ?? "改历史开关失败。"
        }
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
