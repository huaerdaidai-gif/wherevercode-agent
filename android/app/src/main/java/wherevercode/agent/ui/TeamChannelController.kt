package wherevercode.agent.ui

import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import wherevercode.agent.R
import wherevercode.agent.core.agent.ChannelConnectionState
import wherevercode.agent.core.agent.PlatformChannelMessage
import wherevercode.agent.core.team.FanoutBranchStatus
import wherevercode.agent.core.team.FanoutRun
import wherevercode.agent.core.team.TeamChannelMember
import wherevercode.agent.core.team.TeamChatLimits
import wherevercode.agent.core.team.TeamChatRules
import wherevercode.agent.core.team.TeamThread
import wherevercode.agent.core.team.TeamThreadMessage
import wherevercode.agent.core.team.groupTeamMessages
import wherevercode.agent.core.team.teamMessageFromChannel
import wherevercode.agent.core.team.teamThreadTitle

/** 频道页快照（MainActivity 在后台线程组装；本控制器不读 store、不造数据）。 */
data class TeamChannelSnapshot(
    val configured: Boolean,
    val setupHint: String,
    val channelId: String,
    val connectionState: ChannelConnectionState,
    val connectionError: String?,
    val members: List<TeamChannelMember>,
    val messages: List<PlatformChannelMessage>,
    val unreadCount: Int,
    val unreadCapped: Boolean,
    val roundsUsed: Int,
    val fanoutRuns: List<FanoutRun>,
    val fanoutRunning: Boolean,
    val notice: String?,
    /** 2026-11-13 频道权限：可代人署名的发帖者（App 自己），见 teamMessageFromChannel。 */
    val trustedAttributionPosterIds: Set<String> = setOf(wherevercode.agent.core.team.TeamVoice.COORDINATOR_ID),
)

/**
 * V5 团队频道页（P3，2026-11-11）：有界团队聊天 + 只读扇出。
 *
 * 结构与 [ConsoleScreenController] 同款：控制器 + `root` + `render()`，
 * `MainActivity` 只保留路由与数据组装。固定头建一次；`render()` 只
 * 重建其下的消息区与扇出区；底部输入栏固定。
 *
 * 有界（写进界面，不只写进文档）：
 * - 成员是注册表里的全部 Agent（maintainer 2026-11-11 22:19：全部拉入频道，
 *   不只 2–3 个）：本机 App 打头，其余按 Agent 页顺序列出；成员行可
 *   横滑，不怕人多。平台 Agent（Example Agent 等）是端点直连成员；设备/
 *   云端 Agent 由调度器当中转——频道里 `@名字` 叫它，调度器把消息
 *   转到它的 Hand/API，回复再贴回频道。
 * - 短消息：一条最多 500 字，超了发不出去，当场明说。
 * - 轮次上限 3 轮：用户每发一条消息计一轮，到顶后输入栏锁住，点
 *   “开始新一轮”才继续。
 * - 建议-only：页上没有任何改任务状态、认领、标记完成的入口——只有
 *   发消息、找某位成员 1:1 聊、只读扇出三个动作；想派活回一对一聊天
 *   走正常派活流程。
 * - 只读扇出 ≤5：一次最多问 5 个分支，分支只回摘要，不并行写。
 *
 * 连接状态如实显示：WS 推送是“直播”，断了是“轮询兜底”+ 原因，
 * 绝不把兜底说成直播。没有真实数据就明标“暂无……”，绝不造假。
 */
