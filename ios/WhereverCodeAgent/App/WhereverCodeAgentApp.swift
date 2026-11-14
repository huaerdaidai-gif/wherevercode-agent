import SwiftUI
import Combine

public enum AppTab: String, CaseIterable, Hashable, Sendable {
    case chat
    case tasks
    case devices
    case settings
    case channels
    case projects
    case agents
    case console
}

public enum PlatformConnectionStatus: Equatable, Sendable {
    case unknown
    case checking
    case notConfigured(String)
    case connected(String)
    case failed(String)

    public var displayText: String {
        switch self {
        case .unknown:
            return "尚未检查。保存地址与凭证后会自动检查。"
        case .checking:
            return "正在连接平台…"
        case .notConfigured(let msg):
            return msg
        case .connected(let detail):
            return "已连接：\(detail)"
        case .failed(let err):
            return "连接平台失败：\(err)"
        }
    }
}

@MainActor
public final class AppState: ObservableObject {
    @Published public var settings: AppSettings
    @Published public var selectedTab: AppTab = .chat
    @Published public var connectionStatus: PlatformConnectionStatus = .unknown
    @Published public var availableChannels: [ChannelInfo] = []
    @Published public var channelListError: String? = nil
    @Published public var isLoadingChannels: Bool = false

    public let keychain: KeychainStore
    public let brainConfigStore: BrainConfigStore
    public let brainKeyStore: BrainKeyStore
    @Published public private(set) var brainRegistry: BrainAdapterRegistry
    public let brainSettingsModel: BrainSettingsModel
    public lazy var chat: LocalChatModel = LocalChatModel(appState: self, data: .shared)

    public init(
        settings: AppSettings = .load(),
        keychain: KeychainStore = KeychainStore(),
        data: LocalData = .shared
    ) {
        self.settings = settings
        self.keychain = keychain
        let cfgStore = KeyValueBrainConfigStore(kv: data.kv)
        let kStore = BrainKeyStore()
        self.brainConfigStore = cfgStore
        self.brainKeyStore = kStore
        self.brainRegistry = BrainBootstrap.buildRegistry(configStore: cfgStore, keyStore: kStore)
        let model = BrainSettingsModel(configStore: cfgStore, keyStore: kStore)
        self.brainSettingsModel = model
        self.availableChannels = ChannelListCache(kv: data.kv).load()

        model.onChange = { [weak self] in
            guard let self else { return }
            self.brainRegistry = BrainBootstrap.buildRegistry(configStore: cfgStore, keyStore: kStore)
        }
    }

    public var tokenProvider: @Sendable () -> String? {
        let kc = keychain
        return { try? kc.loadToken() }
    }

    public var isConfigured: Bool {
        guard settings.mcpEndpoint != nil,
              let token = try? keychain.loadToken(),
              !token.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return false
        }
        return true
    }

    public func saveToken(_ token: String) throws {
        try keychain.saveToken(token)
    }

    public func deleteToken() throws {
        try keychain.deleteToken()
        connectionStatus = .notConfigured("还没有平台凭证：在设置的「平台连接」里填入平台凭证，或在「平台令牌」里生成一个。")
    }

    public func selectChannel(_ id: String) {
        settings.channelID = id
        settings.save()
    }

    public func goToSettings() {
        selectedTab = .settings
    }

    public func makeMcpClient() -> McpClient? {
        guard let endpoint = settings.mcpEndpoint, isConfigured else { return nil }
        return McpClient(endpoint: endpoint, tokenProvider: tokenProvider)
    }

    public func makeBrainEngine(
        conversations: ConversationStore,
        tools: ToolRegistry,
        eventLog: EventLog,
        approvalGate: ApprovalGate
    ) -> BrainEngine {
        BrainEngine(
            adapters: brainRegistry,
            conversations: conversations,
            contextManager: LocalData.shared.contextManager,
            memory: LocalData.shared.memoryStore,
            skillStore: LocalData.shared.skillStore,
            tools: tools,
            eventLog: eventLog,
            approvalGate: approvalGate
        )
    }

    public func loadChannels() async {
        guard let endpoint = settings.mcpEndpoint else {
            connectionStatus = .notConfigured("平台地址无效：请输入 https:// 开头的完整地址。")
            return
        }
        guard isConfigured else {
            connectionStatus = .notConfigured("还没有平台凭证：在设置的「平台连接」里填入平台凭证，或在「平台令牌」里生成一个。")
            return
        }
        isLoadingChannels = true
        connectionStatus = .checking
        defer { isLoadingChannels = false }

        let client = McpClient(endpoint: endpoint, tokenProvider: tokenProvider)
        let transport = ChannelTransport(client: client)
        do {
            let channels = try await transport.listChannels()
            availableChannels = channels
            ChannelListCache(kv: LocalData.shared.kv).save(channels)
            channelListError = nil
            if channels.count == 1, let only = channels.first {
                selectChannel(only.id)
            }
            connectionStatus = .connected("可见频道 \(channels.count) 个")
        } catch {
            channelListError = error.localizedDescription
            connectionStatus = .failed(error.localizedDescription)
        }
    }
}

@main
public struct WhereverCodeAgentApp: App {
    @StateObject private var appState = AppState()

    public init() {}

    public var body: some Scene {
        WindowGroup {
            FlagshipShellView(appState: appState, chatModel: appState.chat)
        }
    }
}
