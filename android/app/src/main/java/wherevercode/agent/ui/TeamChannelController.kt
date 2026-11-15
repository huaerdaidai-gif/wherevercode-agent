package wherevercode.agent.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
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
    private val onOpenTasks: () -> Unit = {},
    private val onReplyPost: ((String, String) -> Unit)? = null,
    private val onLoadHistory: ((String?, String?, (Result<List<PlatformChannelMessage>>) -> Unit) -> Unit)? = null,
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
    private var section = "messages"
    private var pendingText: String? = null
    private var replyTo: String? = null
    private var historyMessages: List<PlatformChannelMessage>? = null
    private var historyQuery: String? = null
    private var historyBusy = false
    private val replyLine = UiKit.text(context, "", 12f, R.color.accent).apply {
        visibility = View.GONE
        setPadding(dp(16), dp(8), dp(16), dp(8))
        setOnClickListener {
            if (pendingText == null) { replyTo = null; visibility = View.GONE }
        }
    }
    private val historyInput = EditText(context).apply {
        hint = "搜索频道历史"
        setSingleLine(true)
    }
    private lateinit var historyBar: LinearLayout
    private lateinit var inputBar: LinearLayout

    /** Clear only the successfully sent draft; failures retain text for an explicit retry. */
    fun postResult(text: String, success: Boolean) {
        if (pendingText != text) return
        pendingText = null
        messageInput.isEnabled = true
        if (success && messageInput.text.toString().trim() == text) {
            messageInput.setText("")
            replyTo = null
            replyLine.visibility = View.GONE
        }
    }


    init {
        root.addView(
            UiKit.text(context, "团队频道", 20f, R.color.text_primary, bold = true).apply {
                setPadding(dp(16), dp(12), dp(16), dp(2))
            },
        )
        root.addView(
            UiKit.text(
                context,
                "长按 Agent 名字快速 @，或点名字打开对话。任务在项目中管理。",
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
        val tabs = UiKit.horizontal(context)
        listOf("messages" to "消息", "files" to "文件", "tasks" to "任务").forEach { (id, label) ->
            tabs.addView(UiKit.text(context, label, 15f, R.color.accent, bold = true).apply {
                tag = "channel_tab_$id"
                setPadding(dp(18), dp(12), dp(18), dp(12))
                setOnClickListener {
                    section = id
                    snapshot?.let { render(it) }
                }
            })
        }
        root.addView(tabs)
        root.addView(replyLine)
        historyBar = UiKit.horizontal(context)
        historyBar.addView(historyInput.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        listOf("搜索", "更早", "最新").forEach { label ->
            historyBar.addView(UiKit.text(context, label, 12f, R.color.accent).apply {
                setPadding(dp(10), dp(12), dp(10), dp(12))
                setOnClickListener {
                    when (label) {
                        "搜索" -> loadHistory(false)
                        "更早" -> loadHistory(true)
                        else -> {
                            if (!historyBusy) {
                                historyMessages = null
                                historyQuery = null
                                historyInput.setText("")
                                snapshot?.let { render(it) }
                            }
                        }
                    }
                }
            })
        }
        root.addView(historyBar)
        scrollView.addView(content)
        content.addView(bodyContainer)
        root.addView(scrollView)
        inputBar = buildInputBar()
        root.addView(inputBar)
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
        if (pendingText != null) return
        if (!snap.configured) {
            noticeLine.text = snap.setupHint
            return
        }
        val error = TeamChatRules.validatePost(text, snap.roundsUsed)
        if (error != null) {
            noticeLine.text = error
            return
        }
        noticeLine.text = ""
        pendingText = text
        messageInput.isEnabled = false
        val reply = replyTo
        if (reply != null && onReplyPost != null) onReplyPost.invoke(text, reply)
        else onPost(text)
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
        inputBar.visibility = if (section == "messages") View.VISIBLE else View.GONE
        historyBar.visibility = if (section == "messages") View.VISIBLE else View.GONE
        replyLine.visibility = if (section == "messages" && replyTo != null) View.VISIBLE else View.GONE
        if (section == "files") {
            bodyContainer.addView(note("频道文件"))
            bodyContainer.addView(note("附件上传、项目文件、网盘引用、预览和下载暂不可用。"))
            bodyContainer.addView(note("当前没有可验证的频道文件记录。"))
            return
        }
        if (section == "tasks") {
            bodyContainer.addView(note("频道任务关联暂不可用。下方可查看现有任务，不代表任务已关联到本频道。"))
            bodyContainer.addView(UiKit.text(context, "查看现有任务", 15f, R.color.accent).apply {
                setPadding(dp(12), dp(14), dp(12), dp(14))
                setOnClickListener { onOpenTasks() }
            })
            return
        }
        if (!snapshot.configured) {
            bodyContainer.addView(note(snapshot.setupHint))
            return
        }
        bodyContainer.addView(membersRow(snapshot))
        if (snapshot.unreadCount > 0) {
            bodyContainer.addView(unreadRow(snapshot))
        }
        val displayedMessages = historyMessages ?: snapshot.messages
        if (displayedMessages.isEmpty()) {
            bodyContainer.addView(note("频道里暂无消息。发一条“在吗”，看看谁回。"))
        } else {
            // 2026-11-12 团队对话分串：消息按任务成组/成串，一个任务
            // 的讨论不混进另一个任务里；每条带发送者 + 任务归属。
            // 归属头从正文解析（App 贴回的中转回复带的头），无头
            // 的消息进“综合讨论”串。
            val threads = groupTeamMessages(displayedMessages.map { teamMessageFromChannel(it) })
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
            UiKit.text(context, "Agent", 12f, R.color.text_primary, bold = true).apply {
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
                    setOnLongClickListener {
                        if (!member.isLocalApp) mention(member.displayName)
                        !member.isLocalApp
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
        val col = UiKit.vertical(context).apply {
            setPadding(0, 0, 0, dp(10))
            setOnLongClickListener {
                val canMention = snapshot?.members.orEmpty().any {
                    !it.isLocalApp && it.displayName == message.senderName
                }
                val actions = mutableListOf("复制", "发送时间")
                if (canMention) actions.add("@${message.senderName}")
                if (onReplyPost != null && message.messageId.isNotBlank()) actions.add("回复")
                AlertDialog.Builder(context).setItems(actions.toTypedArray()) { _, index ->
                    when (actions[index]) {
                        "复制" -> (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("频道消息", message.text))
                        "回复" -> if (pendingText == null) {
                            replyTo = message.messageId
                            replyLine.text = "回复 ${message.senderName} · 点击取消"
                            replyLine.visibility = View.VISIBLE
                            messageInput.requestFocus()
                        }
                        "发送时间" -> AlertDialog.Builder(context).setMessage(formatTime(message.createdAtSeconds))
                            .setPositiveButton("关闭", null).show()
                        else -> mention(message.senderName)
                    }
                }.show()
                true
            }
        }
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

    private fun loadHistory(older: Boolean) {
        if (historyBusy) return
        val snap = snapshot ?: return
        val loader = onLoadHistory
        if (!snap.configured || loader == null) {
            noticeLine.text = "历史记录暂不可用。"
            return
        }
        val query = if (older) historyQuery else historyInput.text.toString().trim().ifEmpty { null }
        val current = historyMessages ?: snap.messages
        val before = if (older) current.minByOrNull { it.createdAtSeconds }?.id else null
        if (older && before.isNullOrBlank()) {
            noticeLine.text = "没有可继续加载的消息。"
            return
        }
        historyBusy = true
        noticeLine.text = "正在读取历史消息…"
        loader(before, query) { result ->
            historyBusy = false
            result.onSuccess { incoming ->
                historyQuery = query
                historyMessages = (incoming + if (older) current else emptyList())
                    .distinctBy { it.id }.sortedBy { it.createdAtSeconds }
                snapshot?.let { render(it) }
                noticeLine.text = if (incoming.isEmpty()) "没有更多匹配消息。" else "已读取 ${incoming.size} 条历史消息。"
            }.onFailure {
                noticeLine.text = "读取历史消息失败，请重试。"
            }
        }
    }

    private fun mention(name: String) {
        if (pendingText != null) return
        messageInput.text.insert(messageInput.selectionStart.coerceAtLeast(0), "@$name ")
        messageInput.requestFocus()
    }

    private fun formatTime(createdAtSeconds: Double): String {
        if (!createdAtSeconds.isFinite() || createdAtSeconds <= 0) return "时间未知"
        return try {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss XXX", Locale.getDefault())
                .format(Date((createdAtSeconds * 1000).toLong()))
        } catch (_: Exception) {
            ""
        }
    }

    private fun dp(value: Int): Int = UiKit.dp(context, value)
}
