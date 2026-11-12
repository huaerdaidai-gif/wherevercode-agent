package wherevercode.agent.core.agent

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 平台 Agent 聊天事件（桥接层 → ChatViewModel 的流）。
 * 与 [ManusChatEvent] 同款形状：Progress → Result/Failed 终态，
 * 同一套通用会话/历史，不另做平台 Agent 专属会话模型。
 */
sealed class PlatformChatEvent {
    /** 真实进度（已发往平台 / 正在等对方回复），直接显示为轮次内备注。 */
    data class Progress(val message: String) : PlatformChatEvent()

    /**
     * 对方回来的回答正文（完整正文），回填为助手消息。
     * [messageId] 是该回复在频道里的消息 id（桥接层用它推进已消费游标；
     * 为空只在测试替身里出现，真实传输一定有 id）。
     */
    data class Result(val text: String, val messageId: String? = null) : PlatformChatEvent()

    /** 终态失败。文案已归属平台通道，绝不说成别的 Agent 的错，绝不静默回落任何大脑。 */
    data class Failed(val message: String) : PlatformChatEvent()
}

/**
 * 平台 Agent 聊天后端口：调用方只认接口，不认 [PlatformAgentChatBridge]。
 * 与 [ManusChatPort] 对称：流式回 [PlatformChatEvent]，
 * Progress → Result/Failed 终态。
 */
interface PlatformChatPort {
    /** 聊一次平台 Agent：发一条带收件方前缀的消息，等对方在频道里回复。 */
    fun runChat(
        conversationId: String,
        agent: AgentRecord,
        prompt: String,
    ): Flow<PlatformChatEvent>
}

/**
 * 平台 Agent 聊天桥接——聊天消息 → 平台频道往返。
 *
 * 流程：
 * - 发：正文以 `@<平台 Agent ID> ` 开头（`agent_channel_post`
 *   没有收件方字段，这是平台 API 的实际口径，见 [PlatformAgent]），
 *   经 [PlatformChannelTransport] 发到该 Agent 所在的频道（记录的
 *   `platformChannelId`，空着即仍走共享“第一批”群）。
 * - 端点：传输端点由该 Agent 记录的 `platformHost` 拼出
 *   （[PlatformAgent.mcpEndpoint]），空主机名默认 example.invalid；
 *   平台服务器已有 V5 平台端点（192.0.2.10），平台地址填它即走
 *   新端点，空着仍默认老平台端点。
 * - 收：轮询同一频道，取该 Agent（`agent_name` 归一化比对，见
 *   [PlatformAgent.sameAgent]）在发帖之后的第一条非空消息当作回复；
 *   上一轮等待中途断掉（手机休眠、轮询失败）时对方在断档里回的
 *   消息也不丢：下一轮先从已消费游标（[PlatformReplyCursorStore]，
 *   按频道+对方记）往后找，找到就先当作答案显示。
 * - 超时：对方按自己的节奏读平台，可能几分钟才回；默认等 10 分钟，
 *   超时如实报，不伪装成功、不编造回复。轮询本身 transient 失败
 *   （网络抖动）不再直接判整轮失败：记一条进度后等一轮间隔再试，
 *   等到超时为止；取消仍直接抛出。
 *
 * 诚实边界：
 * - 不是实时流式：单次往返数十秒到数分钟，任务级对话合适、实时
 *   聊天不合适；纯文本往返，无工具调用。
 * - 平台 Agent 本人仍无公开聊天 API：App 到它走的是平台通道。
 *   对方若先发“收到/正在处理”这类确认消息，桥接会把它当作答案
 *   （已知局限，不静默绕过）。
 * - 只发任务消息，不传代码、不推代码（maintainer 老规矩）。
 * - 令牌只以参数传入（[tokenProvider]，调用方从本机 Keystore
 *   现取），不缓存、不记日志；本类与单测一律用可注入的假传输，
 *   绝不发真实调用。
 */
