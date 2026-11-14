// WhereverCode Agent — iOS
//
// App entry: a chat-first shell in the Codex/Claude style — a plain
// conversation list as the home screen, with Tasks, Devices and
// Settings one tap away. The tab selection lives in AppState (a real
// binding, not per-tab state), so any screen can switch tabs —
// e.g. the "去设置" action on an unconfigured list selects the
// Settings TAB instead of pushing a second Settings screen onto the
// current tab's navigation stack (the phase-1 navigation bug).
// No mock data anywhere: every list is either loaded from the
// platform or labelled as not implemented / blocked.

import SwiftUI

/// The four top-level tabs. Selection is owned by AppState so the
/// selected tab and the visible page can never drift apart.
enum AppTab: Hashable {
    case chat
    case tasks
    case devices
    case settings
    case channels
    case projects
    case agents
    case console
}

/// Result of the last real platform connection check. Shown in
/// Settings after every save and on demand; a failure always carries
/// the server's / transport's real error text, never a generic one.
enum PlatformConnectionStatus: Equatable {
    /// No check has run yet in this session.
    case unknown
    /// The app cannot even attempt a connection; the string says
    /// exactly what is missing.
    case notConfigured(String)
    case checking
    /// Human-readable summary of what the platform answered.
    case connected(String)
    /// The server's or transport's real error text.
    case failed(String)
}

@main
struct WhereverCodeAgentApp: App {
    @StateObject private var appState = AppState()
    @State private var isDrawerOpen = false

    var body: some Scene {
        WindowGroup {
            ZStack(alignment: .topLeading) {
                VStack(spacing: 0) {
                    HStack(spacing: 8) {
                        Button { withAnimation { isDrawerOpen.toggle() } } label: {
                            Image(systemName: "line.3.horizontal").frame(width: 44, height: 44)
                        }
                        .accessibilityLabel("打开导航")
                        .accessibilityIdentifier("navigation.menu")
                        Text("∞").foregroundStyle(Color.purple).font(.title2.bold())
                        Text("WhereverCode").font(.system(size: 17, weight: .semibold))
                        Spacer()
                        Button { appState.selectedTab = .chat; appState.chat.showHistory = true } label: {
                            Image(systemName: "magnifyingglass").frame(width: 44, height: 44)
                        }.accessibilityLabel("搜索会话")
                        Button { appState.selectedTab = .chat; appState.chat.newConversation() } label: {
                            Image(systemName: "square.and.pencil").frame(width: 44, height: 44)
                        }.accessibilityLabel("新对话")
                    }
                    .padding(.horizontal, 8).frame(height: 60)
                    Group {
                        switch appState.selectedTab {
                        case .chat: LocalChatHomeView(model: appState.chat)
                        case .channels: ChatListView()
                        case .tasks: TasksView()
                        case .devices: DevicesView()
                        case .settings: SettingsView()
                        case .projects: LocalWorkbenchView(section: .projects)
                        case .agents: LocalWorkbenchView(section: .agents)
                        case .console: LocalWorkbenchView(section: .console)
                        }
                    }
                }
                .background(AgentUI.background)
                .environmentObject(appState)

                if isDrawerOpen {
                    Color.black.opacity(0.28)
                        .ignoresSafeArea()
                        .onTapGesture { withAnimation { isDrawerOpen = false } }
                    HStack(spacing: 0) {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("WhereverCode")
                                .font(.title2.bold())
                                .padding(.bottom, 12)
                            drawerItem("对话", icon: "bubble.left", tab: .chat)
                            drawerItem("项目与文件", icon: "folder", tab: .projects)
                            drawerItem("Agent", icon: "person.2", tab: .agents)
                            drawerItem("团队频道", icon: "bubble.left.and.bubble.right", tab: .channels)
                            drawerItem("控制台", icon: "terminal", tab: .console)
                            drawerItem("任务", icon: "checklist", tab: .tasks)
                            drawerItem("设备", icon: "iphone", tab: .devices)
                            Spacer()
                            drawerItem("设置", icon: "gearshape", tab: .settings)
                        }
                        .padding(20)
                        .frame(width: 280)
                        .frame(maxHeight: .infinity, alignment: .topLeading)
                        .background(.regularMaterial)
                        Spacer(minLength: 0)
                    }
                    .transition(.move(edge: .leading))
                }
            }
            .task {
                // Launch check: the connection status must be
                // truthful from the first second — with a complete
                // setup it verifies for real, with an incomplete one
                // it names what is missing. It never sits on
                // "尚未检查" just because nobody tapped anything.
                await appState.refreshConnectionStatus()
            }
            #if compiler(>=6.0)
            .onChange(of: appState.selectedTab) { _, tab in
                // Entering Settings re-checks automatically when
                // there is anything to check with.
                if tab == .settings {
                    Task { await appState.refreshConnectionStatus() }
                }
            }
            #else
            .onChange(of: appState.selectedTab) { tab in
                if tab == .settings {
                    Task { await appState.refreshConnectionStatus() }
                }
            }
            #endif
        }
    }
    private func drawerItem(_ title: String, icon: String, tab: AppTab) -> some View {
        Button {
            appState.selectedTab = tab
            withAnimation(.easeInOut(duration: 0.2)) { isDrawerOpen = false }
        } label: {
            Label(title, systemImage: icon)
                .font(.body)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 12)
                .foregroundStyle(.primary)
        }
        .accessibilityIdentifier("drawer_\(tab)")
    }
}

