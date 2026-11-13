package wherevercode.agent.ui

import wherevercode.agent.R

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import java.util.Locale

/**
 * One chat turn, rendered as layers instead of one concatenated string:
 *
 *   user bubble
 *   Agent header + status
 *   collapsible reasoning
 *   collapsible tool-call cards (arguments + result inside each card)
 *   artifact cards (task products: icon + file name + type, tap to open)
 *   assistant prose bubble (Markdown 轻量渲染，见 [MarkdownLite])
 *   usage line, only when the speaker is a brain/model and real numbers
 *     came back (maintainer 2026-11-11: the page shows the current partner's
 *     real info — a remote Agent has no usage concept, and a brain that
 *     returned no numbers shows nothing, never "unknown")
 *   action icon row (copy / share / read aloud / like / dislike /
 *     regenerate — 只在轮次结束且有助手正文时出现)
 *   isolated-protocol notice
 *   notes / error
 *   one subtle status line while running (latest status only — the
 *     progress state never becomes a bubble, and it disappears when
 *     the reply arrives)
 *
 * The agent header shows the turn's speaker (brain name for brain turns,
 * agent display name for agent turns — in team mode this is how you can
 * tell which member replied); "AGENT" is only the fallback when the
 * speaker is unknown (restored old turns).
 *
 * Tool-call grouping (old project workbench.js groupOperations, native
 * Kotlin — NOT copied JS): a finished turn with 2+ tool calls folds its
 * cards under one group header ("已运行 N 条命令", or the kind-count
 * summary "已读取 N 个文件、运行 M 条命令"); tap expands. A running
 * turn never folds — the live run stays unfolded, exactly like the old
 * trailing-live rule. One call never folds.
 *
 * Tool calls and results never become prose in the assistant bubble. Raw
 * DSML never reaches this class at all: [ChatTimeline] sanitizes it.
 */
