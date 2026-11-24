package wherevercode.agent.ui

import android.content.Context
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import androidx.appcompat.app.AlertDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject
import wherevercode.agent.R
import wherevercode.agent.core.platform.LocalPlatform

/**
 * 派单任务 UI（spec §7，W10/W11）：频道页「任务」分段里的派单任务
 * 列表数据、派单表单与详情对话框。数据走 App 内嵌的本地任务服务
 * （[LocalPlatform] 的 LocalChannelService 以本机 App 身份分发
 * owner 工具）；无权按钮不渲染，服务层再校验一次。状态标签用
 * 服务返回的 state_label（与 transitions.json 一致）。
 */
data class DispatchTaskItem(
    val taskId: String,
    val title: String,
    val goal: String,
    val state: String,
    val stateLabel: String,
    val round: Int,
    val maxRounds: Int,
    val version: Int,
    val assigneeId: String,
    val assigneeName: String,
    val reviewerId: String?,
    val reviewerName: String?,
    val claimedByName: String?,
    val summary: String?,
    val branch: String?,
    val commit: String?,
    val prUrl: String?,
    val verdict: String?,
    val feedback: String?,
    val channelId: String?,
    val deletedAt: Double?,
    val events: List<DispatchTaskEventItem>,
) {
    val isTerminal: Boolean
        get() = state == "approved" || state == "done" || state == "cancelled"
    val canWithdraw: Boolean
        get() = state == "queued" || state == "claimed" || state == "reviewing"
    val canEdit: Boolean get() = state == "queued" || state == "claimed"
    val canDecide: Boolean get() = state == "needs_decision"
    val canReopen: Boolean get() = isTerminal
    val canDelete: Boolean get() = isTerminal && deletedAt == null

    companion object {
        fun parse(view: JSONObject): DispatchTaskItem {
            fun optStr(key: String): String? =
                if (view.isNull(key)) null else view.optString(key).takeIf { it.isNotEmpty() }
            val events = mutableListOf<DispatchTaskEventItem>()
            val rawEvents = view.optJSONArray("events")
            if (rawEvents != null) {
                for (i in 0 until rawEvents.length()) {
                    val e = rawEvents.optJSONObject(i) ?: continue
                    events += DispatchTaskEventItem(
                        who = e.optString("who"),
                        kind = e.optString("kind"),
                        text = e.optString("text"),
                        at = e.optDouble("at", 0.0),
                    )
                }
            }
            return DispatchTaskItem(
                taskId = view.optString("task_id"),
                title = view.optString("title"),
                goal = view.optString("goal"),
                state = view.optString("state"),
                stateLabel = view.optString("state_label", view.optString("state")),
                round = view.optInt("round", 1),
                maxRounds = view.optInt("max_rounds", 3),
                version = view.optInt("version", 1),
                assigneeId = view.optString("assignee_id"),
                assigneeName = view.optString("assignee_name"),
                reviewerId = optStr("reviewer_id"),
                reviewerName = optStr("reviewer_name"),
                claimedByName = optStr("claimed_by_name"),
                summary = optStr("summary"),
                branch = optStr("branch"),
                commit = optStr("commit"),
                prUrl = optStr("pr_url"),
                verdict = optStr("verdict"),
                feedback = optStr("feedback"),
                channelId = optStr("channel_id"),
                deletedAt = if (view.isNull("deleted_at")) null else view.optDouble("deleted_at"),
                events = events,
            )
        }
    }
}

data class DispatchTaskEventItem(
    val who: String,
    val kind: String,
    val text: String,
    val at: Double,
)

/** 本地任务服务客户端（本机 App 身份 = owner 作用域）。 */
object DispatchTaskClient {
    fun available(): Boolean = LocalPlatform.serviceOrNull() != null

    fun call(tool: String, args: JSONObject): JSONObject {
        val service = LocalPlatform.serviceOrNull()
            ?: throw IllegalStateException("本地任务服务未启动")
        return service.dispatch(
            tool, args, LocalPlatform.APP_AGENT_ID, LocalPlatform.APP_AGENT_NAME,
        )
    }

