package wherevercode.agent.ui

import org.json.JSONObject
import wherevercode.agent.core.security.SecretRedactor

/** Lifecycle of one user-visible chat turn. */
enum class TurnStatus {
    RUNNING,
    DONE,
    EMPTY,
    ERROR,
    CANCELLED,
    PAUSED,
    STOPPED,
    GOAL_CHANGED,
}

enum class ToolCallStatus {
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELLED,
}

data class ChatToolCallUi(
    val id: String,
    val name: String,
    val args: String,
    val status: ToolCallStatus,
    val resultSummary: String? = null,
    val resultDetail: String? = null,
    val revision: Long = 0,
)

/**
 * 一轮里的一份任务产物（Manus 附件等）。只存渲染卡片要用的字段：去重键、
 * 显示名、可打开的链接（可能为空——链接临时有效，失效时不编新链接）。
 * 没有附件时列表为空，界面不凭空造卡片。
 */
data class ChatAttachmentUi(
    val key: String,
    val name: String,
    val url: String?,
)

/**
 * 大脑返回的用量（仅厂商返回的数字；未返回时为 null）。
 * 界面按对话方智能显示：只有说话者是大脑/模型且有数字时才显示用量行；
 * 远端 Agent 没有用量概念，永远不显示；没有数字时不显示“未知”。
 */
data class TurnUsage(
    val inputTokens: Int?,
    val outputTokens: Int?,
)

/**
 * 一轮回复的任务归属（Agent 回复挂钩）：任务编号 + 任务标题。
 * 渲染时只显示标题与编号短形式；完整归属强制见归属分支。
 */
data class TurnTaskAttribution(
    val taskId: String,
    val taskTitle: String,
)

data class ChatTurnUi(
    val id: String,
    val userText: String,
    val assistantText: String,
    val reasoning: String,
    val protocolRedacted: Boolean,
    val toolCalls: List<ChatToolCallUi>,
    val notes: List<String>,
    val status: TurnStatus,
    val error: String?,
    val revision: Long,
    /** 本轮用量；null = 大脑未返回用量（界面不显示用量行）。 */
    val usage: TurnUsage? = null,
    /**
     * 本轮是谁在回话（显示名）：大脑轮次是大脑名，Agent 轮次是 Agent
     * 名。空 = 不知道（恢复出的旧轮次），界面回落到“AGENT”。
     * 团队模式下就靠它分清是哪位成员在回。
     *
     * 大脑/Agent 边界（maintainer 2026-11-12 定稿，一个对话、一个说话者）：
     * 一个对话里只有一个说话者——Agent 对话里只有该 Agent 说话，大脑
     * 只在幕后派活、不以说话者身份插话；想跟大脑说话就开大脑对话。
     * 本字段是说话者身份的唯一依据，恢复时也必须带上，绝不回落到
     * 含混的“AGENT”。
     */
    val speaker: String = "",
    /**
     * 说话者是不是大脑/模型（true）还是 Agent（false）。用量行只在
     * true 且有数字时显示——Agent 没有用量概念。
     */
    val speakerIsBrain: Boolean = true,
    /**
     * 本轮回复属于哪个任务（Agent 回复的归属挂钩）。Agent 的每条回复
     * 必须带任务归属，不许裸贴进无关对话。null = 本轮不属于任何任务
     * （普通对话轮次）。完整归属强制（拦裸贴、追踪表外活）由
     * fix/v5-agent-reply-attribution-20261112 那路做，本路只做显示
     * 挂钩：模型带上字段、界面有得渲染。
     */
    val taskAttribution: TurnTaskAttribution? = null,
    /**
     * 大脑/Agent 边界（maintainer 2026-11-12）：本轮走的路线的持久标注
     * （如“直连 Example Agent（大脑已关闭）”“意图不明，已按闲聊处理”）。
     * 与 statusNote 的区别：statusNote 是进行中的 transient 进度
     * （进度 ticker 每秒覆盖、终态后界面不再显示），routeLabel 是
     * 持久的路线/分类标注，轮次结束后仍在，界面常驻显示——用户事后
     * 也能看得出这条消息走的是哪条路、按什么意图处理的。null = 不标
     * （普通轮次）。
     */
    val routeLabel: String? = null,
    /**
     * V5 聊天清理 20261111：进行中的状态（进度事件、“任务已创建…”等）只记
     * 最新一条；界面把它收成一行 subtle 状态行，回复到了就消失，绝不逐条
     * 贴成气泡。null = 本轮没有进行中状态。
     */
    val statusNote: String? = null,
    /** 本轮的任务产物（Manus 附件等）；空 = 无产物，不造卡片。 */
    val attachments: List<ChatAttachmentUi> = emptyList(),
    /**
     * V5 C2-3-R1b（老项目 v6-app.js:510）：流式占位——先贴
     * “正在连接 <大脑>…”，首段正文到后清除，终态以持久化快照为准；
     * 占位永不持久化（恢复出的轮次 placeholder 为 null）。
     */
    val placeholder: String? = null,
) {
    val hasToolCalls: Boolean get() = toolCalls.isNotEmpty()
}