/// Shared app state: settings + token access + client factories.
/// The token itself is never stored here — every read goes to the
/// Keychain at call time (see `tokenProvider`).
@MainActor
final class AppState: ObservableObject {
    @Published var settings: AppSettings {
        didSet { SettingsStore.save(settings) }
    }

    /// Currently selected tab. Every "go to tab X" action in the app
    /// goes through here, so the tab bar highlight and the visible
    /// page are the same fact, not two states that can disagree.
    @Published var selectedTab: AppTab = .chat

    /// Outcome of the most recent connection check (see
    /// `refreshConnectionStatus()`).
    @Published private(set) var connectionStatus: PlatformConnectionStatus = .unknown

    /// Channels the platform returned for the current credential
    /// (from `agent_channel_list`). Drives the Settings channel
    /// picker: channels are chosen by tapping, and a single visible
    /// channel is selected automatically (see `ChannelSelection`).
    @Published private(set) var availableChannels: [ChannelInfo] = []
    /// Real error text from the last channel-list load, if it failed.
    @Published private(set) var channelListError: String?
    @Published private(set) var isLoadingChannels = false

    private nonisolated let keychain = KeychainStore()

    /// The local brain stack: the config store over the shared
    /// local database's kv table, the Keychain-backed key store,
    /// and the adapter registry BrainBootstrap assembles from
    /// them. Built once at launch — the first launch seeds the
    /// built-in brains (a user-deleted brain stays deleted, its
    /// tombstone persists) — and rebuilt after every change made
    /// in the brain settings, so the settings UI and the engine
    /// always read the same stack.
    lazy var chat = LocalChatModel(appState: self)
    let brainConfigStore: BrainConfigStore
    let brainKeyStore: BrainKeyStore
    @Published private(set) var brainRegistry: BrainAdapterRegistry
    let brainSettingsModel: BrainSettingsModel

    init() {
        self.settings = SettingsStore.load()
        let configStore = KeyValueBrainConfigStore(kv: LocalData.shared.kv)
        let keyStore = BrainKeyStore()
        self.brainConfigStore = configStore
        self.brainKeyStore = keyStore
        self.brainRegistry = BrainBootstrap.buildRegistry(
            configStore: configStore, keyStore: keyStore)
        let model = BrainSettingsModel(configStore: configStore, keyStore: keyStore)
        self.brainSettingsModel = model
        model.registryProvider = { [weak self] in
            self?.brainRegistry ?? BrainAdapterRegistry()
        }
        model.onChange = { [weak self] in self?.rebuildBrainStack() }
    }

    /// Rebuild the adapter registry after a brain-settings
    /// change (config saved/deleted/restored, current brain
    /// switched). Cheap: adapters are thin holders over the
    /// shared stores.
    func rebuildBrainStack() {
        brainRegistry = BrainBootstrap.buildRegistry(
            configStore: brainConfigStore, keyStore: brainKeyStore)
    }

    /// The brain engine over the live stack, for the brain chat.
    /// The tool registry is empty until the tool group lands —
    /// the engine reports tool calls as unavailable rather than
    /// faking execution.
    func makeBrainEngine(
        conversations: ConversationStore,
        tools: ToolRegistry,
        eventLog: EventLog,
        approvalGate: ApprovalGate
    ) -> BrainEngine {
        BrainBootstrap.buildEngine(
            configStore: brainConfigStore, registry: brainRegistry,
            conversations: conversations, tools: tools,
            eventLog: eventLog, approvalGate: approvalGate)
    }

    /// Switch to the Settings tab. This is the ONLY way in-app
    /// "去设置" actions navigate — no view pushes a Settings screen
    /// onto its own navigation stack.
    func goToSettings() {
        selectedTab = .settings
    }

    /// The token, read fresh from the Keychain each time. Returns nil
    /// when no token has been saved. Nonisolated so background
    /// networking code can call it without hopping to the main actor.
    nonisolated var tokenProvider: () -> String? {
        { [keychain] in try? keychain.loadToken() }
    }

