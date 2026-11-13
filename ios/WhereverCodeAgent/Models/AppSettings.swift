// WhereverCode Agent — iOS MVP
//
// App settings: the platform address and channel id the user types in.
// The platform address defaults to EMPTY on purpose — the app never
// hardcodes a server address or IP. The token is NOT stored here; it
// lives in the Keychain (see KeychainStore). Settings are plain,
// non-secret configuration and may live in UserDefaults.

import Foundation

/// User-editable connection settings (no secrets).
public struct AppSettings: Equatable {
    /// Platform base URL typed by the user, for example
    /// `https://example.invalid`. Empty until the user fills it in.
    public var platformBaseURLString: String
    /// Channel to open by default. Editable in Settings.
    public var channelID: String

    /// The team channel this app is built to join. This is a channel
    /// identifier, not a server address and not a credential; the user
    /// can change it in Settings at any time.
    public static let defaultChannelID = "ab6379bfa1574c70bc8989cdd70dfcce"

    public init(platformBaseURLString: String = "", channelID: String = defaultChannelID) {
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
        AppSettings(
            platformBaseURLString: defaults.string(forKey: baseURLKey) ?? "",
            channelID: defaults.string(forKey: channelIDKey) ?? AppSettings.defaultChannelID
        )
    }

    public static func save(_ settings: AppSettings, defaults: UserDefaults = .standard) {
        defaults.set(settings.platformBaseURLString, forKey: baseURLKey)
        defaults.set(settings.channelID, forKey: channelIDKey)
    }
}