data class ChatUiState(
    val turns: List<ChatTurnUi> = emptyList(),
    val running: Boolean = false,
    val startedAtMillis: Long? = null,
    val finishedAtMillis: Long? = null,
    val brainLabel: String = "",
    val revision: Long = 0,
)

/**
 * UI-facing turn ledger for the chat screen. It is the single place where
 * brain events become display state:
 *
 * - assistant prose, reasoning, tool calls and tool results are separate
 *   fields (the view renders them as separate, collapsible layers);
 * - raw DSML is isolated by [ChatTextSanitizer] before it reaches any text
 *   field, so it can never be mixed into the normal chat bubble;
 * - tool arguments/results pass through [SecretRedactor] before display;
 * - all mutators are synchronized; snapshots are immutable copies, safe to
 *   hand from the engine thread to the UI thread.
 *
 * Pure Kotlin (no Android imports) so the behaviour is unit-testable.
 */
class ChatTimeline {

    private class MutableToolCall(
        val id: String,
        var name: String,
        var args: String,
        var status: ToolCallStatus = ToolCallStatus.RUNNING,
        var resultSummary: String? = null,
        var resultDetail: String? = null,
        var revision: Long = 0,
    )

    private class MutableTurn(
        val id: String,
        val userText: String,
        val createdAtMillis: Long,
    ) {
        val rawAssistant = StringBuilder()
        val rawReasoning = StringBuilder()
        var extractedThink: String = ""
        var assistantText: String = ""
        var reasoning: String = ""
        var protocolRedacted: Boolean = false
        val toolCalls = LinkedHashMap<String, MutableToolCall>()
        val notes = mutableListOf<String>()
        var status: TurnStatus = TurnStatus.RUNNING
        var error: String? = null
        var usage: TurnUsage? = null
        var speaker: String = ""
        var speakerIsBrain: Boolean = true
        /** 本轮回复属于哪个任务；null = 不属于任何任务（普通对话）。 */
        var taskAttribution: TurnTaskAttribution? = null
        /** 本轮走的路线的持久标注（见 ChatTurnUi.routeLabel）；null = 不标。 */
        var routeLabel: String? = null
        var placeholder: String? = null
        var revision: Long = 0
        /** 进行中的状态（最新一条；终态后界面不再显示）。 */
        var statusNote: String? = null
        /** 本轮的任务产物，按去重键合并。 */
        val attachments = LinkedHashMap<String, ChatAttachmentUi>()
    }

    private val turns = LinkedHashMap<String, MutableTurn>()
    private var revision = 0L
    private var nextTurnId = 0L
    private var running = false
    private var startedAtMillis: Long? = null
    private var finishedAtMillis: Long? = null
    private var brainLabel: String = ""

    @Synchronized
    fun clear() {
        turns.clear()
        running = false
        startedAtMillis = null
        finishedAtMillis = null
        brainLabel = ""
        revision += 1
    }

    /**
     * 开一轮用户轮次。speakerLabel 是本轮说话者的显示名（大脑轮次是大脑
     * 名，Agent 轮次是 Agent 名）；speakerIsBrain 标明说话者是大脑/模型
     * 还是 Agent——用量行只认大脑说话者。占位与时间线级标签沿用该名。
     */
    @Synchronized
    fun beginUserTurn(userText: String, speakerLabel: String, speakerIsBrain: Boolean = true): String {
        nextTurnId += 1
        val id = "turn-$nextTurnId"
        val turn = MutableTurn(id, userText, System.currentTimeMillis())
        // V5 C2-3-R1b（v6-app.js:510）：先贴占位，等首段正文（见
        // appendAssistantText）或终态（见 finishTurn）再处理。
        turn.placeholder = "正在连接 $speakerLabel…"
        turn.speaker = speakerLabel
        turn.speakerIsBrain = speakerIsBrain
        turns[id] = turn
        running = true
        startedAtMillis = turn.createdAtMillis
        finishedAtMillis = null
        this.brainLabel = speakerLabel
        revision += 1
        turn.revision = revision
        return id
    }

