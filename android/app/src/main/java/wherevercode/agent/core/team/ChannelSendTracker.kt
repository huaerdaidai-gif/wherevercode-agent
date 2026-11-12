package wherevercode.agent.core.team

import wherevercode.agent.core.agent.PlatformChannelMessage

/**
 * 频道发送可靠性（2026-11-13，频道能力补齐）：客户端回音跟踪。
 *
 * 服务端 `agent_channel_post` 没有客户端幂等键（参数只有
 * channel_id/text/reply_to），所以 App 侧做不到“发一次、服务端去重”；
 * 能做的是回音确认：发完后等频道的回音（服务端存下的原样消息），
 * 收到了算送达，没收到算“可能没发出去”，由用户手动点“重发”——
 * 绝不自动重发（自动重发会在“其实已发出、只是回音没回来”时造出
 * 重复消息；手动重发前用户看得见原文，重复了也认得出来）。
 *
 * 回音匹配口径：快照里出现与待发文本完全一致、且时间在发送后
 * [echoWindowMs] 内的消息。文本相同是必要条件（服务端原样存正文），
 * 时间窗防把几天前别人发的相同文本误认成回音。同一文本多条待发
 * 按先发先认。
 * 纯内存状态，不持久化；App 重启后待发记录清零（不跨重启追回音）。
 */
class ChannelSendTracker(
    private val echoWindowMs: Long = ECHO_WINDOW_MS,
    private val staleAfterMs: Long = STALE_AFTER_MS,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    data class PendingSend(val text: String, val sentAtMs: Long)

    private val pending = ArrayDeque<PendingSend>()

    /** 记下一条已发出的消息（调用方发完 onPost 后调）。 */
    fun markSent(text: String) {
        pending += PendingSend(text = text, sentAtMs = nowMs())
        while (pending.size > MAX_PENDING) pending.removeFirst()
    }

    /**
     * 拿最新快照对回音：每条待发找一条文本相同、时间在窗内的消息，
     * 找到即算送达并移出待发。返回本次新确认送达的待发（一般是空表）。
     */
    fun matchEcho(messages: List<PlatformChannelMessage>): List<PendingSend> {
        if (pending.isEmpty() || messages.isEmpty()) return emptyList()
        val delivered = mutableListOf<PendingSend>()
        val used = mutableSetOf<PlatformChannelMessage>()
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val send = iterator.next()
            val echo = messages.firstOrNull { message ->
                message !in used &&
                    message.text == send.text &&
                    isEchoTime(message.createdAtSeconds, send.sentAtMs)
            }
            if (echo != null) {
                used += echo
                delivered += send
                iterator.remove()
            }
        }
        return delivered
    }

    /** 还没收到回音的待发（按发送顺序）。 */
    fun pendingNow(): List<PendingSend> = pending.toList()

    /** 发出超过 [staleAfterMs] 还没回音的待发（“可能没发出去”）。 */
    fun stalePending(): List<PendingSend> {
        val now = nowMs()
        return pending.filter { now - it.sentAtMs >= staleAfterMs }
    }

    private fun isEchoTime(createdAtSeconds: Double, sentAtMs: Long): Boolean {
        if (createdAtSeconds <= 0) return false
        val echoMs = (createdAtSeconds * 1000).toLong()
        // 回音时间在发送前后 5 秒（时钟偏差）到发送后 echoWindowMs 之间。
        return echoMs in (sentAtMs - 5_000L)..(sentAtMs + echoWindowMs)
    }

    companion object {
        /** 回音有效窗：发出后 3 分钟内回来的相同文本算回音。 */
        const val ECHO_WINDOW_MS: Long = 180_000L

        /** 超过 30 秒没回音就提示用户（频道轮询 15 秒一轮，30 秒还没见回音值得问一句）。 */
        const val STALE_AFTER_MS: Long = 30_000L

        const val MAX_PENDING: Int = 10
    }
}
