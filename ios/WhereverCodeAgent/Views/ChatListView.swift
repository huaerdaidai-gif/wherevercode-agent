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
            Section("其他对话") {
                Text("本地模型对话可从侧边栏「对话」进入，无需平台连接。")
                    .font(.caption).foregroundStyle(.secondary)
                ReservedFeatureRow(feature: .singleAgentChat)
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
                Text("本地模型对话可从侧边栏「对话」进入，无需平台连接。")
                    .font(.caption).foregroundStyle(.secondary)
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

// Shared native tokens: see docs/NATIVE_DUAL_UI.md. Platform connections
// never gate the local conversation store or the configured brain engine.
enum AgentUI {
    static let background = Color(uiColor: UIColor { traits in
        traits.userInterfaceStyle == .dark
            ? UIColor(red: 11/255, green: 14/255, blue: 29/255, alpha: 1)
            : UIColor(red: 250/255, green: 248/255, blue: 245/255, alpha: 1)
    })
}

/// Until native workspace tools are wired, advertise none and refuse all
/// execution. A model response must never be treated as an executed action.
private struct ChatUnavailableTools: ToolRegistry {
    var workspaceRoot: String? { nil }
    func toolsForCapabilities(_ capabilities: BrainCapabilities?, authorizedTools: Set<String>) -> [ToolDef] { [] }
    func gitHostToolsFor(project: Project?, location: ProjectLocation?) -> [ToolDef] { [] }
    func allToolNames() -> Set<String> { [] }
    func validateOnly(_ action: BrainAction, _ context: ToolContext) -> ToolOutcome? {
        ToolOutcome(ok: false, summary: "iOS 尚未接入工作区执行工具", errorCode: "not_implemented")
    }
    func execute(_ action: BrainAction, _ context: ToolContext, stopped: @escaping () -> Bool) async throws -> ToolOutcome {
        ToolOutcome(ok: false, summary: "iOS 尚未接入工作区执行工具", errorCode: "not_implemented")
    }
    func isReadOnlyExtensionTool(_ name: String) -> Bool { false }
}

/// UI state over the existing app-wide data and engine. No second database,
/// model registry, or credential store is created by the conversation view.
@MainActor
final class LocalChatModel: ObservableObject {
    @Published var draft = ""
    @Published var messages: [ConversationMessage] = []
    @Published var history: [Conversation] = []
    @Published var selectedBrain = ""
    @Published var error: String?
    @Published var streaming = ""
    @Published var isSending = false
    @Published var showHistory = false
    private(set) var conversationID: String?
    private unowned let appState: AppState
    let eventLog = InMemoryEventLog()
    private let data: LocalData
    private var currentTurn: Task<Void, Never>?

    init(appState: AppState, data: LocalData = .shared) {
        self.appState = appState
        self.data = data
        selectedBrain = appState.brainConfigStore.currentBrainId() ?? ""
        conversationID = data.chatPrefs.currentSessionId
        if let id = conversationID, let conversation = try? data.conversations.getConversation(id: id) {
            selectedBrain = conversation.brainId ?? selectedBrain
        }
        reload()
    }

    func reload() {
        do {
            history = try data.conversations.listConversations()
            messages = try conversationID.map { try data.conversations.listMessages(conversationId: $0) } ?? []
        } catch { self.error = error.localizedDescription }
    }

    func newConversation() {
        guard !isSending else { error = "请等待当前回复完成后新建对话"; return }
        conversationID = nil
        data.chatPrefs.currentSessionId = nil
        draft = ""; messages = []; streaming = ""; error = nil
    }

    func open(_ conversation: Conversation) {
        guard !isSending else { return }
        conversationID = conversation.id
        data.chatPrefs.currentSessionId = conversation.id
        selectedBrain = conversation.brainId ?? appState.brainConfigStore.currentBrainId() ?? ""
        error = nil; draft = ""; streaming = ""; showHistory = false
        reload()
    }

    func startSending() {
        guard currentTurn == nil, !isSending else { return }
        currentTurn = Task {
            await send()
            currentTurn = nil
        }
    }

    func stopGenerating() {
        // Cancel inference only; this is not a task-control receipt.
        currentTurn?.cancel()
    }

    func send() async {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !isSending else { return }
        guard !selectedBrain.isEmpty else { error = "请在模型与连接中配置并选择模型"; return }
        isSending = true; error = nil; streaming = ""
        defer { isSending = false; streaming = ""; reload() }
        do {
            if conversationID == nil {
                let conversation = try data.conversations.createConversation(brainId: selectedBrain, title: String(text.prefix(48)))
                conversationID = conversation.id
                data.chatPrefs.currentSessionId = conversation.id
            }
            guard let id = conversationID else { return }
            draft = ""
            let engine = appState.makeBrainEngine(conversations: data.conversations,
                tools: ChatUnavailableTools(), eventLog: eventLog, approvalGate: DenyApprovalGate())
            for try await event in engine.chat(conversationId: id, userText: text,
                brainIdOverride: selectedBrain, allowFailover: false) {
                try Task.checkCancellation()
                switch event {
                case .textDelta(let text): streaming += text
                case .error(let info): error = info.message
                default: break
                }
                reload()
            }
        } catch is CancellationError {
            self.error = "回复已停止"
        } catch {
            self.error = SecretRedactor.redact(error.localizedDescription)
            // Restore unsaved text only; persisted user messages are not duplicated.
            if messages.last?.content != text { draft = text }
        }
    }
}

struct LocalChatHomeView: View {
    @EnvironmentObject private var appState: AppState
    @ObservedObject var model: LocalChatModel
    @State private var query = ""
    @State private var greetingDismissed = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                ScrollViewReader { proxy in
                    ScrollView {
                        VStack(alignment: .leading, spacing: 16) {
                            if model.messages.isEmpty && !model.isSending && !greetingDismissed {
                                VStack(spacing: 12) {
                                    HStack {
                                        Spacer()
                                        Button("✕ 关闭问候") { greetingDismissed = true }
                                            .font(.system(size: 11)).foregroundStyle(.secondary)
                                    }
                                    Text("∞").font(.system(size: 34)).foregroundStyle(Color.purple)
                                        .padding(.top, 20)
                                    Text("今天想做什么？").font(.system(size: 28, weight: .semibold))
                                    Button("项目 · 选择或切换 ›") { appState.selectedTab = .projects }
                                        .font(.system(size: 12)).foregroundStyle(.secondary).frame(minHeight: 48)
                                    HStack(spacing: 8) {
                                        suggestion("检查项目并修复问题")
                                        suggestion("解释这段代码")
                                    }
                                    HStack { suggestion("拆成任务"); Spacer() }
                                }.padding(.vertical, 12)
                            }
                            ForEach(model.messages, id: \.id) { message in
                                VStack(alignment: .leading, spacing: 6) {
                                    Text(message.role == "user" ? "你" : message.source)
                                        .font(.caption).foregroundStyle(.secondary)
                                    Text(message.content).textSelection(.enabled)
                                }
                                .padding(14).frame(maxWidth: .infinity, alignment: .leading)
                                .background(message.role == "user" ? Color.accentColor.opacity(0.1) : Color(uiColor: .secondarySystemGroupedBackground))
                                .clipShape(RoundedRectangle(cornerRadius: 16))
                            }
                            if model.isSending {
                                Text(model.streaming.isEmpty ? "正在等待模型回复…" : model.streaming)
                                    .padding(14).accessibilityIdentifier("chat.streaming")
                            }
                            Color.clear.frame(height: 1).id("bottom")
                        }.padding(16)
                    }
                    .onChange(of: model.streaming) { _ in proxy.scrollTo("bottom", anchor: .bottom) }
                    .onChange(of: model.messages.count) { _ in proxy.scrollTo("bottom", anchor: .bottom) }
                }
                if let error = model.error {
                    Text(error).font(.caption).foregroundStyle(.red).padding(12)
                        .accessibilityIdentifier("chat.error")
                }
                VStack(spacing: 8) {
                    TextField("输入消息…", text: $model.draft, axis: .vertical)
                        .lineLimit(1...5).font(.system(size: 14)).padding(12)
                        .accessibilityIdentifier("chat.input")
                    HStack(spacing: 8) {
                        Menu {
                            Button("项目与文件") { appState.selectedTab = .projects }
                            Button("Agent") { appState.selectedTab = .agents }
                            Button("设备") { appState.selectedTab = .devices }
                            Button("工具与连接") { appState.goToSettings() }
                        } label: {
                            Image(systemName: "plus").frame(width: 36, height: 44)
                        }.accessibilityLabel("更多操作")
                        Picker("模型", selection: $model.selectedBrain) {
                            Text("选择模型").tag("")
                            ForEach(appState.brainConfigStore.listConfigs(), id: \.brainId) { config in
                                Text(config.label + (config.model.isEmpty ? "" : " · " + config.model)).tag(config.brainId)
                            }
                        }.disabled(model.isSending).accessibilityIdentifier("chat.model")
                        .lineLimit(1)
                        Spacer(minLength: 0)
                        if model.isSending {
                            Button { model.stopGenerating() } label: {
                                Image(systemName: "stop.fill").frame(width: 36, height: 36)
                            }.buttonStyle(.bordered).clipShape(Circle())
                                .accessibilityLabel("停止回复").accessibilityIdentifier("chat.stop")
                        } else {
                            Button { model.startSending() } label: {
                                Image(systemName: "arrow.up").frame(width: 36, height: 36)
                            }.buttonStyle(.borderedProminent).clipShape(Circle())
                                .disabled(model.draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                                .accessibilityLabel("发送").accessibilityIdentifier("chat.send")
                        }
                    }.padding(.horizontal, 8)
                }
                .padding(4)
                .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 20))
                .overlay(RoundedRectangle(cornerRadius: 20).stroke(Color.secondary.opacity(0.2)))
                .padding(.horizontal, 10).padding(.bottom, 6)

            }
            .background(AgentUI.background)
            .toolbar(.hidden, for: .navigationBar)
            .sheet(isPresented: $model.showHistory) {
                NavigationStack {
                    List(model.history.filter { query.isEmpty || $0.title.localizedCaseInsensitiveContains(query) }, id: \.id) { conversation in
                        Button(conversation.title.isEmpty ? "未命名对话" : conversation.title) { model.open(conversation) }
                            .disabled(model.isSending)
                    }
                    .overlay { if model.history.isEmpty { Text("暂无会话记录").foregroundStyle(.secondary) } }
                    .searchable(text: $query, prompt: "搜索会话")
                    .navigationTitle("会话")
                    .toolbar { Button("完成") { model.showHistory = false } }
                }
            }
            .onAppear { model.reload() }
        }
    }
    private func suggestion(_ title: String) -> some View {
        Button { model.draft = title + "：" } label: {
            Text(title).font(.system(size: 12)).frame(maxWidth: .infinity, minHeight: 48)
        }
        .buttonStyle(.bordered).tint(.secondary)
    }

}

