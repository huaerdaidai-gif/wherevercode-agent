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

    var body: some Scene {
        WindowGroup {
            TabView(selection: $appState.selectedTab) {
                ChatListView()
                    .tabItem { Label("对话", systemImage: "bubble.left.and.bubble.right") }
                    .tag(AppTab.chat)
                TasksView()
                    .tabItem { Label("任务", systemImage: "checklist") }
                    .tag(AppTab.tasks)
                DevicesView()
                    .tabItem { Label("设备", systemImage: "iphone") }
                    .tag(AppTab.devices)
                SettingsView()
                    .tabItem { Label("设置", systemImage: "gearshape") }
                    .tag(AppTab.settings)
            }
            .environmentObject(appState)
        }
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

    private nonisolated let keychain = KeychainStore()

    init() {
        self.settings = SettingsStore.load()
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
                connectionStatus = .notConfigured("还没有填写频道 ID。")
            case .ok:
                break // unreachable: guarded above
            }
            return
        }
        guard hasToken else {
            connectionStatus = .notConfigured("还没有保存 Agent Token。")
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
            if let transport = makeChannelTransport(),
               let channels = try? await transport.listChannels() {
                summary += "，可见频道 \(channels.count) 个"
            }
            summary += "。"
            connectionStatus = .connected(summary)
        } catch {
            connectionStatus = .failed((error as? McpError)?.message ?? "连接失败。")
        }
    }
}
