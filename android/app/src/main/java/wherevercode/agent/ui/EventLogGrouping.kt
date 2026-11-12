package wherevercode.agent.ui

import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import wherevercode.agent.R
import wherevercode.agent.brain.EngineEvent

/**
 * 运行事件的分组折叠（2026-11-11，maintainer 22:25 密度要求）。
 *
 * 事件列表不要一股脑全铺出来：按轮次/任务分组折叠，默认只展最新
 * 若干条，没有实质内容的事件不单独占一行。
 *
 * - 分组键 = conversationId（空 →“本机”）+ 时间簇：同一
 *   conversationId 内相邻事件间隔 > 10 分钟即另起一组。
 *   [EngineEvent] 只有 conversationId + epochSeconds，没有轮次 id，
 *   所以这是展示层启发式，不是轮次的精确还原。
 * - 无实质内容事件（mapper 摘要为空且 detail 为空/空白）不单独占行，
 *   计入所属组的 [EventGroup.hiddenCount]（组内显示为“另有 N 条记录”）。
 *   原始日志仍在 EventLog 里，不删不改。
 * - [groupEvents] 返回的组按时间倒序（最新组在前），组内事件也按
 *   时间倒序，与现有列表的渲染顺序一致。
 */
data class EventGroup(
    /** 稳定键：conversationId + 组内最早事件时间（刷新后展开状态凭此认组）。 */
    val key: String,
    val title: String,
    val events: List<EngineEvent>,
    val hiddenCount: Int,
)

object EventLogGrouping {
    /** 同一会话内相邻事件超过该间隔即另起一组（秒）。 */
    const val CLUSTER_GAP_SECONDS: Long = 600

    /** 默认展开的最新组数。 */
    const val DEFAULT_VISIBLE_GROUPS: Int = 3

    /** 有实质内容 = mapper 能给出摘要，或 detail 本身非空。 */
    fun hasSubstance(event: EngineEvent): Boolean =
        TaskEventSummaryMapper.from(event).summary.isNotBlank() ||
            event.detail.isNotBlank()

    /** 与 UiKit.shortTime 同格式（MM-dd HH:mm）；纯 JDK，单测无需 Robolectric。 */
    internal fun shortTime(epochSeconds: Long): String {
        if (epochSeconds <= 0) return "—"
        return java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(epochSeconds * 1000))
    }

    fun groupEvents(events: List<EngineEvent>): List<EventGroup> {
        if (events.isEmpty()) return emptyList()
        val ordered = events.sortedBy { it.epochSeconds }
        // 先按（会话，时间簇）切段；无实质内容事件只计数，不占行。
        data class Segment(
            val conversationId: String,
            val visible: MutableList<EngineEvent> = mutableListOf(),
            var hidden: Int = 0,
            var lastEpoch: Long = 0,
        )
        val segments = mutableListOf<Segment>()
        var current: Segment? = null
        // 某会话在当前段之前攒下的无实质内容计数（该会话尚无可见事件时）。
        val pendingHidden = mutableMapOf<String, Int>()
        for (event in ordered) {
            val conversationId = event.conversationId
            val substance = hasSubstance(event)
            if (!substance) {
                val seg = current
                if (seg != null && seg.conversationId == conversationId) {
                    seg.hidden += 1
                } else {
                    pendingHidden[conversationId] = (pendingHidden[conversationId] ?: 0) + 1
                }
                continue
            }
            val seg = current
            if (seg == null || seg.conversationId != conversationId ||
                event.epochSeconds - seg.lastEpoch > CLUSTER_GAP_SECONDS
            ) {
                val next = Segment(conversationId)
                next.hidden = pendingHidden.remove(conversationId) ?: 0
                segments += next
                current = next
            }
            current!!.visible += event
            current!!.lastEpoch = event.epochSeconds
        }
        // 只有无实质内容事件的会话不成组：计数无处可挂，直接丢弃
        // （原始日志仍在 EventLog 里；这类事件本就无可显示内容）。
        return segments.filter { it.visible.isNotEmpty() }.map { seg ->
            val label = seg.conversationId.ifBlank { "本机" }
            val first = seg.visible.first()
            val last = seg.visible.last()
            val timeRange = if (first.epochSeconds == last.epochSeconds) {
                shortTime(first.epochSeconds)
            } else {
                "${shortTime(first.epochSeconds)}–${shortTime(last.epochSeconds)}"
            }
            EventGroup(
                key = "${seg.conversationId}@${first.epochSeconds}",
                title = "$label · $timeRange",
                events = seg.visible.sortedByDescending { it.epochSeconds },
                hiddenCount = seg.hidden,
            )
        }.sortedByDescending { it.events.firstOrNull()?.epochSeconds ?: 0 }
    }
}