    fun listChannelTasks(channelId: String): List<DispatchTaskItem> {
        val result = call("list_tasks", JSONObject().put("limit", 200))
        val raw = result.optJSONArray("tasks") ?: return emptyList()
        val out = mutableListOf<DispatchTaskItem>()
        for (i in 0 until raw.length()) {
            val item = DispatchTaskItem.parse(raw.optJSONObject(i) ?: continue)
            if (item.channelId == channelId) out += item
        }
        return out
    }

    fun getTask(taskId: String): DispatchTaskItem {
        val result = call(
            "get_task",
            JSONObject().put("task_id", taskId).put("include_events", true),
        )
        return DispatchTaskItem.parse(result.optJSONObject("task") ?: JSONObject())
    }

    fun deleteTask(taskId: String) {
        val service = LocalPlatform.serviceOrNull()
            ?: throw IllegalStateException("本地任务服务未启动")
        service.uiDeleteTask(taskId)
    }
}

private fun dispatchTimeText(ts: Double): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date((ts * 1000).toLong()))

/** 派单表单（spec §7：选 Agent 必选、审查人可选、项目、goal、绑当前频道）。 */
fun showCreateDispatchTaskDialog(
    context: Context,
    channelId: String,
    agents: List<Pair<String, String>>,
    onDone: (String?) -> Unit,
) {
    val content = UiKit.vertical(context).apply {
        setPadding(UiKit.dp(context, 20), UiKit.dp(context, 8), UiKit.dp(context, 20), UiKit.dp(context, 16))
    }
    fun label(text: String) = UiKit.text(context, text, 13f, R.color.text_primary, bold = true).apply {
        setPadding(0, UiKit.dp(context, 14), 0, UiKit.dp(context, 6))
    }
    val agentInput = Spinner(context).apply {
        adapter = ArrayAdapter(
            context, android.R.layout.simple_spinner_dropdown_item,
            agents.map { it.second },
        )
    }
    val reviewerInput = Spinner(context).apply {
        adapter = ArrayAdapter(
            context, android.R.layout.simple_spinner_dropdown_item,
            listOf("不指定") + agents.map { it.second },
        )
    }
    val projectInput = EditText(context).apply { hint = "项目标识" }
    val goalInput = EditText(context).apply {
        hint = "任务目标与验收要求"
        minLines = 3
        maxLines = 8
    }
    val error = UiKit.text(context, "", 12f, R.color.danger)
    content.addView(label("执行 Agent"))
    content.addView(agentInput)
    content.addView(label("审查人（可选）"))
    content.addView(reviewerInput)
    content.addView(label("项目"))
    content.addView(projectInput)
    content.addView(label("任务目标"))
    content.addView(goalInput)
    content.addView(error)
    val dialog = AlertDialog.Builder(context).setTitle("派单")
        .setView(ScrollView(context).apply { addView(content) })
        .setPositiveButton("派出", null).setNegativeButton("取消", null).create()
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val agent = agents.getOrNull(agentInput.selectedItemPosition)
            val project = projectInput.text.toString().trim()
            val goal = goalInput.text.toString().trim()
            if (agent == null) {
                error.text = "请选择执行 Agent"
                return@setOnClickListener
            }
            if (project.isEmpty()) {
                projectInput.error = "请填写项目标识"
                return@setOnClickListener
            }
            if (goal.isEmpty()) {
                goalInput.error = "请填写任务目标"
                return@setOnClickListener
            }
            try {
                val args = JSONObject()
                    .put("agent", agent.first)
                    .put("project_id", project)
                    .put("goal", goalInput.text.toString())
                    .put("channel_id", channelId)
                val reviewer = agents.getOrNull(reviewerInput.selectedItemPosition - 1)
                if (reviewer != null) args.put("reviewer", reviewer.first)
                DispatchTaskClient.call("start_task", args)
                dialog.dismiss()
                onDone(null)
            } catch (e: Exception) {
                error.text = e.message ?: "派单失败"
            }
        }
    }
    dialog.show()
}

