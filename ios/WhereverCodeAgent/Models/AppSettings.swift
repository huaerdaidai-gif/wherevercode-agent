// WhereverCode Agent — iOS
//
// App settings: the platform address and the selected channel id.
// The platform address is PREFILLED with the platform's public MCP
// endpoint (`defaultPlatformBaseURLString`) so a fresh install does
// not make the user type a long URL on a phone keyboard; the value
// is a public address, not a credential, and the user can edit it at
// any time. Once the user has saved their own non-empty address it
// is kept verbatim — the prefill never overwrites it (see
// `SettingsStore.load`). The channel is chosen by TAPPING a channel
// in the Settings picker or the conversation list; the id is stored
// here, but the main path never asks the user to type it. The token
// is NOT stored here; it lives in the Keychain (see KeychainStore).
// Settings are plain, non-secret configuration and may live in
// UserDefaults.

import Foundation

/// User-editable connection settings (no secrets).
public struct AppSettings: Equatable {
    /// Platform address. Accepts either the full MCP endpoint URL
    /// (`https://host/api/open/v1/mcp`) or the bare base
    /// (`https://host`): validation strips any path and the client
    /// appends the API paths itself, so the two forms are identical
    /// in effect. Prefilled with `defaultPlatformBaseURLString`
    /// until the user saves their own.
    public var platformBaseURLString: String
    /// The selected channel. Stored as an id, chosen by tapping —
    /// see `ChannelSelection`.
    public var channelID: String

    /// The platform's public MCP endpoint, prefilled as the default
    /// platform address. This is the same endpoint every client of
    /// this platform uses (Android composes it as
    /// `https://<host>/api/open/v1/mcp` from its host field); it is a
    /// well-known public address, not a private address and not a
    /// credential.
    public static let defaultPlatformBaseURLString = "https://v5.cococolo.xyz/api/open/v1/mcp"

    /// The team channel this app opens by default. This is a channel
    /// identifier, not a server address and not a credential; after
    /// connecting, the user picks the channel by tapping in
    /// Settings, and a single visible channel is selected
    /// automatically (see `ChannelSelection`).
    public static let defaultChannelID = "ab6379bfa1574c70bc8989cdd70dfcce"

    public init(
        platformBaseURLString: String = AppSettings.defaultPlatformBaseURLString,
        channelID: String = defaultChannelID
    ) {
        self.platformBaseURLString = platformBaseURLString
        self.channelID = channelID
    }

    /// Validation outcome for the settings form.
    public enum Validation: Equatable {
        case ok
        case missingBaseURL
        case invalidBaseURL(String)
        case missingChannelID
    }

    public var validation: Validation {
        let trimmed = platformBaseURLString.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return .missingBaseURL }
        guard let url = validatedBaseURL else {
            return .invalidBaseURL("平台地址无效：请输入 https:// 开头的完整地址。")
        }
        _ = url
        if channelID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return .missingChannelID
        }
        return .ok
    }

    /// Strictly validated base URL, or nil.
    /// - Requires the `https` scheme (App Transport Security stays on;
    ///   self-signed certificates are never trusted — see McpClient).
    /// - Rejects embedded userinfo (`user:pass@host`) so a token can
    ///   never hide inside the address.
    /// - Trailing slashes and paths are stripped: only scheme + host
    ///   (+ port) are kept, the API paths are appended by the client.
    ///   This is why the prefilled full endpoint URL and a bare host
    ///   behave identically.
    public var validatedBaseURL: URL? {
        let trimmed = platformBaseURLString.trimmingCharacters(in: .whitespacesAndNewlines)
        guard var components = URLComponents(string: trimmed) else { return nil }
        guard components.scheme?.lowercased() == "https" else { return nil }
        guard let host = components.host, !host.isEmpty else { return nil }
        guard components.user == nil, components.password == nil else { return nil }
        components.path = ""
        components.query = nil
        components.fragment = nil
        return components.url
    }

    /// MCP endpoint derived from the validated base URL.
    public var mcpEndpoint: URL? {
        validatedBaseURL?.appendingPathComponent("api/open/v1/mcp")
    }

    /// Channel WebSocket URL derived from the validated base URL.
    /// `https` becomes `wss`; the channel id goes in the query string.
    public func channelWebSocketURL(channelID: String) -> URL? {
        guard let base = validatedBaseURL,
              var components = URLComponents(url: base, resolvingAgainstBaseURL: false) else {
            return nil
        }
        components.scheme = "wss"
        components.path = "/api/open/v1/channel/ws"
        components.queryItems = [URLQueryItem(name: "channel_id", value: channelID)]
        return components.url
    }
}

/// Persistence for `AppSettings` (UserDefaults; no secrets stored here).
public enum SettingsStore {
    private static let baseURLKey = "wherevercode.platformBaseURL"
    private static let channelIDKey = "wherevercode.channelID"

    public static func load(defaults: UserDefaults = .standard) -> AppSettings {
        // Prefill policy: a saved non-empty address is the user's own
        // choice and is kept verbatim. "Never saved" and "saved
        // empty" both fall back to the prefilled public endpoint —
        // a saved empty value can only be the untouched pre-prefill
        // form persisted by a stray save tap, and an empty address
        // cannot connect to anything anyway.
        let savedAddress = defaults.string(forKey: baseURLKey)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return AppSettings(
            platformBaseURLString: savedAddress.isEmpty
                ? AppSettings.defaultPlatformBaseURLString
                : savedAddress,
            channelID: defaults.string(forKey: channelIDKey) ?? AppSettings.defaultChannelID
        )
    }

    public static func save(_ settings: AppSettings, defaults: UserDefaults = .standard) {
        defaults.set(settings.platformBaseURLString, forKey: baseURLKey)
        defaults.set(settings.channelID, forKey: channelIDKey)
    }
}

/// Which channel is selected after a channel-list refresh. Pure and
/// side-effect free so the rule is unit-testable:
/// - If the current selection is still in the list, it stays.
/// - If the list has exactly ONE channel, that channel is selected
///   automatically (there is nothing to choose between).
/// - Otherwise the current selection is left untouched — the user
///   picks by tapping in Settings or in the conversation list. The
///   app never invents a selection the user did not make.
public enum ChannelSelection {
    public static func resolvedChannelID(channels: [ChannelInfo], current: String) -> String {
        if channels.contains(where: { $0.id == current }) { return current }
        if channels.count == 1, let only = channels.first { return only.id }
        return current
    }
}
