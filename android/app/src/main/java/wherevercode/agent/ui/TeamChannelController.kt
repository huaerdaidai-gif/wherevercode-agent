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
import wherevercode.agent.core.agent.ChannelSummary
import wherevercode.agent.core.agent.PlatformChannelMessage
import wherevercode.agent.core.team.ChannelExtras
import wherevercode.agent.core.team.ChannelTaskCard
import wherevercode.agent.core.team.MessageCenter
import wherevercode.agent.core.team.ScheduledChannelPost
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
    val notice: String?,
    /** 频道补全一期：本会话已从频道显式派发的任务卡（回显；状态在任务列表）。 */
    val taskCards: List<ChannelTaskCard> = emptyList(),
    /** 2026-11-13 频道权限：可代人署名的发帖者（App 自己），见 teamMessageFromChannel。 */
    val trustedAttributionPosterIds: Set<String> = setOf(wherevercode.agent.core.team.TeamVoice.COORDINATOR_ID),
    /** 本机在平台上的 agent_id 集合（判断“自己的消息”用：撤回只对自己的显示）。 */
    val selfAgentIds: Set<String> = emptySet(),
    /** 全部频道（团队空间切换行；含 kind/archived，频道补全三期）。 */
    val channels: List<ChannelSummary> = emptyList(),
    /** 当前正在看的频道 id（可能与端点默认频道不同：切换/私聊时）。 */
    val activeChannelId: String = "",
    /** 私聊分段是否选中（方案 A：频道页内「频道／私聊」分段切换）。 */
    val dmMode: Boolean = false,
    /** 私聊频道列表（kind=dm）。 */
    val dmChannels: List<ChannelSummary> = emptyList(),
    /** 消息中心聚合（@我 + 未读，频道补全三期）。 */
    val messageCenter: MessageCenter? = null,
    /** 待发的定时消息（频道补全二期）。 */
    val scheduled: List<ScheduledChannelPost> = emptyList(),
    /** 本频道通知档：全部 / 仅@我 / 免打扰（本机偏好，频道补全三期）。 */
    val notifyMode: String = "全部",
    /** 频道内搜索词（非空且 searchResults 非 null 时列表显示搜索结果）。 */
    val searchQuery: String = "",
    val searchResults: List<PlatformChannelMessage>? = null,
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
 *   内留任务卡回显。频道讨论中的 @ 作为消息提醒。
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
    private val onRefresh: () -> Unit,
    private val onCreateTask: ((String, () -> Unit) -> Unit)? = null,
    private val onReconnect: () -> Unit = {},
    /** 未连接空态里的「前往 Agent 管理」（双端对齐批D，与 iOS 同文同钮）。 */
    private val onOpenAgentManagement: () -> Unit = {},
    /** 页头「＋ 新建」（与 iOS 同位同序；菜单内容由调用方给）。 */
    private val onCreateNew: () -> Unit = {},
    // ---- 频道补全二/三/四期动作（默认空实现，旧调用方不受影响）----
    private val onSwitchChannel: (String) -> Unit = {},
    private val onToggleDmMode: (Boolean) -> Unit = {},
    private val onStartDm: (agentId: String) -> Unit = {},
    private val onMessageRecall: (messageId: String) -> Unit = {},
    private val onMessageDelete: (messageId: String) -> Unit = {},
    private val onTogglePin: (messageId: String, pinned: Boolean) -> Unit = { _, _ -> },
    private val onReact: (messageId: String, emoji: String, on: Boolean) -> Unit = { _, _, _ -> },
    private val onForward: (message: TeamThreadMessage) -> Unit = {},
    private val onShowReaders: (messageId: String) -> Unit = {},
    private val onPostEx: ((text: String, replyToId: String?, attachmentRef: String?) -> Unit)? = null,
    private val onSchedulePost: ((text: String, delaySeconds: Long) -> Unit)? = null,
    private val onCancelScheduled: (id: String) -> Unit = {},
    private val onNotifyModeChange: (String) -> Unit = {},
    private val onSearch: (String) -> Unit = {},
    private val onClearSearch: () -> Unit = {},
    private val onOpenAttachmentRef: (String) -> Unit = {},
    private val onRequestAttachmentRef: () -> Unit = {},
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
            caption = "频道消息 · 所有成员可见，可用 @名字 提醒成员"
            input.hint = "给频道发一条消息（≤4000 字），@名字 提醒成员…"
            onSendRequest = { sendMessage() }
        }

    private var snapshot: TeamChannelSnapshot? = null
    private var asTask = false

    /** 正在回复的消息（发送时带 reply_to；发送/取消后清空）。 */
    private var replyingTo: TeamThreadMessage? = null

    /** 待随下一条消息发出的附件引用（引用制；只存引用字符串）。 */
    private var pendingAttachmentRef: String? = null

    private val replyBanner: TextView = UiKit.text(context, "", 11f, R.color.accent).apply {
        visibility = View.GONE
        setPadding(dp(16), 0, dp(16), dp(2))
        setOnClickListener { clearReply() }
    }

    private val searchInput: EditText = EditText(context).apply {
        hint = "搜本频道消息…"
        inputType = InputType.TYPE_CLASS_TEXT
        minLines = 1
        maxLines = 1
        setPadding(dp(10), dp(6), dp(10), dp(6))
    }

    private fun clearReply() {
        replyingTo = null
        replyBanner.visibility = View.GONE
    }

    /** 调用方（附件引用输入框）回填待发附件引用。 */
    fun setPendingAttachmentRef(ref: String?) {
        pendingAttachmentRef = ref?.takeIf { it.isNotBlank() }
        noticeLine.text = if (pendingAttachmentRef != null) {
            "附件引用已添加，将随下一条消息发出：$pendingAttachmentRef"
        } else {
            "附件引用已清除。"
        }
    }

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
        root.addView(replyBanner)
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
        // 频道补全二期：附件引用（引用制）与定时发送，都是发送前的显式动作。
        val toolsRow = UiKit.horizontal(context).apply { setPadding(0, dp(6), 0, 0) }
        toolsRow.addView(
            UiKit.text(context, "加附件引用", 11f, R.color.text_secondary).apply {
                setBackgroundResource(R.drawable.bg_chip)
                setPadding(dp(10), dp(5), dp(10), dp(5))
                setOnClickListener { onRequestAttachmentRef() }
            },
        )
        if (onSchedulePost != null) {
            toolsRow.addView(
                UiKit.text(context, "定时发送", 11f, R.color.text_secondary).apply {
                    setBackgroundResource(R.drawable.bg_chip)
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { leftMargin = dp(8) }
                    setOnClickListener { showScheduleMenu() }
                },
            )
        }
        bar.addView(toolsRow)
        return bar
    }


    /** 定时发送菜单：选延时后把当前输入交给调用方入队（发送前可取消）。 */
    private fun showScheduleMenu() {
        val text = composer.input.text.toString().trim()
        if (text.isBlank()) {
            noticeLine.text = "先写好消息，再设定时发送。"
            return
        }
        val options = listOf("10 分钟后" to 600L, "1 小时后" to 3600L, "3 小时后" to 10800L)
        ListDialog.show(context, "定时发送", null, options.map { it.first }) { which ->
            val delay = options.getOrNull(which)?.second ?: return@show
            onSchedulePost?.invoke(text, delay)
            if (composer.input.text.toString().trim() == text) composer.input.setText("")
        }
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
        // @所有人门闩（频道补全二期阻塞③）：只有 owner/协调者可发。
        // 本机 App 即协调者（TeamVoice.COORDINATOR_ID = "local-app"），
        // 且在快照的特权集里，本机发出的 @all 放行；若本机身份将来不
        // 再是协调者（特权集里没有本机），此处拦截并提示。服务端广播
        // 语义待定，本轮不另造工具名。
        if (ChannelExtras.mentionsAllText(text) &&
            wherevercode.agent.core.team.TeamVoice.COORDINATOR_ID !in snap.trustedAttributionPosterIds
        ) {
            noticeLine.text = "只有频道 owner/协调者可以 @所有人，消息未发送。"
            return
        }
        val error = TeamChatRules.validatePost(text, snap.roundsUsed)
        if (error != null) {
            noticeLine.text = error
            return
        }
        noticeLine.text = ""
        composer.input.setText("")
        val replyId = replyingTo?.messageId
        val attachment = pendingAttachmentRef
        clearReply()
        pendingAttachmentRef = null
        if (onPostEx != null && (replyId != null || attachment != null)) {
            onPostEx.invoke(text, replyId, attachment)
        } else {
            onPost(text)
        }
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
        bodyContainer.addView(segmentRow(snapshot))
        bodyContainer.addView(membersRow(snapshot))
        if (snapshot.dmMode) {
            bodyContainer.addView(dmListSection(snapshot))
        } else {
            if (snapshot.channels.isNotEmpty()) {
                bodyContainer.addView(channelSwitcherRow(snapshot))
            }
            bodyContainer.addView(searchRow(snapshot))
            snapshot.messageCenter?.let { bodyContainer.addView(messageCenterSection(it)) }
        }
        if (snapshot.unreadCount > 0) {
            bodyContainer.addView(unreadRow(snapshot))
        }
        val pinned = ChannelExtras.pinnedMessages(snapshot.messages)
        if (pinned.isNotEmpty() && !snapshot.dmMode) {
            bodyContainer.addView(pinnedSection(pinned))
        }
        if (snapshot.scheduled.isNotEmpty()) {
            bodyContainer.addView(scheduledSection(snapshot.scheduled))
        }
        val searchResults = snapshot.searchResults
        if (searchResults != null) {
            bodyContainer.addView(
                note("搜索“${snapshot.searchQuery}”：${searchResults.size} 条结果（点“清除”回全量）"),
            )
            if (searchResults.isEmpty()) {
                bodyContainer.addView(note("没有搜到相关消息。"))
            } else {
                searchResults.forEach { raw ->
                    bodyContainer.addView(
                        messageRow(
                            teamMessageFromChannel(raw, snapshot.trustedAttributionPosterIds),
                            ChannelExtras.replyQuote(searchResults, raw),
                            isOwn = raw.agentId in snapshot.selfAgentIds,
                        ),
                    )
                }
            }
        } else if (snapshot.messages.isEmpty()) {
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
                thread.messages.forEach { view ->
                    val raw = snapshot.messages.firstOrNull { it.id == view.messageId }
                    bodyContainer.addView(
                        messageRow(
                            view,
                            raw?.let { ChannelExtras.replyQuote(snapshot.messages, it) },
                            isOwn = view.senderId in snapshot.selfAgentIds,
                        ),
                    )
                }
            }
        }
        bodyContainer.addView(newRoundRow())
        if (snapshot.taskCards.isNotEmpty()) {
            bodyContainer.addView(taskCardsSection(snapshot.taskCards))
        }
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
        // 逐频道通知档（本机偏好）：全部 → 仅@我 → 免打扰 循环切换。
        row.addView(
            UiKit.text(context, "通知：${snapshot.notifyMode}", 11f, R.color.text_secondary).apply {
                setBackgroundResource(R.drawable.bg_chip)
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setOnClickListener {
                    val next = when (snapshot.notifyMode) {
                        "全部" -> "仅@我"
                        "仅@我" -> "免打扰"
                        else -> "全部"
                    }
                    onNotifyModeChange(next)
                }
            },
        )
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

    /** 「频道／私聊」分段（方案 A，Funk 2026-11-20 09:31 定）。 */
    private fun segmentRow(snapshot: TeamChannelSnapshot): LinearLayout {
        val row = UiKit.horizontal(context).apply { setPadding(0, dp(2), 0, dp(8)) }
        fun segmentChip(label: String, active: Boolean, dm: Boolean) =
            UiKit.text(context, label, 12f, R.color.text_primary, bold = active).apply {
                setBackgroundResource(if (active) R.drawable.bg_chip_accent else R.drawable.bg_chip)
                setPadding(dp(14), dp(6), dp(14), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { if (dm) leftMargin = dp(8) }
                setOnClickListener { onToggleDmMode(dm) }
            }
        row.addView(segmentChip("频道", !snapshot.dmMode, dm = false))
        row.addView(segmentChip("私聊", snapshot.dmMode, dm = true))
        return row
    }

    /** 团队空间：频道与成员同页——频道切换 chips（当前频道高亮，归档带标）。 */
    private fun channelSwitcherRow(snapshot: TeamChannelSnapshot): LinearLayout {
        val row = UiKit.horizontal(context).apply { setPadding(0, 0, 0, dp(8)) }
        val scroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val chips = UiKit.horizontal(context)
        ChannelExtras.groupChannels(snapshot.channels).forEach { channel ->
            val active = channel.id == snapshot.activeChannelId
            chips.addView(
                UiKit.text(
                    context,
                    channel.name + if (channel.archived) "（已归档）" else "",
                    12f,
                    R.color.text_primary,
                    bold = active,
                ).apply {
                    setBackgroundResource(if (active) R.drawable.bg_chip_accent else R.drawable.bg_chip)
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    setOnClickListener { onSwitchChannel(channel.id) }
                },
            )
        }
        scroller.addView(chips)
        row.addView(scroller)
        return row
    }

    /** 私聊分段内容：已有私聊 chips + 点成员发起私聊（只给频道成员）。 */
    private fun dmListSection(snapshot: TeamChannelSnapshot): LinearLayout {
        val col = UiKit.vertical(context).apply { setPadding(0, 0, 0, dp(8)) }
        if (snapshot.dmChannels.isEmpty()) {
            col.addView(note("暂无私聊。点下面任一成员即可发起私聊（私聊只在频道成员之间）。"))
        } else {
            val row = UiKit.horizontal(context)
            snapshot.dmChannels.forEach { dm ->
                val active = dm.id == snapshot.activeChannelId
                row.addView(
                    UiKit.text(context, dm.name, 12f, R.color.text_primary, bold = active).apply {
                        setBackgroundResource(if (active) R.drawable.bg_chip_accent else R.drawable.bg_chip)
                        setPadding(dp(10), dp(6), dp(10), dp(6))
                        setOnClickListener { onSwitchChannel(dm.id) }
                    },
                )
            }
            col.addView(row)
        }
        val startRow = UiKit.horizontal(context).apply { setPadding(0, dp(4), 0, 0) }
        startRow.addView(UiKit.text(context, "发起私聊：", 11f, R.color.text_tertiary))
        snapshot.members.filter { !it.isLocalApp }.forEach { member ->
            startRow.addView(
                UiKit.text(context, member.displayName, 11f, R.color.accent).apply {
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    setOnClickListener { onStartDm(member.agentId) }
                },
            )
        }
        // 成员可能很多，发起行放进横滑容器。
        val scroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(startRow)
        }
        col.addView(scroller)
        return col
    }

    /** 频道内搜索行（平台 q 搜索；结果由调用方回填快照）。 */
    private fun searchRow(snapshot: TeamChannelSnapshot): LinearLayout {
        // searchInput 跨 render 复用，先从上一轮的父容器摘下再挂新行。
        (searchInput.parent as? android.view.ViewGroup)?.removeView(searchInput)
        val row = UiKit.horizontal(context).apply { setPadding(0, 0, 0, dp(8)) }
        if (searchInput.text.toString() != snapshot.searchQuery && snapshot.searchResults == null) {
            searchInput.setText(snapshot.searchQuery)
        }
        row.addView(
            searchInput.apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        row.addView(
            UiKit.text(context, "搜索", 12f, R.color.accent, bold = true).apply {
                setBackgroundResource(R.drawable.bg_chip_accent)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setOnClickListener { onSearch(searchInput.text.toString().trim()) }
            },
        )
        if (snapshot.searchResults != null) {
            row.addView(
                UiKit.text(context, "清除", 12f, R.color.text_secondary).apply {
                    setPadding(dp(10), dp(6), dp(4), dp(6))
                    setOnClickListener {
                        searchInput.setText("")
                        onClearSearch()
                    }
                },
            )
        }
        return row
    }

    /** 消息中心（聚合 @我 与未读；点条目不跳转，只呈现，频道补全三期）。 */
    private fun messageCenterSection(center: MessageCenter): LinearLayout {
        val col = UiKit.vertical(context).apply { setPadding(0, 0, 0, dp(8)) }
        col.addView(
            UiKit.text(
                context,
                "消息中心 · 未读 ${center.unreadCount} · @我未读 ${center.unreadMentionCount}",
                12f,
                R.color.text_primary,
                bold = true,
            ),
        )
        center.mentionMessages.takeLast(5).forEach { m ->
            col.addView(
                UiKit.text(
                    context,
                    "@我 · ${m.agentName}：${ChannelExtras.displayText(m).take(60)}",
                    11f,
                    R.color.text_tertiary,
                ).apply { setPadding(0, dp(2), 0, 0) },
            )
        }
        if (center.mentionMessages.isEmpty()) {
            col.addView(note("暂无 @我的消息。"))
        }
        return col
    }

    /** 置顶区（置顶消息常驻顶部，频道补全二期）。 */
    private fun pinnedSection(pinned: List<PlatformChannelMessage>): LinearLayout {
        val col = UiKit.vertical(context).apply {
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
        col.addView(UiKit.text(context, "置顶消息", 12f, R.color.text_primary, bold = true))
        pinned.forEach { m ->
            col.addView(
                UiKit.text(
                    context,
                    "${m.agentName}：${ChannelExtras.displayText(m).take(120)}",
                    12f,
                    R.color.text_secondary,
                ).apply { setPadding(0, dp(3), 0, 0) },
            )
        }
        return col
    }

    /** 待发定时消息区（发送前可取消，频道补全二期）。 */
    private fun scheduledSection(items: List<ScheduledChannelPost>): LinearLayout {
        val col = UiKit.vertical(context).apply { setPadding(0, 0, 0, dp(8)) }
        col.addView(UiKit.text(context, "定时发送（待发）", 12f, R.color.text_primary, bold = true))
        items.sortedBy { it.dueAtSeconds }.forEach { item ->
            val row = UiKit.horizontal(context).apply { setPadding(0, dp(3), 0, 0) }
            row.addView(
                UiKit.text(
                    context,
                    "${formatTime(item.dueAtSeconds)} 发：${item.text.take(60)}",
                    11f,
                    R.color.text_tertiary,
                ).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            row.addView(
                UiKit.text(context, "取消", 11f, R.color.accent, bold = true).apply {
                    setPadding(dp(8), dp(3), dp(8), dp(3))
                    setOnClickListener { onCancelScheduled(item.id) }
                },
            )
            col.addView(row)
        }
        return col
    }

    private fun actionChip(label: String, onClick: () -> Unit): TextView =
        UiKit.text(context, label, 10f, R.color.text_secondary).apply {
            setBackgroundResource(R.drawable.bg_chip)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dp(6) }
            setOnClickListener { onClick() }
        }

    private fun messageRow(
        message: TeamThreadMessage,
        replyQuote: String? = null,
        isOwn: Boolean = false,
    ): LinearLayout {
        val col = UiKit.vertical(context).apply { setPadding(0, 0, 0, dp(10)) }
        val time = formatTime(message.createdAtSeconds)
        col.addView(
            UiKit.text(context, "${message.senderName} · $time", 11f, R.color.text_tertiary, bold = true),
        )
        if (replyQuote != null) {
            col.addView(UiKit.text(context, replyQuote, 11f, R.color.text_tertiary))
        }
        message.forwardOf?.takeIf { it.isNotBlank() }?.let {
            col.addView(UiKit.text(context, "转发自 $it", 10f, R.color.text_tertiary))
        }
        col.addView(UiKit.text(context, message.text, 13f, R.color.text_primary))
        message.attachmentRef?.takeIf { it.isNotBlank() }?.let { ref ->
            col.addView(
                UiKit.text(context, "附件：$ref", 11f, R.color.accent).apply {
                    setPadding(0, dp(2), 0, 0)
                    setOnClickListener { onOpenAttachmentRef(ref) }
                },
            )
        }
        val reactionLine = ChannelExtras.reactionText(message.reactions)
        if (reactionLine.isNotBlank()) {
            col.addView(
                UiKit.text(context, reactionLine, 11f, R.color.text_secondary)
                    .apply { setPadding(0, dp(2), 0, 0) },
            )
        }
        // @所有人渲染：只有 owner/协调者发的才算全员提及（亮起）；
        // 其他人写的 @all 只当普通文本，并如实注一句不生效。
        val snap = snapshot
        if (snap != null && ChannelExtras.mentionsAllText(message.text)) {
            val live = message.senderId in snap.trustedAttributionPosterIds ||
                message.senderId in snap.selfAgentIds
            col.addView(
                UiKit.text(
                    context,
                    if (live) "＠所有人" else "（@所有人未生效：仅 owner/协调者可用）",
                    11f,
                    if (live) R.color.accent else R.color.text_tertiary,
                ).apply { setPadding(0, dp(2), 0, 0) },
            )
        }
        if (!message.recalled) {
            // 消息动作（频道补全二期）：回复/置顶/反应/转发/已读/撤回/删除。
            // 撤回只对自己的消息显示；删除点了由平台按权限裁决并如实报错。
            val actions = UiKit.horizontal(context).apply { setPadding(0, dp(4), 0, 0) }
            actions.addView(actionChip("回复") {
                replyingTo = message
                replyBanner.text = "正在回复 ${message.senderName}：${message.text.take(30)}（点此取消）"
                replyBanner.visibility = View.VISIBLE
            })
            actions.addView(actionChip(if (message.pinned) "取消置顶" else "置顶") {
                onTogglePin(message.messageId, !message.pinned)
            })
            ChannelExtras.REACTION_CHOICES.take(2).forEach { emoji ->
                actions.addView(actionChip(emoji) {
                    val on = !ChannelExtras.myReaction(message.reactions, emoji)
                    onReact(message.messageId, emoji, on)
                })
            }
            actions.addView(actionChip("转发") { onForward(message) })
            actions.addView(actionChip("已读") { onShowReaders(message.messageId) })
            if (isOwn) {
                actions.addView(actionChip("撤回") { onMessageRecall(message.messageId) })
            }
            actions.addView(actionChip("删除") { onMessageDelete(message.messageId) })
            val scroller = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(actions)
            }
            col.addView(scroller)
        }
        if (onCreateTask != null && !message.recalled) {
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

    /** 一个任务的讨论串头：任务编号 + 标题，无任务归属的写“综合讨论”。 */
    private fun threadHeaderRow(thread: TeamThread): LinearLayout {
        val row = UiKit.horizontal(context).apply { setPadding(0, dp(8), 0, dp(2)) }
        row.addView(
            UiKit.text(context, teamThreadTitle(thread), 12f, R.color.text_primary, bold = true),
        )
        return row
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
