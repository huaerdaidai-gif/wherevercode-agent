import Foundation

/// Brain display name, ported from the Android baseline
/// `ui/BrainDisplayName.kt` on main @4496658e.
///
/// The user can customise the name in Settings; the default is the
/// neutral "WhereverCode". Every user-visible brain name goes through
/// here — never a hard-coded product codename, and never a real
/// person's name (the greeting is a fixed "你好" and does not read any
/// stored identity). Blank input restores the default (the key is
/// removed).
public final class BrainDisplayName {
    /// kv key (the value is a display name only, no secret).
    public static let key = "brain.displayName"

    /// Default brain display name.
    public static let defaultName = "WhereverCode"

    private let kv: KeyValueStore

    public init(kv: KeyValueStore) {
        self.kv = kv
    }

    /// Stored/input -> display name: blank (including all-whitespace)
    /// resolves to the default; anything else is trimmed and used
    /// as-is.
    public static func resolve(_ stored: String?) -> String {
        let trimmed = stored?.trimmingCharacters(in: .whitespacesAndNewlines)
        return (trimmed == nil || trimmed!.isEmpty) ? defaultName : trimmed!
    }

    /// Default session label: "<name>会话".
    public static func sessionLabel(_ stored: String?) -> String {
        "\(resolve(stored))会话"
    }

    /// The current display name (default when never stored).
    public func load() -> String {
        Self.resolve((try? kv.get(Self.key)) ?? nil)
    }

    /// Store a custom name; blank input removes the key (restores the
    /// default).
    public func save(_ raw: String) {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        try? kv.set(Self.key, trimmed.isEmpty ? nil : trimmed)
    }
}
