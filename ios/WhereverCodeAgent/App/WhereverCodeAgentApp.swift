// WhereverCode Agent — iOS v1.5 Build 6 Flagship Assembly
//
// Root shell: Claude minimal home + 4-parent drawer (对话 / 工作台 / 控制台 / 设置),
// zero bottom TabBar, full PR #13 AppState capabilities preserved.

import SwiftUI

public enum AppTab: String, CaseIterable, Hashable {
    case chat
    case tasks
    case devices
    case settings
    case channels
    case projects
    case agents
    case console
}

public enum PlatformConnectionStatus: Equatable {
    case unknown
    case notConfigured(String)
    case checking
    case connected(String)
    case failed(String)

    public var displayText: String {
        switch self {
        case .unknown: return "尚未检查"
        case .notConfigured(let msg): return msg
        case .checking: return "正在检查连接…"
        case .connected(let msg): return msg
        case .failed(let msg): return "连接失败：\(msg)"
        }
    }
}

@main
struct WhereverCodeAgentApp: App {
    @StateObject private var appState = AppState()

    var body: some Scene {
        WindowGroup {
            FlagshipShellView(appState: appState, chatModel: appState.chat)
                .environmentObject(appState)
                .task {
                    await appState.refreshConnectionStatus()
                }
        }
    }
}

@MainActor
public final class AppState: ObservableObject {
    @Published public var settings: AppSettings {
        didSet {
            if settings.mcpEndpoint != oldValue.mcpEndpoint { resetChannelIdentity() }
            SettingsStore.save(settings)
        }
    }

    @Published public var selectedTab: AppTab = .chat
    @Published public private(set) var connectionStatus: PlatformConnectionStatus = .unknown
    @Published public private(set) var availableChannels: [ChannelInfo] = []
    @Published public private(set) var channelListError: String?
    @Published public private(set) var isLoadingChannels = false

    public var onSelectRoute: ((String) -> Void)?

    public nonisolated let keychain: KeychainStore
    public let data: LocalData
    public lazy var chat = LocalChatModel(appState: self, data: data)
    public let brainConfigStore: BrainConfigStore
    public let brainKeyStore: BrainKeyStore
    @Published public private(set) var brainRegistry: BrainAdapterRegistry
    public let brainSettingsModel: BrainSettingsModel
    public let modelConfigStore: ModelConfigStore
    public let modelSettingsModel: ModelSettingsModel
    public let compositeBrainConfigStore: CompositeBrainConfigStore

    public init(
        settings: AppSettings = SettingsStore.load(),
        keychain: KeychainStore = KeychainStore(),
        data: LocalData = .shared
    ) {
        self.settings = settings
        self.keychain = keychain
        self.data = data
        let configStore = KeyValueBrainConfigStore(kv: data.kv)
        let keyStore = BrainKeyStore()
        self.brainConfigStore = configStore
        self.brainKeyStore = keyStore
        let brainModel = BrainSettingsModel(configStore: configStore, keyStore: keyStore)
        self.brainSettingsModel = brainModel

        let modelStore = KeyValueModelConfigStore(kv: data.kv)
        self.modelConfigStore = modelStore
        let mModel = ModelSettingsModel(
            configStore: modelStore,
            keyStore: keyStore,
            routingStore: IndependentModelRoutingStore(),
            customProviderStore: IndependentCustomFreeProviderStore(),
            brainConfigStore: configStore,
            agentRegistryStore: data.agents
        )
        self.modelSettingsModel = mModel

        // 注入 AgentRegistryStore 引用计数依赖（保证删 Agent 时三处对称释放密钥）
        data.agents.keyStore = keyStore
        data.agents.brainConfigStore = configStore
        data.agents.modelConfigStore = modelStore

        // 启动迁移：按 ID 幂等补齐 + 一次性状态迁移
        ModelMigrationManager.runStartupMigration(
            kv: data.kv,
            brainStore: configStore,
            modelStore: modelStore
        )
        // 订阅授权目录补齐：与安卓同为 4 项（ChatGPT + Qwen/Kimi/Grok 会员）
        ModelMigrationManager.seedSubscriptionDirectory(modelStore: modelStore)
        mModel.reload()

        let composite = CompositeBrainConfigStore(modelStore: modelStore, brainStore: configStore)
        self.compositeBrainConfigStore = composite

        self.brainRegistry = BrainBootstrap.buildRegistry(
            configStore: composite, keyStore: keyStore)

        brainModel.registryProvider = { [weak self] in
            self?.brainRegistry ?? BrainAdapterRegistry()
        }
        brainModel.onChange = { [weak self] in self?.rebuildBrainStack() }

        mModel.registryProvider = { [weak self] in
            self?.brainRegistry ?? BrainAdapterRegistry()
        }
        mModel.onChange = { [weak self] in self?.rebuildBrainStack() }

        repairCurrentBrainReference()
    }

