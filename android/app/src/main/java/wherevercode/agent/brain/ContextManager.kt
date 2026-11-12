package wherevercode.agent.brain

/**
 * ContextManager: assemble the message list for one brain call (stage 2).
 *
 * Order (audit section 4.4): system prompt + long-term memory summary +
 * recent N rounds (within budget) + current user message + tool
 * definitions (carried in ChatRequest.tools, not inlined here).
 *
 * Budget: 24,000 ESTIMATED tokens by default, the same conservative
 * estimate as the old `deepseek_context.py:DEFAULT_BUDGET_TOKENS`.
 * Every token figure in this file is an estimate: fields and names use
 * `estimatedTokens`, and an estimate must NEVER be presented as provider
 * usage. Real usage comes only from BrainEvent.Usage (provider-reported).
 *
 * Estimation rule: roughly 1 token per 4 characters (conservative
 * Chinese/English mix). Over budget, compress from the OLDEST round
 * first: drop raw tool outputs (keep the conclusion), then summarise
 * whole old rounds. Every compression is reported in [AssembledContext]
 * so the caller can log it as an event.
 */
data class AssembledContext(
    val messages: List<ChatMessage>,
    val estimatedTokens: Int,
    val compressedRounds: Int,
    val compressionNotes: List<String>,
)

class ContextManager(
    private val budgetEstimatedTokens: Int = DEFAULT_BUDGET_ESTIMATED_TOKENS,
    private val charsPerToken: Int = 4,
) {
    companion object {
        const val DEFAULT_BUDGET_ESTIMATED_TOKENS: Int = 24000
        const val TOOL_OUTPUT_KEEP_CHARS: Int = 500
        const val ROUND_SUMMARY_KEEP_CHARS: Int = 200
        /**
         * 每张随附图片的估算 token（V5 按主流改，maintainer 2026-11-11）。
         * 粗估：1024px 以内的 JPEG 在 OpenAI / xAI 的计价里约几百到
         * 一千 token；这里按 1000 估，宁可高估不少报。真实用量只认
         * BrainEvent.Usage（厂商返回的数字），估算永不对外宣称用量。
         */
        const val ESTIMATED_TOKENS_PER_IMAGE: Int = 1000
    }

    /** Conservative character-based estimate. Never provider usage. */
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        return (text.length + charsPerToken - 1) / charsPerToken
    }

    private fun estimateMessages(messages: List<ChatMessage>): Int =
        messages.sumOf {
            estimateTokens(it.content) + 4 + it.images.size * ESTIMATED_TOKENS_PER_IMAGE
        } // +4: per-message framing overhead, estimated

    fun build(
        systemPrompt: String,
        memorySummary: String,
        history: List<Message>,
        currentMessage: String,
        tools: List<ToolDef>,
        // V5 技能记忆（2026-11-11）：本项目积累的相关技能（已按任务
        // 内容捞过、最多 3 条、约 300 token），无相关技能时传 ""、
        // 不注入。放在记忆摘要之后、工具之前：做法偏好先于工具清单。
        skillSummary: String = "",
        /**
         * 当轮用户消息随附的图片（V5 按主流改，maintainer 2026-11-11）。
         * 只贴在当轮的 user 消息上，不进历史；不支持 vision 的大脑由
         * BrainEngine 在发请求前逐轮过滤掉，这里只负责组装。
         */
        currentImages: List<ChatImage> = emptyList(),
    ): AssembledContext {
        val notes = mutableListOf<String>()
        var compressedRounds = 0
        val systemMessages = mutableListOf<ChatMessage>()
        if (systemPrompt.isNotBlank()) {
            systemMessages += ChatMessage(role = "system", content = systemPrompt)
        }
        if (memorySummary.isNotBlank()) {
            systemMessages += ChatMessage(role = "system", content = "长期记忆摘要：\n$memorySummary")
        }
        if (skillSummary.isNotBlank()) {
            systemMessages +=
                ChatMessage(
                    role = "system",
                    content =
                        "相关技能（本项目积累，走通的做法优先用，坑主动避开）：\n$skillSummary",
                )
        }
        // Tool definitions travel in ChatRequest.tools; a short textual
        // reminder is kept so text-fallback brains still see them.
        if (tools.isNotEmpty()) {
            val toolText = tools.joinToString("\n") { "- ${it.name}：${it.description}" }
            systemMessages += ChatMessage(role = "system", content = "可用工具：\n$toolText")
        }

        var roundMessages: List<ChatMessage> = history
            .filter { it.role != "system" }
            .map {
                ChatMessage(
                    role = it.role,
                    content = it.content,
                    name = it.source,
                    toolCallId = it.toolCallId,
                    toolCalls = it.toolCalls,
                )
            }
        val current = ChatMessage(role = "user", content = currentMessage, images = currentImages)

        fun total(msgs: List<ChatMessage>): Int = estimateMessages(systemMessages + msgs + current)

        // Step 1: shrink raw tool outputs first, keep the conclusion.
        if (total(roundMessages) > budgetEstimatedTokens) {
            roundMessages = roundMessages.map { msg ->
                if (msg.role == "tool" && msg.content.length > TOOL_OUTPUT_KEEP_CHARS) {
                    val kept = msg.content.take(TOOL_OUTPUT_KEEP_CHARS)
                    notes += "工具原始输出已压缩，仅保留前 $TOOL_OUTPUT_KEEP_CHARS 字符（结论优先）"
                    msg.copy(content = "[工具输出已压缩，仅保留结论]\n$kept")
                } else {
                    msg
                }
            }
        }

        // Step 2: compress whole rounds from the OLDEST first.
        // A "round" is a user message plus the assistant/tool messages
        // that follow it, up to the next user message.
        var working: MutableList<ChatMessage> = roundMessages.toMutableList()
        while (total(working) > budgetEstimatedTokens && working.size > 1) {
            val firstUser = working.indexOfFirst { it.role == "user" }
            val start = if (firstUser >= 0) firstUser else 0
            var end = start + 1
            while (end < working.size && working[end].role != "user") end++
            val round = working.subList(start, end)
            val roundText = round.joinToString(" ") { it.content }.trim()
            val summary = roundText.take(ROUND_SUMMARY_KEEP_CHARS)
            val replacement = ChatMessage(
                role = "system",
                content = "[上下文压缩]此前一轮对话摘要（估算 token，已压缩）：$summary",
            )
            val next = mutableListOf<ChatMessage>()
            next += working.subList(0, start)
            next += replacement
            next += working.subList(end, working.size)
            working = next
            compressedRounds += 1
            notes += "已压缩最旧一轮对话（第 $compressedRounds 轮），仅保留要点"
        }

        val assembled = systemMessages + working + current
        return AssembledContext(
            messages = assembled,
            estimatedTokens = estimateMessages(assembled),
            compressedRounds = compressedRounds,
            compressionNotes = notes,
        )
    }
}
