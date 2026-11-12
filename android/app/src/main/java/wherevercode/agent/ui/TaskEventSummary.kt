package wherevercode.agent.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import wherevercode.agent.brain.EngineEvent
import wherevercode.agent.R
import wherevercode.agent.core.security.SecretRedactor
import wherevercode.agent.core.task.KanbanStatus
import wherevercode.agent.core.task.TaskEvent
import wherevercode.agent.core.task.TaskEventKind

/** User-facing event copy; raw event fields remain available on demand. */
data class TaskEventSummary(val title: String, val summary: String = "")

object TaskEventSummaryMapper {
    /**
     * 全部真实存在的 kind（以 BrainEngine.log()、BrainBootstrap 与
     * MainActivity WS feed 的实际调用点为准，2026-11-11 核对）：
     * turn_done、workspace_recovery、user_message、tools_unused、
     * brain_switched、error、control_routed、tool_result、
     * tool_result_cached、empty_workspace_no_read、failover_retry、
     * cache_hit_denied、ask_user_awaiting、ask_user_invalid、
     * web_search_failed、remote_agent_event、tool_arg_repaired、
     * tool_call_repeated、sse_malformed_line。
     *
     * 脱敏：BrainEngine.log() 写日志时已过 SecretRedactor（2000 字），
     * BrainBootstrap 的 sse_malformed_line 写时也已脱敏（500 字）；
     * 只有 MainActivity 的 remote_agent_event 是直接写 eventLog、
     * 未过脱敏——mapper 这里对全部 detail 派生的摘要再脱敏一次，
     * 兜底。error 类摘要取原因首句，不再只给“去看详情”。
     * 未知 kind 不再显示光秃“执行记录”：标题直接用 kind 原文，
     * 摘要取 detail 首行截 120 字；detail 为空则摘要为空，
     * 由事件分组当作“无实质内容”过滤（见 EventLogGrouping）。
     */
    fun from(event: EngineEvent): TaskEventSummary = when (event.kind) {
        "turn_done" -> {
            val toolCount = Regex("(?:^|\\s)tools=(\\d+)").find(event.detail)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
            val switched = Regex("(?:^|\\s)switched=(true|false)").find(event.detail)
                ?.groupValues?.getOrNull(1) == "true"
            val parts = mutableListOf<String>()
            toolCount?.let { parts += if (it == 0) "本轮未调用工具" else "调用了 $it 项工具" }
            parts += if (switched) "本轮切换了模型" else "使用当前模型"
            TaskEventSummary("回复已完成", parts.joinToString(" · "))
        }
        "workspace_recovery" -> {
            val removed = number("removed", event.detail)
            val kept = number("kept", event.detail)
            val lockAvailable = Regex("(?:^|\\s)lock_available=(true|false)")
                .find(event.detail)?.groupValues?.getOrNull(1) == "true"
            val summary = if (!lockAvailable) {
                "未能取得工作区锁，过期执行目录未清理。"
            } else {
                buildList {
                    if (removed != null) add("清理了 $removed 个过期执行目录")
                    if (kept != null) add("保留 $kept 个目录")
                }.joinToString(" · ").ifBlank { "检查已完成" }
            }
            TaskEventSummary("工作区检查", summary)
        }
        "user_message" -> TaskEventSummary("已发送请求", firstLine(event.detail))
        "tools_unused" -> TaskEventSummary("本轮未调用工具", firstLine(event.detail))
        "brain_switched" -> TaskEventSummary("已切换模型", firstLine(event.detail))
        "error" -> TaskEventSummary("执行遇到问题", firstSentence(event.detail))
        "control_routed" -> TaskEventSummary("控制指令已处理", firstLine(event.detail))
        "tool_result" -> TaskEventSummary("工具步骤已返回", firstLine(event.detail))
        "tool_result_cached" -> TaskEventSummary("工具步骤已返回（缓存）", firstLine(event.detail))
        "empty_workspace_no_read" -> TaskEventSummary("工作区为空", firstLine(event.detail))
        "failover_retry" -> TaskEventSummary("正在重试", firstLine(event.detail))
        "cache_hit_denied" -> TaskEventSummary("缓存未采用", firstLine(event.detail))
        "ask_user_awaiting" -> TaskEventSummary("等待用户回答", firstLine(event.detail))
        "ask_user_invalid" -> TaskEventSummary("回答未被接受", firstLine(event.detail))
        "web_search_failed" -> TaskEventSummary("联网搜索失败", firstLine(event.detail))
        "remote_agent_event" -> TaskEventSummary("远端动态", firstLine(event.detail))
        "tool_arg_repaired" -> TaskEventSummary("工具参数已补全", firstLine(event.detail))
        "tool_call_repeated" -> TaskEventSummary("重复调用已拦截", firstLine(event.detail))
        "sse_malformed_line" -> TaskEventSummary("收到畸形数据行", firstLine(event.detail))
        else -> TaskEventSummary(event.kind, firstLine(event.detail))
    }