    /** A standalone notice (busy rejection, config hint, control feedback). */
    @Synchronized
    fun beginSystemTurn(note: String): String {
        nextTurnId += 1
        val id = "turn-$nextTurnId"
        val turn = MutableTurn(id, "", System.currentTimeMillis())
        turn.status = TurnStatus.DONE
        turn.notes += note
        turn.revision = revision + 1
        turns[id] = turn
        revision += 1
        return id
    }

    @Synchronized
    fun appendNote(turnId: String?, note: String) {
        val target = turnId?.let { turns[it] } ?: turns.values.lastOrNull() ?: return
        if (note.isBlank()) return
        target.notes += SecretRedactor.redact(note, 4000)
        touch(target)
    }

    /**
     * V5 聊天清理 20261111：进行中的状态只记最新一条（覆盖，不累加）。
     * 与 [appendNote] 的区别：进度状态不是内容，界面只把它显示为一行
     * subtle 状态行，回复到了就消失；终态说明、报错、控制反馈等内容仍走
     * appendNote，照常以备注卡片显示。
     */
    @Synchronized
    fun appendStatusNote(turnId: String?, note: String) {
        val target = turnId?.let { turns[it] } ?: turns.values.lastOrNull() ?: return
        if (note.isBlank()) return
        target.statusNote = SecretRedactor.redact(note, 4000)
        touch(target)
    }

    /** 本轮新增任务产物（按 key 去重；同一份文件重复上报只留一份）。 */
    @Synchronized
    fun addAttachments(turnId: String?, attachments: List<ChatAttachmentUi>) {
        val target = turnId?.let { turns[it] } ?: turns.values.lastOrNull() ?: return
        var changed = false
        for (attachment in attachments) {
            if (attachment.name.isBlank()) continue
            if (target.attachments.put(attachment.key, attachment) == null) changed = true
        }
        if (changed) touch(target)
    }

    @Synchronized
    fun appendAssistantText(turnId: String, delta: String) {
        val turn = turns[turnId] ?: return
        if (delta.isEmpty()) return
        turn.rawAssistant.append(delta)
        applyAssistantText(turn)
        touch(turn)
    }

    /**
     * 把助手正文整体替换为累计快照（流式任务执行用；老项目
     * turn_timeline.js 同规则：累计快照替换，不累加；同一条
     * assistant_message 的旧内容被新快照整体取代，绝不重复贴）。
     * 复用 appendAssistantText 的消毒/脱敏/抽 reasoning/清占位逻辑。
     */
    @Synchronized
    fun setAssistantText(turnId: String, text: String) {
        val turn = turns[turnId] ?: return
        turn.rawAssistant.clear()
        turn.rawAssistant.append(text)
        applyAssistantText(turn)
        touch(turn)
    }

    private fun applyAssistantText(turn: MutableTurn) {
        val sanitized = ChatTextSanitizer.sanitize(turn.rawAssistant.toString())
        turn.assistantText = SecretRedactor.redactStreamChunk(sanitized.text, 20000)
        turn.protocolRedacted = turn.protocolRedacted || sanitized.protocolRedacted
        if (sanitized.reasoning.isNotBlank()) turn.extractedThink = sanitized.reasoning
        // V5 C2-3-R1b（v6-app.js:510）：首段非空正文到，占位清除（final
        // 整体替换占位的前半步；最终以持久化快照为准）。
        if (turn.placeholder != null && turn.assistantText.isNotBlank()) {
            turn.placeholder = null
        }
        recomputeReasoning(turn)
    }

    @Synchronized
    fun appendReasoning(turnId: String, delta: String) {
        val turn = turns[turnId] ?: return
        if (delta.isEmpty()) return
        turn.rawReasoning.append(delta)
        recomputeReasoning(turn)
        touch(turn)
    }

