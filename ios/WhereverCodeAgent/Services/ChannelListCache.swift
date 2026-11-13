import Foundation

/// Local snapshot of the team-channel list, so the conversation
/// home can render the last-known channels instantly (and keep
/// showing them when the platform is unreachable) instead of
/// starting from an empty list on every cold open. Backed by the
/// local data layer's kv table (checklist A7/F3); the platform
/// stays the source of truth — every successful list call
/// rewrites the snapshot.
public final class ChannelListCache {
    private struct CachedChannel: Codable {
        var id: String
        var name: String
        var participants: [String]
        var lastMessageText: String?
    }

    private let kv: KeyValueStore
    private let storageKey = "channelListCache.v1"

    public init(kv: KeyValueStore) {
        self.kv = kv
    }

    public convenience init() {
        self.init(kv: LocalData.shared.kv)
    }

    public func load() -> [ChannelInfo] {
        guard let cached = try? kv.getJSON(storageKey, as: [CachedChannel].self), let cached else {
            return []
        }
        return cached.map {
            ChannelInfo(id: $0.id, name: $0.name, participants: $0.participants, lastMessageText: $0.lastMessageText)
        }
    }

    public func save(_ channels: [ChannelInfo]) {
        let cached = channels.map {
            CachedChannel(id: $0.id, name: $0.name, participants: $0.participants, lastMessageText: $0.lastMessageText)
        }
        try? kv.setJSON(storageKey, cached)
    }

    public func clear() {
        try? kv.set(storageKey, nil)
    }
}
