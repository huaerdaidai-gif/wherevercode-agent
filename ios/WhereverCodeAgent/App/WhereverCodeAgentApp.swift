// WhereverCode Agent — iOS MVP
//
// App entry: a minimal chat-first shell in the Codex/Claude style —
// a plain conversation list as the home screen, with Tasks, Devices
// and Settings one tap away. No mock data anywhere: every list is
// either loaded from the platform or labelled as not implemented.

import SwiftUI

@main
struct WhereverCodeAgentApp: App {
    @StateObject private var appState = AppState()

    var body: some Scene {
        WindowGroup {
            TabView {
                ChatListView()
                    .tabItem { Label("对话", systemImage: "bubble.left.and.bubble.right") }
                TasksView()
                    .tabItem { Label("任务", systemImage: "checklist") }
                DevicesView()
                    .tabItem { Label("设备", systemImage: "iphone") }
                SettingsView()
                    .tabItem { Label("设置", systemImage: "gearshape") }
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

    private nonisolated let keychain = KeychainStore()

    init() {
        self.settings = SettingsStore.load()
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
}
