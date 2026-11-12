package wherevercode.agent.core.agent

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

/**
 * Manus 聊天事件（桥接层 → ChatViewModel 的流）。
 *
 * Manus 是任务型 API，没有聊天补全接口：一次聊天消息对应一次
 * Manus 任务（创建 → 轮询 → 取结果）。本桥接层只做这个翻译，
 * 不造平行聊天系统：路由、会话、历史、持久化全走通用聊天
 * （AgentChatRouter / ChatViewModel / SessionLedger /
 * ConversationStore，见 maintainer 2026-11-10 22:22 通用对话模式）。
 */
sealed class ManusChatEvent {
    /** 真实进度（已建任务 / 执行中），直接显示为轮次内备注。 */
    data class Progress(val message: String) : ManusChatEvent()

    /**
     * 流式：某条 assistant_message 的当前全文快照（老项目
     * turn_timeline.js 同规则：累计快照替换，不累加；调用方按
     * messageId 记最新一份，拼接后整体替换正文，绝不重复贴）。
     */
    data class TextSnapshot(val messageId: String, val text: String) : ManusChatEvent()

    /**
     * 流式：一次已完成的工具使用（API 只在完成时才可见，无进行中信号；
     * status 非 success 时 ok=false）。调用方以 id 为卡片 id，
     * 按「运行中→成功/失败」落工具卡。
     */
    data class ToolUsed(
        val id: String,
        val tool: String,
        val brief: String,
        val detail: String,
        val ok: Boolean,
    ) : ManusChatEvent()

    /** 流式：新出现的产物附件（已按 file_uid/url 去重）。 */
    data class Attachments(val files: List<ManusTaskAttachment>) : ManusChatEvent()

    /** 任务终态取到的回答正文，回填为助手消息（仍为完整正文）。 */
    data class Result(
        val text: String,
        val attachments: List<ManusTaskAttachment> = emptyList(),
    ) : ManusChatEvent()

    /**
     * 终态失败。文案已归属 Manus（含原始错误码供诊断），
     * 绝不说成 ChatGPT 的错，绝不静默回落任何大脑。
     */
    data class Failed(val message: String) : ManusChatEvent()
}

/**
 * Manus 聊天后端口：调用方只认接口，不认 [ManusChatBridge]。
 * 与 [wherevercode.agent.core.mcp.RemoteAgentChatPort] 对称：
 * 流式回 [ManusChatEvent]，Progress → Result/Failed 终态。
 */
interface ManusChatPort {
    /**
     * 聊一次 Manus：首条建任务，后续跟进同一任务（以
     * [conversationId] 查 [ManusTaskIdStore]）。
     */
    fun runChat(
        conversationId: String,
        agent: AgentRecord,
        prompt: String,
    ): Flow<ManusChatEvent>

    /**
     * 停止该会话对应的 Manus 任务（暂停/停止/改目标时调用）。
     * Key 在调用时经 [ManusChatBridge] 的 apiKeyProvider 现取，
     * 不缓存、不持久化。
     */
    fun stopTask(conversationId: String, agent: AgentRecord): Boolean

    /** 忘掉该会话的 Manus 任务（改目标后下一条建新任务）。 */
    fun forgetConversation(conversationId: String)
}

/**
 * 阶段 C（V5 紧急开发）：Manus 聊天桥接——聊天消息 → Manus 任务。
 *
 * 流程（官方 v2 文档，2026-11-10 直读 live 文档复核）：
 * - 首条消息：POST /v2/task.create，立即持久化 task_id；
 * - 跟进：POST /v2/task.sendMessage（task.sendMessage 页：
 *   "Sends a follow-up message to a task for multi-turn
 *   conversation. Use this after task.create to continue talking"）；
 * - 轮询：GET /v2/task.detail 到 stopped / waiting / error /
 *   deleted；结果：GET /v2/task.listMessages 取最新文本。
 * - waiting 也取结果回填（Agent 的提问/中间回答），任务保留待跟进。
 * - 超时/失败都保留 task_id（稍后跟进可继续同一任务），
 *   只有改目标才忘掉（下一条建新任务）。
 *
 * 计费：每次 runChat 的首条都会创建一个真实 Manus 任务（按任务
 * 计费）；跟进不建新任务。K70 真机验证走免费积分（maintainer 2026-11-10
 * 22:10 确认）；额度耗尽或要切付费时停下、如实报，不静默切付费。
 * 本类与单测一律用可注入的假传输/假 Key，绝不发真实调用。
 *
 * 密钥只以参数传入（[apiKeyProvider]，调用方从本机 Keystore 读），
 * 不记日志、不进任何返回对象与摘要。
 *
 * 回复取自非 assistant_message 字段时记一条诊断（[onDiagnostic]，
 * 调用方接日志）：回复不静默丢掉，但要让真机上看得出它落在了
 * 哪个字段（2026-11-11 Manus 回复提取修复）。
 */
