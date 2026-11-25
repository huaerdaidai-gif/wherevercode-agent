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
import wherevercode.agent.core.agent.channelConnectionStateLabel
import wherevercode.agent.core.agent.ChannelSummary
import wherevercode.agent.core.agent.PlatformChannelMessage
import wherevercode.agent.core.team.ChannelExtras
import wherevercode.agent.core.team.ChannelTaskCard
import wherevercode.agent.core.team.MessageCenter
import wherevercode.agent.core.team.ScheduledChannelPost
import wherevercode.agent.core.team.TeamChannelMember
import wherevercode.agent.core.team.channelRoleLabel
import wherevercode.agent.core.team.TRANSIENT_NOTICE_TTL_MS
import wherevercode.agent.core.team.transientNoticeVisible
import wherevercode.agent.core.team.channelTaskGoal
import wherevercode.agent.core.team.TeamChatLimits
import wherevercode.agent.core.team.TeamChatRules
import wherevercode.agent.core.team.TeamThread
import wherevercode.agent.core.team.TeamThreadMessage
import wherevercode.agent.core.team.groupTopicThreads
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
    /** 本机不是该频道成员（参与者名单已知且本机不在册）：空态显示无权限提示而非邀请发帖。 */
    val notChannelMember: Boolean = false,
    /** 私聊发起名单（频道全体成员减本机自己；与成员行分开，理由见 dmCandidates）。 */
    val dmCandidates: List<TeamChannelMember> = emptyList(),
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
    /** 本频道通知档：全部消息 / 仅@我 / 静音（与 iOS 同词；本机偏好，频道补全三期）。 */
    val notifyMode: String = "全部消息",
    /** 频道内搜索词（非空且 searchResults 非 null 时列表显示搜索结果）。 */
    val searchQuery: String = "",
    val searchResults: List<PlatformChannelMessage>? = null,
    /** 输入中信令提示：正在输入成员名称（零 token，频道孤儿项补全）。 */
    val typingSenderName: String? = null,
    /** 当前激活的自由话题根消息 id（非空时仅聚焦该话题串，频道孤儿项补全）。 */
    val activeTopicThreadRootId: String? = null,
    /** №12 历史浏览窗口（非 null 时主列表显示该窗口而非直播消息）。 */
    val browseMessages: List<PlatformChannelMessage>? = null,
    /** 浏览窗口里要高亮并滚到的一条（搜索跳原文/日期跳转的目标）。 */
    val browseHighlightId: String? = null,
    val browseHasOlder: Boolean = false,
    val browseHasNewer: Boolean = false,
    /** №12 当前频道的新成员历史可见状态（平台 history_visible）。 */
    val historyVisible: Boolean = true,
    /** 本机在当前频道是否 owner/协调员（可改历史开关）。 */
    val canManageHistory: Boolean = false,
    /** 是否显示历史开关行（仅普通频道显示，私聊不显示）。 */
    val showHistoryToggle: Boolean = false,
    /** 218 分段二（消息/文件/任务，与 iOS 同位）：0 消息 1 文件 2 任务。 */
    val sectionTab: Int = 0,
    /** 文件分段数据：服务端 kind=file/image/link 过滤拉取（合并去重）。 */
    val channelFiles: List<PlatformChannelMessage> = emptyList(),
    val filesLoaded: Boolean = false,
    /** 派单任务（App 内嵌任务服务，仅本地平台频道）：本频道绑定的任务。 */
    val dispatchAvailable: Boolean = false,
    val dispatchTasks: List<DispatchTaskItem> = emptyList(),
    val dispatchError: String? = null,
    /** 频道区第一层列表页（对齐 iOS，Funk 2026-11-25 批）：true = 列表层（频道/私聊名单），点行才进对话层。 */
    val listMode: Boolean = false,
    /** 列表里每个频道/私聊的本机通知档（长按菜单静音项文案用）：id → 全部消息/仅@我/静音。 */
    val channelNotifyModes: Map<String, String> = emptyMap(),
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
    /** 页内标题行 ☰（221 与 iOS 对齐：频道页自带页眉，进页藏壳层顶栏）。 */
    private val onOpenDrawer: () -> Unit = {},
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
    /** №12 带过滤的搜索（发言人/起止时间/分类）；默认回落普通搜索。 */
    private val onSearchFiltered: (query: String, sender: String?, fromTs: Long?, toTs: Long?, kind: String?) -> Unit =
        { q, _, _, _, _ -> onSearch(q) },
    private val onClearSearch: () -> Unit = {},
    /** №12 搜索结果点按：跳到原文上下文。 */
    private val onJumpToMessage: (message: PlatformChannelMessage) -> Unit = {},
    /** №12 历史浏览窗口翻页与退出。 */
    private val onBrowseLoadOlder: () -> Unit = {},
    private val onBrowseLoadNewer: () -> Unit = {},
    private val onExitBrowse: () -> Unit = {},
    /** №12 跳到日期（参数为该日 00:00 的 Unix 秒）。 */
    private val onJumpToDate: (fromTs: Long) -> Unit = {},
    /** №12 新成员历史可见开关变更。 */
    private val onHistoryVisibleChange: (Boolean) -> Unit = {},
    private val onOpenAttachmentRef: (String) -> Unit = {},
    private val onRequestAttachmentRef: () -> Unit = {},
    private val onSelectTopicThread: (rootMessageId: String?) -> Unit = {},
    /** 218 分段二切换（消息/文件/任务）。 */
    private val onSectionTabChange: (Int) -> Unit = {},
    /** 对话层顶栏「‹」：回第一层列表页（与 iOS 导航返回同位）。 */
    private val onShowChannelList: () -> Unit = {},
    /** 列表行长按/管理列表：打开指定频道的设置页（第二轮对齐）。 */
    private val onChannelSettings: (String) -> Unit = {},
    /** 私聊设置入口（iOS 私聊设置同位）。 */
    private val onDmSettings: (String) -> Unit = {},
    /** 列表行长按：设指定频道/私聊的通知档（本机偏好）。 */
    private val onSetChannelNotify: (channelId: String, mode: String) -> Unit = { _, _ -> },
    /** 列表行长按：归档/取消归档指定频道。 */
    private val onToggleChannelArchived: (channelId: String, archived: Boolean) -> Unit = { _, _ -> },
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
    /** 重连/刷新行与底部输入栏（对话框铬）：未配时藏掉，配好后回来。 */
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
            input.hint = "给频道发一条短消息（≤4000 字），@名字 叫一位 Agent…"
            onSendRequest = { sendMessage() }
        }

    private var snapshot: TeamChannelSnapshot? = null
    private var asTask = false

    /** 正在回复的消息（发送时带 reply_to；发送/取消后清空）。 */
    private var replyingTo: TeamThreadMessage? = null

    /** 待随下一条消息发出的附件引用（引用制；只存引用字符串）。 */
    private var pendingAttachmentRef: String? = null
    // 临时提示计时（快照通知只短暂出现，见 transientNoticeVisible）。
    private var lastNoticeText: String? = null
    private var lastNoticeAtMs: Long = 0
    private val clearNoticeRunnable = Runnable {
        if (noticeLine.text.toString() == lastNoticeText) {
            noticeLine.text = ""
            // 列表层没有常驻提示行：文案过期清空时把行一并收起，
            // 不留空白带（对话层提示行是对话框铬的一部分，保持原样）。
            if (snapshot?.listMode == true) noticeLine.visibility = View.GONE
        }
    }

    private val replyBanner: TextView = UiKit.text(context, "", 11f, R.color.accent).apply {
        visibility = View.GONE
        setPadding(dp(16), 0, dp(16), dp(2))
        setOnClickListener { clearReply() }
    }

    private val typingBanner: TextView = UiKit.text(context, "", 11f, R.color.text_secondary).apply {
        visibility = View.GONE
        setPadding(dp(16), 0, dp(16), dp(2))
    }

    private val searchInput: EditText = EditText(context).apply {
        hint = "搜本频道消息…"
        inputType = InputType.TYPE_CLASS_TEXT
        minLines = 1
        maxLines = 1
        setPadding(dp(10), dp(6), dp(10), dp(6))
    }

    // ---- №12 搜索过滤（控制器持有，点选即重跑搜索）----
    private var filterSender: String? = null
    private var filterRangeIndex: Int = 0 // 0 全部时间 1 今天 2 近7天 3 近30天
    private var filterKind: String? = null // null / image / file / link

    // ---- №12 @ 成员选择器 ----
    private val mentionBar: LinearLayout = UiKit.horizontal(context).apply {
        setPadding(dp(16), dp(4), dp(8), dp(4))
    }
    private val mentionScroller: HorizontalScrollView = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        visibility = View.GONE
        addView(mentionBar)
    }
    private var mentionRangeStart: Int = -1
    private var mentionRangeEnd: Int = -1

    private fun clearReply() {
        replyingTo = null
        replyBanner.visibility = View.GONE
        if (snapshot?.activeTopicThreadRootId != null) {
            onSelectTopicThread(null)
        }
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

    /** 页头导航钮与标题（render 按层级更新：列表层 ☰＋「频道」，对话层 ‹＋当前频道名）。 */
    private lateinit var navButton: TextView
    private lateinit var titleText: TextView

    init {
        val titleRow = UiKit.horizontal(context)
        // 221 与 iOS 对齐：☰ 进页内标题行（进频道页时壳层顶栏隐藏，
        // 与项目页同式）；点它仍开同一个抽屉。
        titleRow.addView(
            UiKit.text(context, "☰", 19f, R.color.text_primary, bold = true).apply {
                navButton = this
                setPadding(dp(16), dp(12), dp(6), dp(2))
                setOnClickListener {
                    if (snapshot?.listMode == false) onShowChannelList() else onOpenDrawer()
                }
            },
        )
        titleRow.addView(
            UiKit.text(context, "频道", 20f, R.color.text_primary, bold = true).apply {
                titleText = this
                setPadding(dp(2), dp(12), dp(8), dp(2))
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
        connectionLine.setPadding(dp(16), 0, dp(16), dp(2))
        root.addView(connectionLine)
        // 220 与 iOS 对齐：状态行不带「重新连接／刷新消息」钮（重连自动、刷新靠重进与轮询兜底）。
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
        root.addView(typingBanner)
        root.addView(mentionScroller)
        inputBar = buildInputBar()
        root.addView(inputBar)
        composer.input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { updateMentionBar() }
        })
    }

    /**
     * 对话框铬（照片走查批 D）：发送方式说明、连接状态行、
     * 诊断、轮次、提示、底部输入栏。未配时整套藏掉，只留页头与引导
     * （与 iOS 未配 Token 视图同构）；配好后整套回来。诊断行平时藏
     * 着，点「查看连接诊断」才展开（与原有行为一致）。
     */
    private fun setDialogChromeVisible(visible: Boolean, hasDiagnostic: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        connectionLine.visibility = v
        diagnosticToggle.visibility =
            if (!visible) View.GONE else if (hasDiagnostic) View.VISIBLE else View.GONE
        diagnosticLine.visibility = View.GONE
        roundLine.visibility = v
        noticeLine.visibility = v
        inputBar.visibility = v
        if (!visible) mentionScroller.visibility = View.GONE
    }

    private fun buildInputBar(): LinearLayout {
        val bar = UiKit.vertical(context).apply {
            setPadding(dp(16), dp(8), dp(16), dp(12))
        }
        if (onCreateTask != null) {
            // 220 与 iOS 对齐：模式切换用分段条（与 iOS 同形），不用单选钮。
            val modes = UiKit.horizontal(context).apply { setPadding(0, 0, 0, dp(6)) }
            lateinit var discussion: TextView
            lateinit var task: TextView
            fun refreshModes() {
                discussion.setBackgroundResource(if (!asTask) R.drawable.bg_chip_accent else R.drawable.bg_chip)
                discussion.setTypeface(null, if (!asTask) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                task.setBackgroundResource(if (asTask) R.drawable.bg_chip_accent else R.drawable.bg_chip)
                task.setTypeface(null, if (asTask) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                composer.sendLabel?.text = if (asTask) "创建任务" else "发送"
                composer.input.hint = if (asTask) "任务目标与验收要求…" else "给频道发一条短消息（≤4000 字），@名字 叫一位 Agent…"
            }
            fun modeSegment(label: String, taskMode: Boolean) =
                UiKit.text(context, label, 12f, R.color.text_primary).apply {
                    gravity = android.view.Gravity.CENTER
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f,
                    )
                    setOnClickListener {
                        asTask = taskMode
                        refreshModes()
                    }
                }
            discussion = modeSegment("仅讨论", taskMode = false)
            task = modeSegment("转为正式任务", taskMode = true)
            modes.addView(discussion)
            modes.addView(task)
            bar.addView(modes)
            refreshModes()
        }
        // 220 与 iOS 对齐：附件引用/定时发送收进输入框左侧「＋」菜单（与 iOS 同位同形）。
        val composerRow = UiKit.horizontal(context)
        composerRow.addView(
            UiKit.text(context, "＋", 16f, R.color.text_primary, bold = true).apply {
                gravity = android.view.Gravity.CENTER
                setBackgroundResource(R.drawable.bg_chip)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { rightMargin = dp(8) }
                setOnClickListener {
                    val options = mutableListOf("加附件引用")
                    if (onSchedulePost != null) options.add("定时发送")
                    ListDialog.show(context, "更多", null, options) { which ->
                        when (options.getOrNull(which)) {
                            "加附件引用" -> onRequestAttachmentRef()
                            "定时发送" -> showScheduleMenu()
                        }
                    }
                }
            },
        )
        composer.layoutParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f,
        )
        composerRow.addView(composer)
        bar.addView(composerRow)
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
            noticeLine.text = "只有频道 owner/协调员可以 @所有人，消息未发送。"
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
        connectionLine.text = channelConnectionStateLabel(snapshot.connectionState) +
            if (snapshot.connectionState == ChannelConnectionState.POLLING && certificateFailure) {
                " · 证书验证失败，安全连接未建立"
            } else {
                ""
            }
        diagnosticLine.text = diagnostic.orEmpty()
        diagnosticToggle.text = "查看连接诊断"
        // 218：去掉「第 X/3 轮」计数呈现（与 iOS 统一）；到顶提示保留。
        roundLine.text = if (snapshot.roundsUsed >= TeamChatLimits.MAX_ROUNDS) {
            "已到 ${TeamChatLimits.MAX_ROUNDS} 轮上限，点“开始新一轮”继续"
        } else {
            ""
        }
        // 临时提示：同一条通知只在首次出现后的 TTL 内显示，过期自动
        // 隐去（延迟回调负责无新快照时也清行）；新通知重新计时。
        if (snapshot.notice != lastNoticeText) {
            lastNoticeText = snapshot.notice
            lastNoticeAtMs = System.currentTimeMillis()
            noticeLine.removeCallbacks(clearNoticeRunnable)
            if (!snapshot.notice.isNullOrBlank()) {
                noticeLine.postDelayed(clearNoticeRunnable, TRANSIENT_NOTICE_TTL_MS + 50)
            }
        }
        noticeLine.text = if (
            transientNoticeVisible(lastNoticeText, lastNoticeAtMs, System.currentTimeMillis())
        ) {
            lastNoticeText.orEmpty()
        } else {
            ""
        }
        if (snapshot.typingSenderName != null) {
            typingBanner.text = "${snapshot.typingSenderName} 正在输入…"
            typingBanner.visibility = View.VISIBLE
        } else {
            typingBanner.visibility = View.GONE
        }
        bodyContainer.removeAllViews()
        updateChannelHeader(snapshot)
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
        if (snapshot.listMode) {
            // 第一层 = 列表页（与 iOS 同构）：分段常驻 + 名称搜索 +
            // 频道/私聊名单或空态；对话框铬（输入栏等）整套藏掉，
            // 提示行单独留着回显管理操作结果。
            setDialogChromeVisible(false, diagnostic != null)
            if (noticeLine.text.isNotBlank()) noticeLine.visibility = View.VISIBLE
            bodyContainer.addView(segmentRow(snapshot))
            bodyContainer.addView(channelListSearchRow())
            val listContainer = UiKit.vertical(context)
            channelListContainer = listContainer
            fillChannelList(listContainer, snapshot)
            bodyContainer.addView(listContainer)
            return
        }
        if (!snapshot.dmMode) {
            bodyContainer.addView(sectionTabsRow(snapshot))
        }
        bodyContainer.addView(membersRow(snapshot))
        if (!snapshot.dmMode && snapshot.sectionTab == 1) {
            bodyContainer.addView(filesSection(snapshot))
            setDialogChromeVisible(true, diagnostic != null)
            return
        }
        if (!snapshot.dmMode && snapshot.sectionTab == 2) {
            bodyContainer.addView(tasksSection(snapshot))
            setDialogChromeVisible(true, diagnostic != null)
            return
        }
        if (!snapshot.dmMode) {
            bodyContainer.addView(searchRow(snapshot))
            bodyContainer.addView(filterRow(snapshot))
            bodyContainer.addView(toolsRow(snapshot))
            snapshot.messageCenter?.let {
                bodyContainer.addView(
                    messageCenterSection(it, ChannelExtras.pinnedMessages(snapshot.messages).size),
                )
            }
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
        val browse = snapshot.browseMessages
        if (browse != null) {
            bodyContainer.addView(browseSection(snapshot, browse))
        } else if (searchResults != null) {
            bodyContainer.addView(
                note("搜索“${snapshot.searchQuery}”：${searchResults.size} 条结果（点一条跳到原文；点“清除”回全量）"),
            )
            if (searchResults.isEmpty()) {
                bodyContainer.addView(note("没有搜到相关消息。"))
            } else {
                searchResults.forEach { raw ->
                    val rowView = messageRow(
                        teamMessageFromChannel(raw, snapshot.trustedAttributionPosterIds),
                        ChannelExtras.replyQuote(searchResults, raw),
                        isOwn = raw.agentId in snapshot.selfAgentIds,
                    )
                    rowView.setOnClickListener { onJumpToMessage(raw) }
                    bodyContainer.addView(rowView)
                }
            }
        } else if (snapshot.messages.isEmpty()) {
            bodyContainer.addView(
                note(
                    if (snapshot.notChannelMember) {
                        "无权限：你不是该频道成员。"
                    } else {
                        "频道里暂无消息。发一条“在吗”，看看谁回。"
                    },
                ),
            )
        } else if (snapshot.activeTopicThreadRootId != null) {
            val allViews = snapshot.messages.map { teamMessageFromChannel(it, snapshot.trustedAttributionPosterIds) }
            val activeRootId = snapshot.activeTopicThreadRootId
            val topicThreads = groupTopicThreads(allViews)
            val activeThread = topicThreads.firstOrNull { it.rootMessageId == activeRootId }
            val bannerTitle = activeThread?.let { teamThreadTitle(it) } ?: "话题讨论"
            val threadHeader = UiKit.horizontal(context).apply {
                setPadding(dp(16), dp(8), dp(16), dp(8))
                addView(
                    UiKit.text(context, "当前话题：$bannerTitle（点此返回全部）", 12f, R.color.accent, bold = true).apply {
                        setOnClickListener { onSelectTopicThread(null) }
                    },
                )
            }
            bodyContainer.addView(threadHeader)
            val msgs = activeThread?.messages ?: allViews.filter { it.messageId == activeRootId || it.replyToId == activeRootId }
            msgs.forEach { view ->
                val raw = snapshot.messages.firstOrNull { it.id == view.messageId }
                bodyContainer.addView(
                    messageRow(
                        view,
                        raw?.let { ChannelExtras.replyQuote(snapshot.messages, it) },
                        isOwn = view.senderId in snapshot.selfAgentIds,
                    ),
                )
            }
        } else {
            // 221 与 iOS 对齐：主消息流平铺——不按任务分串、不加串头，
            // 每条一张卡片按时间顺序排；话题聚焦走话题分支与话题清单。
            snapshot.messages.forEach { raw ->
                val view = teamMessageFromChannel(raw, snapshot.trustedAttributionPosterIds)
                bodyContainer.addView(
                    messageRow(
                        view,
                        ChannelExtras.replyQuote(snapshot.messages, raw),
                        isOwn = view.senderId in snapshot.selfAgentIds,
                    ),
                )
            }
        }
        bodyContainer.addView(newRoundRow())
        if (snapshot.taskCards.isNotEmpty() && snapshot.sectionTab != 2) {
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
                    (if (member.isLocalApp) "本机 App" else member.displayName) +
                        channelRoleLabel(member.role).let { if (it.isEmpty()) "" else " · $it" },
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
        // 逐频道通知档（本机偏好）：全部消息 → 仅@我 → 静音 循环切换。
        row.addView(
            UiKit.text(context, "通知：${snapshot.notifyMode}", 11f, R.color.text_secondary).apply {
                setBackgroundResource(R.drawable.bg_chip)
                setPadding(dp(8), dp(6), dp(8), dp(6))
                setOnClickListener {
                    val next = when (snapshot.notifyMode) {
                        "全部消息" -> "仅@我"
                        "仅@我" -> "静音"
                        else -> "全部消息"
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

    /** 「频道／私聊」分段（方案 A，Funk 2026-11-20 09:31 定；220 改分段条形态，与 iOS 同形）。 */
    private fun segmentRow(snapshot: TeamChannelSnapshot): LinearLayout {
        val row = UiKit.horizontal(context).apply { setPadding(0, dp(2), 0, dp(8)) }
        fun segment(label: String, active: Boolean, dm: Boolean) =
            UiKit.text(context, label, 12f, R.color.text_primary, bold = active).apply {
                gravity = android.view.Gravity.CENTER
                setBackgroundResource(if (active) R.drawable.bg_chip_accent else R.drawable.bg_chip)
                setPadding(dp(14), dp(7), dp(14), dp(7))
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                )
                setOnClickListener { onToggleDmMode(dm) }
            }
        row.addView(segment("频道", !snapshot.dmMode, dm = false))
        row.addView(segment("私聊", snapshot.dmMode, dm = true))
        return row
    }
    /** 页头随层级更新：列表层 ☰＋「频道」；对话层 ‹＋当前频道/私聊名（与 iOS 导航同构）。 */
    private fun updateChannelHeader(snapshot: TeamChannelSnapshot) {
        if (!::navButton.isInitialized || !::titleText.isInitialized) return
        if (snapshot.listMode) {
            navButton.text = "☰"
            titleText.text = "频道"
        } else {
            navButton.text = "‹"
            titleText.text = snapshot.channels
                .firstOrNull { it.id == snapshot.activeChannelId }?.name
                ?.takeIf { it.isNotBlank() } ?: "频道"
        }
    }

    // ---- 第一层列表页（频道区对齐 iOS，Funk 2026-11-25 批） ----

    /** 列表页搜索过滤词（频道按名称＋成员名、私聊按名称＋成员名/角色；本机过滤，不查服务端）。 */
    private var channelListQuery: String = ""

    /** 列表容器（搜索输入只重填它，不整页重渲染，保住输入焦点）。 */
    private var channelListContainer: LinearLayout? = null

    /** 搜索清除钮（iOS 搜索框同位：输入非空才显示，点按清空）。 */
    private val channelListClearButton = UiKit.text(context, "✕", 14f, R.color.text_secondary).apply {
        visibility = View.GONE
        setPadding(dp(10), dp(6), dp(4), dp(6))
        setOnClickListener { channelListSearchInput.setText("") }
    }

    private val channelListSearchInput: EditText = EditText(context).apply {
        hint = "搜索频道名称、简介或消息…"
        inputType = InputType.TYPE_CLASS_TEXT
        minLines = 1
        maxLines = 1
        setPadding(dp(10), dp(6), dp(10), dp(6))
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString().orEmpty()
                channelListClearButton.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
                if (q != channelListQuery) {
                    channelListQuery = q
                    val snap = snapshot
                    val box = channelListContainer
                    if (snap != null && box != null) fillChannelList(box, snap)
                }
            }
        })
    }

    private fun channelListSearchRow(): LinearLayout {
        // 跨 render 复用同一输入框：先从上一轮父容器摘下再挂新行。
        (channelListSearchInput.parent as? android.view.ViewGroup)?.removeView(channelListSearchInput)
        (channelListClearButton.parent as? android.view.ViewGroup)?.removeView(channelListClearButton)
        val row = UiKit.horizontal(context).apply { setPadding(0, 0, 0, dp(8)) }
        row.addView(
            channelListSearchInput.apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
                )
            },
        )
        channelListClearButton.visibility =
            if (channelListSearchInput.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        row.addView(channelListClearButton)
        return row
    }

    private fun fillChannelList(container: LinearLayout, snapshot: TeamChannelSnapshot) {
        container.removeAllViews()
        container.addView(
            if (snapshot.dmMode) dmListPage(snapshot) else channelListPage(snapshot),
        )
    }

    /** 频道列表（第一层）：点行进对话层；空态文案与 iOS 一字不差。 */
    private fun channelListPage(snapshot: TeamChannelSnapshot): LinearLayout {
        val col = UiKit.vertical(context)
        val all = ChannelExtras.groupChannels(snapshot.channels)
        val q = channelListQuery.trim()
        val shown = if (q.isEmpty()) all
            else all.filter { ch ->
                ch.name.contains(q, ignoreCase = true) ||
                    ch.participantDetails.any { it.agentName.contains(q, ignoreCase = true) }
            }
        if (shown.isEmpty()) {
            col.addView(
                channelListEmptyState(
                    title = if (q.isEmpty()) "暂无频道" else "未找到匹配的频道",
                    hint = "点击右上角「＋」可创建团队讨论频道",
                ),
            )
            return col
        }
        shown.forEach { ch -> col.addView(channelListRow(ch)) }
        return col
    }

    private fun channelListRow(ch: ChannelSummary): LinearLayout {
        val row = UiKit.vertical(context).apply {
            setPadding(dp(4), dp(10), dp(4), dp(10))
            setOnClickListener { onSwitchChannel(ch.id) }
            setOnLongClickListener {
                showChannelRowMenu(ch)
                true
            }
        }
        row.addView(
            UiKit.text(
                context,
                "# " + ch.name + (if (ch.archived) "（已归档）" else ""),
                15f,
                R.color.text_primary,
                bold = true,
            ),
        )
        val memberCount = ch.participantDetails.size.takeIf { it > 0 }
            ?: ch.participantIds.size
        if (memberCount > 0) {
            row.addView(
                UiKit.text(context, "$memberCount 位成员", 11f, R.color.text_tertiary).apply {
                    setPadding(0, dp(2), 0, 0)
                },
            )
        }
        return row
    }

    /** 频道行长按菜单（iOS 行长按同位：频道设置/静音/归档；静音走本机通知档，归档走服务端）。 */
    private fun showChannelRowMenu(ch: ChannelSummary) {
        val snap = snapshot ?: return
        val mode = snap.channelNotifyModes[ch.id] ?: "全部消息"
        val items = arrayOf(
            "频道设置",
            if (mode == "静音") "取消静音" else "设为静音",
            if (ch.archived) "取消归档" else "归档频道",
        )
        android.app.AlertDialog.Builder(context)
            .setTitle("# ${ch.name}")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> onChannelSettings(ch.id)
                    1 -> onSetChannelNotify(ch.id, if (mode == "静音") "全部消息" else "静音")
                    2 -> onToggleChannelArchived(ch.id, !ch.archived)
                }
            }
            .show()
    }

    /** 私聊列表（第一层）：已有私聊点行进对话层；下方是发起私聊名单（只给频道成员）。空态文案与 iOS 一字不差。 */
    private fun dmListPage(snapshot: TeamChannelSnapshot): LinearLayout {
        val col = UiKit.vertical(context)
        val q = channelListQuery.trim()
        val shown = if (q.isEmpty()) snapshot.dmChannels
            else snapshot.dmChannels.filter { dm ->
                dm.name.contains(q, ignoreCase = true) ||
                    dm.participantDetails.any {
                        it.agentName.contains(q, ignoreCase = true) ||
                            (it.role?.contains(q, ignoreCase = true) == true)
                    }
            }
        if (shown.isEmpty()) {
            col.addView(
                channelListEmptyState(
                    title = "暂无私聊",
                    hint = "点右上角「＋ 新建 → 发起私聊」，与频道成员开始 1:1 私聊\n（私聊只在频道成员之间，与单 Agent 对话并存）",
                ),
            )
        } else {
            shown.forEach { dm -> col.addView(dmListRow(dm)) }
        }
        if (snapshot.dmCandidates.isNotEmpty()) {
            col.addView(
                UiKit.text(context, "发起私聊", 12f, R.color.text_secondary, bold = true).apply {
                    setPadding(0, dp(14), 0, dp(4))
                },
            )
            snapshot.dmCandidates.forEach { member ->
                col.addView(
                    UiKit.text(context, member.displayName, 14f, R.color.text_primary).apply {
                        setPadding(dp(4), dp(9), dp(4), dp(9))
                        setOnClickListener { onStartDm(member.agentId) }
                    },
                )
            }
        }
        return col
    }

    private fun dmListRow(dm: ChannelSummary): LinearLayout {
        val row = UiKit.vertical(context).apply {
            setPadding(dp(4), dp(10), dp(4), dp(10))
            setOnClickListener { onSwitchChannel(dm.id) }
            setOnLongClickListener {
                showDmRowMenu(dm)
                true
            }
        }
        row.addView(UiKit.text(context, dm.name, 15f, R.color.text_primary, bold = true))
        return row
    }

    /** 私聊行长按菜单（iOS 行长按同位：私聊设置/静音）。 */
    private fun showDmRowMenu(dm: ChannelSummary) {
        val snap = snapshot ?: return
        val mode = snap.channelNotifyModes[dm.id] ?: "全部消息"
        val items = arrayOf(
            "私聊设置",
            if (mode == "静音") "取消静音" else "设为静音",
        )
        android.app.AlertDialog.Builder(context)
            .setTitle(dm.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> onDmSettings(dm.id)
                    1 -> onSetChannelNotify(dm.id, if (mode == "静音") "全部消息" else "静音")
                }
            }
            .show()
    }

    private fun channelListEmptyState(title: String, hint: String): LinearLayout {
        val col = UiKit.vertical(context).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(40), 0, dp(40))
        }
        col.addView(
            UiKit.text(context, title, 16f, R.color.text_primary, bold = true).apply {
                gravity = android.view.Gravity.CENTER
            },
        )
        col.addView(
            UiKit.text(context, hint, 12f, R.color.text_tertiary).apply {
                gravity = android.view.Gravity.CENTER
                setPadding(0, dp(6), 0, 0)
            },
        )
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
                setOnClickListener { runSearch() }
            },
        )
        val topicCount = groupTopicThreads(
            snapshot.messages.map { teamMessageFromChannel(it, snapshot.trustedAttributionPosterIds) }
        ).size
        row.addView(
            UiKit.text(context, if (topicCount > 0) "话题($topicCount)" else "话题", 12f, R.color.accent, bold = true).apply {
                setBackgroundResource(R.drawable.bg_chip_accent)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { leftMargin = dp(6) }
                setOnClickListener { showTopicThreadsDialog(snapshot) }
            },
        )
        if (snapshot.searchResults != null) {
            row.addView(
                UiKit.text(context, "清除", 12f, R.color.text_secondary).apply {
                    setPadding(dp(10), dp(6), dp(4), dp(6))
                    setOnClickListener {
                        searchInput.setText("")
                        filterSender = null
                        filterRangeIndex = 0
                        filterKind = null
                        onClearSearch()
                    }
                },
            )
        }
        return row
    }

    // ---- №12 搜索过滤 / 工具行 / 历史浏览 / @ 选择器 ----

    private fun smallChip(label: String, onClick: () -> Unit): TextView =
        UiKit.text(context, label, 11f, R.color.accent).apply {
            setPadding(dp(10), dp(5), dp(10), dp(5))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dp(6) }
            setOnClickListener { onClick() }
        }

    /** 用当前过滤状态跑一次搜索（关键词可为空：分类/成员/时间单独成搜）。 */
    private fun runSearch() {
        val (fromTs, toTs) = when (filterRangeIndex) {
            1 -> {
                val cal = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.HOUR_OF_DAY, 0)
                    set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0)
                    set(java.util.Calendar.MILLISECOND, 0)
                }
                (cal.timeInMillis / 1000) to null
            }
            2 -> (System.currentTimeMillis() / 1000 - 7 * 86400) to null
            3 -> (System.currentTimeMillis() / 1000 - 30 * 86400) to null
            else -> null to null
        }
        onSearchFiltered(
            searchInput.text.toString().trim(),
            filterSender, fromTs, toTs, filterKind,
        )
    }

    /** 过滤行：成员 / 时间 / 分类三枚循环切换 chip，点选即重跑搜索。 */
    private fun filterRow(snapshot: TeamChannelSnapshot): View {
        val inner = UiKit.horizontal(context).apply { setPadding(0, 0, 0, dp(8)) }
        inner.addView(
            UiKit.text(context, "过滤：", 11f, R.color.text_tertiary).apply {
                setPadding(0, dp(5), 0, dp(5))
            },
        )
        inner.addView(smallChip("成员：" + (filterSender ?: "全部")) {
            val names = snapshot.members.map { it.displayName }
                .filter { it.isNotBlank() }.distinct()
            val options = listOf<String?>(null) + names
            val idx = options.indexOf(filterSender)
            filterSender = options[(idx + 1) % options.size]
            snapshot.let { render(it) }
            runSearch()
        })
        val rangeLabels = listOf("全部时间", "今天", "近7天", "近30天")
        inner.addView(smallChip("时间：" + rangeLabels[filterRangeIndex]) {
            filterRangeIndex = (filterRangeIndex + 1) % rangeLabels.size
            snapshot.let { render(it) }
            runSearch()
        })
        val kindLabels = listOf(null to "全部", "image" to "图片", "file" to "文件", "link" to "链接")
        val kindIndex = kindLabels.indexOfFirst { it.first == filterKind }.coerceAtLeast(0)
        inner.addView(smallChip("分类：" + kindLabels[kindIndex].second) {
            filterKind = kindLabels[(kindIndex + 1) % kindLabels.size].first
            snapshot.let { render(it) }
            runSearch()
        })
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(inner)
        }
    }

    /** 工具行：跳到日期 + 新成员历史开关（仅普通频道）。 */
    private fun toolsRow(snapshot: TeamChannelSnapshot): View {
        val inner = UiKit.horizontal(context).apply { setPadding(0, 0, 0, dp(8)) }
        inner.addView(
            UiKit.text(context, "工具：", 11f, R.color.text_tertiary).apply {
                setPadding(0, dp(5), 0, dp(5))
            },
        )
        inner.addView(smallChip("跳到日期") {
            val cal = java.util.Calendar.getInstance()
            android.app.DatePickerDialog(
                context,
                { _, year, month, day ->
                    val picked = java.util.Calendar.getInstance().apply {
                        set(year, month, day, 0, 0, 0)
                        set(java.util.Calendar.MILLISECOND, 0)
                    }
                    onJumpToDate(picked.timeInMillis / 1000)
                },
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH),
                cal.get(java.util.Calendar.DAY_OF_MONTH),
            ).show()
        })
        if (snapshot.showHistoryToggle) {
            val label = if (snapshot.historyVisible) {
                "新成员看历史：开"
            } else {
                "新成员看历史：关"
            }
            inner.addView(smallChip(label) {
                if (snapshot.canManageHistory) {
                    onHistoryVisibleChange(!snapshot.historyVisible)
                } else {
                    noticeLine.text = "只有频道 owner/协调员能改“新成员看历史”开关。"
                }
            })
        }
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(inner)
        }
    }

    /** 历史浏览窗口（搜索跳原文 / 日期跳转共用）：横幅 + 翻页 + 高亮目标。 */
    private fun browseSection(
        snapshot: TeamChannelSnapshot,
        browse: List<PlatformChannelMessage>,
    ): LinearLayout {
        val col = UiKit.vertical(context)
        col.addView(note("正在查看历史消息；点“回到最新”返回实时消息。"))
        if (snapshot.browseHasOlder) {
            col.addView(smallChip("加载更早") { onBrowseLoadOlder() })
        }
        if (browse.isEmpty()) {
            col.addView(note("这一段没有消息。"))
        }
        var highlightView: View? = null
        browse.forEach { raw ->
            val rowView = messageRow(
                teamMessageFromChannel(raw, snapshot.trustedAttributionPosterIds),
                ChannelExtras.replyQuote(browse, raw),
                isOwn = raw.agentId in snapshot.selfAgentIds,
                highlight = raw.id == snapshot.browseHighlightId,
            )
            if (raw.id == snapshot.browseHighlightId) highlightView = rowView
            col.addView(rowView)
        }
        if (snapshot.browseHasNewer) {
            col.addView(smallChip("加载更新") { onBrowseLoadNewer() })
        }
        col.addView(smallChip("回到最新") { onExitBrowse() })
        highlightView?.let { hv ->
            val scroll = messageListView.scrollView
            scroll.post {
                val hvLoc = IntArray(2)
                hv.getLocationInWindow(hvLoc)
                val scLoc = IntArray(2)
                scroll.getLocationInWindow(scLoc)
                scroll.smoothScrollTo(0, (scroll.scrollY + hvLoc[1] - scLoc[1] - dp(48)).coerceAtLeast(0))
            }
        }
        return col
    }

    /** @ 成员选择器：输入 @词 时在输入栏上方列出匹配成员，点选补全。 */
    private fun updateMentionBar() {
        val text = composer.input.text.toString()
        val caret = composer.input.selectionStart.coerceIn(0, text.length)
        var at = -1
        var i = caret - 1
        while (i >= 0) {
            val ch = text[i]
            if (ch == '@') {
                at = i
                break
            }
            if (ch == ' ' || ch == '\n') break
            i--
        }
        if (at < 0 || (at > 0 && text[at - 1] != ' ' && text[at - 1] != '\n')) {
            mentionScroller.visibility = View.GONE
            return
        }
        val token = text.substring(at + 1, caret)
        val names = snapshot?.members?.map { it.displayName }
            ?.filter { it.isNotBlank() }?.distinct().orEmpty()
        val matches = names.filter {
            it.contains(token, ignoreCase = true) && !it.equals(token, ignoreCase = true)
        }.take(8)
        if (matches.isEmpty()) {
            mentionScroller.visibility = View.GONE
            return
        }
        mentionRangeStart = at
        mentionRangeEnd = caret
        mentionBar.removeAllViews()
        matches.forEachIndexed { index, name ->
            mentionBar.addView(
                UiKit.text(context, "@$name", 12f, R.color.accent, bold = true).apply {
                    setBackgroundResource(R.drawable.bg_chip)
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { if (index > 0) leftMargin = dp(6) }
                    setOnClickListener { applyMention(name) }
                },
            )
        }
        mentionScroller.visibility = View.VISIBLE
    }

    private fun applyMention(name: String) {
        val text = composer.input.text.toString()
        if (mentionRangeStart < 0 || mentionRangeEnd < mentionRangeStart) return
        val end = mentionRangeEnd.coerceAtMost(text.length)
        val newText = text.substring(0, mentionRangeStart) + "@" + name + " " +
            text.substring(end)
        composer.input.setText(newText)
        composer.input.setSelection(
            (mentionRangeStart + name.length + 2).coerceAtMost(newText.length),
        )
        mentionScroller.visibility = View.GONE
    }

    private fun showTopicThreadsDialog(snapshot: TeamChannelSnapshot) {
        val allViews = snapshot.messages.map { teamMessageFromChannel(it, snapshot.trustedAttributionPosterIds) }
        val topicThreads = groupTopicThreads(allViews)
        if (topicThreads.isEmpty()) {
            noticeLine.text = "当前频道暂无自由话题。点击任意消息下的「话题」即可发起。"
            return
        }
        val titles = topicThreads.map { thread ->
            val title = thread.topicTitle ?: "话题讨论"
            val count = thread.messages.size
            "话题 · $title ($count 条)"
        }
        ListDialog.show(context, "频道自由话题列表", "选择话题进入串内讨论：", titles) { which ->
            val chosen = topicThreads.getOrNull(which)
            if (chosen != null) {
                onSelectTopicThread(chosen.rootMessageId)
                val root = chosen.messages.firstOrNull()
                if (root != null) {
                    replyingTo = root
                    replyBanner.text = "正在话题回复 ${root.senderName}：${root.text.take(30)}（点此退出话题）"
                    replyBanner.visibility = View.VISIBLE
                }
            }
        }
    }

    /** 218 分段二（与 iOS 的消息/文件/任务分段同位同名；220 改分段条形态，与 iOS 同形）。 */
    private fun sectionTabsRow(snapshot: TeamChannelSnapshot): LinearLayout {
        val row = UiKit.horizontal(context).apply { setPadding(0, 0, 0, dp(8)) }
        val labels = listOf("消息", "文件", "任务")
        labels.forEachIndexed { index, label ->
            val active = snapshot.sectionTab == index
            row.addView(
                UiKit.text(context, label, 12f, R.color.text_primary, bold = active).apply {
                    gravity = android.view.Gravity.CENTER
                    setBackgroundResource(if (active) R.drawable.bg_chip_accent else R.drawable.bg_chip)
                    setPadding(dp(14), dp(7), dp(14), dp(7))
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f,
                    )
                    setOnClickListener { onSectionTabChange(index) }
                },
            )
        }
        return row
    }
    /** 文件分段：服务端 kind 过滤拉回的文件类消息（引用制呈现同消息行）。 */
    private fun filesSection(snapshot: TeamChannelSnapshot): LinearLayout {
        val col = UiKit.vertical(context)
        if (!snapshot.filesLoaded) {
            col.addView(note("正在加载频道文件…"))
        } else if (snapshot.channelFiles.isEmpty()) {
            col.addView(note("本频道暂无文件类消息（图片 / 文件 / 链接）。"))
        } else {
            snapshot.channelFiles.forEach { raw ->
                col.addView(
                    messageRow(
                        teamMessageFromChannel(raw, snapshot.trustedAttributionPosterIds),
                        ChannelExtras.replyQuote(snapshot.channelFiles, raw),
                        isOwn = raw.agentId in snapshot.selfAgentIds,
                    ),
                )
            }
        }
        return col
    }

    /**
     * 任务分段（缺口如实记录，218）：服务端没有按频道的任务接口，
     * 这里只显示本机从本频道派发的任务卡（本会话回显，与 iOS 读本机
     * 任务同口径）；频道历史任务无法从服务端拉取，不占位冒充。
     */
    private fun tasksSection(snapshot: TeamChannelSnapshot): LinearLayout {
        val col = UiKit.vertical(context)
        if (snapshot.dispatchAvailable) {
            col.addView(dispatchTasksSection(snapshot))
            col.addView(note("以下为本机协同任务卡（本会话回显），与上方派单任务是两套账。"))
        } else {
            col.addView(note("服务端暂无按频道的任务接口：此处只显示本机从本频道派发的任务，频道历史任务无法从服务端拉取，完整任务请到任务列表查看。"))
        }
        if (snapshot.taskCards.isEmpty()) {
            col.addView(note("本会话还没有从本频道派发的任务。用输入栏「转为正式任务」发送即在此留卡。"))
        } else {
            col.addView(taskCardsSection(snapshot.taskCards))
        }
        return col
    }

    /**
     * 派单任务区（任务派工打通，W11）：数据来自 App 内嵌任务服务
     * （list_tasks 按 channel_id 过滤），区别于下方本机协同任务卡。
     * 派单/详情/操作都在本区内完成，动作后走 onRefresh 重建快照。
     */
    private fun dispatchTasksSection(snapshot: TeamChannelSnapshot): LinearLayout {
        val col = UiKit.vertical(context).apply { setPadding(0, 0, 0, dp(8)) }
        val header = UiKit.horizontal(context)
        header.addView(
            UiKit.text(
                context, "派单任务 (${snapshot.dispatchTasks.size})",
                12f, R.color.text_primary, bold = true,
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
                )
            },
        )
        header.addView(
            UiKit.text(context, "＋ 派单", 12f, R.color.text_primary, bold = true).apply {
                setBackgroundResource(R.drawable.bg_chip)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener {
                    showCreateDispatchTaskDialog(
                        context,
                        snapshot.activeChannelId,
                        snapshot.members.map { it.agentId to it.displayName },
                    ) { onRefresh() }
                }
            },
        )
        col.addView(header)
        if (snapshot.dispatchError != null) {
            col.addView(note("派单任务加载失败：${snapshot.dispatchError}"))
        } else if (snapshot.dispatchTasks.isEmpty()) {
            col.addView(note("本频道还没有派单任务。点「派单」把活派给一位 Agent，进度和交付会回到本频道。"))
        } else {
            for (task in snapshot.dispatchTasks) {
                col.addView(dispatchTaskCard(snapshot, task))
            }
        }
        return col
    }

    private fun dispatchTaskCard(snapshot: TeamChannelSnapshot, task: DispatchTaskItem): LinearLayout {
        val card = UiKit.vertical(context).apply {
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
            setOnClickListener {
                showDispatchTaskDetailDialog(
                    context, task.taskId,
                    snapshot.members.map { it.agentId to it.displayName },
                ) { onRefresh() }
            }
        }
        val titleRow = UiKit.horizontal(context)
        titleRow.addView(
            UiKit.text(context, task.title, 13f, R.color.text_primary, bold = true).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
                )
            },
        )
        titleRow.addView(UiKit.text(context, task.stateLabel, 11f, R.color.text_secondary, bold = true))
        card.addView(titleRow)
        card.addView(
            UiKit.text(context, task.goal, 12f, R.color.text_secondary).apply {
                setPadding(0, dp(3), 0, 0)
                maxLines = 2
            },
        )
        val meta = StringBuilder("执行：${task.assigneeName}")
        if (task.reviewerName != null) meta.append(" · 审查：${task.reviewerName}")
        if (task.maxRounds > 1) meta.append(" · 第 ${task.round}/${task.maxRounds} 轮")
        card.addView(
            UiKit.text(context, meta.toString(), 10f, R.color.text_tertiary).apply {
                setPadding(0, dp(3), 0, 0)
            },
        )
        return card
    }

    /** 消息中心（聚合 @我 与未读；点条目不跳转，只呈现，频道补全三期）。
     *  218 计数口径 B（与 iOS 同定义同注记）：置顶并入本行；三项均为
     *  当前已加载消息内统计，行下明示范围，不冒充全局数。 */
    private fun messageCenterSection(center: MessageCenter, pinnedCount: Int): LinearLayout {
        val col = UiKit.vertical(context).apply { setPadding(0, 0, 0, dp(8)) }
        col.addView(
            UiKit.text(
                context,
                "消息中心 · 未读 ${center.unreadCount} · @我未读 ${center.unreadMentionCount} · 置顶 $pinnedCount",
                12f,
                R.color.text_primary,
                bold = true,
            ),
        )
        col.addView(
            UiKit.text(context, "计数按当前已加载消息统计", 10f, R.color.text_tertiary),
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

    /**
     * 一条频道消息（221 与 iOS 卡片式对齐）：白卡圆角描边，头部是
     * 头像圈＋姓名（粗）＋右侧时间，其下回复引用行、正文与元信息行；
     * 动作（回复/发起话题/置顶/反应/转发/已读/撤回/删除/复制/转为
     * 正式任务）收进长按菜单，与 iOS 长按菜单同集合同顺序。
     */
    private fun messageRow(
        message: TeamThreadMessage,
        replyQuote: String? = null,
        isOwn: Boolean = false,
        highlight: Boolean = false,
    ): LinearLayout {
        val col = UiKit.vertical(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(
                    if (highlight) {
                        androidx.core.content.ContextCompat.getColor(context, R.color.accent_soft)
                    } else {
                        android.graphics.Color.WHITE
                    },
                )
                setStroke(dp(1), android.graphics.Color.rgb(232, 230, 225))
            }
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            }
        }
        // 头部：头像圈（姓名首字，颜色按姓名稳定取）＋姓名＋右侧时间。
        val header = UiKit.horizontal(context).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val avatarColors = intArrayOf(
            android.graphics.Color.rgb(110, 132, 155),
            android.graphics.Color.rgb(150, 118, 92),
            android.graphics.Color.rgb(104, 142, 110),
            android.graphics.Color.rgb(140, 110, 160),
            android.graphics.Color.rgb(96, 128, 168),
            android.graphics.Color.rgb(168, 120, 96),
        )
        val avatarColor =
            avatarColors[(message.senderName.hashCode() and Int.MAX_VALUE) % avatarColors.size]
        header.addView(
            UiKit.text(
                context,
                message.senderName.trim().take(1).uppercase(),
                13f,
                android.R.color.white,
                bold = true,
            ).apply {
                gravity = android.view.Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(avatarColor)
                }
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                    marginEnd = dp(8)
                }
            },
        )
        header.addView(
            UiKit.text(context, message.senderName, 14f, R.color.text_primary, bold = true),
        )
        header.addView(
            View(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
            },
        )
        header.addView(
            UiKit.text(context, formatTime(message.createdAtSeconds), 11f, R.color.text_tertiary),
        )
        col.addView(header)
        if (replyQuote != null) {
            col.addView(
                UiKit.text(context, replyQuote, 11f, R.color.text_tertiary)
                    .apply { setPadding(0, dp(4), 0, 0) },
            )
        }
        message.forwardOf?.takeIf { it.isNotBlank() }?.let {
            col.addView(UiKit.text(context, "转发自 $it", 10f, R.color.text_tertiary))
        }
        val rendered = ChannelMarkdownRenderer.render(message.text)
        val msgTv = UiKit.text(context, rendered, 13f, R.color.text_primary).apply {
            if (message.text.contains("http://") || message.text.contains("https://")) {
                movementMethod = android.text.method.LinkMovementMethod.getInstance()
            }
        }
        col.addView(msgTv)
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
            col.setOnLongClickListener {
                showMessageActions(message, isOwn)
                true
            }
        }
        return col
    }

    /** 长按消息菜单（221 与 iOS 长按菜单同集合同顺序）。 */
    private fun showMessageActions(message: TeamThreadMessage, isOwn: Boolean) {
        val labels = mutableListOf<String>()
        val handlers = mutableListOf<() -> Unit>()
        fun add(label: String, handler: () -> Unit) {
            labels += label
            handlers += handler
        }
        add("回复") {
            replyingTo = message
            replyBanner.text = "正在回复 ${message.senderName}：${message.text.take(30)}（点此取消）"
            replyBanner.visibility = View.VISIBLE
        }
        add("发起话题") {
            onSelectTopicThread(message.messageId)
            replyingTo = message
            replyBanner.text = "正在话题回复 ${message.senderName}：${message.text.take(30)}（点此退出话题）"
            replyBanner.visibility = View.VISIBLE
        }
        add(if (message.pinned) "取消置顶" else "置顶") {
            onTogglePin(message.messageId, !message.pinned)
        }
        ChannelExtras.REACTION_CHOICES.take(2).forEach { emoji ->
            add(emoji) {
                val on = !ChannelExtras.myReaction(message.reactions, emoji)
                onReact(message.messageId, emoji, on)
            }
        }
        add("转发") { onForward(message) }
        add("已读情况") { onShowReaders(message.messageId) }
        if (isOwn) {
            add("撤回") { onMessageRecall(message.messageId) }
        }
        add("删除") { onMessageDelete(message.messageId) }
        add("复制") {
            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText("message", message.text),
            )
        }
        if (onCreateTask != null) {
            add("转为正式任务") {
                onCreateTask.invoke(channelTaskGoal(message.senderName, message.text)) {}
            }
        }
        ListDialog.show(context, message.senderName, null, labels) { which ->
            handlers.getOrNull(which)?.invoke()
        }
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
