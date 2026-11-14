import Foundation

public final class ContextManager {
    public var budgetEstimatedTokens: Int
    public let charsPerToken: Int

    public init(budgetEstimatedTokens: Int = 32_000, charsPerToken: Int = 3) {
        self.budgetEstimatedTokens = budgetEstimatedTokens
        self.charsPerToken = max(1, charsPerToken)
    }

    public func estimateTokens(for messages: [ConversationMessage]) -> Int {
        let totalChars = messages.reduce(0) { $0 + $1.content.count }
        return max(1, totalChars / charsPerToken)
    }

    public func trimIfNeeded(_ messages: [ConversationMessage]) -> (messages: [ConversationMessage], compressedRounds: Int, compressionNotes: [String]) {
        var current = messages
        var compressed = 0
        var notes: [String] = []
        while estimateTokens(for: current) > budgetEstimatedTokens && current.count > 2 {
            let removed = current.removeFirst()
            compressed += 1
            notes.append("已压缩最旧一轮对话（第 \(compressed) 轮），仅保留要点：\(removed.content.prefix(80))")
        }
        return (current, compressed, notes)
    }
}
