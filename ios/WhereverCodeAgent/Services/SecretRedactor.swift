import Foundation

public enum SecretRedactor {
    private static let patterns: [String] = [
        "(?is)-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----.*?-----END [A-Z0-9 ]*PRIVATE KEY-----",
        "(?i)\\bsk-[A-Za-z0-9_-]{12,}\\b",
        "(?i)\\bBearer\\s+[A-Za-z0-9._-]{8,}",
        "(?i)\\bv5_[A-Za-z0-9_-]{8,}\\b"
    ]

    public static func redact(_ input: String) -> String {
        var result = input
        for pattern in patterns {
            if let regex = try? NSRegularExpression(pattern: pattern, options: []) {
                let range = NSRange(result.startIndex..<result.endIndex, in: result)
                result = regex.stringByReplacingMatches(in: result, options: [], range: range, withTemplate: "[REDACTED_SECRET]")
            }
        }
        return result
    }
}