class PlatformAgentChatBridge(
    private val tokenProvider: (AgentRecord) -> String?,
    private val transportFactory: ((() -> String?), String) -> PlatformChannelTransport =
        { provider, endpoint ->
            PlatformChannelMcpTransport(tokenProvider = provider, endpoint = endpoint)
        },
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val cursorStore: PlatformReplyCursorStore = InMemoryPlatformReplyCursorStore(),
) : PlatformChatPort {

    override fun runChat(
        conversationId: String,
        agent: AgentRecord,
        prompt: String,
    ): Flow<PlatformChatEvent> = flow {
        val targetId = agent.platformAgentId?.trim().takeIf { !it.isNullOrBlank() }
        if (targetId == null) {
            emit(PlatformChatEvent.Failed("该平台 Agent 没有填平台 Agent ID：请先在 Agent 页补填（如 example-agent），再发送。"))
            return@flow
        }
        val endpoint = PlatformAgent.mcpEndpoint(agent.platformHost)
        if (endpoint.isBlank()) {
            emit(PlatformChatEvent.Failed("未配置平台地址：请在 Agent 设置中填写 HTTPS 平台主机名。"))
            return@flow
        }
        val channelId = PlatformAgent.effectiveChannelId(agent.platformChannelId)
        if (channelId.isBlank()) {
            emit(PlatformChatEvent.Failed("未配置平台频道：请在 Agent 设置中填写平台提供的频道 ID。"))
            return@flow
        }
        val token = tokenProvider(agent)
        if (token.isNullOrBlank()) {
            emit(
                PlatformChatEvent.Failed(
                    "未配置平台 Agent 凭证：请先在你的平台服务中创建 Agent，" +
                        "再在平台 Agent 表单填入凭证（只存本机 Keystore）。本次消息未发出。",
                ),
            )
            return@flow
        }
        if (prompt.isBlank()) {
            emit(PlatformChatEvent.Failed("没有可发给 ${agent.displayName} 的问题：本轮没有用户消息。"))
            return@flow
        }
        val wireText = PlatformAgent.buildWireText(targetId, prompt.trim())
        if (wireText.length > PlatformAgent.MAX_POST_CHARS) {
            emit(
                PlatformChatEvent.Failed(
                    "问题太长（${wireText.length} 字，平台单条上限 ${PlatformAgent.MAX_POST_CHARS} 字）：请拆短再问。",
                ),
            )
            return@flow
        }
        val startSeconds = System.currentTimeMillis() / 1000.0
        // 传输按本轮的 Agent 绑定令牌：令牌是 App 级的平台凭证，
        // 调用方从本机 Keystore 现取、只活在本轮里。端点跟本轮 Agent
        // 记录的平台地址走。
        val transport = transportFactory({ token }, endpoint)
        try {
            val postedId = transport.postMessage(channelId, wireText)
            emit(PlatformChatEvent.Progress("已发给 ${agent.displayName}（平台通道），正在等它回复…"))
            val resumeAfterId = cursorStore.get(channelId, targetId)
            var pollFailedNoted = false
            val reply = withTimeoutOrNull(timeoutMs) {
                pollForReply(
                    transport, channelId, postedId, targetId, startSeconds,
                    resumeAfterId,
                    onPollError = {
                        if (!pollFailedNoted) {
                            pollFailedNoted = true
                            emit(PlatformChatEvent.Progress("频道轮询失败，正在重试…"))
                        }
                    },
                )
            }
            if (reply == null) {
                emit(
                    PlatformChatEvent.Failed(
                        "等 ${agent.displayName} 回复超时（已等 ${timeoutMs / 60_000} 分钟）。" +
                            "对方按自己的节奏读平台，可能还没看到消息；重试会在频道里再发一条，请确认后再试。",
                    ),
                )
            } else {
                emit(PlatformChatEvent.Result(reply.text.trim(), reply.id))
                // 先显示、再推进游标：显示被取消时游标不动，下轮还能找回这条回复。
                if (reply.id.isNotBlank()) cursorStore.put(channelId, targetId, reply.id)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(PlatformChatEvent.Failed("平台通道调用失败：${e.message ?: "未知错误"}"))
        }
    }

    /**
     * 轮询直到目标 Agent 回复。跑在 [withTimeoutOrNull] 里，循环本身
     * 没有自己的出口——超时是唯一的出口（回复除外）。
     *
     * 两个加固（2026-11-11，Example Agent 回复丢了那次的教训）：
     * - 断档续回：先从 [resumeAfterId]（上次已消费到的回复 id）往后
     *   找——对方在上一轮等待断掉期间回的消息，下一轮还能捡回来；
     *   没有游标时退回本轮发帖 id。[resumeAfterId] 指向的消息已不
     *   在频道里时服务端会报“消息不存在”，这时清掉游标、退回发帖
     *   id 再试一次，不让一个过期游标卡死整轮；别的轮询失败（网络
     *   抖动）不走这条路，游标留着重试。
     * - 轮询失败重试：一次轮询抛错（网络抖动）不再直接判整轮失败，
     *   [onPollError] 只在第一次失败时记一条进度，之后等一轮间隔
     *   再试，等到超时为止；取消仍直接抛出。
     */
    private suspend fun pollForReply(
        transport: PlatformChannelTransport,
        channelId: String,
        postedId: String,
        targetId: String,
        startSeconds: Double,
        resumeAfterId: String?,
        onPollError: (suspend () -> Unit)? = null,
    ): PlatformChannelMessage? {
        val postedIdKnown = postedId.isNotBlank()
        var afterId: String? = resumeAfterId?.takeIf { it.isNotBlank() }
            ?: postedId.takeIf { it.isNotBlank() }
        var resumed = resumeAfterId?.isNotBlank() == true
        while (true) {
            val messages = try {
                transport.pollMessages(channelId, afterId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (resumed && e.message?.contains(STALE_CURSOR_MESSAGE) == true) {
                    // 游标过期：服务端说那条消息已不在频道里。清掉游标、
                    // 退回本轮发帖 id 再试一次。别的失败（网络抖动）不
                    // 清游标，走下面的重试，断档回复下轮还能找回。
                    resumed = false
                    cursorStore.clear(channelId, targetId)
                    afterId = postedId.takeIf { it.isNotBlank() }
                    continue
                }
                onPollError?.invoke()
                delay(pollIntervalMs)
                continue
            }
            for (msg in messages) {
                if (msg.id.isNotBlank()) afterId = msg.id
                if (isReply(msg, targetId, postedId, postedIdKnown, startSeconds)) {
                    return msg
                }
            }
            delay(pollIntervalMs)
        }
    }

    /**
     * 一条消息算作答案：是目标 Agent 的、非空、不是我们自己的发帖。
     * 发帖返回了 id 时，服务端的 `after_id` 排序已保证“在我们发帖
     * 之后”，不用比时钟（手机与服务端时钟可能不同）；发帖 id 未知
     * 时退回时间戳比对（留 60 秒时钟偏差宽限）。
     */
    private fun isReply(
        msg: PlatformChannelMessage,
        targetId: String,
        postedId: String,
        postedIdKnown: Boolean,
        startSeconds: Double,
    ): Boolean {
        if (!PlatformAgent.sameAgent(msg.agentName, targetId)) return false
        if (msg.text.isBlank()) return false
        if (postedIdKnown) return msg.id != postedId
        return msg.createdAtSeconds > startSeconds - UNKNOWN_POST_ID_CLOCK_GRACE_SECONDS
    }

    companion object {
        /** 一次往返最多等 10 分钟：对方按自己的节奏轮询平台。 */
        const val DEFAULT_TIMEOUT_MS: Long = 600_000L

        /** 等回复时每 15 秒轮询一次频道。 */
        const val DEFAULT_POLL_INTERVAL_MS: Long = 15_000L

        /** 发帖 id 未知时的时钟偏差宽限（秒）。 */
        const val UNKNOWN_POST_ID_CLOCK_GRACE_SECONDS: Double = 60.0

        /**
         * 服务端对已不存在的消息 id 的报错原文（V5 端点
         * `_message_cursor`，message_not_found）：只有这句才算游标
         * 过期，别的轮询失败一律按网络抖动重试、游标留着。
         */
        const val STALE_CURSOR_MESSAGE: String = "消息不存在"
    }
}
