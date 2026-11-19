// WhereverCode Agent — iOS
//
// App settings: the platform address and the selected channel id.
// No platform address is baked in: a fresh install starts
// unconfigured (empty) and every surface says 「未配置」 until the
// user saves their own address; a saved non-empty address is kept
// verbatim (see `SettingsStore.load`). Local use never depends on
// the platform address. The channel is chosen by TAPPING a channel
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
    /// in effect. Starts empty (unconfigured) until the user
    /// saves their own.
    public var platformBaseURLString: String
    /// The selected channel. Stored as an id, chosen by tapping —
    /// see `ChannelSelection`.
    public var channelID: String

    /// No platform address is baked in. A fresh install starts
    /// unconfigured (empty): the address only ever comes from the
    /// user's own saved configuration, and every surface must say
    /// 「未配置」 while it is empty instead of showing a preset host.
    public static let defaultPlatformBaseURLString = ""
    public static let defaultPlatformBaseURL = defaultPlatformBaseURLString

    /// No channel is baked in either. After connecting, the user
    /// picks the channel by tapping in Settings, and a single
    /// visible channel is selected automatically
    /// (see `ChannelSelection`).
    public static let defaultChannelID = ""

    public init(
        platformBaseURLString: String = AppSettings.defaultPlatformBaseURLString,
        channelID: String = defaultChannelID
    ) {
        self.platformBaseURLString = platformBaseURLString
        self.channelID = channelID
    }

    public static func load(from defaults: UserDefaults = .standard) -> AppSettings {
        SettingsStore.load(defaults: defaults)
    }

    public func save(to defaults: UserDefaults = .standard) {
        SettingsStore.save(self, defaults: defaults)
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
    ///   This is why a full endpoint URL and a bare host
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
        // No-prefill policy: a saved non-empty address is the user's
        // own choice and is kept verbatim. "Never saved" and "saved
        // empty" both stay empty (unconfigured) — nothing is ever
        // substituted behind the user's back.
        let savedAddress = defaults.string(forKey: baseURLKey)?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return AppSettings(
            platformBaseURLString: savedAddress,
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