struct LocalWorkbenchView: View {
    enum Section { case projects, agents, console }
    let section: Section
    @EnvironmentObject private var appState: AppState
    @State private var projects: [ProjectWithHomes] = []
    @State private var agents: [AgentRecord] = []
    @State private var loadError: String?
    var body: some View {
        NavigationStack {
            List {
                if let loadError { Text(loadError).foregroundStyle(.red) }
                switch section {
                case .projects:
                    if projects.isEmpty { Text("暂无本地项目").foregroundStyle(.secondary) }
                    ForEach(projects, id: \.project.projectId) { item in
                        DisclosureGroup(item.project.name) {
                            ForEach(item.homes, id: \.locationId) { home in
                                Text(home.workspacePath).textSelection(.enabled)
                            }
                            Text("iOS 文件浏览与编辑尚未接入，当前仅展示已保存的项目位置。")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                case .agents:
                    if agents.isEmpty { Text("暂无已登记 Agent").foregroundStyle(.secondary) }
                    ForEach(agents, id: \.agentId) { agent in
                        VStack(alignment: .leading) {
                            Text(agent.displayName)
                            Text(agent.lastTestSummary ?? "尚未验证连接").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    Text("外部 Agent 直接聊天与设备控制尚未接入；可使用团队频道的真实连接。")
                        .font(.caption).foregroundStyle(.secondary)
                case .console:
                    let events = appState.chat.eventLog.entries()
                    if events.isEmpty { Text("当前进程暂无模型事件").foregroundStyle(.secondary) }
                    ForEach(events, id: \.seq) { event in
                        VStack(alignment: .leading) {
                            Text(event.kind).font(.headline)
                            Text(event.detail).font(.caption).textSelection(.enabled)
                        }
                    }
                    Text("此处仅显示当前进程的模型事件。平台审批与交付结果请进入任务详情；未接入的控制不可执行。")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            .navigationTitle(section == .projects ? "项目与文件" : section == .agents ? "Agent" : "控制台")
            .task {
                do {
                    projects = try LocalData.shared.projects.listProjects()
                    agents = try LocalData.shared.agents.listAgents()
                } catch { loadError = error.localizedDescription }
            }
        }
    }
}