class ChatTurnView(
    context: Context,
    private val onCopy: ((String) -> Unit)? = null,
    private val onRegenerate: ((String) -> Unit)? = null,
) : LinearLayout(context) {

    private val userBubble: TextView
    private val agentHeader: LinearLayout
    private val agentLabel: TextView
    private val statusChip: TextView
    /**
     * 大脑/Agent 边界（maintainer 2026-11-12）：任务归属行——Agent 回复属于
     * 哪个任务（任务编号短形式 + 标题）。有归属时才显示，绝不让 Agent
     * 回复裸贴进无关对话。
     */
    private val taskAttributionLine: TextView
    /**
     * 大脑/Agent 边界（maintainer 2026-11-12）：路线标注行——本轮走的路
     * （直连/意图不明）的持久标注。
     */
    private val routeLabelLine: TextView
    private val reasoningCard: LinearLayout
    private val reasoningTitle: TextView
    private val reasoningChevron: ImageView
    private val reasoningBody: TextView
    private val assistantBubble: TextView
    private val usageLine: TextView
    private val actionRow: LinearLayout
    private val copyIcon: ImageView
    private val shareIcon: ImageView
    private val readAloudIcon: ImageView
    private val likeIcon: ImageView
    private val dislikeIcon: ImageView
    private val regenerateIcon: ImageView
    private val protocolChip: TextView
    private val toolContainer: LinearLayout
    private val toolGroupHeader: LinearLayout
    private val toolGroupText: TextView
    private val toolGroupChevron: ImageView
    private val attachmentContainer: LinearLayout
    private val notesContainer: LinearLayout
    private val errorCard: TextView
    private val errorDetailsToggle: TextView
    private val errorDetails: TextView
    private val errorRetry: TextView
    private val statusFooter: TextView

    private val toolCards = LinkedHashMap<String, ToolCallCardView>()
    private val attachmentCards = LinkedHashMap<String, ArtifactCardView>()
    private var reasoningExpanded = false
    /** 工具组折叠状态（本实例只绑一轮，见 bind 的轮次切换复位）。 */
    private var toolGroupExpanded = false
    private var boundTurn: ChatTurnUi? = null
    /** 本地赞/踩（只记在界面上，没有服务端投票接口，不发往任何地方）。 */
    private val feedback = HashMap<String, Boolean>()
    private var tts: TextToSpeech? = null
    private var readingAloud = false

    init {
        orientation = VERTICAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        setPadding(0, 0, 0, 0)

        // V5 C2-3-R1: 用户气泡照 V5 原型图——紫渐变（浅色默认 Claude 极简
        // 也是紫渐变发送气泡；深色档原型霓虹同色）。
        userBubble = UiKit.text(context, "", 15f, R.color.user_text).apply {
            background = userBubbleBackground()
            setPadding(dp(14), dp(11), dp(14), dp(11))
            setLineSpacing(dp(2).toFloat(), 1f)
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END
                topMargin = dp(14)
                bottomMargin = dp(2)
                marginStart = dp(52)
            }
            visibility = GONE
        }
        addView(userBubble)

        agentHeader = UiKit.horizontal(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
                bottomMargin = dp(4)
            }
        }
        val agentDot = View(context).apply {
            setBackgroundResource(R.drawable.bg_dot)
            layoutParams = LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(7) }
        }
        agentHeader.addView(agentDot)
        agentLabel = UiKit.text(context, "AGENT", 10f, R.color.home_link, bold = true).apply {
            letterSpacing = 0.08f
        }
        agentHeader.addView(agentLabel)
        statusChip = UiKit.text(context, "", 10f, R.color.text_secondary).apply {
            setPadding(dp(8), dp(3), dp(8), dp(3))
            setBackgroundResource(R.drawable.bg_chip)
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            }
        }
        agentHeader.addView(statusChip)
        addView(agentHeader)

        taskAttributionLine = UiKit.text(context, "", 10f, R.color.home_text_secondary).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2)
                bottomMargin = dp(4)
            }
            visibility = GONE
        }
        addView(taskAttributionLine)

        // 大脑/Agent 边界（maintainer 2026-11-12）：路线标注行——本轮走的路
        // （直连/意图不明）的持久标注，抬头下方小字常驻显示；无标注
        // 时不显示、不留旧文案。
        routeLabelLine = UiKit.text(context, "", 10f, R.color.home_text_secondary).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(2)
                bottomMargin = dp(4)
            }
            visibility = GONE
        }
        addView(routeLabelLine)

        reasoningCard = UiKit.vertical(context).apply {
            background = UiKit.rounded(context, R.color.home_card, 14, R.color.home_card_border, 1)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
            visibility = GONE
        }
        val reasoningHeader = UiKit.horizontal(context).apply {
            setPadding(dp(11), dp(9), dp(9), dp(9))
            isClickable = true
            isFocusable = true
        }
        reasoningTitle = UiKit.text(context, "思考过程", 11f, R.color.home_text_secondary, bold = true)
        reasoningHeader.addView(
            reasoningTitle,
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )
        reasoningChevron = UiKit.icon(context, R.drawable.ic_chevron, 16, R.color.text_tertiary)
        reasoningHeader.addView(reasoningChevron)
        reasoningBody = UiKit.text(context, "", 12f, R.color.home_text_secondary).apply {
            setPadding(dp(12), dp(9), dp(12), dp(11))
            setLineSpacing(dp(2).toFloat(), 1f)
            visibility = GONE
        }
        reasoningCard.addView(reasoningHeader)
        reasoningCard.addView(reasoningBody)
        reasoningHeader.setOnClickListener {
            reasoningExpanded = !reasoningExpanded
            reasoningBody.visibility = if (reasoningExpanded) VISIBLE else GONE
            reasoningChevron.rotation = if (reasoningExpanded) 90f else 0f
        }
        addView(reasoningCard)

        // V5 C2-3-R1b（v6-collab.js:412 appendLiveTimeline）：步骤行插在
        // 回答气泡前——工具卡容器先于助手气泡加入，时间线先步骤后正文。
        toolContainer = UiKit.vertical(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        addView(toolContainer)

        // 老项目流式参照（只学思路：重复的操作行收成一行可折叠汇总，
        // 不是照搬模板——新项目按自己的 README/设计做，见 V5-UI.md）。
        // 终态轮次工具 ≥2 个时，工具卡收进这行汇总头之下，点按展开；
        // 运行中不收（进行中的那组保持展开），单个工具不收。
        toolGroupHeader = UiKit.horizontal(context).apply {
            setPadding(dp(11), dp(9), dp(8), dp(9))
            isClickable = true
            isFocusable = true
            contentDescription = "工具组"
            visibility = GONE
        }
        toolGroupText = UiKit.text(context, "", 12f, R.color.home_text_secondary).apply {
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        toolGroupHeader.addView(toolGroupText)
        toolGroupChevron = UiKit.icon(context, R.drawable.ic_chevron, 16, R.color.text_tertiary)
        toolGroupHeader.addView(toolGroupChevron)
        toolGroupHeader.setOnClickListener {
            toolGroupExpanded = !toolGroupExpanded
            boundTurn?.let { syncToolCards(it) }
        }
        toolContainer.addView(toolGroupHeader)

        // V5 聊天清理 20261111：任务产物卡片插在回答气泡前（产物先于正文，
        // 与工具卡同序）；没有附件时容器为空，不凭空造卡片。
        attachmentContainer = UiKit.vertical(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        addView(attachmentContainer)

        assistantBubble = UiKit.text(context, "", 15f, R.color.home_text).apply {
            background = UiKit.rounded(context, R.color.home_card, 16, R.color.home_card_border, 1)
            setPadding(dp(14), dp(11), dp(14), dp(11))
            setLineSpacing(dp(3).toFloat(), 1f)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
            visibility = GONE
        }
        addView(assistantBubble)

        // 用量行（maintainer 2026-11-11：按对话方智能出现）：11sp 副文色小字，
        // 只在轮次结束、有助手层、说话者是大脑/模型、且真有数字时出现；
        // Agent 轮次（没有用量概念）、大脑没返回数字时都不出现，绝不写“未知”。
        usageLine = UiKit.text(context, "", 11f, R.color.home_text_secondary).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
            visibility = GONE
        }
        addView(usageLine)

        // V5 聊天清理 20261111：回复下方一排小图标（复制 / 分享 / 朗读 /
        // 赞 / 踩 / 重新生成），替代原来的文字链接。
        // 只在轮次结束且有助手正文时出现；运行中整行隐藏，避免中途复制
        // 半截回复。赞/踩只记在本地界面，没有服务端投票接口。
        actionRow = UiKit.horizontal(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
            visibility = GONE
        }
        copyIcon = actionIcon(context, R.drawable.ic_copy, "复制").apply {
            setOnClickListener { boundTurn?.let { onCopy?.invoke(it.assistantText) } }
        }
        actionRow.addView(copyIcon)
        shareIcon = actionIcon(context, R.drawable.ic_share, "分享").apply {
            setOnClickListener { boundTurn?.let { shareText(it.assistantText) } }
        }
        actionRow.addView(shareIcon)
        readAloudIcon = actionIcon(context, R.drawable.ic_volume, "朗读").apply {
            setOnClickListener { boundTurn?.let { toggleReadAloud(it.assistantText) } }
        }
        actionRow.addView(readAloudIcon)
        likeIcon = actionIcon(context, R.drawable.ic_thumb_up, "赞").apply {
            setOnClickListener { boundTurn?.let { setFeedback(it.id, true) } }
        }
        actionRow.addView(likeIcon)
        dislikeIcon = actionIcon(context, R.drawable.ic_thumb_down, "踩").apply {
            setOnClickListener { boundTurn?.let { setFeedback(it.id, false) } }
        }
        actionRow.addView(dislikeIcon)
        regenerateIcon = actionIcon(context, R.drawable.ic_refresh, "重新生成").apply {
            setOnClickListener { boundTurn?.let { onRegenerate?.invoke(it.userText) } }
        }
        actionRow.addView(regenerateIcon)
        addView(actionRow)

        protocolChip = UiKit.text(
            context,
            "已隔离原始工具协议（DSML），未进入聊天气泡",
            10f,
            R.color.warning,
        ).apply {
            setBackgroundResource(R.drawable.bg_chip_warning)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            }
            visibility = GONE
        }
        addView(protocolChip)

        notesContainer = UiKit.vertical(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }
        addView(notesContainer)

        errorCard = UiKit.text(context, "", 12f, R.color.danger).apply {
            setBackgroundResource(R.drawable.bg_error)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setLineSpacing(dp(2).toFloat(), 1f)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
                bottomMargin = dp(6)
            }
            visibility = GONE
        }
        addView(errorCard)

        errorDetails = UiKit.text(context, "", 10f, R.color.text_tertiary).apply {
            visibility = GONE
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundResource(R.drawable.bg_system_note)
        }
        addView(errorDetails)
        errorDetailsToggle = UiKit.text(context, "查看原始诊断", 11f, R.color.accent).apply {
            visibility = GONE
            setPadding(dp(4), dp(2), dp(4), dp(4))
            setOnClickListener {
                errorDetails.visibility = if (errorDetails.visibility == VISIBLE) GONE else VISIBLE
                text = if (errorDetails.visibility == VISIBLE) "收起原始诊断" else "查看原始诊断"
            }
        }
        addView(errorDetailsToggle)
        errorRetry = UiKit.text(context, "重试本轮", 11f, R.color.accent, bold = true).apply {
            visibility = GONE
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundResource(R.drawable.bg_chip_accent)
            setOnClickListener { boundTurn?.let { onRegenerate?.invoke(it.userText) } }
        }
        addView(errorRetry)

        statusFooter = UiKit.text(context, "", 11f, R.color.home_text_secondary).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
                bottomMargin = dp(8)
            }
            visibility = GONE
        }
        addView(statusFooter)
    }

    fun bind(turn: ChatTurnUi) {
        // 本实例只绑一轮（MainActivity 按轮次 id 复用视图）；轮次换了，
        // 工具组折叠回到默认收起，上一轮的展开状态不带到新轮次。
        if (boundTurn?.id != turn.id) toolGroupExpanded = false
        boundTurn = turn
        val systemOnly = turn.userText.isBlank() &&
            turn.assistantText.isBlank() &&
            turn.toolCalls.isEmpty() &&
            turn.notes.isNotEmpty()

        userBubble.text = turn.userText
        userBubble.visibility = if (turn.userText.isNotBlank()) VISIBLE else GONE
        userBubble.maxWidth = (resources.displayMetrics.widthPixels * 0.82f).toInt()

        val hasAssistantLayer = turn.assistantText.isNotBlank() ||
            turn.reasoning.isNotBlank() ||
            turn.toolCalls.isNotEmpty() ||
            turn.notes.isNotEmpty() ||
            turn.error != null
        val headerVisible = !systemOnly && (turn.status == TurnStatus.RUNNING || hasAssistantLayer)
        agentHeader.visibility = if (headerVisible) VISIBLE else GONE
        if (headerVisible) {
            // 轮次抬头写说话者的名（大脑轮次是大脑名，Agent 轮次是 Agent 名；
            // 团队模式下就靠它分清是哪位成员在回）。说话者未知时回落到“AGENT”
            // （恢复出的旧轮次没有说话者记录）。
            //
            // 大脑/Agent 边界（maintainer 2026-11-12，一个对话、一个说话者）：
            // 大脑轮次抬头加“大脑 · ”前缀，让说话者身份一眼可辨——大脑
            // 和 Agent 绝不能再用同一个含混标签。Agent 轮次只写 Agent 名。
            val speaker = turn.speaker.ifBlank { "AGENT" }
            agentLabel.text = if (turn.speakerIsBrain && turn.speaker.isNotBlank()) {
                "大脑 · $speaker"
            } else {
                speaker
            }
            statusChip.text = statusLabel(turn.status)
            statusChip.setTextColor(UiKit.color(context, statusColor(turn.status)))
            statusChip.setBackgroundResource(statusChipBackground(turn.status))
        } else {
            // Header is hidden (e.g. user-only turn): leave no stale text in
            // it, so the tree contains no agent header content. The chip text
            // was previously set unconditionally here, which made a hidden
            // header still carry "AGENT" / "完成" in the view tree.
            agentLabel.text = ""
            statusChip.text = ""
        }

        // 大脑/Agent 边界（maintainer 2026-11-12）：任务归属行——本轮回复属于
        // 哪个任务。编号只显示短形式（前 8 位），标题过长截断；无归属
        // 时不留旧文案（本文件惯例：隐藏的视图不带 stale 文本）。
        val attribution = turn.taskAttribution
        if (attribution != null && headerVisible) {
            val shortId = attribution.taskId.take(8)
            val title = attribution.taskTitle.trim().let {
                if (it.length > 40) it.take(40) + "…" else it
            }
            taskAttributionLine.text = if (title.isBlank()) {
                "任务 · $shortId"
            } else {
                "任务 · $shortId · $title"
            }
            taskAttributionLine.visibility = VISIBLE
        } else {
            taskAttributionLine.text = ""
            taskAttributionLine.visibility = GONE
        }

        // 大脑/Agent 边界（maintainer 2026-11-12）：路线标注行——本轮走的路
        // （直连/意图不明）常驻显示；无标注或 header 隐藏时不显示、
        // 不留旧文案。
        val routeLabel = turn.routeLabel
        if (!routeLabel.isNullOrBlank() && headerVisible) {
            routeLabelLine.text = routeLabel
            routeLabelLine.visibility = VISIBLE
        } else {
            routeLabelLine.text = ""
            routeLabelLine.visibility = GONE
        }

        if (turn.reasoning.isNotBlank()) {
            reasoningCard.visibility = VISIBLE
            reasoningTitle.text = "思考过程 · ${turn.reasoning.length} 字"
            reasoningBody.text = turn.reasoning
        } else {
            reasoningCard.visibility = GONE
        }

        if (turn.assistantText.isNotBlank()) {
            assistantBubble.visibility = VISIBLE
            assistantBubble.setTextColor(UiKit.color(context, R.color.home_text))
            assistantBubble.text = renderMarkdown(turn.assistantText)
        } else if (turn.status == TurnStatus.RUNNING && !turn.placeholder.isNullOrBlank()) {
            // V5 C2-3-R1b（v6-app.js:510）：运行中且正文未到，占位先贴在
            // 助手气泡里（副文色），首段正文到后整体替换。
            assistantBubble.visibility = VISIBLE
            assistantBubble.setTextColor(UiKit.color(context, R.color.home_text_secondary))
            assistantBubble.text = turn.placeholder
        } else {
            assistantBubble.visibility = GONE
        }

        syncAttachmentCards(turn)

        // 用量行按对话方智能出现（maintainer 2026-11-11）：只在轮次结束、有助手
        // 层、说话者是大脑/模型、且真有数字时显示；Agent 轮次没有用量概念，
        // 大脑没返回数字时也不显示“未知”这种废话。
        val usage = usageText(turn.usage)
        val showUsage = turn.status != TurnStatus.RUNNING &&
            turn.speakerIsBrain && usage != null && hasAssistantLayer
        usageLine.visibility = if (showUsage) VISIBLE else GONE
        // 隐藏时不留旧文案（本文件惯例：隐藏的视图不带 stale 文本），
        // 否则 GONE 的行仍会在视图树里被看到。
        usageLine.text = if (showUsage) usage else ""

        // V5 聊天清理 20261111：操作图标行只在轮次结束且有助手正文时出现。
        // 运行中整行隐藏（避免中途复制半截回复）。
        val finishedWithText = turn.status != TurnStatus.RUNNING && turn.assistantText.isNotBlank()
        actionRow.visibility = if (finishedWithText) VISIBLE else GONE
        if (finishedWithText) {
            applyFeedback(turn.id)
        }

        protocolChip.visibility = if (turn.protocolRedacted) VISIBLE else GONE

        syncToolCards(turn)

        notesContainer.removeAllViews()
        turn.notes.forEach { note ->
            notesContainer.addView(noteView(note))
        }

        if (turn.status == TurnStatus.ERROR && !turn.error.isNullOrBlank()) {
            errorCard.visibility = VISIBLE
            errorCard.text = friendlyError(turn.error)
            errorDetailsToggle.visibility = VISIBLE
            errorDetailsToggle.text = "查看原始诊断"
            errorDetails.visibility = GONE
            errorDetails.text = turn.error
            errorRetry.visibility = if (turn.userText.isNotBlank()) VISIBLE else GONE
        } else {
            errorCard.visibility = GONE
            errorDetailsToggle.visibility = GONE
            errorDetails.visibility = GONE
            errorRetry.visibility = GONE
        }

        // V5 聊天清理 20261111：进行中的状态只占一行 subtle 状态行——
        // 最新一条状态，没有就回落到“工具执行中…”/“正在生成…”；回复到了
        // （轮次结束）就直接消失，绝不逐条贴成气泡、绝不连刷。
        if (turn.status == TurnStatus.RUNNING) {
            val runningTool = turn.toolCalls.any { it.status == ToolCallStatus.RUNNING }
            statusFooter.visibility = VISIBLE
            statusFooter.text = turn.statusNote?.takeIf { it.isNotBlank() }
                ?: if (runningTool) "工具执行中…" else "正在生成…"
        } else {
            statusFooter.visibility = GONE
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopReading()
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
    }

    private fun syncToolCards(turn: ChatTurnUi) {
        // 老项目流式参照（只学思路）：终态轮次工具 ≥2 个时收成一组，
        // 默认折叠、点汇总头展开；运行中那组保持展开（进行中的活不藏），
        // 单个工具不成组。工具卡本身的状态（失败自动展开等）不受影响。
        val groupable = turn.status != TurnStatus.RUNNING && turn.toolCalls.size >= 2
        if (groupable) {
            toolGroupHeader.visibility = VISIBLE
            toolGroupText.text = toolCallGroupSummary(turn.toolCalls)
            toolGroupChevron.rotation = if (toolGroupExpanded) 90f else 0f
        } else {
            toolGroupHeader.visibility = GONE
        }
        val cardsVisible = !groupable || toolGroupExpanded
        val wanted = turn.toolCalls.map { it.id }.toSet()
        val stale = toolCards.keys.filter { it !in wanted }
        stale.forEach { id ->
            toolCards.remove(id)?.let { toolContainer.removeView(it) }
        }
        turn.toolCalls.forEach { call ->
            val card = toolCards.getOrPut(call.id) {
                ToolCallCardView(context).also { toolContainer.addView(it) }
            }
            card.bind(call)
            card.visibility = if (cardsVisible) VISIBLE else GONE
        }
    }

    private fun syncAttachmentCards(turn: ChatTurnUi) {
        val wanted = turn.attachments.map { it.key }.toSet()
        val stale = attachmentCards.keys.filter { it !in wanted }
        stale.forEach { key ->
            attachmentCards.remove(key)?.let { attachmentContainer.removeView(it) }
        }
        turn.attachments.forEach { attachment ->
            val card = attachmentCards.getOrPut(attachment.key) {
                ArtifactCardView(context).also { attachmentContainer.addView(it) }
            }
            card.bind(attachment)
        }
    }

    /** 操作行里的一枚图标按钮：36dp 触区、20dp 图标、副文色。 */
    private fun actionIcon(
        context: Context,
        @DrawableRes res: Int,
        description: String,
    ): ImageView = ImageView(context).apply {
        setImageResource(res)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setColorFilter(UiKit.color(context, R.color.text_secondary))
        setPadding(dp(8), dp(8), dp(8), dp(8))
        layoutParams = LayoutParams(dp(36), dp(36)).apply {
            marginEnd = dp(2)
        }
        contentDescription = description
        isClickable = true
        isFocusable = true
    }

    private fun setFeedback(turnId: String, like: Boolean) {
        // 赞/踩二选一，再点一次取消；只记在本地界面，不发往任何地方。
        if (feedback[turnId] == like) feedback.remove(turnId) else feedback[turnId] = like
        applyFeedback(turnId)
    }

    private fun applyFeedback(turnId: String) {
        val state = feedback[turnId]
        likeIcon.isSelected = state == true
        dislikeIcon.isSelected = state == false
        likeIcon.setColorFilter(
            UiKit.color(
                context,
                if (state == true) R.color.accent else R.color.text_secondary,
            ),
        )
        dislikeIcon.setColorFilter(
            UiKit.color(
                context,
                if (state == false) R.color.accent else R.color.text_secondary,
            ),
        )
    }

    private fun shareText(text: String) {
        if (text.isBlank()) return
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            val chooser = Intent.createChooser(intent, "分享回复")
            if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (_: Exception) {
            // 没有可分享的目标时静默，不崩。
        }
    }

    private fun toggleReadAloud(text: String) {
        if (readingAloud) {
            stopReading()
            applyReadAloudTint()
            return
        }
        if (text.isBlank()) return
        readingAloud = true
        applyReadAloudTint()
        try {
            if (tts == null) {
                tts = TextToSpeech(context) { status ->
                    if (status == TextToSpeech.SUCCESS && readingAloud) {
                        speak(text)
                    }
                }
            }
            // 引擎已就绪时直接读；未就绪时 speak 静默失败，等 onInit 回调再读。
            speak(text)
        } catch (_: Exception) {
            readingAloud = false
            applyReadAloudTint()
        }
    }

    private fun speak(text: String) {
        try {
            val engine = tts ?: return
            engine.language = Locale.getDefault()
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "chat-turn")
        } catch (_: Exception) {
        }
    }

    private fun stopReading() {
        if (!readingAloud) return
        readingAloud = false
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
    }

    private fun applyReadAloudTint() {
        readAloudIcon.setColorFilter(
            UiKit.color(
                context,
                if (readingAloud) R.color.accent else R.color.text_secondary,
            ),
        )
    }

    private fun noteView(note: String): TextView = UiKit.text(
        context,
        note,
        11f,
        R.color.home_text_secondary,
    ).apply {
        background = UiKit.rounded(context, R.color.home_card, 12, R.color.home_card_border, 1)
        setPadding(dp(11), dp(9), dp(11), dp(9))
        setLineSpacing(dp(2).toFloat(), 1f)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(5)
            bottomMargin = dp(2)
        }
    }

    private fun statusLabel(status: TurnStatus): String = when (status) {
        TurnStatus.RUNNING -> "生成中"
        TurnStatus.DONE -> "完成"
        TurnStatus.EMPTY -> "空响应"
        TurnStatus.ERROR -> "错误"
        TurnStatus.CANCELLED -> "已取消"
        TurnStatus.PAUSED -> "已暂停"
        TurnStatus.STOPPED -> "已停止"
        TurnStatus.GOAL_CHANGED -> "已改目标"
    }

    private fun friendlyError(raw: String): String {
        val payloadStart = raw.indexOf('{')
        val payload = if (payloadStart >= 0) raw.substring(payloadStart) else raw.substringAfter(": ")
        val json = runCatching { org.json.JSONObject(payload) }.getOrNull()
        val code = json?.optJSONObject("error")?.optString("code")
            ?: json?.optString("code")
            ?: ""
        val message = json?.optJSONObject("error")?.optString("message")
            ?: json?.optString("message")
            ?: ""
        return when (code.lowercase()) {
            "unsupported_country_region_territory" ->
                "模型服务当前不支持此账号所在的地区。可以检查服务区域，或切换到当前可用的模型后重试。"
            "insufficient_quota", "billing_hard_limit_reached" ->
                "模型服务额度不足。请检查账号用量或更换已配置的模型后重试。"
            "invalid_api_key", "authentication_error" ->
                "模型服务认证失败。请检查该 Agent 的凭证设置后重试。"
            else -> message.takeIf { it.isNotBlank() }?.let { "本轮没有完成：${it.take(180)}。可检查配置后重试。" }
                ?: "本轮没有完成。可检查网络和模型配置后重试；详细原因已收在诊断信息中。"
        }
    }

    @ColorRes
    private fun statusColor(status: TurnStatus): Int = when (status) {
        TurnStatus.RUNNING -> R.color.accent
        TurnStatus.DONE -> R.color.success
        TurnStatus.EMPTY -> R.color.warning
        TurnStatus.ERROR -> R.color.danger
        TurnStatus.CANCELLED -> R.color.text_tertiary
        TurnStatus.PAUSED -> R.color.warning
        TurnStatus.STOPPED -> R.color.danger
        TurnStatus.GOAL_CHANGED -> R.color.warning
    }

    private fun statusChipBackground(status: TurnStatus): Int = when (status) {
        TurnStatus.RUNNING -> R.drawable.bg_chip_accent
        TurnStatus.DONE -> R.drawable.bg_chip_success
        TurnStatus.EMPTY, TurnStatus.PAUSED, TurnStatus.GOAL_CHANGED -> R.drawable.bg_chip_warning
        TurnStatus.ERROR, TurnStatus.STOPPED -> R.drawable.bg_chip_danger
        TurnStatus.CANCELLED -> R.drawable.bg_chip
    }

    /** V5 C2-3-R1：用户气泡紫渐变底（原型图；浅色默认也是紫渐变气泡）。 */
    private fun userBubbleBackground(): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                UiKit.color(context, R.color.home_gradient_start),
                UiKit.color(context, R.color.home_gradient_end),
            ),
        ).apply {
            cornerRadii = floatArrayOf(
                dp(18).toFloat(), dp(18).toFloat(),
                dp(5).toFloat(), dp(5).toFloat(),
                dp(18).toFloat(), dp(18).toFloat(),
                dp(18).toFloat(), dp(18).toFloat(),
            )
        }

    /**
     * Markdown 轻量渲染：把 [MarkdownLite] 的块画成 Spannable。
     * 纯文本不含任何标记时输出与原文完全相同的字符（ChatTurnViewTest
     * 依赖这一点：助手气泡正文必须原样出现）。
     */
    private fun renderMarkdown(text: String): CharSequence {
        val blocks = MarkdownLite.parse(text)
        val out = SpannableStringBuilder()
        for ((index, block) in blocks.withIndex()) {
            if (index > 0) out.append("\n\n")
            when (block) {
                is MdBlock.Heading -> {
                    val start = out.length
                    appendInlines(out, block.inlines)
                    out.setSpan(
                        StyleSpan(Typeface.BOLD), start, out.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    out.setSpan(
                        RelativeSizeSpan(if (block.level <= 1) 1.2f else 1.1f),
                        start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                is MdBlock.Paragraph -> appendInlines(out, block.inlines)
                is MdBlock.CodeBlock -> {
                    val start = out.length
                    out.append(block.code)
                    out.setSpan(
                        TypefaceSpan("monospace"), start, out.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                is MdBlock.BulletList -> {
                    block.items.forEachIndexed { itemIndex, item ->
                        if (itemIndex > 0) out.append('\n')
                        out.append("• ")
                        appendInlines(out, item)
                    }
                }
                is MdBlock.Quote -> {
                    val start = out.length
                    appendInlines(out, block.inlines)
                    out.setSpan(
                        StyleSpan(Typeface.ITALIC), start, out.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
        }
        return out
    }

    private fun appendInlines(out: SpannableStringBuilder, inlines: List<MdInline>) {
        for (inline in inlines) {
            when (inline) {
                is MdInline.Plain -> out.append(inline.text)
                is MdInline.Bold -> {
                    val start = out.length
                    out.append(inline.text)
                    out.setSpan(
                        StyleSpan(Typeface.BOLD), start, out.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                is MdInline.Italic -> {
                    val start = out.length
                    out.append(inline.text)
                    out.setSpan(
                        StyleSpan(Typeface.ITALIC), start, out.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                is MdInline.Code -> {
                    val start = out.length
                    out.append(inline.text)
                    out.setSpan(
                        TypefaceSpan("monospace"), start, out.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                is MdInline.Link -> out.append(inline.text)
            }
        }
    }

    private fun dp(value: Int): Int = UiKit.dp(context, value)
}

/**
 * V5 聊天清理 20261111：任务产物卡片——图标 + 文件名 + 类型，点按打开
 * （系统浏览器/查看器）。只渲染已有的附件：没有附件就不建卡片；附件没有
 * 链接时卡片只展示、不响应点按，绝不编链接。
 */
private class ArtifactCardView(context: Context) : LinearLayout(context) {

    private val iconBadge: LinearLayout
    private val icon: ImageView
    private val thumbView: ImageView
    private val titleText: TextView
    private val typeText: TextView
    private var boundUrl: String? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = UiKit.rounded(context, R.color.home_card, 14, R.color.home_card_border, 1)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        }
        setPadding(dp(12), dp(11), dp(12), dp(11))
        isClickable = true
        isFocusable = true

        iconBadge = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            background = UiKit.rounded(context, R.color.accent_soft, 10)
            layoutParams = LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) }
        }
        icon = UiKit.icon(context, R.drawable.ic_file, 22, R.color.accent)
        iconBadge.addView(icon)
        addView(iconBadge)

        // V5 按主流改（maintainer 2026-11-11）：本地图片附件的缩略图（64dp，
        // CENTER_CROP）。只在 bind 成功解出缩略图时显示，否则回落到
        // 文件图标徽章——解不出来也不留空白框。
        thumbView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = LayoutParams(dp(64), dp(64)).apply { marginEnd = dp(12) }
            visibility = GONE
        }
        addView(thumbView)

        val texts = UiKit.vertical(context).apply {
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        titleText = UiKit.text(context, "", 14f, R.color.home_text, bold = true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        texts.addView(titleText)
        typeText = UiKit.text(context, "", 11f, R.color.home_text_secondary).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(3)
            }
        }
        texts.addView(typeText)
        addView(texts)

        addView(UiKit.icon(context, R.drawable.ic_chevron, 18, R.color.text_tertiary))

        setOnClickListener {
            val url = boundUrl?.takeIf { it.isNotBlank() } ?: return@setOnClickListener
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                // V5 按主流改：本地附件是 FileProvider content URI，接收方
                // 需要一次性读权限，不加这个会打不开。
                if (url.startsWith("content://")) {
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (_: Exception) {
                // 链接打不开时静默，不崩。
            }
        }
    }

    fun bind(attachment: ChatAttachmentUi) {
        titleText.text = attachment.name
        val typeLabel = artifactTypeLabel(attachment.name)
        typeText.text = if (attachment.url.isNullOrBlank()) {
            typeLabel
        } else {
            "$typeLabel · 临时链接"
        }
        boundUrl = attachment.url?.takeIf { it.isNotBlank() }
        contentDescription = "打开附件：${attachment.name}"
        // V5 按主流改：本地图片附件显示缩略图；远程链接（Manus 产物等）
        // 不在主线程碰网络，永远只显示文件图标。
        val thumb = loadLocalThumbnail(attachment)
        if (thumb != null) {
            thumbView.setImageBitmap(thumb)
            thumbView.visibility = VISIBLE
            iconBadge.visibility = GONE
        } else {
            thumbView.setImageBitmap(null)
            thumbView.visibility = GONE
            iconBadge.visibility = VISIBLE
        }
    }

    /**
     * 本地图片缩略图（目标边 256px，下采样解码，快）。
     * 只读 content:// 本地 URI；http(s) 远程链接、非图片、解码失败
     * 一律返回 null（调用方回落文件图标，不崩、不留白）。
     */
    private fun loadLocalThumbnail(attachment: ChatAttachmentUi): android.graphics.Bitmap? {
        val url = attachment.url?.takeIf { it.startsWith("content://") } ?: return null
        val ext = attachment.name.substringAfterLast('.', "").lowercase()
        if (ext !in setOf("jpg", "jpeg", "png", "webp", "gif")) return null
        return try {
            val uri = Uri.parse(url)
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sampleSize = 1
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            while (longest / sampleSize > 256) sampleSize *= 2
            val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (_: Exception) {
            null
        }
    }

    private fun dp(value: Int): Int = UiKit.dp(context, value)
}

/**
 * 产物类型标签：只按文件名后缀给 honest 的通用类型；未知后缀不编，
 * 回落到“文件”。Manus API 不提供文件 +/- 行数，卡片不显示行数。
 */
internal fun artifactTypeLabel(fileName: String): String {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "html", "htm" -> "HTML"
        "pdf" -> "PDF"
        "py" -> "Python"
        "js", "mjs", "cjs" -> "JavaScript"
        "ts", "tsx" -> "TypeScript"
        "kt", "kts" -> "Kotlin"
        "java" -> "Java"
        "md", "markdown" -> "Markdown"
        "txt" -> "文本"
        "json" -> "JSON"
        "csv" -> "CSV"
        "png", "jpg", "jpeg", "gif", "webp" -> "图片"
        "zip", "tar", "gz" -> "压缩包"
        "mp3", "wav", "ogg" -> "音频"
        "mp4", "mov" -> "视频"
        else -> if (ext.length in 1..5) ext.uppercase() else "文件"
    }
}

/**
 * 用量行文案（maintainer 2026-11-11：按对话方智能出现/消失）：只有真有数字
 * 才返回文案，否则 null（界面不显示行）。只列有数字的那一半，绝不写
 * “未知”——大脑没返回数字时不显示行，而不是显示一行废话。
 */
internal fun usageText(usage: TurnUsage?): String? {
    if (usage == null) return null
    val parts = mutableListOf<String>()
    usage.inputTokens?.let { parts += "输入 $it tokens" }
    usage.outputTokens?.let { parts += "输出 $it tokens" }
    if (parts.isEmpty()) return null
    return "用量：" + parts.joinToString(" · ")
}

/**
 * 工具组分类：老项目 workbench.js operationKind 的思路（按工具名/中文
 * 动作标签分读、搜、改、测、命令五类），原生 Kotlin 重写，不搬 JS。
 * 顺序照老项目：读 → 搜 → 改 → 测 → 命令。
 */
internal enum class ToolCallKind { READ, SEARCH, EDIT, TEST, COMMAND }

internal fun toolCallKind(name: String): ToolCallKind {
    val label = ToolActionLabels.labelFor(name)
    val lower = name.lowercase(Locale.ROOT)
    return when {
        "read" in lower || "读取" in label || "列出文件" in label -> ToolCallKind.READ
        "grep" in lower || "glob" in lower || "search" in lower ||
            "搜索" in label || "查找" in label -> ToolCallKind.SEARCH
        "edit" in lower || "write" in lower || "修改" in label -> ToolCallKind.EDIT
        "test" in lower || "测试" in label -> ToolCallKind.TEST
        else -> ToolCallKind.COMMAND
    }
}

/**
 * 工具组汇总文案：老项目 workbench.js groupOperations 的思路（同类计数
 * 合成一行），原生 Kotlin 重写，不搬 JS。格式如“已读取 2 个文件、
 * 运行 3 条命令”；全是命令时退成“已运行 N 条命令”；有失败的缀
 * “（N 条失败）”。已取消的调用照算（它也是跑过的一步，只是没跑完）。
 */
internal fun toolCallGroupSummary(calls: List<ChatToolCallUi>): String {
    var read = 0
    var search = 0
    var edit = 0
    var test = 0
    var command = 0
    calls.forEach { call ->
        when (toolCallKind(call.name)) {
            ToolCallKind.READ -> read++
            ToolCallKind.SEARCH -> search++
            ToolCallKind.EDIT -> edit++
            ToolCallKind.TEST -> test++
            ToolCallKind.COMMAND -> command++
        }
    }
    val parts = mutableListOf<String>()
    if (read > 0) parts += "读取 $read 个文件"
    if (search > 0) parts += "搜索 $search 次"
    if (edit > 0) parts += "修改 $edit 个文件"
    if (test > 0) parts += "运行 $test 次测试"
    if (command > 0) parts += "运行 $command 条命令"
    var text = if (parts.isEmpty()) "已运行 ${calls.size} 条命令" else "已" + parts.joinToString("、")
    val failures = calls.count { it.status == ToolCallStatus.FAILED }
    if (failures > 0) text += "（$failures 条失败）"
    return text
}

/** Collapsible tool call + result card. */
private class ToolCallCardView(context: Context) : LinearLayout(context) {

    private val statusDot: TextView
    private val actionLabel: TextView
    private val nameText: TextView
    private val statusText: TextView
    private val chevron: ImageView
    private val body: LinearLayout
    private val argsText: TextView
    private val argsLabel: TextView
    private val resultLabel: TextView
    private val resultText: TextView
    private var expanded = false
    private var autoExpandedForFailure = false

    init {
        orientation = VERTICAL
        // V5 C2-3-R1：工具卡照首页 token 走（白底/深色卡 + 细边框 + 小圆角，
        // 默认折叠；日志/参数用深色等宽面板，照 v2-02）。
        background = UiKit.rounded(context, R.color.home_card, 14, R.color.home_card_border, 1)
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
            bottomMargin = dp(4)
        }

        val header = UiKit.horizontal(context).apply {
            setPadding(dp(11), dp(9), dp(8), dp(9))
            isClickable = true
            isFocusable = true
        }
        statusDot = UiKit.text(context, "●", 10f, R.color.accent).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(7)
            }
        }
        header.addView(statusDot)
        // V5 C2-3-R1b（agent_runners.py:61）：中文动作标签加粗在前，
        // 工具名（等宽灰字）仍保留原样——现有测试按原名断言，不断。
        actionLabel = UiKit.text(context, "", 12f, R.color.home_text, bold = true).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(6)
            }
        }
        header.addView(actionLabel)
        nameText = UiKit.text(context, "tool", 12f, R.color.home_text, bold = true, mono = true)
        header.addView(nameText, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        statusText = UiKit.text(context, "", 10f, R.color.home_text_secondary)
        header.addView(statusText)
        chevron = UiKit.icon(context, R.drawable.ic_chevron, 16, R.color.text_tertiary).apply {
            layoutParams = LayoutParams(dp(16), dp(16)).apply { marginStart = dp(4) }
        }
        header.addView(chevron)
        addView(header)

        body = UiKit.vertical(context).apply {
            setPadding(dp(11), dp(8), dp(11), dp(10))
            visibility = GONE
        }
        argsLabel = UiKit.text(context, "参数", 9f, R.color.home_text_secondary, bold = true)
        body.addView(argsLabel)
        argsText = UiKit.text(context, "", 11f, R.color.home_log_text, mono = true).apply {
            background = UiKit.rounded(context, R.color.home_log_bg, 10)
            setPadding(dp(9), dp(7), dp(9), dp(7))
            setLineSpacing(dp(1).toFloat(), 1f)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
                bottomMargin = dp(8)
            }
        }
        body.addView(argsText)
        resultLabel = UiKit.text(context, "结果", 9f, R.color.home_text_secondary, bold = true)
        body.addView(resultLabel)
        resultText = UiKit.text(context, "", 11f, R.color.home_text_secondary).apply {
            background = UiKit.rounded(context, R.color.home_log_bg, 10)
            setPadding(dp(9), dp(7), dp(9), dp(7))
            setLineSpacing(dp(2).toFloat(), 1f)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            }
        }
        body.addView(resultText)
        addView(body)

        header.setOnClickListener {
            expanded = !expanded
            applyExpanded()
        }
    }

    fun bind(call: ChatToolCallUi) {
        actionLabel.text = ToolActionLabels.labelFor(call.name)
        nameText.text = call.name
        statusDot.setTextColor(UiKit.color(context, toolColor(call.status)))
        statusText.text = toolLabel(call.status)
        statusText.setTextColor(UiKit.color(context, toolColor(call.status)))
        if (call.args.isBlank() || call.args == "{}") {
            argsLabel.visibility = GONE
            argsText.visibility = GONE
        } else {
            argsLabel.visibility = VISIBLE
            argsText.visibility = VISIBLE
            argsText.text = call.args
        }
        if (call.resultSummary.isNullOrBlank()) {
            resultLabel.visibility = GONE
            resultText.visibility = GONE
        } else {
            resultLabel.visibility = VISIBLE
            resultText.visibility = VISIBLE
            resultText.text = call.resultSummary
            resultText.setTextColor(
                UiKit.color(
                    context,
                    if (call.status == ToolCallStatus.FAILED) R.color.danger else R.color.home_text_secondary,
                ),
            )
        }
        if (call.status == ToolCallStatus.FAILED && !autoExpandedForFailure) {
            autoExpandedForFailure = true
            expanded = true
        }
        applyExpanded()
    }

    private fun applyExpanded() {
        body.visibility = if (expanded) VISIBLE else GONE
        chevron.rotation = if (expanded) 90f else 0f
    }

    private fun toolLabel(status: ToolCallStatus): String = when (status) {
        ToolCallStatus.RUNNING -> "运行中"
        ToolCallStatus.SUCCESS -> "成功"
        ToolCallStatus.FAILED -> "失败"
        ToolCallStatus.CANCELLED -> "已取消"
    }

    @ColorRes
    private fun toolColor(status: ToolCallStatus): Int = when (status) {
        ToolCallStatus.RUNNING -> R.color.accent
        ToolCallStatus.SUCCESS -> R.color.success
        ToolCallStatus.FAILED -> R.color.danger
        ToolCallStatus.CANCELLED -> R.color.text_tertiary
    }

    private fun dp(value: Int): Int = UiKit.dp(context, value)
}
