import Foundation

/// ContextManager: assemble the message list for one brain call,
/// ported from the Android baseline `brain/ContextManager.kt` on
/// main @4496658e.
///
/// Order: system prompt + long-term memory summary + recent N rounds
/// (within budget) + current user message + tool definitions
/// (carried in ChatRequest.tools, not inlined here).
///
/// Budget: 24,000 ESTIMATED tokens by default. Every token figure in
/// this file is an estimate: fields and names use `estimatedTokens`,
/// and an estimate must NEVER be presented as provider usage. Real
/// usage comes only from BrainEvent.usage (provider-reported).
///
/// Estimation rule: roughly 1 token per 4 characters (conservative
/// Chinese/English mix). Over budget, compress from the OLDEST round
/// first: drop raw tool outputs (keep the conclusion), then summarise
/// whole old rounds. Every compression is reported in
/// [AssembledContext] so the caller can log it as an event.
public struct AssembledContext: Equatable {
    public var messages: [ChatMessage]
    public var estimatedTokens: Int
    public var compressedRounds: Int
    public var compressionNotes: [String]

    public init(
        messages: [ChatMessage],
        estimatedTokens: Int,
        compressedRounds: Int,
        compressionNotes: [String]
    ) {
        self.messages = messages
        self.estimatedTokens = estimatedTokens
        self.compressedRounds = compressedRounds
        self.compressionNotes = compressionNotes
    }
}

public final class ContextManager {
    public static let defaultBudgetEstimatedTokens = 24000
    public static let toolOutputKeepChars = 500
    public static let roundSummaryKeepChars = 200
    /// Estimated tokens per attached image: a <=1024px JPEG costs a
    /// few hundred to a thousand tokens on mainstream providers; 1000
    /// deliberately over-estimates. Real usage only ever comes from
    /// the provider (BrainEvent.usage).
    public static let estimatedTokensPerImage = 1000

    private let budgetEstimatedTokens: Int
    private let charsPerToken: Int

    public init(
        budgetEstimatedTokens: Int = ContextManager.defaultBudgetEstimatedTokens,
        charsPerToken: Int = 4
    ) {
        self.budgetEstimatedTokens = budgetEstimatedTokens
        self.charsPerToken = charsPerToken
    }

    /// Conservative character-based estimate. Never provider usage.
    public func estimateTokens(_ text: String) -> Int {
        if text.isEmpty { return 0 }
        return (text.count + charsPerToken - 1) / charsPerToken
    }

    private func estimateMessages(_ messages: [ChatMessage]) -> Int {
        messages.reduce(0) {
            $0 + estimateTokens($1.content) + 4
                + $1.images.count * Self.estimatedTokensPerImage
        }
    }

    public func build(
        systemPrompt: String,
        memorySummary: String,
        history: [ConversationMessage],
        currentMessage: String,
        tools: [ToolDef],
        skillSummary: String = "",
        currentImages: [ChatImage] = []
    ) -> AssembledContext {
        var notes: [String] = []
        var compressedRounds = 0
        var systemMessages: [ChatMessage] = []
        if !systemPrompt.trimmingCharacters(in: .whitespaces).isEmpty {
            systemMessages.append(ChatMessage(role: "system", content: systemPrompt))
        }
        if !memorySummary.trimmingCharacters(in: .whitespaces).isEmpty {
            systemMessages.append(ChatMessage(
                role: "system", content: "长期记忆摘要：\n\(memorySummary)"))
        }
        if !skillSummary.trimmingCharacters(in: .whitespaces).isEmpty {
            systemMessages.append(ChatMessage(
                role: "system",
                content: "相关技能（本项目积累，走通的做法优先用，坑主动避开）：\n\(skillSummary)"))
        }
        // Tool definitions travel in ChatRequest.tools; a short
        // textual reminder is kept so text-fallback brains still see
        // them.
        if !tools.isEmpty {
            let toolText = tools.map { "- \($0.name)：\($0.description)" }
                .joined(separator: "\n")
            systemMessages.append(ChatMessage(role: "system", content: "可用工具：\n\(toolText)"))
        }

        var roundMessages: [ChatMessage] = history
            .filter { $0.role != "system" }
            .map {
                ChatMessage(
                    role: $0.role,
                    content: $0.content,
                    toolCallId: $0.toolCallId,
                    name: $0.source,
                    toolCalls: $0.toolCalls)
            }
        let current = ChatMessage(role: "user", content: currentMessage, images: currentImages)

        func total(_ msgs: [ChatMessage]) -> Int {
            estimateMessages(systemMessages + msgs + [current])
        }

        // Step 1: shrink raw tool outputs first, keep the conclusion.
        if total(roundMessages) > budgetEstimatedTokens {
            roundMessages = roundMessages.map { msg in
                if msg.role == "tool" && msg.content.count > Self.toolOutputKeepChars {
                    let kept = String(msg.content.prefix(Self.toolOutputKeepChars))
                    notes.append("工具原始输出已压缩，仅保留前 \(Self.toolOutputKeepChars) 字符（结论优先）")
                    var copy = msg
                    copy.content = "[工具输出已压缩，仅保留结论]\n\(kept)"
                    return copy
                }
                return msg
            }
        }

        // Step 2: compress whole rounds from the OLDEST first. A
        // "round" is a user message plus the assistant/tool messages
        // that follow it, up to the next user message.
        var working = roundMessages
        while total(working) > budgetEstimatedTokens && working.count > 1 {
            let firstUser = working.firstIndex { $0.role == "user" }
            let start = firstUser ?? 0
            var end = start + 1
            while end < working.count && working[end].role != "user" { end += 1 }
            let round = working[start..<end]
            let roundText = round.map { $0.content }
                .joined(separator: " ")
                .trimmingCharacters(in: .whitespaces)
            let summary = String(roundText.prefix(Self.roundSummaryKeepChars))
            let replacement = ChatMessage(
                role: "system",
                content: "[上下文压缩]此前一轮对话摘要（估算 token，已压缩）：\(summary)")
            var next = Array(working[..<start])
            next.append(replacement)
            next += working[end...]
            working = next
            compressedRounds += 1
            notes.append("已压缩最旧一轮对话（第 \(compressedRounds) 轮），仅保留要点")
        }

        let assembled = systemMessages + working + [current]
        return AssembledContext(
            messages: assembled,
            estimatedTokens: estimateMessages(assembled),
            compressedRounds: compressedRounds,
            compressionNotes: notes)
    }
}