    private fun number(key: String, detail: String): Int? =
        Regex("(?:^|\\s)$key=(\\d+)").find(detail)?.groupValues?.getOrNull(1)?.toIntOrNull()

    /** detail 首个非空行，脱敏后取前 120 字；空白 detail 返回空串。 */
    private fun firstLine(detail: String, limit: Int = 120): String {
        val line = detail.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (line.isBlank()) return ""
        return SecretRedactor.redact(line).take(limit)
    }

    /**
     * detail 首句（按 。！？与换行切），脱敏后取前 120 字；
     * 无可用首句时回落到“可展开记录详情查看原因。”。
     */
    private fun firstSentence(detail: String, limit: Int = 120): String {
        val sentence = detail.split('。', '！', '？', '\n')
            .firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (sentence.isBlank()) return "可展开记录详情查看原因。"
        return SecretRedactor.redact(sentence).take(limit)
    }
}

fun taskEventRow(context: Context, event: EngineEvent): View {
    val summary = TaskEventSummaryMapper.from(event)
    val dp: (Int) -> Int = { UiKit.dp(context, it) }
    val card = UiKit.vertical(context).apply {
        setBackgroundResource(R.drawable.bg_system_note)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(5) }
    }
    card.addView(UiKit.text(
        context,
        "${UiKit.shortTime(event.epochSeconds)} · ${summary.title}",
        11f,
        R.color.text_secondary,
        bold = true,
    ))
    if (summary.summary.isNotBlank()) {
        card.addView(UiKit.text(context, summary.summary, 10f, R.color.text_tertiary))
    }
    val toggle = UiKit.text(context, "查看记录详情", 10f, R.color.accent).apply {
        setPadding(0, dp(5), 0, 0)
    }
    val raw = UiKit.text(context, "${event.kind}\n${event.detail}", 10f, R.color.text_tertiary, mono = true).apply {
        visibility = View.GONE
        setTextIsSelectable(true)
        setPadding(0, dp(5), 0, 0)
    }
    toggle.setOnClickListener {
        raw.visibility = if (raw.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        toggle.text = if (raw.visibility == View.VISIBLE) "收起记录详情" else "查看记录详情"
    }
    card.addView(toggle)
    card.addView(raw)
    return card
}

/**
 * 任务状态轨迹行（P0 审计事件）：时间 · 事件中文名 · 从态 → 到态（+ 原因）。
 * 与 taskEventRow（大脑轮次事件）并列：控制台 LOGS 模块下半段与任务
 * 详情页“状态轨迹”区用它；原始字段（kind/from/to/attemptId/actor）
 * 可展开查看。审计事件是持久的（Room），重启仍在。
 */
fun taskStateEventRow(context: Context, event: TaskEvent): View {
    val dp: (Int) -> Int = { UiKit.dp(context, it) }
    val card = UiKit.vertical(context).apply {
        setBackgroundResource(R.drawable.bg_system_note)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(5) }
    }
    val title = buildString {
        append(TaskEventKind.labelOf(event.kind))
        val from = event.fromState?.let { KanbanStatus.labelOf(it) }
        val to = KanbanStatus.labelOf(event.toState)
        if (from != null) append("：$from → $to") else append(" → $to")
    }
    card.addView(UiKit.text(
        context,
        "${UiKit.shortTime(event.createdAtEpochSeconds)} · $title",
        11f,
        R.color.text_secondary,
        bold = true,
    ))
    event.reason?.takeIf { it.isNotBlank() }?.let { reason ->
        card.addView(UiKit.text(context, reason, 10f, R.color.text_tertiary))
    }
    val toggle = UiKit.text(context, "查看记录详情", 10f, R.color.accent).apply {
        setPadding(0, dp(5), 0, 0)
    }
    val rawDetail = buildString {
        append(event.kind)
        append("\n${event.fromState ?: "—"} -> ${event.toState}")
        event.attemptId?.let { append("\nattemptId=$it") }
        event.actor?.let { append("\nactor=$it") }
        append("\n${event.taskId}")
    }
    val raw = UiKit.text(context, rawDetail, 10f, R.color.text_tertiary, mono = true).apply {
        visibility = View.GONE
        setTextIsSelectable(true)
        setPadding(0, dp(5), 0, 0)
    }
    toggle.setOnClickListener {
        raw.visibility = if (raw.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        toggle.text = if (raw.visibility == View.VISIBLE) "收起记录详情" else "查看记录详情"
    }
    card.addView(toggle)
    card.addView(raw)
    return card
}

/**
 * 任务状态中文显示。唯一事实来源是 [KanbanStatus.labelOf]（主流 Kanban
 * 映射：queued→待办、assigned/working→进行中、review→待审核、
 * blocked→已阻塞、completed→已完成；failed/cancelled 是终态）。
 * 之前这里没覆盖 review / blocked / assigned，三者都掉进“处理中”，
 * 待审核与已阻塞在界面上看不见（2026-11-11 主流补齐修）。
 */
fun taskStateLabel(state: String): String = KanbanStatus.labelOf(state)