/** 任务详情（交付信息 + 最近动态 + 权限矩阵操作）。 */
fun showDispatchTaskDetailDialog(
    context: Context,
    taskId: String,
    agents: List<Pair<String, String>>,
    onChanged: () -> Unit,
) {
    val task = try {
        DispatchTaskClient.getTask(taskId)
    } catch (e: Exception) {
        AlertDialog.Builder(context).setTitle("任务详情")
            .setMessage(e.message ?: "加载失败")
            .setPositiveButton("好", null).show()
        return
    }
    val content = UiKit.vertical(context).apply {
        setPadding(UiKit.dp(context, 20), UiKit.dp(context, 8), UiKit.dp(context, 20), UiKit.dp(context, 16))
    }
    fun line(text: String, size: Float = 13f, color: Int = R.color.text_secondary, bold: Boolean = false) =
        UiKit.text(context, text, size, color, bold = bold).apply {
            setPadding(0, UiKit.dp(context, 3), 0, 0)
        }
    content.addView(line(task.title, 15f, R.color.text_primary, bold = true))
    content.addView(line("状态：${task.stateLabel} · 执行：${task.assigneeName}"))
    if (task.reviewerName != null) content.addView(line("审查：${task.reviewerName}"))
    if (task.claimedByName != null) content.addView(line("当前持有人：${task.claimedByName}"))
    content.addView(line("轮次：第 ${task.round}/${task.maxRounds} 轮 · 版本 v${task.version}"))
    content.addView(line(task.goal, 13f, R.color.text_primary))
    if (task.summary != null || task.branch != null || task.commit != null || task.prUrl != null) {
        content.addView(line("交付", 13f, R.color.text_primary, bold = true))
        if (task.summary != null) content.addView(line(task.summary))
        if (task.branch != null) content.addView(line("分支：${task.branch}"))
        if (task.commit != null) content.addView(line("提交：${task.commit}"))
        if (task.prUrl != null) content.addView(line("PR：${task.prUrl}"))
        if (task.verdict != null) content.addView(line("审查结论：${task.verdict}"))
        if (task.feedback != null) content.addView(line(task.feedback))
    }
    val reasonInput = EditText(context).apply {
        hint = "理由（验收/取消必填，撤回可选）"
    }
    if (task.canDecide || task.canWithdraw) content.addView(reasonInput)
    if (task.events.isNotEmpty()) {
        content.addView(line("最近动态", 13f, R.color.text_primary, bold = true))
        for (event in task.events) {
            content.addView(line(event.text, 12f, R.color.text_primary))
            content.addView(line("${event.who} · ${dispatchTimeText(event.at)}", 10f, R.color.text_tertiary))
        }
    }
    val error = UiKit.text(context, "", 12f, R.color.danger)
    content.addView(error)

    val builder = AlertDialog.Builder(context).setTitle("任务详情")
        .setView(ScrollView(context).apply { addView(content) })
        .setNegativeButton("关闭", null)
    if (task.canDecide) {
        builder.setNeutralButton("验收通过", null)
        builder.setPositiveButton("取消任务", null)
    }
    val dialog = builder.create()
    if (task.canDecide) {
        // 失败时不关窗（理由必填等错误要留在窗内给用户看，同 CreateTaskDialog 口径）。
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                if (runDispatchAction(error) {
                        DispatchTaskClient.call(
                            "decide_task",
                            JSONObject().put("task_id", task.taskId)
                                .put("action", "done").put("reason", reasonInput.text.toString()),
                        )
                        onChanged()
                    }
                ) {
                    dialog.dismiss()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (runDispatchAction(error) {
                        DispatchTaskClient.call(
                            "decide_task",
                            JSONObject().put("task_id", task.taskId)
                                .put("action", "cancel").put("reason", reasonInput.text.toString()),
                        )
                        onChanged()
                    }
                ) {
                    dialog.dismiss()
                }
            }
        }
    }
    // 撤回 / 编辑 / 重开 / 删除走详情内的行内按钮（AlertDialog 按钮位不够）。
    val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    fun actionButton(text: String, action: () -> Unit) {
        val button = UiKit.text(context, text, 12f, R.color.text_primary, bold = true).apply {
            setBackgroundResource(R.drawable.bg_chip)
            setPadding(
                UiKit.dp(context, 10), UiKit.dp(context, 6),
                UiKit.dp(context, 10), UiKit.dp(context, 6),
            )
            setOnClickListener { action() }
        }
        actions.addView(button)
    }
    if (task.canWithdraw) {
        actionButton("撤回任务") {
            runDispatchAction(error) {
                val args = JSONObject().put("task_id", task.taskId)
                val reason = reasonInput.text.toString().trim()
                if (reason.isNotEmpty()) args.put("reason", reason)
                DispatchTaskClient.call("withdraw_task", args)
                dialog.dismiss()
                onChanged()
            }
        }
    }
    if (task.canEdit) {
        actionButton("编辑/改派") {
            dialog.dismiss()
            showEditDispatchTaskDialog(context, task, agents, onChanged)
        }
    }
    if (task.canReopen) {
        actionButton("重开任务") {
            runDispatchAction(error) {
                DispatchTaskClient.call(
                    "reopen_task", JSONObject().put("task_id", task.taskId),
                )
                dialog.dismiss()
                onChanged()
            }
        }
    }
    if (task.canDelete) {
        actionButton("删除任务") {
            AlertDialog.Builder(context).setTitle("删除这个任务？")
                .setMessage("删除后列表不再显示，账本保留墓碑。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除") { _, _ ->
                    runDispatchAction(error) {
                        DispatchTaskClient.deleteTask(task.taskId)
                        dialog.dismiss()
                        onChanged()
                    }
                }.show()
        }
    }
    if (actions.childCount > 0) content.addView(actions)
    dialog.show()
}