    private fun recomputeReasoning(turn: MutableTurn) {
        val fromEvents = ChatTextSanitizer.sanitize(turn.rawReasoning.toString())
        turn.protocolRedacted = turn.protocolRedacted || fromEvents.protocolRedacted
        val pieces = buildList {
            if (turn.extractedThink.isNotBlank()) add(turn.extractedThink)
            if (fromEvents.text.isNotBlank()) add(fromEvents.text)
            if (fromEvents.reasoning.isNotBlank() && fromEvents.reasoning != turn.extractedThink) {
                add(fromEvents.reasoning)
            }
        }
        turn.reasoning = SecretRedactor.redactStreamChunk(pieces.joinToString("\n\n"), 20000)
    }

    @Synchronized
    fun addToolCall(turnId: String, callId: String, name: String, args: Map<String, Any?>) {
        val turn = turns[turnId] ?: return
        val existing = turn.toolCalls[callId]
        val pretty = prettyArgs(args)
        if (existing == null) {
            val call = MutableToolCall(callId, name, pretty)
            call.revision = revision + 1
            turn.toolCalls[callId] = call
        } else {
            existing.name = name
            existing.args = pretty
            existing.revision += 1
        }
        touch(turn)
    }

    @Synchronized
    fun addToolResult(turnId: String, callId: String, ok: Boolean, summary: String) {
        val turn = turns[turnId] ?: return
        val call = turn.toolCalls[callId] ?: MutableToolCall(
            id = callId,
            name = "tool",
            args = "",
        ).also { turn.toolCalls[callId] = it }
        call.status = if (ok) ToolCallStatus.SUCCESS else ToolCallStatus.FAILED
        call.resultSummary = SecretRedactor.redact(summary, 4000)
        // BrainEvent.ToolResult carries the human summary; the engine already
        // keeps full data out of the event. Do not invent a detail body.
        call.resultDetail = null
        call.revision += 1
        touch(turn)
    }

    @Synchronized
    fun cancelRunningTools(turnId: String) {
        val turn = turns[turnId] ?: return
        var changed = false
        turn.toolCalls.values.forEach { call ->
            if (call.status == ToolCallStatus.RUNNING) {
                call.status = ToolCallStatus.CANCELLED
                call.revision += 1
                changed = true
            }
        }
        if (changed) touch(turn)
    }

    /**
     * 阶段 3（U1）：记录大脑返回的用量。Usage 事件可能在 Done 之前
     * 到达；只存最后一次返回的数字，绝不估算、绝不补零。
     * 注意：只存数、不决定显示——显示由界面按说话者（speakerIsBrain）
     * 和有无数字来定；Agent 轮次即使误收到用量也不显示。
     */
    @Synchronized
    fun setUsage(turnId: String, inputTokens: Int?, outputTokens: Int?) {
        val turn = turns[turnId] ?: return
        turn.usage = TurnUsage(inputTokens, outputTokens)
        touch(turn)
    }

    /**
     * 大脑/Agent 边界（maintainer 2026-11-12）：给一轮挂任务归属。Agent 的
     * 每条回复必须带任务编号/标题，不许裸贴进无关对话。taskId 为空时
     * 清掉归属（本轮不属于任何任务）。
     */
    @Synchronized
    fun setTaskAttribution(turnId: String, taskId: String?, taskTitle: String) {
        val turn = turns[turnId] ?: return
        turn.taskAttribution = taskId?.takeIf { it.isNotBlank() }?.let {
            TurnTaskAttribution(taskId = it, taskTitle = taskTitle)
        }
        touch(turn)
    }

    /**
     * 大脑/Agent 边界（maintainer 2026-11-12）：给一轮贴持久的路线/分类标注
     * （如直连、意图不明）。与 appendStatusNote 的区别：不被进度 ticker
     * 覆盖，轮次结束后仍在。空串 = 不标。
     */
    @Synchronized
    fun setRouteLabel(turnId: String?, label: String) {
        val target = turnId?.let { turns[it] } ?: turns.values.lastOrNull() ?: return
        if (label.isBlank()) return
        target.routeLabel = SecretRedactor.redact(label, 4000)
        touch(target)
    }