    var hasToken: Bool { keychain.hasToken }

    func saveToken(_ token: String) throws {
        try keychain.saveToken(token)
        objectWillChange.send()
    }

    func deleteToken() throws {
        try keychain.deleteToken()
        objectWillChange.send()
    }

    /// True when the app has everything needed to talk to the platform.
    var isConfigured: Bool {
        settings.validation == .ok && hasToken
    }

    /// MCP client for the configured endpoint, or nil when the
    /// platform address is missing/invalid.
    func makeMcpClient() -> McpClient? {
        guard let endpoint = settings.mcpEndpoint else { return nil }
        return McpClient(endpoint: endpoint, tokenProvider: tokenProvider)
    }

    // MARK: - Channels

    /// Load the visible channel list from the platform and apply
    /// the selection rule (`ChannelSelection`): the current
    /// selection survives while it is still listed; a single
    /// visible channel is selected automatically. Failures land in
    /// `channelListError` with the real error text.
    func loadChannels() async {
        guard let transport = makeChannelTransport() else {
            channelListError = "平台地址无效，读不到频道列表。"
            return
        }
        isLoadingChannels = true
        defer { isLoadingChannels = false }
        do {
            let channels = try await transport.listChannels()
            availableChannels = channels
            channelListError = nil
            let resolved = ChannelSelection.resolvedChannelID(
                channels: channels, current: settings.channelID
            )
            if resolved != settings.channelID {
                settings.channelID = resolved
            }
        } catch {
            channelListError = (error as? McpError)?.message ?? "加载频道失败。"
        }
    }

    /// Select a channel by tapping it (Settings picker or the
    /// conversation list). The id is persisted via `settings`.
    func selectChannel(_ channelID: String) {
        settings.channelID = channelID
    }

    // MARK: - Token generation

    /// "生成并保存 Token": issue a token through the platform's
    /// `issue_agent_token` tool (the same call Android makes) and
    /// store it in the Keychain as this app's platform credential.
    /// The token value never reaches the view layer — it exists
    /// only inside the returned result, and only the metadata is
    /// meant to be shown. On success the connection is re-checked
    /// (and the channel list reloaded) immediately.
    func generateAndSaveToken(agentId: String, name: String) async -> IssueTokenResult {
        guard let client = makeMcpClient() else {
            return .failed("平台地址无效：请先在上方填写并保存一个 https 平台地址。")
        }
        let issuer = PlatformTokenIssuer(callTool: { toolName, arguments in
            try await client.callTool(name: toolName, arguments: arguments)
        })
        let result = await issuer.issueToken(agentId: agentId, name: name)
        guard case .issued(let token, _, _, _) = result else { return result }
        do {
            try saveToken(token)
        } catch {
            return .failed("Token 已签发，但存入 Keychain 失败：请再点一次「生成并保存 Token」重新签发。")
        }
        await refreshConnectionStatus()
        return result
    }

    func makeChannelTransport() -> ChannelTransport? {
        guard let client = makeMcpClient() else { return nil }
        return ChannelTransport(client: client)
    }

    func makeTaskService() -> TaskService? {
        guard let client = makeMcpClient() else { return nil }
        return TaskService(client: client)
    }

    // MARK: - Connection check

    /// Run a real connection check against the platform right now:
    /// `tools/list` plus the visible channel count. The result is
    /// published in `connectionStatus` with the real error text on
    /// failure. Called after every settings/token save and on demand
    /// from the Settings screen.
    func refreshConnectionStatus() async {
        guard settings.validation == .ok else {
            switch settings.validation {
            case .missingBaseURL:
                connectionStatus = .notConfigured("还没有填写平台地址。")
            case .invalidBaseURL(let detail):
                connectionStatus = .notConfigured(detail)
            case .missingChannelID:
                connectionStatus = .notConfigured("还没有选择频道。")
            case .ok:
                break // unreachable: guarded above
            }
            return
        }
        guard hasToken else {
            connectionStatus = .notConfigured("还没有平台凭证：在设置的「平台连接」里填入平台凭证，或在「平台令牌」里生成一个。")
            return
        }
        guard let client = makeMcpClient() else {
            connectionStatus = .notConfigured("平台地址无效：请输入 https:// 开头的完整地址。")
            return
        }
        connectionStatus = .checking
        do {
            let tools = try await client.listToolNames()
            var summary = "已连接：平台返回 \(tools.count) 个工具"
            await loadChannels()
            if channelListError == nil {
                summary += "，可见频道 \(availableChannels.count) 个"
            }
            summary += "。"
            connectionStatus = .connected(summary)
        } catch {
            connectionStatus = .failed((error as? McpError)?.message ?? "连接失败。")
        }
    }
}