/**
 * 分组事件列表的渲染（MainActivity 任务屏“运行事件”与控制台
 * “执行日志”共用）。
 *
 * - 最新 [EventLogGrouping.DEFAULT_VISIBLE_GROUPS] 组默认展开；
 *   更早的组各折成一行“标题 · N 条”，点开展开（再点收起）；
 * - 组总数 > 3 时底部给“展开更多（还有 N 组）/收起”切换；
 * - 展开状态由调用方以 [expandedKeys]（Set）与 [showAll] 持有；
 *   单组展开/收起直接改 [expandedKeys] 后调 [onStateChanged] 重新
 *   渲染（刷新后不丢）；底部“展开更多/收起”调 [onToggleShowAll]，
 *   由调用方翻转 [showAll]（收起时同时清空 [expandedKeys] 回到默认）。
 */
object EventGroupViews {
    fun render(
        context: Context,
        container: LinearLayout,
        groups: List<EventGroup>,
        expandedKeys: MutableSet<String>,
        showAll: Boolean,
        onStateChanged: () -> Unit,
        onToggleShowAll: () -> Unit,
    ) {
        container.removeAllViews()
        if (groups.isEmpty()) return
        val defaultVisible = EventLogGrouping.DEFAULT_VISIBLE_GROUPS
        groups.forEachIndexed { index, group ->
            val expanded = index < defaultVisible || showAll || group.key in expandedKeys
            if (!expanded) {
                container.addView(collapsedRow(context, group, onStateChanged, expandedKeys))
            } else {
                container.addView(groupHeader(context, group, index >= defaultVisible, onStateChanged, expandedKeys))
                group.events.forEach { event ->
                    container.addView(taskEventRow(context, event))
                }
                if (group.hiddenCount > 0) {
                    container.addView(
                        UiKit.text(
                            context,
                            "另有 ${group.hiddenCount} 条记录（无实质内容，已折叠）",
                            10f,
                            R.color.text_tertiary,
                        ).apply { setPadding(0, UiKit.dp(context, 2), 0, UiKit.dp(context, 6)) },
                    )
                }
            }
        }
        if (groups.size > defaultVisible) {
            val remaining = groups.size - defaultVisible
            val toggle = UiKit.text(
                context,
                if (showAll) "收起" else "展开更多（还有 $remaining 组）",
                11f,
                R.color.accent,
                bold = true,
            ).apply {
                setBackgroundResource(R.drawable.bg_chip)
                setPadding(UiKit.dp(context, 12), UiKit.dp(context, 7), UiKit.dp(context, 12), UiKit.dp(context, 7))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = UiKit.dp(context, 6) }
                setOnClickListener { onToggleShowAll() }
            }
            container.addView(toggle)
        }
    }

    private fun collapsedRow(
        context: Context,
        group: EventGroup,
        onStateChanged: () -> Unit,
        expandedKeys: MutableSet<String>,
    ): TextView = UiKit.text(
        context,
        "${group.title} · ${group.events.size} 条",
        11f,
        R.color.text_secondary,
    ).apply {
        setBackgroundResource(R.drawable.bg_system_note)
        setPadding(UiKit.dp(context, 10), UiKit.dp(context, 8), UiKit.dp(context, 10), UiKit.dp(context, 8))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = UiKit.dp(context, 5) }
        setOnClickListener {
            expandedKeys += group.key
            onStateChanged()
        }
    }

    private fun groupHeader(
        context: Context,
        group: EventGroup,
        collapsible: Boolean,
        onStateChanged: () -> Unit,
        expandedKeys: MutableSet<String>,
    ): TextView = UiKit.text(
        context,
        "${group.title} · ${group.events.size} 条" + if (collapsible) " · 点按收起" else "",
        11f,
        R.color.text_secondary,
        bold = true,
    ).apply {
        setPadding(0, UiKit.dp(context, 6), 0, UiKit.dp(context, 2))
        if (collapsible) {
            setOnClickListener {
                expandedKeys -= group.key
                onStateChanged()
            }
        }
    }
}
