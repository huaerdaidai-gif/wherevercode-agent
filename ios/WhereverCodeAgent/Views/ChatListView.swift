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
            Section {
                VStack(alignment: .leading, spacing: 12) {
                    Text("今天想做什么？")
                        .font(.system(size: 29, weight: .semibold, design: .rounded))
                        .foregroundStyle(.primary)
                    Text("从对话开始，或打开侧边栏进入任务与设备。")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 24)
                .listRowBackground(Color.clear)
            }
            if !platformConnected {
                Section {
                    HStack(spacing: 12) {
                        Image(systemName: "network.slash")
                            .foregroundStyle(.secondary)
                        VStack(alignment: .leading, spacing: 3) {
                            Text("平台尚未连接").font(.subheadline.weight(.medium))
                            Text("团队频道与远程任务需要连接；不影响其他本地功能。")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer(minLength: 4)
                        Button("连接") { onGoSettings() }
                            .font(.subheadline.weight(.medium))
                    }
                    .padding(.vertical, 5)
                }
            }
            if platformConnected, let loadError {
                Section {
                    HStack {
                        Text(loadError)
                            .font(.caption)
                            .foregroundStyle(.red)
                        Spacer()
                        Button("重试") { onRetry() }
                    }
                }
            }
            Section("团队频道") {
                if !platformConnected {
                    Text("连接平台后在这里查看和进入频道")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                } else if channels.isEmpty {
                    Text(loadError == nil
                         ? (isLoading ? "正在加载频道…" : "暂无可见频道")
                         : "频道加载失败")
                        .font(.subheadline)
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
            Section("本地模型对话") {
                NavigationLink {
                    BrainConversationView()
                } label: {
                    Label("新建对话", systemImage: "square.and.pencil")
                }
                Text("使用设置中已配置的模型；不需要连接 WhereverCode 平台")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
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
        // Local snapshot first (data layer): the last-known channel
        // list renders immediately and survives an unreachable
        // platform; the fetch below stays the source of truth.
        if channels.isEmpty {
            channels = ChannelListCache().load()
        }
        isLoading = true
        defer { isLoading = false }
        do {
            channels = try await transport.listChannels()
            ChannelListCache().save(channels)
            errorMessage = nil
        } catch {
            errorMessage = (error as? McpError)?.message ?? "加载频道失败。"
        }
    }
}


// A deliberately tool-free registry: model conversation works without platform
// access; tool requests are refused instead of silently executing writes.
private struct ChatOnlyToolRegistry: ToolRegistry {
    let workspaceRoot: String? = nil
    func toolsForCapabilities(_ capabilities: BrainCapabilities?, authorizedTools: Set<String>) -> [ToolDef] { [] }
    func gitHostToolsFor(project: Project?, location: ProjectLocation?) -> [ToolDef] { [] }
    func allToolNames() -> Set<String> { [] }
    func validateOnly(_ action: BrainAction, _ context: ToolContext) -> ToolOutcome? {
        ToolOutcome(ok: false, summary: "当前对话未授权执行工具", errorCode: "tool_unavailable")
    }
    func execute(_ action: BrainAction, _ context: ToolContext, stopped: @escaping () -> Bool) async throws -> ToolOutcome {
        ToolOutcome(ok: false, summary: "当前对话未授权执行工具", errorCode: "tool_unavailable")
    }
    func isReadOnlyExtensionTool(_ name: String) -> Bool { false }
}

struct BrainConversationView: View {
    @EnvironmentObject private var appState: AppState
    @State private var conversationId = UUID().uuidString
    @State private var draft = ""
    @State private var messages: [ConversationMessage] = []
    @State private var isSending = false
    @State private var streamedText = ""
    @State private var errorText: String?
    @State private var currentTurn: Task<Void, Never>?

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 16) {
                    ForEach(messages, id: \.id) { message in
                        if message.role == "user" || message.role == "assistant" {
                            VStack(alignment: message.role == "user" ? .trailing : .leading, spacing: 5) {
                                Text(message.role == "user" ? "你" : message.source)
                                    .font(.caption).foregroundStyle(.secondary)
                                Text(message.content)
                                    .frame(maxWidth: .infinity, alignment: message.role == "user" ? .trailing : .leading)
                                    .textSelection(.enabled)
                            }
                        }
                    }
                    if !streamedText.isEmpty {
                        Text(streamedText)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .textSelection(.enabled)
                    }
                    if let errorText {
                        Text(errorText).foregroundStyle(.red).font(.caption)
                    }
                }
                .padding(16)
            }
            Divider()
            HStack(alignment: .bottom, spacing: 12) {
                TextField("发送消息", text: $draft, axis: .vertical)
                    .lineLimit(1...5)
                    .textFieldStyle(.roundedBorder)
                    .accessibilityIdentifier("brain_chat_input")
                if isSending {
                    Button("停止", systemImage: "stop.fill") {
                        currentTurn?.cancel()
                        currentTurn = nil
                        isSending = false
                    }
                    .labelStyle(.iconOnly)
                    .accessibilityIdentifier("brain_chat_stop")
                } else {
                    Button("发送", systemImage: "arrow.up.circle.fill") {
                        send()
                    }
                    .labelStyle(.iconOnly)
                    .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    .accessibilityIdentifier("brain_chat_send")
                }
            }
            .padding(12)
        }
        .navigationTitle("模型对话")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { reload() }
        .onDisappear { currentTurn?.cancel() }
    }

    private func reload() {
        messages = (try? LocalData.shared.conversations.listMessages(conversationId: conversationId)) ?? []
    }

    private func send() {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !isSending else { return }
        draft = ""
        streamedText = ""
        errorText = nil
        isSending = true
        let id = conversationId
        let engine = appState.makeBrainEngine(
            conversations: LocalData.shared.conversations,
            tools: ChatOnlyToolRegistry(),
            eventLog: InMemoryEventLog(),
            approvalGate: DenyApprovalGate()
        )
        currentTurn = Task {
            do {
                for try await event in engine.chat(conversationId: id, userText: text) {
                    if Task.isCancelled { break }
                    switch event {
                    case .textDelta(let delta): streamedText += delta
                    case .error(let info): errorText = String(describing: info)
                    default: break
                    }
                }
            } catch {
                if !Task.isCancelled { errorText = error.localizedDescription }
            }
            reload()
            streamedText = ""
            isSending = false
            currentTurn = nil
        }
    }
}