    /**
     * 阶段 3（U1）：从快照恢复一轮（终态，不重跑）。只用于默认会
     * 话恢复；恢复出的轮次 id 重新编号，不与现有轮次冲突。
     *
     * 大脑/Agent 边界（maintainer 2026-11-12）：恢复必须带说话者（speaker /
     * speakerIsBrain）与任务归属——恢复出的轮次不许回落到含混的
     * “AGENT”；说话者空时由调用方按对话方补（见
     * ChatViewModel.appendRestoredTurns）。
     */
    @Synchronized
    fun restoreTurn(
        userText: String,
        assistantText: String,
        status: TurnStatus,
        error: String?,
        speaker: String = "",
        speakerIsBrain: Boolean = true,
        taskAttribution: TurnTaskAttribution? = null,
        routeLabel: String? = null,
    ): String {
        nextTurnId += 1
        val id = "turn-$nextTurnId"
        val turn = MutableTurn(id, userText, System.currentTimeMillis())
        if (assistantText.isNotEmpty()) {
            turn.rawAssistant.append(assistantText)
            turn.assistantText = assistantText
        }
        turn.status = status
        turn.error = error
        turn.speaker = speaker
        turn.speakerIsBrain = speakerIsBrain
        turn.taskAttribution = taskAttribution
        turn.routeLabel = routeLabel
        turn.revision = revision + 1
        turns[id] = turn
        revision += 1
        return id
    }

    @Synchronized
    fun finishTurn(
        turnId: String,
        status: TurnStatus,
        error: String? = null,
    ) {
        val turn = turns[turnId] ?: return
        turn.status = status
        if (!error.isNullOrBlank()) turn.error = SecretRedactor.redact(error, 4000)
        // V5 C2-3-R1b（v6-app.js:510）：占位终态处理。出错且正文为空时，
        // 占位改写为错误原文（已脱敏），error 字段清掉，避免错误卡重复；
        // 其他终态（空响应/取消/停止等）占位直接清除，永不持久化。
        if (turn.placeholder != null) {
            if (status == TurnStatus.ERROR && turn.assistantText.isBlank()) {
                turn.assistantText = turn.error ?: ""
                turn.error = null
            }
            turn.placeholder = null
        }
        cancelRunningToolsLocked(turn)
        running = false
        finishedAtMillis = System.currentTimeMillis()
        touch(turn)
    }

    private fun cancelRunningToolsLocked(turn: MutableTurn) {
        turn.toolCalls.values.forEach { call ->
            if (call.status == ToolCallStatus.RUNNING) {
                call.status = ToolCallStatus.CANCELLED
                call.revision += 1
            }
        }
    }

    private fun touch(turn: MutableTurn) {
        revision += 1
        turn.revision = revision
    }

    @Synchronized
    fun snapshot(): ChatUiState = ChatUiState(
        turns = turns.values.map { turn ->
            ChatTurnUi(
                id = turn.id,
                userText = turn.userText,
                assistantText = turn.assistantText,
                reasoning = turn.reasoning,
                protocolRedacted = turn.protocolRedacted,
                toolCalls = turn.toolCalls.values.map { call ->
                    ChatToolCallUi(
                        id = call.id,
                        name = call.name,
                        args = call.args,
                        status = call.status,
                        resultSummary = call.resultSummary,
                        resultDetail = call.resultDetail,
                        revision = call.revision,
                    )
                },
                notes = turn.notes.toList(),
                status = turn.status,
                error = turn.error,
                revision = turn.revision,
                placeholder = turn.placeholder,
                statusNote = turn.statusNote,
                attachments = turn.attachments.values.toList(),
                usage = turn.usage,
                speaker = turn.speaker,
                speakerIsBrain = turn.speakerIsBrain,
                taskAttribution = turn.taskAttribution,
                routeLabel = turn.routeLabel,
            )
        },
        running = running,
        startedAtMillis = startedAtMillis,
        finishedAtMillis = finishedAtMillis,
        brainLabel = brainLabel,
        revision = revision,
    )

    private fun prettyArgs(args: Map<String, Any?>): String {
        if (args.isEmpty()) return "{}"
        return try {
            SecretRedactor.redact(JSONObject(args).toString(2), 6000)
        } catch (_: Exception) {
            SecretRedactor.redact(
                args.entries.joinToString(",\n") { (key, value) -> "\"$key\": $value" },
                6000,
            )
        }
    }
}
