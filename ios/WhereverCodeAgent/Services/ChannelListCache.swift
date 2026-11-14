import Foundation

public final class ChannelListCache {
    private let kv: KeyValueStore
    private let storageKey = "channelListCache.v1"

    public init(kv: KeyValueStore) {
        self.kv = kv
    }

    public func load() -> [ChannelInfo] {
        (try? kv.getJSON(storageKey, as: [ChannelInfo].self)) ?? []
    }

    public func save(_ channels: [ChannelInfo]) {
        try? kv.setJSON(storageKey, channels)
    }
}
