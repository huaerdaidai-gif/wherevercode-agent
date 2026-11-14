import Foundation

public final class BrainDisplayName {
    private let kv: KeyValueStore

    public init(kv: KeyValueStore) {
        self.kv = kv
    }

    public func get() -> String {
        (try? kv.get("brain.displayName")) ?? "WhereverCode Brain"
    }

    public func set(_ name: String) {
        try? kv.set("brain.displayName", name)
    }
}
