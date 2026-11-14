import Foundation

public struct AppSettings: Equatable, Sendable {
    public static let defaultPlatformBaseURL = "https://v5.cococolo.xyz/api/open/v1/mcp"
    public static let defaultChannelID = "ab6379bfa1574c70bc8989cdd70dfcce"
    public static let baseURLKey = "wherevercode.platformBaseURL"
    public static let channelIDKey = "wherevercode.channelID"

    public var platformBaseURLString: String
    public var channelID: String

    public init(
        platformBaseURLString: String = AppSettings.defaultPlatformBaseURL,
        channelID: String = AppSettings.defaultChannelID
    ) {
        self.platformBaseURLString = platformBaseURLString
        self.channelID = channelID
    }

    public static func load(from defaults: UserDefaults = .standard) -> AppSettings {
        let url = defaults.string(forKey: baseURLKey) ?? defaultPlatformBaseURL
        let ch = defaults.string(forKey: channelIDKey) ?? defaultChannelID
        return AppSettings(platformBaseURLString: url, channelID: ch)
    }

    public func save(to defaults: UserDefaults = .standard) {
        defaults.set(platformBaseURLString, forKey: Self.baseURLKey)
        defaults.set(channelID, forKey: Self.channelIDKey)
    }

    public var mcpEndpoint: URL? {
        let trimmed = platformBaseURLString.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let url = URL(string: trimmed), url.scheme?.lowercased() == "https" else {
            return nil
        }
        if url.path.isEmpty || url.path == "/" {
            return url.appendingPathComponent("api/open/v1/mcp")
        }
        return url
    }

    public func channelWebSocketURL(channelID: String) -> URL? {
        guard let endpoint = mcpEndpoint, var components = URLComponents(url: endpoint, resolvingAgainstBaseURL: false) else {
            return nil
        }
        components.scheme = "wss"
        components.path = "/api/open/v1/channel/ws"
        components.queryItems = [URLQueryItem(name: "channel_id", value: channelID)]
        return components.url
    }
}
