package wherevercode.agent.core.team

/**
 * 频道引用回复（2026-11-13，频道能力补齐）。
 *
 * 服务端 `agent_channel_post` 支持结构化 `reply_to`（消息 id），但 App
 * 的传输层目前只发 `channel_id` + `text`（传输层属 MCP 工人，
 * core/agent，接线见 DELIVERY）。在结构化接线落地前，引用用正文
 * 编码——第一行写引用行，正文照常跟在后面：
 *
 * `> 回复 #<消息id> <发送者>：<被引正文摘要>`
 *
 * - 所有客户端（App、GPT 经 MCP 读）都能直接看懂，不依赖新字段；
 * - App 读回时解析引用行，渲染成“↩ 回复 …”的引用块，不跟正文混在一起；
 * - 结构化 `reply_to` 接线后，引用行仍保留作回显（双写），老消息照样解析。
 * 引用行只占一行，摘要最多 80 字，不挤占 500 字的发帖上限太多。
 */
data class ChannelQuote(
    val messageId: String,
    val senderName: String,
    val excerpt: String,
)

object ChannelQuoteCodec {
    const val MAX_EXCERPT_CHARS: Int = 80

    private const val PREFIX: String = "> 回复 #"

    /** 在正文前加引用行。body 原样保留（调用方先拼好任务头再调这里，见组合顺序）。 */
    fun encode(quote: ChannelQuote, body: String): String {
        val excerpt = quote.excerpt.trim().replace("\n", " ").take(MAX_EXCERPT_CHARS)
        val sender = quote.senderName.trim().ifBlank { "未知" }
        return "$PREFIX${quote.messageId} $sender：$excerpt\n$body"
    }

    /**
     * 解析正文第一行的引用行。认且只认 `> 回复 #<id> <发送者>：<摘要>`；
     * 没有或格式不对返回 null（调用方原样显示正文）。
     * 发送者名可能含空格：id 取到第一个空格，发送者取到第一个中文冒号。
     */
    fun parse(text: String): ChannelQuote? {
        val firstLine = text.lineSequence().firstOrNull() ?: return null
        if (!firstLine.startsWith(PREFIX)) return null
        val rest = firstLine.removePrefix(PREFIX)
        val idEnd = rest.indexOf(' ')
        if (idEnd <= 0) return null
        val messageId = rest.substring(0, idEnd).trim()
        if (messageId.isBlank()) return null
        val senderAndExcerpt = rest.substring(idEnd + 1)
        val colon = senderAndExcerpt.indexOf('：')
        if (colon <= 0) return null
        val sender = senderAndExcerpt.substring(0, colon).trim()
        if (sender.isBlank()) return null
        val excerpt = senderAndExcerpt.substring(colon + 1).trim()
        return ChannelQuote(messageId = messageId, senderName = sender, excerpt = excerpt)
    }

    /** 去掉正文第一行的引用行（展示用）；没有引用行原样返回。 */
    fun strip(text: String): String {
        if (parse(text) == null) return text
        val newline = text.indexOf('\n')
        return if (newline < 0) "" else text.substring(newline + 1)
    }

    /**
     * 给一条消息做引用摘要：去掉归属头（[发送者 · 任务 …] 的头）与引用行，
     * 取第一行正文、压成一行、截断到 [MAX_EXCERPT_CHARS]。
     */
    fun excerptOf(text: String): String {
        val body = strip(stripTeamAttributionHeader(text)).trim()
        val firstLine = body.lineSequence().firstOrNull().orEmpty()
        return firstLine.replace(Regex("\\s+"), " ").trim().take(MAX_EXCERPT_CHARS)
    }
}