class TeamChannelController(
    private val context: Context,
    private val onOpenChatWithAgent: (agentId: String) -> Unit,
    private val onPost: (text: String) -> Unit,
    private val onMarkRead: () -> Unit,
    private val onNewRound: () -> Unit,
    private val onStartFanout: (question: String) -> Unit,
    private val onRefresh: () -> Unit,
) {
    val root: LinearLayout = UiKit.vertical(context).apply {
        visibility = View.GONE
    }

    private val scrollView: ScrollView = ScrollView(context).apply {
        isFillViewport = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        )
    }

    private val content: LinearLayout = UiKit.vertical(context).apply {
        setPadding(dp(16), dp(8), dp(16), dp(20))
    }

    /** 消息 + 扇出区容器：每次 render() 重建。固定头与输入栏不在其中。 */
    private val bodyContainer: LinearLayout = UiKit.vertical(context)

    private val connectionLine: TextView = UiKit.text(context, "", 11f, R.color.text_tertiary)
    private val roundLine: TextView = UiKit.text(context, "", 11f, R.color.text_tertiary)
    private val noticeLine: TextView = UiKit.text(context, "", 11f, R.color.text_tertiary)

    private val messageInput: EditText = EditText(context).apply {
        hint = "给频道发一条短消息（≤500 字），@名字 叫一位 Agent…"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        minLines = 1
        maxLines = 4
        setPadding(dp(10), dp(8), dp(10), dp(8))
    }

    private val fanoutInput: EditText = EditText(context).apply {
        hint = "问大家一个问题（只读：大家只看不写）…"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        minLines = 1
        maxLines = 4
        setPadding(dp(10), dp(8), dp(10), dp(8))
    }

    private var snapshot: TeamChannelSnapshot? = null

    init {
        root.addView(
            UiKit.text(context, "团队频道", 20f, R.color.text_primary, bold = true).apply {
                setPadding(dp(16), dp(12), dp(16), dp(2))
            },
        )
        root.addView(
            UiKit.text(
                context,
                "全部 Agent 都在频道里：本机 App + 注册表里的每一位；" +
                    "设备/云端 Agent 由调度器当中转——@名字 叫它，回复贴回频道。" +
                    "短消息、3 轮一议、建议-only——群消息绝不直接触发派活；" +
                    "只读扇出最多问 5 个分支，大家只看不写。",
                11f,
                R.color.text_tertiary,
            ).apply { setPadding(dp(16), 0, dp(16), dp(6)) },
        )
        connectionLine.setPadding(dp(16), 0, dp(16), dp(2))
        root.addView(connectionLine)
        roundLine.setPadding(dp(16), 0, dp(16), dp(2))
        root.addView(roundLine)
        noticeLine.setPadding(dp(16), 0, dp(16), dp(6))
        root.addView(noticeLine)
        scrollView.addView(content)
        content.addView(bodyContainer)
        root.addView(scrollView)
        root.addView(buildInputBar())
    }

    private fun buildInputBar(): LinearLayout {
        val bar = UiKit.vertical(context).apply {
            setPadding(dp(16), dp(8), dp(16), dp(12))
        }
        val sendRow = UiKit.horizontal(context)
        sendRow.addView(
            messageInput.apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        sendRow.addView(
            UiKit.text(context, "发送", 12f, R.color.accent, bold = true).apply {
                setBackgroundResource(R.drawable.bg_chip_accent)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener { sendMessage() }
            },
        )
        bar.addView(sendRow)
        val fanoutRow = UiKit.horizontal(context).apply { setPadding(0, dp(8), 0, 0) }
        fanoutRow.addView(
            fanoutInput.apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        fanoutRow.addView(
            UiKit.text(context, "只读问大家", 12f, R.color.accent, bold = true).apply {
                setBackgroundResource(R.drawable.bg_chip_accent)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener { startFanout() }
            },
        )
        bar.addView(fanoutRow)
        return bar
    }

    private fun sendMessage() {
        val snap = snapshot ?: return
        val text = messageInput.text.toString().trim()
        val error = TeamChatRules.validatePost(text, snap.roundsUsed)
        if (error != null) {
            noticeLine.text = error
            return
        }
        noticeLine.text = ""
        messageInput.setText("")
        onPost(text)
    }

    private fun startFanout() {
        val question = fanoutInput.text.toString().trim()
        if (question.isBlank()) {
            noticeLine.text = "先写一个问题再问大家。"
            return
        }
        noticeLine.text = ""
        fanoutInput.setText("")
        onStartFanout(question)
    }

    fun render(snapshot: TeamChannelSnapshot) {
        this.snapshot = snapshot
        connectionLine.text = when (snapshot.connectionState) {
            ChannelConnectionState.LIVE -> "● 直播推送（WS 已连）"
            ChannelConnectionState.CONNECTING -> "○ 正在连接频道…"
            ChannelConnectionState.POLLING ->
                "○ 轮询兜底（推送已断）" +
                    (snapshot.connectionError?.let { "：$it" } ?: "")
            ChannelConnectionState.STOPPED -> "○ 频道未连接"
        }
        roundLine.text = if (snapshot.roundsUsed >= TeamChatLimits.MAX_ROUNDS) {
            "已到 ${TeamChatLimits.MAX_ROUNDS} 轮上限，点“开始新一轮”继续"
        } else {
            "第 ${snapshot.roundsUsed + 1}/${TeamChatLimits.MAX_ROUNDS} 轮"
        }
        noticeLine.text = snapshot.notice.orEmpty()
        bodyContainer.removeAllViews()
        if (!snapshot.configured) {
            bodyContainer.addView(note(snapshot.setupHint))
            return
        }
        bodyContainer.addView(membersRow(snapshot))
        if (snapshot.unreadCount > 0) {
            bodyContainer.addView(unreadRow(snapshot))
        }
        if (snapshot.messages.isEmpty()) {
            bodyContainer.addView(note("频道里暂无消息。发一条“在吗”，看看谁回。"))
        } else {
            // 2026-11-12 团队对话分串：消息按任务成组/成串，一个任务
            // 的讨论不混进另一个任务里；每条带发送者 + 任务归属。
            // 归属头从正文解析（App 贴回的中转回复带的头），无头
            // 的消息进“综合讨论”串。
            val threads = groupTeamMessages(
                snapshot.messages.map { teamMessageFromChannel(it, snapshot.trustedAttributionPosterIds) },
            )
            threads.forEach { thread ->
                bodyContainer.addView(threadHeaderRow(thread))
                thread.messages.forEach { bodyContainer.addView(messageRow(it)) }
            }
        }
        bodyContainer.addView(newRoundRow())
        bodyContainer.addView(fanoutSection(snapshot))
    }

    private fun membersRow(snapshot: TeamChannelSnapshot): LinearLayout {
        val row = UiKit.horizontal(context).apply { setPadding(0, dp(4), 0, dp(8)) }
        row.addView(
            UiKit.text(context, "成员", 12f, R.color.text_primary, bold = true).apply {
                setPadding(0, 0, dp(8), 0)
            },
        )
        // 成员是全部 Agent（8 位以上也常见），芯片行放进横滑容器，不怕人多。
        val scroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            )
        }
        val chips = UiKit.horizontal(context)
        snapshot.members.forEach { member ->
            chips.addView(
                UiKit.text(
                    context,
                    (if (member.isLocalApp) "本机 App" else member.displayName),
                    12f,
                    R.color.text_primary,
                ).apply {
                    setBackgroundResource(R.drawable.bg_chip_accent)
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    setOnClickListener {
                        if (!member.isLocalApp) onOpenChatWithAgent(member.agentId)
                    }
                },
            )
        }
        scroller.addView(chips)
        row.addView(scroller)
        return row
    }

    private fun unreadRow(snapshot: TeamChannelSnapshot): LinearLayout {
        val row = UiKit.horizontal(context).apply { setPadding(0, 0, 0, dp(8)) }
        row.addView(
            UiKit.text(
                context,
                if (snapshot.unreadCapped) "50+ 条未读" else "${snapshot.unreadCount} 条未读",
                12f,
                R.color.accent,
                bold = true,
            ).apply { setPadding(0, 0, dp(10), 0) },
        )
        row.addView(
            UiKit.text(context, "标为已读", 12f, R.color.accent, bold = true).apply {
                setBackgroundResource(R.drawable.bg_chip_accent)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener { onMarkRead() }
            },
        )
        return row
    }

    /** 一个任务的讨论串头：任务编号 + 标题，无任务归属的写“综合讨论”。 */
    private fun threadHeaderRow(thread: TeamThread): LinearLayout {
        val row = UiKit.horizontal(context).apply { setPadding(0, dp(8), 0, dp(2)) }
        row.addView(
            UiKit.text(context, teamThreadTitle(thread), 12f, R.color.text_primary, bold = true),
        )
        return row
    }

    private fun messageRow(message: TeamThreadMessage): LinearLayout {
        val col = UiKit.vertical(context).apply { setPadding(0, 0, 0, dp(10)) }
        val time = formatTime(message.createdAtSeconds)
        col.addView(
            UiKit.text(context, "${message.senderName} · $time", 11f, R.color.text_tertiary, bold = true),
        )
        col.addView(UiKit.text(context, message.text, 13f, R.color.text_primary))
        return col
    }

    private fun newRoundRow(): LinearLayout {
        val row = UiKit.horizontal(context).apply { setPadding(0, dp(6), 0, dp(4)) }
        row.addView(
            UiKit.text(context, "开始新一轮", 12f, R.color.accent, bold = true).apply {
                setBackgroundResource(R.drawable.bg_chip_accent)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener { onNewRound() }
            },
        )
        row.addView(
            UiKit.text(context, "一轮最多 3 条消息；新一轮从头计。", 10f, R.color.text_tertiary).apply {
                setPadding(dp(10), 0, 0, 0)
            },
        )
        return row
    }

    private fun fanoutSection(snapshot: TeamChannelSnapshot): LinearLayout {
        val col = UiKit.vertical(context).apply { setPadding(0, dp(10), 0, 0) }
        col.addView(
            UiKit.text(context, "只读扇出", 14f, R.color.text_primary, bold = true),
        )
        col.addView(
            UiKit.text(
                context,
                "一次最多问 ${TeamChatLimits.MAX_FANOUT_BRANCHES} 个分支；分支只读摘要，" +
                    "不改文件、不认领、不提交。结果只作建议，看完再决定派不派活。",
                11f,
                R.color.text_tertiary,
            ).apply { setPadding(0, 0, 0, dp(6)) },
        )
        if (snapshot.fanoutRunning) {
            col.addView(note("正在问大家…分支回齐后这里会列出摘要。"))
        }
        if (snapshot.fanoutRuns.isEmpty() && !snapshot.fanoutRunning) {
            col.addView(note("还没有问过大家。在下面输入问题，点“只读问大家”。"))
        }
        snapshot.fanoutRuns.forEach { run -> col.addView(fanoutRunRow(run)) }
        return col
    }

    private fun fanoutRunRow(run: FanoutRun): LinearLayout {
        val col = UiKit.vertical(context).apply { setPadding(0, 0, 0, dp(12)) }
        col.addView(
            UiKit.text(context, "问：${run.question.take(200)}", 12f, R.color.text_primary, bold = true),
        )
        // 2026-11-12 团队对话分串：扇出是讨论（多分支、各署名），
        // 关联任务写清在串头上，各分支的回复按这个任务分组。
        run.taskId?.takeIf { it.isNotBlank() }?.let { taskId ->
            col.addView(
                UiKit.text(context, "任务 ${taskId.take(8)}", 11f, R.color.text_tertiary)
                    .apply { setPadding(0, dp(2), 0, 0) },
            )
        }
        run.branches.forEach { branch ->
            val statusText = when (branch.status) {
                FanoutBranchStatus.DONE -> "已回"
                FanoutBranchStatus.RUNNING -> "问着呢…"
                FanoutBranchStatus.PENDING -> "等着问"
                FanoutBranchStatus.FAILED -> "没回：${branch.error.orEmpty().take(100)}"
                FanoutBranchStatus.TIMEOUT -> "超时没回"
            }
            col.addView(
                UiKit.text(context, "${branch.displayName}：$statusText", 12f, R.color.text_primary)
                    .apply { setPadding(0, dp(2), 0, 0) },
            )
            branch.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                col.addView(
                    UiKit.text(context, summary, 12f, R.color.text_tertiary)
                        .apply { setPadding(dp(8), 0, 0, dp(4)) },
                )
            }
        }
        return col
    }

    private fun note(text: String): TextView =
        UiKit.text(context, text, 12f, R.color.text_tertiary).apply {
            setPadding(0, dp(4), 0, dp(4))
        }

    private fun formatTime(createdAtSeconds: Double): String {
        if (createdAtSeconds <= 0) return ""
        return try {
            SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(Date((createdAtSeconds * 1000).toLong()))
        } catch (_: Exception) {
            ""
        }
    }

    private fun dp(value: Int): Int = UiKit.dp(context, value)
}
