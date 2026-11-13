import Foundation

/// SecretRedactor: erase secrets from event / chat / log text,
/// ported from the Android baseline `core/security/SecretRedactor.kt`
/// on main @4496658e (itself a port of the old project's event-secret
/// patterns — same 8 patterns, same order, same group rules: a
/// pattern with 4 groups keeps groups 1+2, redacts group 3, keeps
/// group 4; 2 groups keeps group 1; 1 group keeps group 1; 0 groups
/// replaces the whole match with [REDACTED]).
///
/// Streaming rule: a quoted credential can be incomplete until a
/// later transport chunk arrives, so the open tail is masked BEFORE
/// the ordinary complete-value redactor runs.
///
/// EVERY string that goes into chat, the event log, the audit log or
/// a task result must pass through here first.
public enum SecretRedactor {
    public static let redacted = "[REDACTED]"
    public static let defaultLimit = 2000

    private static let secretName =
        "(?:[A-Z0-9_]*(?:API[_-]?KEY|ACCESS[_-]?KEY|SECRET(?:[_-]?ACCESS[_-]?KEY)?|SESSION[_-]?TOKEN|TOKEN|PASSWORD|PASSWD|PRIVATE[_-]?KEY))"

    private struct PatternRule {
        let regex: NSRegularExpression
        let groups: Int
    }

    private static func regex(_ pattern: String) -> NSRegularExpression {
        try! NSRegularExpression(pattern: pattern, options: [])
    }

    // Same 8 patterns as the Android `_EVENT_SECRET_PATTERNS`, same
    // order. Kotlin `(?i)` / `(?is)` inline flags map to ICU inline
    // flags unchanged.
    private static let secretPatterns: [PatternRule] = [
        PatternRule(
            regex: regex("(?is)-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----.*?-----END [A-Z0-9 ]*PRIVATE KEY-----"),
            groups: 0),
        PatternRule(
            regex: regex("(?i)(\\bauthorization\\b\\s*[\"\\']?\\s*[:=]\\s*[\"\\']?)(?:bearer\\s+|basic\\s+)?[^\"\\'\\s,;}]+"),
            groups: 1),
        PatternRule(
            regex: regex("(?i)([\"\\']?\\b\(secretName)\\b[\"\\']?\\s*[:=]\\s*)([\"\\'])(.*?)(\\2)"),
            groups: 4),
        PatternRule(
            regex: regex("(?i)([\"\\']?\\b\(secretName)\\b[\"\\']?\\s*[:=]\\s*)(?![\"\\'])([^\\s,;}]+)"),
            groups: 2),
        PatternRule(
            regex: regex("(?i)(--(?:api[-_]?key|access[-_]?key|secret|token|password|passwd)(?:=|\\s+))([\"\\'])(.*?)(\\2)"),
            groups: 4),
        PatternRule(
            regex: regex("(?i)(--(?:api[-_]?key|access[-_]?key|secret|token|password|passwd)(?:=|\\s+))([\"\\']?)([^\\s\"\\']+)(\\2)"),
            groups: 4),
        PatternRule(
            regex: regex("(?i)\\bsk-[A-Za-z0-9_-]{12,}\\b"),
            groups: 0),
        PatternRule(
            regex: regex("(?i)\\bBearer\\s+[A-Za-z0-9._-]{8,}"),
            groups: 0),
    ]

    private static let openTailSecretName = regex(
        "(?i)([\"\\']?\\b\(secretName)\\b[\"\\']?\\s*[:=]\\s*[\"\\'])[^\"\\']*$")
    private static let openTailBearerOrSk = regex(
        "(?i)\\b(?:sk-[A-Za-z0-9_-]*|Bearer\\s+[A-Za-z0-9._-]*)$")

    private static func replace(
        _ text: String,
        using regex: NSRegularExpression,
        transform: (NSTextCheckingResult, String) -> String
    ) -> String {
        let matches = regex.matches(in: text, range: NSRange(text.startIndex..., in: text))
        guard !matches.isEmpty else { return text }
        var result = ""
        var cursor = text.startIndex
        for match in matches {
            guard let range = Range(match.range, in: text) else { continue }
            result += text[cursor..<range.lowerBound]
            result += transform(match, text)
            cursor = range.upperBound
        }
        result += text[cursor...]
        return result
    }

    private static func group(_ match: NSTextCheckingResult, _ index: Int, in text: String) -> String {
        guard index < match.numberOfRanges,
              let range = Range(match.range(at: index), in: text)
        else { return "" }
        return String(text[range])
    }

    /// Redact secrets and keep the last [limit] chars.
    public static func redact(_ text: String, limit: Int = defaultLimit) -> String {
        var value = text
        for rule in secretPatterns {
            switch rule.groups {
            case 4:
                value = replace(value, using: rule.regex) { match, source in
                    group(match, 1, in: source) + group(match, 2, in: source)
                        + redacted + group(match, 4, in: source)
                }
            case 2, 1:
                value = replace(value, using: rule.regex) { match, source in
                    group(match, 1, in: source) + redacted
                }
            default:
                value = replace(value, using: rule.regex) { _, _ in redacted }
            }
        }
        let safeLimit = max(0, limit)
        if safeLimit == 0 { return "" }
        return value.count <= safeLimit ? value : String(value.suffix(safeLimit))
    }

    /// Streaming variant: mask an open (incomplete) credential tail
    /// first, then run the ordinary redactor. Use for every
    /// incremental chunk.
    public static func redactStreamChunk(_ text: String, limit: Int = defaultLimit) -> String {
        var value = replace(text, using: openTailSecretName) { match, source in
            group(match, 1, in: source) + redacted
        }
        value = replace(value, using: openTailBearerOrSk) { _, _ in redacted }
        return redact(value, limit: limit)
    }

    /// True when the text contains anything that looks like a secret.
    public static func containsSecret(_ text: String) -> Bool {
        if redact(text, limit: Int.max) != text { return true }
        if openTailSecretName.firstMatch(
            in: text, range: NSRange(text.startIndex..., in: text)) != nil { return true }
        if openTailBearerOrSk.firstMatch(
            in: text, range: NSRange(text.startIndex..., in: text)) != nil { return true }
        return false
    }
}