class ManusChatBridge(
    private val adapter: ManusTaskAdapter = ManusTaskAdapter(),
    private val apiKeyProvider: (AgentRecord) -> String?,
    private val taskIdStore: ManusTaskIdStore,
    private val pollIntervalMs: Long = 5_000,
    private val timeoutMs: Long = 600_000,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val onDiagnostic: (String) -> Unit = {},
    /**
     * 跟进后终态宽限（毫秒）：sendMessage 刚发完时，Manus 侧的状态
     * 可能还停在上一轮的终态（waiting/stopped），没来得及翻回
     * running；宽限内的终态不认，继续轮询等它先跑起来，宽限过了
     * 再认终态。0 = 不宽限（立即认终态，旧行为）。
     * 不宽限时第一轮轮询就会把上一轮的回复当成本轮的回答（2026-11-11
     * K70：每轮回复都错位一轮）。
     */
    private val terminalGraceMs: Long = 30_000,
) : ManusChatPort {

    override fun runChat(
        conversationId: String,
        agent: AgentRecord,
        prompt: String,
    ): Flow<ManusChatEvent> = flow {
        val apiKey = apiKeyProvider(agent)
        if (apiKey.isNullOrBlank()) {
            emit(
                ManusChatEvent.Failed(
                    "Manus API Key 未配置：请先在 Agent 页填写 Manus API Key" +
                        "（只存本机 Keystore），再发送。本次不建任务、不计费。",
                ),
            )
            return@flow
        }
        val baseUrl = agent.baseUrl
        val existingTaskId = taskIdStore.get(conversationId)
        val taskId = if (existingTaskId != null) {
            emit(ManusChatEvent.Progress("正在向 Manus 发送跟进消息…"))
            when (val sent = adapter.sendMessage(apiKey, baseUrl, existingTaskId, prompt, agent.model)) {
                is ManusResult.Success -> existingTaskId
                is ManusResult.Failure -> {
                    emit(ManusChatEvent.Failed(describeFailure(sent.error)))
                    return@flow
                }
            }
        } else {
            emit(ManusChatEvent.Progress("正在创建 Manus 任务…"))
            when (val created = adapter.createTask(apiKey, baseUrl, prompt, agent.model)) {
                is ManusResult.Success -> {
                    // 创建成功立即持久化 task_id（适配器幂等只在同一
                    // 实例内有效，App 重启后靠这里恢复跟进）。
                    taskIdStore.put(conversationId, created.value.taskId)
                    created.value.taskId
                }
                is ManusResult.Failure -> {
                    emit(ManusChatEvent.Failed(describeFailure(created.error)))
                    return@flow
                }
            }
        }
        emit(ManusChatEvent.Progress("Manus 任务已创建，正在执行…"))
        // 本轮高水位（2026-11-11 K70 污染：一条“在吗”回了整段历史——
        // 桥接每轮新建去重状态，listMessages 又返回整任务历史，旧
        // assistant_message 全被当成新快照重发）。
        // 跟进时先快照一次当前任务的全部消息 id，本轮只流出快照之后
        // 的新消息，绝不重发历史；新任务没有历史，基线为空（也不必
        // 多一次调用，且 Manus 回得再快也不会被基线吞掉）。
        val baseline = if (existingTaskId != null) {
            when (val fetched = fetchBaseline(apiKey, baseUrl, taskId)) {
                is ManusResult.Success -> fetched.value
                is ManusResult.Failure -> {
                    emit(ManusChatEvent.Failed(describeFailure(fetched.error)))
                    return@flow
                }
            }
        } else {
            Baseline.EMPTY
        }
        // 终态宽限只对跟进轮次有意义：任务是上一轮留下的，状态可能
        // 还停在上一轮的 waiting/stopped。新任务是本轮刚创建的，
        // 状态就是新鲜的——一开始就是 stopped 就是真终态，不必空转
        // 等宽限（否则 create 轮次会在终态上白白轮询满 30 秒）。
        val isFollowUp = existingTaskId != null
        // 流式轮询（老项目 turn_timeline.js 同规则：累计快照替换不累加、
        // 按消息 id 去重、连续相同进度去重）：每轮先取状态、再取消息，
        // 新增内容当场流出，不等终态才回填。取消信号：flow 收集被取消时
        // 循环在下一轮停下（最长多等一个轮询间隔，不静默烧额度）。
        val runContext = currentCoroutineContext()
        val stream = StreamState(baseline)
        val deadlineMs = System.currentTimeMillis() + timeoutMs
        val turnStartMs = System.currentTimeMillis()
        var seenRunning = false
        while (true) {
            val status = when (val got = adapter.getTask(apiKey, baseUrl, taskId)) {
                is ManusResult.Success -> got.value
                is ManusResult.Failure -> {
                    emit(ManusChatEvent.Failed(describeFailure(got.error)))
                    return@flow
                }
            }
            val page = when (val messages = adapter.listMessages(apiKey, baseUrl, taskId)) {
                is ManusResult.Success -> messages.value
                is ManusResult.Failure -> {
                    emit(ManusChatEvent.Failed(describeFailure(messages.error)))
                    return@flow
                }
            }
            emitStreaming(page, stream) { emit(it) }
            val terminal = status.status != ManusTaskAdapter.STATUS_RUNNING
            // 终态可能是上一轮残留：跟进刚发完、Manus 还没开始跑新一轮
            // 时，状态仍显示上一轮的 waiting/stopped；宽限内不认终态，
            // 继续轮询等它先跑起来（seenRunning），宽限过了再认终态。
            // 不这么做，第一轮就会把上一轮的回复当成本轮回答（错位一轮）。
            val staleTerminal = terminal && !seenRunning && isFollowUp &&
                System.currentTimeMillis() - turnStartMs < terminalGraceMs
            if (!staleTerminal) {
                when (status.status) {
                    ManusTaskAdapter.STATUS_STOPPED -> {
                        // stopped 不一定是终态（后台任务可能继续），但聊天
                        // 轮次到这里取一次当前结果回填；跟进仍走同一任务。
                        emitTerminalResult(page, stream, "Manus 任务已结束，但没有返回可显示的内容。") { emit(it) }
                        return@flow
                    }
                    ManusTaskAdapter.STATUS_WAITING -> {
                        // Agent 在等用户输入/确认：把它的提问回填，任务保留，
                        // 下一条消息继续跟进同一任务。
                        emitTerminalResult(
                            page,
                            stream,
                            "Manus 正在等待进一步输入，但没有返回可显示的内容；请直接发送跟进消息。",
                        ) { emit(it) }
                        return@flow
                    }
                    ManusTaskAdapter.STATUS_ERROR -> {
                        emit(ManusChatEvent.Failed("Manus 任务执行出错。"))
                        return@flow
                    }
                    ManusTaskAdapter.STATUS_DELETED -> {
                        emit(ManusChatEvent.Failed("Manus 任务已被删除。"))
                        return@flow
                    }
                    ManusTaskAdapter.STATUS_RUNNING -> {
                        seenRunning = true
                    }
                }
            }
            if (!runContext.isActive) {
                // 调用方已取消轮次：不补发终态事件（ViewModel 的
                // finally 会按取消落终态）。
                return@flow
            }
            if (System.currentTimeMillis() >= deadlineMs) {
                // 超时只停轮询，不停任务：task_id 保留，稍后跟进同一任务。
                emit(
                    ManusChatEvent.Failed(
                        "Manus 任务超时（${timeoutMs / 1000} 秒）：任务可能仍在 " +
                            "Manus 侧运行，稍后发送新消息可继续跟进同一任务。",
                    ),
                )
                return@flow
            }
            sleeper(pollIntervalMs)
        }
    }

    /**
     * runChat 一轮内的流式去重状态（只活一轮，不持久化）。
     * 高水位（[baselineKeys] / [snapshots] / [seenAttachmentKeys]）
     * 在轮次开始时由基线快照预填：快照时已有的消息、快照、附件一律
     * 不重发；本轮新到的才流出。
     * snapshots：assistant_message 的最新全文快照（按消息 id 记）；
     * allAttachments：本轮累计的产物附件（只含本轮新的，按 dedupeKey
     * 去重，终态 Result 只带这些，绝不把上一轮的产物再贴一遍）。
     */
    private class StreamState(baseline: Baseline) {
        /** 本轮高水位键（快照时已有的消息；终态取结果时也只认快照外的新消息）。 */
        val baselineKeys: Set<String> = baseline.messageKeys
        /** 已见消息键（高水位预填；本轮新到的随即加入）。 */
        val seenIds = baseline.messageKeys.toHashSet()
        val snapshots = LinkedHashMap<String, String>().apply { putAll(baseline.snapshotTexts) }
        val seenAttachmentKeys = baseline.attachmentKeys.toHashSet()
        val allAttachments = LinkedHashMap<String, ManusTaskAttachment>()
        /** 本轮已发过的进度文案（整轮去重，不只连续相同——来回翻的
         * 相同文案也不重发）。 */
        val seenProgressTexts = HashSet<String>()
        var anySnapshot = false
    }

    /** 一次 listMessages 快照出的本轮高水位：快照时已有的消息。 */
    private data class Baseline(
        /** 快照时已有的消息键（id；无 id 时 type+文本哈希）。 */
        val messageKeys: Set<String>,
        /** 快照时 assistant_message 的全文（键 → 文本；文本变了才算新快照）。 */
        val snapshotTexts: Map<String, String>,
        /** 快照时已有的产物附件去重键。 */
        val attachmentKeys: Set<String>,
    ) {
        companion object {
            val EMPTY = Baseline(emptySet(), emptyMap(), emptySet())

            fun from(page: ManusMessagesPage): Baseline {
                val keys = HashSet<String>()
                val snaps = LinkedHashMap<String, String>()
                val attKeys = HashSet<String>()
                for (message in page.messages) {
                    val key = messageKey(message)
                    keys.add(key)
                    if (message.type == "assistant_message" && !message.text.isNullOrBlank()) {
                        snaps[key] = message.text
                    }
                    for (attachment in message.attachments) {
                        attKeys.add(attachment.dedupeKey())
                    }
                }
                return Baseline(keys, snaps, attKeys)
            }
        }
    }

    /** 跟进时取一次当前任务的消息列表，快照成本轮高水位。 */
    private fun fetchBaseline(
        apiKey: String,
        baseUrl: String?,
        taskId: String,
    ): ManusResult<Baseline> {
        return when (val page = adapter.listMessages(apiKey, baseUrl, taskId)) {
            is ManusResult.Success -> ManusResult.Success(Baseline.from(page.value))
            is ManusResult.Failure -> ManusResult.Failure(page.error)
        }
    }

    /**
     * Manus 默认的状态翻转文案（无信息量，只有“还在跑/刚停”的意思）：
     * 轮次本身已有运行中指示（状态芯片 + “正在生成…”），不必再逐条
     * 贴成气泡。其他 brief（“正在写报告”等）是有信息量的进度，照常流出。
     */
    private fun isGenericStatusBrief(brief: String): Boolean {
        return when (brief.trim().lowercase()) {
            "manus is running", "manus is working", "running",
            "manus finished working", "manus finished", "finished",
            "manus stopped", "stopped",
            -> true
            else -> false
        }
    }

    /**
     * 把一页 listMessages 的新增内容流出（按时间顺序旧→新；listMessages
     * 本身是 order=desc，新在前，故先反转）。
     * - assistant_message：文本变了才发 TextSnapshot（累计快照）；
     * - status_update / explanation / error_message / 未知类型：首次出现
     *   发一次 Progress（连续相同文案去重）；
     * - tool_used：首次出现发一次 ToolUsed（API 只在完成时才可见，
     *   无进行中信号，不伪造运行中）；
     * - user_message：绝不流出（用户自己发的内容）。
     */
    private suspend fun emitStreaming(
        page: ManusMessagesPage,
        state: StreamState,
        emit: suspend (ManusChatEvent) -> Unit,
    ) {
        for (message in page.messages.asReversed()) {
            val key = messageKey(message)
            when (message.type) {
                "user_message" -> {
                    // 绝不把用户自己发的内容当进度/回复。
                }
                "assistant_message" -> {
                    val text = message.text
                    if (!text.isNullOrBlank() && state.snapshots[key] != text) {
                        state.snapshots[key] = text
                        state.anySnapshot = true
                        emit(ManusChatEvent.TextSnapshot(key, text))
                    }
                    emitNewAttachments(message, state, emit)
                }
                "status_update" -> {
                    if (state.seenIds.add(key)) {
                        val brief = message.text
                        // 通用运行/完成翻转不发气泡（轮次本身已有运行中指示）；
                        // 有信息量的 brief 照常发，但整轮去重（来回翻的
                        // 相同文案也不重发，不只连续相同）。
                        if (!brief.isNullOrBlank() && !isGenericStatusBrief(brief) &&
                            state.seenProgressTexts.add(brief)
                        ) {
                            emit(ManusChatEvent.Progress(brief))
                        }
                    }
                    emitNewAttachments(message, state, emit)
                }
                "tool_used" -> {
                    if (state.seenIds.add(key)) {
                        val tool = message.toolName?.takeIf { it.isNotBlank() } ?: "tool"
                        val brief = message.text?.takeIf { it.isNotBlank() } ?: tool
                        val ok = message.toolStatus?.let { it.equals("success", ignoreCase = true) } ?: true
                        emit(
                            ManusChatEvent.ToolUsed(
                                id = key,
                                tool = tool,
                                brief = brief,
                                detail = message.toolDetail ?: "",
                                ok = ok,
                            ),
                        )
                    }
                    emitNewAttachments(message, state, emit)
                }
                "explanation", "error_message" -> {
                    if (state.seenIds.add(key)) {
                        message.text?.takeIf { it.isNotBlank() }?.let { emit(ManusChatEvent.Progress(it)) }
                    }
                    emitNewAttachments(message, state, emit)
                }
                else -> {
                    // 未知类型：有正文就当进度流出一次（不静默丢），绝不当地回复。
                    if (state.seenIds.add(key)) {
                        message.text?.takeIf { it.isNotBlank() }?.let { emit(ManusChatEvent.Progress(it)) }
                    }
                    emitNewAttachments(message, state, emit)
                }
            }
        }
    }

    /** 一条消息的新产物附件（去重后）流出一次。 */
    private suspend fun emitNewAttachments(
        message: ManusTaskMessage,
        state: StreamState,
        emit: suspend (ManusChatEvent) -> Unit,
    ) {
        val fresh = message.attachments.filter { state.seenAttachmentKeys.add(it.dedupeKey()) }
        if (fresh.isEmpty()) return
        for (file in fresh) state.allAttachments[file.dedupeKey()] = file
        emit(ManusChatEvent.Attachments(fresh))
    }

    /** 终态结果：正文取 pickResultText（完整正文，旧口径不变），附件取本轮累计。 */
    private suspend fun emitTerminalResult(
        page: ManusMessagesPage,
        state: StreamState,
        emptyMessage: String,
        emit: suspend (ManusChatEvent) -> Unit,
    ) {
        val text = pickResultText(page.messages, state.baselineKeys)
        val files = state.allAttachments.values.toList()
        if (text != null) {
            emit(ManusChatEvent.Result(text, files))
        } else if (state.anySnapshot) {
            // 正文已通过快照流完：不再报“没有可显示的内容”。
            emit(ManusChatEvent.Result("", files))
        } else {
            emit(ManusChatEvent.Failed(emptyMessage))
        }
    }

    override fun stopTask(conversationId: String, agent: AgentRecord): Boolean {
        val taskId = taskIdStore.get(conversationId) ?: return false
        val apiKey = apiKeyProvider(agent) ?: return false
        return try {
            when (adapter.stopTask(apiKey, agent.baseUrl, taskId)) {
                is ManusResult.Success -> true
                is ManusResult.Failure -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    override fun forgetConversation(conversationId: String) {
        taskIdStore.clear(conversationId)
    }

    /**
     * 取结果正文（官方 v2 listMessages 格式，2026-11-11 直读 live 文档复核）：
     * 只认本轮新消息（[baselineKeys] 之外的）：上一轮的回复再新也是旧的，
     * 绝不能当成本轮的回答回填（2026-11-11 K70：每轮回复都错位一轮）。
     * 首选最新一条 assistant_message（真正的回答）；其次 explanation /
     * error_message / tool_used / status_update（解释/错误/进度文本，
     * 有总比没有强）；绝不把 user_message（用户自己发的内容）当回答返回；
     * 通用状态翻转文案（“Manus is running”之类）也不当回答。
     * 回复落在非 assistant_message 字段时记一条诊断，不静默丢掉。
     */
    private fun pickResultText(
        messages: List<ManusTaskMessage>,
        baselineKeys: Set<String>,
    ): String? {
        val fresh = messages.filter { messageKey(it) !in baselineKeys }
        fresh.firstOrNull { it.type == "assistant_message" && !it.text.isNullOrBlank() }
            ?.text?.let { return it }
        val fallbackTypes = listOf("explanation", "error_message", "tool_used", "status_update")
        for (type in fallbackTypes) {
            val found = fresh.firstOrNull {
                it.type == type && !it.text.isNullOrBlank() &&
                    !(type == "status_update" && isGenericStatusBrief(it.text))
            }
            if (found != null) {
                onDiagnostic("Manus 回复取自 $type 字段（非 assistant_message），已回填显示。")
                return found.text
            }
        }
        val other = fresh.firstOrNull { it.type != "user_message" && !it.text.isNullOrBlank() }
        if (other != null) {
            onDiagnostic("Manus 回复取自未知类型字段（${other.type}），已回填显示。")
            return other.text
        }
        return null
    }

    /** 失败文案：归属 Manus 在前，原始错误码在后供诊断。 */
    private fun describeFailure(error: ManusError): String =
        "Manus 调用失败（${rawCode(error)}）：${error.describe()}"

    /**
     * 官方错误码（Introduction 页错误表；额度类无专用码——服务端
     * 原样 code 已保留在 detail 里，见 [ManusTaskAdapter.mapErrorResponse]）。
     */
    private fun rawCode(error: ManusError): String = when (error) {
        ManusError.Unauthorized -> "unauthenticated"
        ManusError.Forbidden -> "permission_denied"
        ManusError.NotFound -> "not_found"
        ManusError.RateLimited -> "rate_limited"
        is ManusError.InvalidArgument -> "invalid_argument"
        is ManusError.HttpError -> "http_${error.code}"
        is ManusError.NetworkError -> "network_error"
        is ManusError.InvalidResponse -> "invalid_response"
        ManusError.Timeout -> "timeout"
    }

}

/**
 * 一条 Manus 消息的去重键：id 优先；无 id 时用 type+文本哈希
 *（不用页内下标——新消息加入后下标会漂移，同一条消息会被当成
 * 新消息重发）。文件级私有：[ManusChatBridge] 的成员与嵌套类
 * [ManusChatBridge.Baseline] 的 companion 都要调它。
 */
private fun messageKey(message: ManusTaskMessage): String =
    message.id?.takeIf { it.isNotBlank() }
        ?: "no-id-${message.type}-${(message.text ?: "").hashCode()}"
