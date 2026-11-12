package wherevercode.agent.ui

import android.content.Context
import android.content.SharedPreferences
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
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
import wherevercode.agent.core.team.ChannelAccess
import wherevercode.agent.core.team.ChannelHistory
import wherevercode.agent.core.team.ChannelKeyValueStore
import wherevercode.agent.core.team.ChannelMuteStore
import wherevercode.agent.core.team.ChannelQuote
import wherevercode.agent.core.team.ChannelSendTracker
import wherevercode.agent.core.team.ChannelTaskLink
import wherevercode.agent.core.team.FanoutBranchStatus
import wherevercode.agent.core.team.FanoutRun
import wherevercode.agent.core.team.PlatformChannelInfo
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
    /** 本账号的频道目录（`agent_channel_list` 结果；未接线时为空表，只显示当前频道）。 */
    val channels: List<PlatformChannelInfo> = emptyList(),
    /** 我方身份是否已加入当前频道（未加入时只能看名和成员，不能读/发；默认 true = 保持现有行为）。 */
    val joined: Boolean = true,
    /** 当前频道的显示名（未接线时为 null，显示频道 id）。 */
    val currentChannelName: String? = null,
)

/** SharedPreferences 适配 [ChannelKeyValueStore]（频道屏蔽等本机存储用）。 */
internal class SharedPreferencesChannelKeyValueStore(
    private val prefs: SharedPreferences,
) : ChannelKeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
}

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
 *   转到它的 Hand/API，回复贴回频道。
 * - 短消息：一条最多 500 字，超了发不出去，当场明说。
 * - 轮次上限 3 轮：用户每发一条消息计一轮，到顶后输入栏锁住，点
 *   “开始新一轮”才继续。
 * - 建议-only：页上没有任何改任务状态、认领、标记完成的入口——只有
 *   发消息、找某位成员 1:1 聊、只读扇出三个动作；想派活回一对一聊天
 *   走正常派活流程。频道消息关联任务也只是讨论备注（归属头），
 *   绝不调任务接口、不冒充派工。
 * - 只读扇出 ≤5：一次最多问 5 个分支，分支只回摘要，不并行写。
 *
 * 频道能力补齐（2026-11-13）：
 * - 频道目录：顶部显示当前频道；目录接线后列出全部频道（已加入的点
 *   按切换、未加入的点按加入）；创建频道没有入口——`agent_channel_create`
 *   服务端有但 Funk 只授权了 list/join/messages/post，create 保持屏蔽。
 * - 搜索：搜本机已加载的消息（正文/发送者）；全量搜（服务端 `q`）
 *   待传输层接线，搜不到要明说只搜了已加载的。
 * - 分页：默认显示最新 20 条，“加载更早”每次 +20（本机已加载的范围）；
 *   更早的在服务器上（`before_id`），待传输层接线。
 * - 引用回复：每条消息下都有可见的“回复”按钮（不藏长按），引用行
 *   编进正文第一行，读回渲染成引用块；结构化 `reply_to` 待传输层接线。
 * - 任务关联：发帖时可填任务编号，正文前加归属头、读回按任务分串；
 *   只作备注，不派活。
 * - 成员管理：点成员芯片展开详情——@他、1:1 聊天、本机屏蔽（只影响
 *   本机显示）；服务端没有踢人/退频道工具，成员增减走平台。
 * - 发送回音：发完等频道回音，30 秒没回音提示“可能没发出去”，
 *   手动点“重发”（绝不自动重发，避免造重复消息）。
 * - 权限门：未加入频道时只显示频道名和成员，不读消息、不发消息；
 *   参与者名单接线后，非成员的消息不显示、不中转（[ChannelAccess]）。
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
    /** 切换到另一个已加入的频道（MainActivity 接线后生效；未接线时点按无动作）。 */
    private val onSelectChannel: (channelId: String) -> Unit = {},
    /** 加入一个频道（MainActivity 接线后生效；未接线时点按无动作）。 */
    private val onJoinChannel: (channelId: String) -> Unit = {},
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

    /** 频道内搜索框（固定行，搜本机已加载的消息）。 */
    private val searchInput: EditText = EditText(context).apply {
        hint = "在频道里搜消息（只搜本机已加载的）…"
        inputType = InputType.TYPE_CLASS_TEXT
        maxLines = 1
        setPadding(dp(10), dp(8), dp(10), dp(8))
    }

    /** 引用回复条（有待发引用时才显示）。 */
    private val quoteBar: LinearLayout = UiKit.horizontal(context).apply {
        visibility = View.GONE
        setPadding(0, 0, 0, dp(6))
    }
    private val quoteText: TextView = UiKit.text(context, "", 11f, R.color.text_tertiary)

    /** 关联任务行（点“关联任务”展开任务编号输入框）。 */
    private val taskInput: EditText = EditText(context).apply {
        hint = "任务编号（前 8 位），只作讨论备注，不派活"
        inputType = InputType.TYPE_CLASS_TEXT
        maxLines = 1
        visibility = View.GONE
        setPadding(dp(10), dp(8), dp(10), dp(8))
    }

    /** 重发条（有超 30 秒没回音的待发时才显示）。 */
    private val retryRow: LinearLayout = UiKit.horizontal(context).apply {
        visibility = View.GONE
        setPadding(0, dp(6), 0, 0)
    }

    private val muteStore: ChannelMuteStore = ChannelMuteStore(
        SharedPreferencesChannelKeyValueStore(
            context.getSharedPreferences("team_channel_prefs", Context.MODE_PRIVATE),
        ),
    )
    private val sendTracker: ChannelSendTracker = ChannelSendTracker()

    private var snapshot: TeamChannelSnapshot? = null
    private var lastChannelId: String? = null
    private var searchQuery: String = ""
    private var visibleMessageCount: Int = ChannelHistory.PAGE_SIZE
    private var pendingQuote: ChannelQuote? = null
    private var selectedMemberId: String? = null

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
        root.addView(buildSearchRow())
        scrollView.addView(content)
        content.addView(bodyContainer)
        root.addView(scrollView)
        root.addView(buildInputBar())
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString().orEmpty()
                rerender()
            }
        })
    }

    private fun buildSearchRow(): LinearLayout {
        val row = UiKit.horizontal(context).apply {
            setPadding(dp(16), 0, dp(16), dp(6))
        }
        row.addView(
            searchInput.apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        row.addView(
            chip("清除") { searchInput.setText("") },
        )
        return row
    }

    private fun buildInputBar(): LinearLayout {
        val bar = UiKit.vertical(context).apply {
            setPadding(dp(16), dp(8), dp(16), dp(12))
        }
        quoteBar.addView(
            quoteText.apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        quoteBar.addView(
            chip("✕") { clearQuote() },
        )
        bar.addView(quoteBar)
        val taskRow = UiKit.horizontal(context)
        taskRow.addView(
            chip("关联任务") {
                val show = taskInput.visibility != View.VISIBLE
                taskInput.visibility = if (show) View.VISIBLE else View.GONE
                if (!show) taskInput.setText("")
            },
        )
        taskRow.addView(
            UiKit.text(context, ChannelTaskLink.NOT_DISPATCH_NOTE, 10f, R.color.text_tertiary).apply {
                setPadding(dp(10), 0, 0, 0)
            },
        )
        bar.addView(taskRow)
        bar.addView(
            taskInput.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            },
        )
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
        retryRow.addView(
            UiKit.text(context, "上一条可能没发出去（未收到回音）", 11f, R.color.text_tertiary).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        retryRow.addView(
            chip("重发", accent = true) { resendStale() },
        )
        bar.addView(retryRow)
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
        if (!snap.joined) {
            noticeLine.text = ChannelAccess.postBlockedReason(false)
            return
        }
        val body = messageInput.text.toString().trim()
        if (body.isBlank()) {
            noticeLine.text = TeamChatRules.validatePost("", snap.roundsUsed)
            return
        }
        // 组合顺序：任务头 → 引用行 → 正文（归属头必须在正文开头，读回才认得出任务）。
        var composed = body
        pendingQuote?.let { composed = ChannelQuoteCodec.encode(it, composed) }
        val taskId = if (taskInput.visibility == View.VISIBLE) {
            taskInput.text.toString().trim()
        } else {
            ""
        }
        if (taskId.isNotBlank()) composed = ChannelTaskLink.encode(taskId, null, composed)
        val error = TeamChatRules.validatePost(composed, snap.roundsUsed)
        if (error != null) {
            noticeLine.text = error
            return
        }
        noticeLine.text = ""
        messageInput.setText("")
        clearQuote()
        taskInput.setText("")
        taskInput.visibility = View.GONE
        sendTracker.markSent(composed)
        updateSendRows()
        onPost(composed)
    }

    private fun resendStale() {
        val snap = snapshot ?: return
        if (!snap.joined) return
        val stale = sendTracker.stalePending().lastOrNull() ?: return
        sendTracker.markSent(stale.text)
        updateSendRows()
        onPost(stale.text)
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

    private fun clearQuote() {
        pendingQuote = null
        updateQuoteBar()
    }

    private fun updateQuoteBar() {
        val quote = pendingQuote
        if (quote == null) {
            quoteBar.visibility = View.GONE
        } else {
            quoteBar.visibility = View.VISIBLE
            quoteText.text = "回复 ${quote.senderName}：${quote.excerpt}"
        }
    }

    private fun updateSendRows() {
        retryRow.visibility =
            if (sendTracker.stalePending().isNotEmpty()) View.VISIBLE else View.GONE
    }

    /** 本机 UI 状态变化后的重渲染（搜索、选成员、加载更早等不经过 MainActivity）。 */
    private fun rerender() {
        snapshot?.let { render(it) }
    }

    fun render(snapshot: TeamChannelSnapshot) {
        // 换频道后本机窗口状态重置（搜索词保留，引用与选中的成员不跨频道）。
        if (lastChannelId != null && lastChannelId != snapshot.channelId) {
            visibleMessageCount = ChannelHistory.PAGE_SIZE
            pendingQuote = null
            updateQuoteBar()
            selectedMemberId = null
        }
        lastChannelId = snapshot.channelId
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
        // 发送回音：对一下快照里有没有刚发的回音，再拼提示行。
        sendTracker.matchEcho(snapshot.messages)
        val pendingCount = sendTracker.pendingNow().size
        val staleCount = sendTracker.stalePending().size
        val noticeParts = mutableListOf<String>()
        snapshot.notice?.takeIf { it.isNotBlank() }?.let { noticeParts += it }
        if (pendingCount > 0 && staleCount == 0) noticeParts += "发送中…"
        if (staleCount > 0) noticeParts += "上一条可能没发出去，可点“重发”"
        noticeLine.text = noticeParts.joinToString("；")
        updateSendRows()
        bodyContainer.removeAllViews()
        if (!snapshot.configured) {
            bodyContainer.addView(note(snapshot.setupHint))
            return
        }
        bodyContainer.addView(channelSection(snapshot))
        bodyContainer.addView(membersRow(snapshot))
        selectedMemberId?.let { id ->
            snapshot.members.firstOrNull { it.agentId == id }?.let { member ->
                bodyContainer.addView(memberDetailRow(snapshot, member))
            }
        }
        if (!snapshot.joined) {
            // 未加入：服务端只给看频道名和成员（上面两行），消息与输入都不给。
            bodyContainer.addView(note("你还没加入这个频道：只能看到频道名和成员，不能读消息、不能发消息。"))
            return
        }
        if (snapshot.unreadCount > 0) {
            bodyContainer.addView(unreadRow(snapshot))
        }
        // 本机屏蔽：只影响本机显示（被屏蔽成员的消息不渲染，条数如实写清）。
        val visibleMessages = snapshot.messages.filter { !isMutedMessage(snapshot.channelId, it) }
        val mutedCount = snapshot.messages.size - visibleMessages.size
        if (mutedCount > 0) {
            bodyContainer.addView(note("已屏蔽成员的消息 $mutedCount 条（点成员可取消屏蔽）。"))
        }
        if (searchQuery.isNotBlank()) {
            val matches = ChannelHistory.search(visibleMessages, searchQuery)
            bodyContainer.addView(
                note("找到 ${matches.size} 条（只搜了本机已加载的 ${visibleMessages.size} 条）。"),
            )
            if (matches.isEmpty()) {
                bodyContainer.addView(note("没有匹配的消息。换个词试试，或去服务器上搜（待接线）。"))
            } else {
                matches.forEach { message ->
                    bodyContainer.addView(messageRow(teamMessageFromChannel(message)))
                }
            }
        } else if (visibleMessages.isEmpty()) {
            bodyContainer.addView(note("频道里暂无消息。发一条“在吗”，看看谁回。"))
        } else {
            val olderRemaining = ChannelHistory.olderRemaining(visibleMessages, visibleMessageCount)
            if (olderRemaining > 0) {
                bodyContainer.addView(loadOlderRow(olderRemaining))
            }
            val window = ChannelHistory.visibleWindow(visibleMessages, visibleMessageCount)
            // 2026-11-12 团队对话分串：消息按任务成组/成串，一个任务
            // 的讨论不混进另一个任务里；每条带发送者 + 任务归属。
            // 归属头从正文解析（App 贴回的中转回复带的头），无头
            // 的消息进“综合讨论”串。
            val threads = groupTeamMessages(window.map { teamMessageFromChannel(it) })
            threads.forEach { thread ->
                bodyContainer.addView(threadHeaderRow(thread))
                thread.messages.forEach { bodyContainer.addView(messageRow(it)) }
            }
        }
        bodyContainer.addView(newRoundRow())
        bodyContainer.addView(fanoutSection(snapshot))
    }

    /** 频道区：当前频道 + 目录（接线后才有目录；没目录只显示当前频道）。 */
    private fun channelSection(snapshot: TeamChannelSnapshot): LinearLayout {
        val col = UiKit.vertical(context).apply { setPadding(0, dp(4), 0, dp(4)) }
        val title = snapshot.currentChannelName?.trim()?.takeIf { it.isNotBlank() }
            ?: snapshot.channelId
        col.addView(
            UiKit.text(
                context,
                "频道：$title" + if (!snapshot.joined) "（未加入）" else "",
                12f,
                R.color.text_primary,
                bold = true,
            ),
        )
        if (!snapshot.joined) {
            col.addView(
                chip("加入频道", accent = true) { onJoinChannel(snapshot.channelId) }.apply {
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                },
            )
            return col
        }
        if (snapshot.channels.isEmpty()) return col
        val scroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        val chips = UiKit.horizontal(context)
        snapshot.channels.forEach { channel ->
            val label = (if (channel.id == snapshot.channelId) "● " else "") +
                channel.name + if (!channel.joined) "（加入）" else ""
            chips.addView(
                chip(label) {
                    if (channel.id == snapshot.channelId) return@chip
                    if (channel.joined) onSelectChannel(channel.id) else onJoinChannel(channel.id)
                },
            )
        }
        scroller.addView(chips)
        col.addView(scroller)
        return col
    }

    private fun loadOlderRow(olderRemaining: Int): LinearLayout {
        val row = UiKit.horizontal(context).apply { setPadding(0, 0, 0, dp(8)) }
        row.addView(
            UiKit.text(context, "更早还有 $olderRemaining 条", 11f, R.color.text_tertiary).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        row.addView(
            chip("加载更早") {
                visibleMessageCount += ChannelHistory.PAGE_SIZE
                rerender()
            },
        )
        return row
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
                chip(
                    if (member.isLocalApp) "本机 App" else member.displayName,
                ) {
                    selectedMemberId =
                        if (selectedMemberId == member.agentId) null else member.agentId
                    rerender()
                },
            )
        }
        scroller.addView(chips)
        row.addView(scroller)
        return row
    }

    /** 成员详情条：点成员芯片展开——@他、1:1 聊天、本机屏蔽（点一下就有入口，不藏长按）。 */
    private fun memberDetailRow(snapshot: TeamChannelSnapshot, member: TeamChannelMember): LinearLayout {
        val col = UiKit.vertical(context).apply { setPadding(0, dp(2), 0, dp(8)) }
        val role = when {
            member.isLocalApp -> "本机 App（你）"
            member.isPlatformAgent -> "平台 Agent · 端点直连"
            else -> "设备/云端 Agent · 由调度器中转"
        }
        col.addView(
            UiKit.text(context, "${member.displayName} · $role", 12f, R.color.text_primary, bold = true),
        )
        val row = UiKit.horizontal(context).apply { setPadding(0, dp(4), 0, 0) }
        if (!member.isLocalApp) {
            row.addView(
                chip("@他") { messageInput.append("@${member.displayName} ") },
            )
            row.addView(
                chip("1:1 聊天") { onOpenChatWithAgent(member.agentId) },
            )
            val muted = muteStore.isMuted(snapshot.channelId, member.agentId, member.displayName)
            row.addView(
                chip(if (muted) "取消屏蔽" else "屏蔽") {
                    muteStore.setMuted(
                        snapshot.channelId,
                        member.agentId,
                        member.displayName,
                        !muted,
                    )
                    rerender()
                },
            )
        }
        col.addView(row)
        col.addView(
            UiKit.text(context, "屏蔽只影响本机显示，不影响频道、不通知对方。", 10f, R.color.text_tertiary),
        )
        return col
    }

    private fun isMutedMessage(channelId: String, message: PlatformChannelMessage): Boolean {
        val view = teamMessageFromChannel(message)
        return muteStore.isMuted(channelId, message.agentId, view.senderName)
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
        // 引用回复：引用行不跟正文混在一起，渲染成引用块。
        val quote = ChannelQuoteCodec.parse(message.text)
        val body = ChannelQuoteCodec.strip(message.text)
        quote?.let {
            col.addView(
                UiKit.text(context, "↩ 回复 ${it.senderName}：${it.excerpt}", 11f, R.color.text_tertiary)
                    .apply { setPadding(dp(8), 0, 0, dp(2)) },
            )
        }
        col.addView(UiKit.text(context, body, 13f, R.color.text_primary))
        col.addView(
            UiKit.horizontal(context).apply { setPadding(0, dp(2), 0, 0) }.also { row ->
                row.addView(
                    chip("回复") {
                        pendingQuote = ChannelQuote(
                            messageId = message.messageId,
                            senderName = message.senderName,
                            excerpt = ChannelQuoteCodec.excerptOf(body),
                        )
                        updateQuoteBar()
                    },
                )
            },
        )
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

    /** 成员/操作芯片（与原来成员芯片同一外观；accent = 主操作样式）。 */
    private fun chip(text: String, accent: Boolean = false, onClick: () -> Unit = {}): TextView =
        UiKit.text(
            context,
            text,
            12f,
            if (accent) R.color.accent else R.color.text_primary,
            bold = accent,
        ).apply {
            setBackgroundResource(R.drawable.bg_chip_accent)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { onClick() }
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
