package wherevercode.agent.ui

import android.content.Context
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.RadioGroup
import android.widget.RadioButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import wherevercode.agent.R
import wherevercode.agent.core.agent.ChannelConnectionState
import wherevercode.agent.core.agent.PlatformChannelMessage
import wherevercode.agent.core.team.ChannelTaskCard
import wherevercode.agent.core.team.FanoutBranchStatus
import wherevercode.agent.core.team.FanoutRun
import wherevercode.agent.core.team.TeamChannelMember
import wherevercode.agent.core.team.channelTaskGoal
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
    /** 频道补全一期：本会话已从频道显式派发的任务卡（回显；状态在任务列表）。 */
    val taskCards: List<ChannelTaskCard> = emptyList(),
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
 * - 消息上限：一条最多 4000 字（与平台/iOS 统一），超了发不出去，当场明说。
 * - 轮次上限 3 轮：用户每发一条消息计一轮，到顶后输入栏锁住，点
 *   “开始新一轮”才继续。
 * - 频道不执行代码、派发只走显式动作（频道补全一期，supersede 旧
 *   「建议-only」口径）：页上没有任何改任务状态、认领、标记完成的
 *   入口；派发只有两条显式路——输入栏「作为任务发送」与每条消息的
 *   「转为正式任务」，都先开创建任务表单、确认后才建单，派发后频道
 *   内留任务卡回显。其余动作仍是发消息、1:1 聊、只读扇出。
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
    private val onCreateTask: ((String, () -> Unit) -> Unit)? = null,
    private val onReconnect: () -> Unit = {},
    /** 未连接空态里的「前往 Agent 管理」（双端对齐批D，与 iOS 同文同钮）。 */
    private val onOpenAgentManagement: () -> Unit = {},
    /** 页头「＋ 新建」（与 iOS 同位同序；菜单内容由调用方给）。 */
    private val onCreateNew: () -> Unit = {},
) {
    val root: LinearLayout = UiKit.vertical(context).apply {
        visibility = View.GONE
    }

    /**
     * 消息列表宿主（对话组件收敛）：与单聊页共用 [MessageListView]；
     * 内容 padding 沿用频道现状（16/8/16/20）。
     */
    private val messageListView: MessageListView = MessageListView(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        )
        content.setPadding(dp(16), dp(8), dp(16), dp(20))
    }

    /** 消息 + 扇出区容器：每次 render() 重建。固定头与输入栏不在其中。 */
    private val bodyContainer: LinearLayout = messageListView.content

    private val connectionLine: TextView = UiKit.text(context, "", 11f, R.color.text_tertiary)
    private val roundLine: TextView = UiKit.text(context, "", 11f, R.color.text_tertiary)
    private val noticeLine: TextView = UiKit.text(context, "", 11f, R.color.text_tertiary)
    /** 发送方式说明行（对话框铬的一部分）：未配时随整套铬一起藏掉。 */
    private val explanationLine: TextView = UiKit.text(
        context,
        "仅讨论会发送频道消息；作为任务发送会打开创建表单，确认后进入任务列表。",
        11f,
        R.color.text_tertiary,
    ).apply { setPadding(dp(16), 0, dp(16), dp(6)) }
    /** 重连/刷新行与底部输入栏（对话框铬）：未配时藏掉，配好后回来。 */
    private lateinit var connectionActions: LinearLayout
    private lateinit var inputBar: LinearLayout
    private val diagnosticToggle: TextView = UiKit.text(context, "查看连接诊断", 10f, R.color.accent).apply {
        visibility = View.GONE
        setPadding(dp(16), 0, dp(16), dp(3))
    }
    private val diagnosticLine: TextView = UiKit.text(context, "", 10f, R.color.text_tertiary).apply {
        visibility = View.GONE
        setPadding(dp(16), 0, dp(16), dp(4))
    }

    /**
     * 输入区（对话组件收敛）：与单聊页共用 [ChatComposerView] 的
     * INLINE 排布（说明行 + 输入框/发送钮同行）。发送语义留在本类
     * （[sendMessage]），组件只发发送请求。hint 沿用频道现状。
     */
    private val composer: ChatComposerView =
        ChatComposerView(context, ChatComposerView.Arrangement.INLINE).apply {
            caption = "频道消息 · 所有成员可见，可用 @名字 邀请回复"
            input.hint = "给频道发一条消息（≤4000 字），@名字 叫一位 Agent…"
            onSendRequest = { sendMessage() }
        }

    private val fanoutInput: EditText = EditText(context).apply {
        hint = "问大家一个问题（只读：大家只看不写）…"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        minLines = 1
        maxLines = 4
        setPadding(dp(10), dp(8), dp(10), dp(8))
    }

    private var snapshot: TeamChannelSnapshot? = null
    private var asTask = false

    init {
        val titleRow = UiKit.horizontal(context)
        titleRow.addView(
            UiKit.text(context, "频道", 20f, R.color.text_primary, bold = true).apply {
                setPadding(dp(16), dp(12), dp(8), dp(2))
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
                )
            },
        )
        titleRow.addView(
            UiKit.text(context, "＋ 新建", 13f, R.color.text_primary, bold = true).apply {
                setBackgroundResource(R.drawable.bg_chip)
                setPadding(dp(12), dp(7), dp(12), dp(7))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    rightMargin = dp(16)
                    topMargin = dp(10)
                }
                setOnClickListener { onCreateNew() }
            },
        )
        root.addView(titleRow)
        root.addView(explanationLine)
        connectionLine.setPadding(dp(16), 0, dp(16), dp(2))
        root.addView(connectionLine)
        connectionActions = UiKit.horizontal(context).apply {
            setPadding(dp(16), 0, dp(16), dp(2))
        }
        connectionActions.addView(
            UiKit.text(context, "重新连接", 11f, R.color.accent, bold = true).apply {
                setBackgroundResource(R.drawable.bg_chip_accent)
                setPadding(dp(10), dp(5), dp(10), dp(5))
                setOnClickListener { onReconnect() }
            },
        )
        connectionActions.addView(
            UiKit.text(context, "刷新消息", 11f, R.color.text_secondary).apply {
                setPadding(dp(10), dp(5), dp(10), dp(5))
                setOnClickListener { onRefresh() }
            },
        )
        root.addView(connectionActions)
        root.addView(diagnosticToggle)
        root.addView(diagnosticLine)
        diagnosticToggle.setOnClickListener {
            val expanded = diagnosticLine.visibility != View.VISIBLE
            diagnosticLine.visibility = if (expanded) View.VISIBLE else View.GONE
            diagnosticToggle.text = if (expanded) "收起连接诊断" else "查看连接诊断"
        }
        roundLine.setPadding(dp(16), 0, dp(16), dp(2))
        root.addView(roundLine)
        noticeLine.setPadding(dp(16), 0, dp(16), dp(6))
        root.addView(noticeLine)
        root.addView(messageListView)
        inputBar = buildInputBar()
        root.addView(inputBar)
    }

    /**
     * 对话框铬（照片走查批 D）：发送方式说明、连接状态行、重连/刷新、
     * 诊断、轮次、提示、底部输入栏。未配时整套藏掉，只留页头与引导
     * （与 iOS 未配 Token 视图同构）；配好后整套回来。诊断行平时藏
     * 着，点「查看连接诊断」才展开（与原有行为一致）。
     */
    private fun setDialogChromeVisible(visible: Boolean, hasDiagnostic: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        explanationLine.visibility = v
        connectionLine.visibility = v
        connectionActions.visibility = v
        diagnosticToggle.visibility =
            if (!visible) View.GONE else if (hasDiagnostic) View.VISIBLE else View.GONE
        diagnosticLine.visibility = View.GONE
        roundLine.visibility = v
        noticeLine.visibility = v
        inputBar.visibility = v
    }

    private fun buildInputBar(): LinearLayout {
        val bar = UiKit.vertical(context).apply {
            setPadding(dp(16), dp(8), dp(16), dp(12))
        }
        if (onCreateTask != null) {
            val modes = RadioGroup(context).apply { orientation = LinearLayout.HORIZONTAL }
            val discussion = RadioButton(context).apply { id = View.generateViewId(); text = "仅讨论"; minHeight = dp(48) }
            val task = RadioButton(context).apply { id = View.generateViewId(); text = "作为任务发送"; minHeight = dp(48) }
            modes.addView(discussion)
            modes.addView(task)
            modes.check(discussion.id)
            modes.setOnCheckedChangeListener { _, id ->
                asTask = id == task.id
                composer.sendLabel?.text = if (asTask) "创建任务" else "发送"
                composer.input.hint = if (asTask) "任务目标与验收要求…" else "发送消息，或 @ 一位成员…"
            }
            bar.addView(modes)
        }
        bar.addView(composer)
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
        val fanoutColumn = UiKit.vertical(context)
        fanoutColumn.addView(UiKit.text(context, "只读问大家 · 仅生成建议，不会派活或写文件", 10f, R.color.text_tertiary))
        fanoutColumn.addView(fanoutRow)
        fanoutColumn.visibility = View.GONE
        val advanced = UiKit.text(context, "只读协作工具　展开 ›", 12f, R.color.text_secondary).apply {
            minHeight = dp(48)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        advanced.setOnClickListener {
            val expanded = fanoutColumn.visibility != View.VISIBLE
            fanoutColumn.visibility = if (expanded) View.VISIBLE else View.GONE
            advanced.text = if (expanded) "只读协作工具　收起 ⌃" else "只读协作工具　展开 ›"
        }
        bar.addView(advanced)
        bar.addView(fanoutColumn)
        return bar
    }


    private fun sendMessage() {
        val text = composer.input.text.toString().trim()
        if (asTask) {
            if (text.isBlank()) {
                noticeLine.text = "请填写任务目标"
                return
            }
            onCreateTask?.invoke(text) {
                if (composer.input.text.toString().trim() == text) composer.input.setText("")
            }
            return
        }
        val snap = snapshot ?: return
        if (!snap.configured) {
            noticeLine.text = "频道尚未连接；消息未发送。"
            return
        }
        val error = TeamChatRules.validatePost(text, snap.roundsUsed)
        if (error != null) {
            noticeLine.text = error
            return
        }
        noticeLine.text = ""
        composer.input.setText("")
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
        val diagnostic = snapshot.connectionError?.takeIf { it.isNotBlank() }
        val certificateFailure = diagnostic?.let(::isCertificateFailure) == true
        connectionLine.text = when (snapshot.connectionState) {
            ChannelConnectionState.LIVE -> "● 直播推送（WS 已连）"
            ChannelConnectionState.CONNECTING -> "○ 正在连接频道…"
            ChannelConnectionState.POLLING ->
                "○ 轮询兜底（每 15 秒补读；约 45 秒后重连推送）" +
                    if (certificateFailure) " · 证书验证失败，安全连接未建立" else ""
            ChannelConnectionState.STOPPED -> "○ 频道未连接"
        }
        diagnosticLine.text = diagnostic.orEmpty()
        diagnosticToggle.text = "查看连接诊断"
        roundLine.text = if (snapshot.roundsUsed >= TeamChatLimits.MAX_ROUNDS) {
            "已到 ${TeamChatLimits.MAX_ROUNDS} 轮上限，点“开始新一轮”继续"
        } else {
            "第 ${snapshot.roundsUsed + 1}/${TeamChatLimits.MAX_ROUNDS} 轮"
        }
        noticeLine.text = snapshot.notice.orEmpty()
        bodyContainer.removeAllViews()
        if (!snapshot.configured) {
            // 照片走查批 D：未配时只留页头与引导，对话框铬整套藏掉
            // （与 iOS 未配 Token 视图同构）；引导文案与 iOS 同文。
            setDialogChromeVisible(false, diagnostic != null)
            bodyContainer.addView(note(snapshot.setupHint))
            bodyContainer.addView(
                // 黑胶囊钮，对 iOS 黑色「前往 Agent 管理」钮（原为浅色 chip）。
                UiKit.text(context, "前往 Agent 管理", 12f, R.color.on_accent, bold = true).apply {
                    setBackgroundResource(R.drawable.bg_pill_dark)
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(10) }
                    setOnClickListener { onOpenAgentManagement() }
                },
            )
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
        if (snapshot.taskCards.isNotEmpty()) {
            bodyContainer.addView(taskCardsSection(snapshot.taskCards))
        }
        bodyContainer.addView(fanoutSection(snapshot))
        // 配好后对话框铬整套回来（含诊断钮按有无诊断显隐）。
        setDialogChromeVisible(true, diagnostic != null)
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
        if (onCreateTask != null) {
            // 显式派发（与 iOS 每条消息的「转为正式任务」同名同义）：
            // 点按只打开创建任务表单，确认后才建单；不在频道内执行。
            col.addView(
                UiKit.text(context, "转为正式任务", 11f, R.color.accent, bold = true).apply {
                    setBackgroundResource(R.drawable.bg_chip_accent)
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(4) }
                    setOnClickListener {
                        onCreateTask.invoke(channelTaskGoal(message.senderName, message.text)) {}
                    }
                },
            )
        }
        return col
    }

    /** 已从频道派发的任务卡区（回显）：只展示目标与去向，状态不在频道内改。 */
    private fun taskCardsSection(cards: List<ChannelTaskCard>): LinearLayout {
        val col = UiKit.vertical(context).apply { setPadding(0, dp(10), 0, 0) }
        col.addView(
            UiKit.text(context, "已从频道派发", 14f, R.color.text_primary, bold = true),
        )
        col.addView(
            UiKit.text(
                context,
                "任务已进入任务列表，进度与结果在任务列表查看；频道内不改任务状态。",
                11f,
                R.color.text_tertiary,
            ).apply { setPadding(0, 0, 0, dp(6)) },
        )
        cards.forEach { card ->
            val cardView = UiKit.vertical(context).apply {
                setBackgroundResource(R.drawable.bg_card)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(5) }
            }
            cardView.addView(
                UiKit.text(context, card.goal.take(200), 12f, R.color.text_primary, bold = true),
            )
            val time = formatTime(card.createdAtSeconds)
            if (time.isNotBlank()) {
                cardView.addView(
                    UiKit.text(context, "派发于 $time", 10f, R.color.text_tertiary)
                        .apply { setPadding(0, dp(2), 0, 0) },
                )
            }
            col.addView(cardView)
        }
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
                FanoutBranchStatus.FAILED -> "连接失败"
                FanoutBranchStatus.TIMEOUT -> "超时没回"
            }
            val branchCard = UiKit.vertical(context).apply {
                setBackgroundResource(R.drawable.bg_card)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(5) }
            }
            branchCard.addView(UiKit.text(context, "${branch.displayName} · $statusText", 12f, R.color.text_primary, bold = true))
            branch.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                val summaryView = UiKit.text(context, summary, 12f, R.color.text_tertiary).apply {
                    maxLines = 4
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, dp(4), 0, 0)
                }
                summaryView.setOnClickListener {
                    summaryView.maxLines = if (summaryView.maxLines == 4) 24 else 4
                }
                branchCard.addView(summaryView)
            }
            branch.error?.takeIf { it.isNotBlank() }?.let { error ->
                val detail = UiKit.text(context, error, 10f, R.color.text_tertiary).apply {
                    visibility = View.GONE
                    setPadding(0, dp(4), 0, 0)
                }
                val toggle = UiKit.text(context, "查看失败诊断", 10f, R.color.accent).apply {
                    setPadding(0, dp(4), 0, 0)
                    setOnClickListener {
                        detail.visibility = if (detail.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                        text = if (detail.visibility == View.VISIBLE) "收起失败诊断" else "查看失败诊断"
                    }
                }
                branchCard.addView(toggle)
                branchCard.addView(detail)
            }
            col.addView(branchCard)
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

    private fun isCertificateFailure(error: String): Boolean {
        val normalized = error.lowercase(Locale.ROOT)
        return listOf("certificate", "certpath", "trust anchor", "sslhandshake", "证书", "证书链")
            .any(normalized::contains)
    }

    private fun dp(value: Int): Int = UiKit.dp(context, value)
}