private fun runDispatchAction(errorView: android.widget.TextView, body: () -> Unit): Boolean =
    try {
        body()
        true
    } catch (e: Exception) {
        errorView.text = e.message ?: "操作失败"
        false
    }

/** 编辑 / 改派（expected_version 乐观锁，改派自动释放原执行人）。 */
fun showEditDispatchTaskDialog(
    context: Context,
    task: DispatchTaskItem,
    agents: List<Pair<String, String>>,
    onChanged: () -> Unit,
) {
    val content = UiKit.vertical(context).apply {
        setPadding(UiKit.dp(context, 20), UiKit.dp(context, 8), UiKit.dp(context, 20), UiKit.dp(context, 16))
    }
    fun label(text: String) = UiKit.text(context, text, 13f, R.color.text_primary, bold = true).apply {
        setPadding(0, UiKit.dp(context, 14), 0, UiKit.dp(context, 6))
    }
    val titleInput = EditText(context).apply { setText(task.title) }
    val goalInput = EditText(context).apply {
        setText(task.goal)
        minLines = 3
        maxLines = 8
    }
    val agentInput = Spinner(context).apply {
        adapter = ArrayAdapter(
            context, android.R.layout.simple_spinner_dropdown_item,
            agents.map { it.second },
        )
        setSelection(agents.indexOfFirst { it.first == task.assigneeId }.coerceAtLeast(0))
    }
    val reviewerInput = Spinner(context).apply {
        adapter = ArrayAdapter(
            context, android.R.layout.simple_spinner_dropdown_item,
            listOf("不指定") + agents.map { it.second },
        )
        setSelection((agents.indexOfFirst { it.first == task.reviewerId } + 1).coerceAtLeast(0))
    }
    val error = UiKit.text(context, "", 12f, R.color.danger)
    content.addView(label("标题"))
    content.addView(titleInput)
    content.addView(label("任务目标"))
    content.addView(goalInput)
    content.addView(label("执行 Agent"))
    content.addView(agentInput)
    content.addView(label("审查人"))
    content.addView(reviewerInput)
    content.addView(error)
    val dialog = AlertDialog.Builder(context).setTitle("编辑 / 改派")
        .setView(ScrollView(context).apply { addView(content) })
        .setPositiveButton("保存", null).setNegativeButton("取消", null).create()
    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                val args = JSONObject()
                    .put("task_id", task.taskId)
                    .put("expected_version", task.version)
                if (titleInput.text.toString() != task.title) {
                    args.put("title", titleInput.text.toString())
                }
                if (goalInput.text.toString() != task.goal) {
                    args.put("goal", goalInput.text.toString())
                }
                val agent = agents.getOrNull(agentInput.selectedItemPosition)
                if (agent != null && agent.first != task.assigneeId) {
                    args.put("agent", agent.first)
                }
                val reviewer = agents.getOrNull(reviewerInput.selectedItemPosition - 1)
                if ((reviewer?.first) != task.reviewerId) {
                    if (reviewer == null) args.put("reviewer", JSONObject.NULL)
                    else args.put("reviewer", reviewer.first)
                }
                DispatchTaskClient.call("update_task", args)
                dialog.dismiss()
                onChanged()
            } catch (e: Exception) {
                error.text = e.message ?: "保存失败"
            }
        }
    }
    dialog.show()
}