    public func rebuildBrainStack() {
        brainRegistry = BrainBootstrap.buildRegistry(
            configStore: compositeBrainConfigStore, keyStore: brainKeyStore)
        repairCurrentBrainReference()
    }

    /// P0 device fix: a stale current-brain reference (e.g. the
    /// seeded "siwc-chatgpt" membership brain, whose adapter is not
    /// registered on iOS yet, or a deleted brain) made every send
    /// fail with "找不到大脑配置 siwc-chatgpt". Clear the dangling
    /// reference so the UI honestly shows "未配置大脑" and lets the
    /// user pick a brain that can actually run.
    private func repairCurrentBrainReference() {
        guard let current = compositeBrainConfigStore.currentBrainId() else { return }
        let config = brainRegistry.configFor(current) ?? compositeBrainConfigStore.getConfig(brainId: current)
        if config == nil || brainRegistry.get(config!.adapterId) == nil {
            if modelConfigStore.currentModelId() == current {
                modelConfigStore.setCurrentModelId(nil)
                modelSettingsModel.reload()
            }
            if brainConfigStore.currentBrainId() == current {
                brainConfigStore.setCurrentBrainId(nil)
                brainSettingsModel.reload()
            }
        }
    }

    public func makeBrainEngine(
        conversations: ConversationStore,
        tools: ToolRegistry,
        eventLog: EventLog,
        approvalGate: ApprovalGate
    ) -> BrainEngine {
        BrainBootstrap.buildEngine(
            configStore: compositeBrainConfigStore, registry: brainRegistry,
            conversations: conversations, tools: tools,
            eventLog: eventLog, approvalGate: approvalGate,
            memory: LocalData.shared.memoryStore,
            skillStore: LocalData.shared.skillStore)
    }

    public func goToSettings() {
        selectedTab = .settings
        onSelectRoute?("settings-root")
    }

    public nonisolated var tokenProvider: () -> String? {
        { [keychain] in try? keychain.loadToken() }
    }

    public var hasToken: Bool { keychain.hasToken }

    @Published private(set) var channelSenderID: String?
    private var channelPostIDs: Set<String> = []

    private func resetChannelIdentity() {
        channelSenderID = nil
        channelPostIDs.removeAll()
        availableChannels = []
    }

    func trackChannelPost(_ id: String) {
        guard !id.isEmpty, channelSenderID == nil else { return }
        channelPostIDs.insert(id)
    }

    func learnChannelSender(messageID: String, senderID: String) {
        guard channelPostIDs.contains(messageID), !senderID.isEmpty else { return }
        channelSenderID = senderID
        channelPostIDs.removeAll()
    }

    public func saveToken(_ token: String) throws {
        try keychain.saveToken(token)
        resetChannelIdentity()
        objectWillChange.send()
    }

    public func deleteToken() throws {
        try keychain.deleteToken()
        resetChannelIdentity()
        objectWillChange.send()
    }

    public var isConfigured: Bool {
        settings.validation == .ok && hasToken
    }

    public func makeMcpClient() -> McpClient? {
        guard let endpoint = settings.mcpEndpoint else { return nil }
        return McpClient(endpoint: endpoint, tokenProvider: tokenProvider)
    }

    public func loadChannels() async {
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

    public func selectChannel(_ channelID: String) {
        settings.channelID = channelID
    }

    public func generateAndSaveToken(agentId: String, name: String) async -> IssueTokenResult {
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

    public func makeChannelTransport() -> ChannelTransport? {
        guard let client = makeMcpClient() else { return nil }
        return ChannelTransport(client: client)
    }

    public func makeTaskService() -> TaskService? {
        guard let client = makeMcpClient() else { return nil }
        return TaskService(client: client)
    }

    public func refreshConnectionStatus() async {
        guard settings.validation == .ok else {
            switch settings.validation {
            case .missingBaseURL:
                connectionStatus = .notConfigured("还没有填写平台地址。")
            case .invalidBaseURL(let detail):
                connectionStatus = .notConfigured(detail)
            case .missingChannelID:
                connectionStatus = .notConfigured("还没有选择频道。")
            case .ok:
                break
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
