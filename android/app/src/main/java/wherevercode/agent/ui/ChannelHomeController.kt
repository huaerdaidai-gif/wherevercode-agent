package wherevercode.agent.ui

import android.app.AlertDialog
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import wherevercode.agent.R

/**
 * 频道页（2026-11-16 照 iOS「频道与协作」形态收敛）：
 * 标题行（频道与协作 + 「＋ 新建」）→ 频道/私聊分段控件 → 搜索 →
 * 快捷 chip 行（只放真实可达入口）→ 频道卡片列表。
 *
 * 诚实边界不变：本 App 只接入了一个已配置频道（平台没有给客户端
 * 完整频道列表接口），卡片只渲染快照里的真实频道、真实未读数与
 * 真实末条消息；私聊服务尚未接入，私聊页只列已有 Agent 对话入口。
 * 「＋ 新建」与「频道管理」点开如实说明暂不可用，绝不假造创建成功。
 *
 * Navigation over the existing channel snapshot and Agent sessions; owns no message store.
 */
class ChannelHomeController(
    private val context: Context,
    private val onOpenChannel: () -> Unit,
    private val onOpenAgent: (String) -> Unit,
    private val onManageAgents: () -> Unit,
) {
    val root = UiKit.vertical(context).apply { visibility = View.GONE }
    private val body = UiKit.vertical(context)
    private var snapshot: TeamChannelSnapshot? = null
    private var privateChats = false
    private var query = ""

    private lateinit var channelsTab: TextView
    private lateinit var privateTab: TextView

    init {
        root.setPadding(dp(16), dp(12), dp(16), dp(12))
        // ── 标题行：频道与协作 + 「＋ 新建」pill ──
        val header = UiKit.horizontal(context).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(UiKit.text(context, "频道与协作", 22f, R.color.text_primary, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(UiKit.text(context, "＋ 新建", 14f, R.color.text_primary).apply {
            tag = "channel_home_create"
            background = UiKit.rounded(context, R.color.bg, 999, R.color.divider, 1)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { showCreateUnavailable() }
        })
        root.addView(header)
        // ── 分段控件：频道 / 私聊 ──
        val tabs = UiKit.horizontal(context).apply {
            background = UiKit.rounded(context, R.color.accent_soft, 10)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12); bottomMargin = dp(10) }
        }
        channelsTab = segmentTab("频道", "channel_home_channels", selected = true) {
            privateChats = false
            renderBody()
        }
        privateTab = segmentTab("私聊", "channel_home_private", selected = false) {
            privateChats = true
            renderBody()
        }
        tabs.addView(channelsTab)
        tabs.addView(privateTab)
        root.addView(tabs)
        // ── 搜索 ──
        root.addView(EditText(context).apply {
            tag = "channel_home_search"
            hint = "搜索频道名称、简介或消息…"
            setSingleLine(true)
            textSize = 14f
            setBackgroundResource(R.drawable.bg_input)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(6) }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    query = s?.toString().orEmpty().trim()
                    renderBody()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        })
        root.addView(ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            addView(body)
        })
        renderBody()
    }

    fun render(snapshot: TeamChannelSnapshot) {
        this.snapshot = snapshot
        renderBody()
    }

    private fun segmentTab(label: String, viewTag: String, selected: Boolean, onClick: () -> Unit): TextView =
        UiKit.text(context, label, 14f, R.color.text_primary).apply {
            tag = viewTag
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f)
            setOnClickListener { onClick() }
            applySegmentStyle(this, selected)
        }

    private fun applySegmentStyle(tab: TextView, selected: Boolean) {
        if (selected) {
            tab.background = UiKit.rounded(context, R.color.bg, 8)
            tab.setTextColor(UiKit.color(context, R.color.text_primary))
            tab.paint.isFakeBoldText = true
        } else {
            tab.background = null
            tab.setTextColor(UiKit.color(context, R.color.text_secondary))
            tab.paint.isFakeBoldText = false
        }
    }

    private fun refreshSegments() {
        if (::channelsTab.isInitialized) applySegmentStyle(channelsTab, !privateChats)
        if (::privateTab.isInitialized) applySegmentStyle(privateTab, privateChats)
    }

    private fun showCreateUnavailable() {
        AlertDialog.Builder(context).setTitle("新建暂不可用")
            .setMessage("创建频道与发起私聊尚未接入：平台没有给客户端开放创建接口。现有频道和消息不会被修改。")
            .setPositiveButton("知道了", null).show()
    }

    private fun showManageUnavailable() {
        AlertDialog.Builder(context).setTitle("频道管理暂不可用")
            .setMessage("创建、改名、删除、成员权限、免打扰、忽略、归档与清除尚不可用。现有频道和消息不会被修改。")
            .setPositiveButton("知道了", null).show()
    }

    private fun renderBody() {
        refreshSegments()
        body.removeAllViews()
        val current = snapshot
        if (privateChats) {
            body.addView(note("私聊服务尚未接入。下方为已有设备和云端 Agent 对话入口。"))
            val members = current?.members.orEmpty().filter {
                !it.isLocalApp && !it.isPlatformAgent && it.agentId != "app" && it.displayName.contains(query, ignoreCase = true)
            }
            if (members.isEmpty()) body.addView(note("没有匹配的 Agent。"))
            members.forEach { member ->
                body.addView(action(member.displayName, "channel_private_${member.agentId}") {
                    onOpenAgent(member.agentId)
                })
            }
            body.addView(action("管理 Agent", "channel_manage_agents", onManageAgents))
            return
        }
        body.addView(chipsRow())
        if (current?.configured == true) {
            if (query.isEmpty() || "已配置频道".contains(query) || current.channelId.contains(query, true)) {
                body.addView(channelCard(current))
            } else {
                body.addView(note("没有匹配的已配置频道。"))
            }
            body.addView(note("完整频道列表暂不可用。"))
        } else {
            body.addView(note(current?.setupHint ?: "正在读取频道配置…"))
            body.addView(action("查看频道连接", "channel_open_existing", onOpenChannel))
        }
    }

    /** 快捷 chip 行：只放本页真实可达的动作，不放演示条目。 */
    private fun chipsRow(): View {
        val row = UiKit.horizontal(context).apply {
            setPadding(0, dp(2), 0, dp(8))
        }
        row.addView(chip("团队空间", "channel_chip_team") { onOpenChannel() })
        row.addView(chip("Agent 列表", "channel_chip_agents") { onManageAgents() })
        row.addView(chip("频道管理", "channel_chip_manage") { showManageUnavailable() })
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun chip(label: String, viewTag: String, onClick: () -> Unit): TextView =
        UiKit.text(context, label, 13f, R.color.accent).apply {
            tag = viewTag
            background = UiKit.rounded(context, R.color.accent_soft, 999)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(8) }
            setOnClickListener { onClick() }
        }

    /** 已配置频道卡片：# 图标块 + 名称 + 成员数 + 末条消息 + 未读角标，全部取自真实快照。 */
    private fun channelCard(current: TeamChannelSnapshot): View {
        val card = UiKit.vertical(context).apply {
            tag = "channel_open_existing"
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4); bottomMargin = dp(6) }
            setOnClickListener { onOpenChannel() }
        }
        val top = UiKit.horizontal(context).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(UiKit.text(context, "#", 20f, R.color.text_primary, bold = true).apply {
            gravity = Gravity.CENTER
            background = UiKit.rounded(context, R.color.accent_soft, 10)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        })
        val titles = UiKit.vertical(context).apply {
            setPadding(dp(12), 0, dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titles.addView(UiKit.text(context, "已配置频道", 16f, R.color.text_primary, bold = true))
        if (current.members.isNotEmpty()) {
            val agents = current.members.count { !it.isLocalApp }
            titles.addView(UiKit.text(context, "${current.members.size} 名成员 · $agents 个 Agent", 12f, R.color.text_tertiary).apply {
                setPadding(0, dp(3), 0, 0)
            })
        }
        top.addView(titles)
        if (current.unreadCount > 0) {
            top.addView(UiKit.text(context, "${current.unreadCount}${if (current.unreadCapped) "+" else ""}", 11f, R.color.bg, bold = true).apply {
                gravity = Gravity.CENTER
                background = UiKit.rounded(context, R.color.home_danger, 999)
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
            })
        }
        card.addView(top)
        current.messages.lastOrNull()?.let { last ->
            card.addView(UiKit.text(context, last.text.take(120), 13f, R.color.text_secondary).apply {
                maxLines = 2
                setPadding(0, dp(8), 0, 0)
            })
        }
        return card
    }

    private fun action(label: String, viewTag: String, action: () -> Unit) =
        UiKit.text(context, label, 15f, R.color.accent).apply {
            tag = viewTag
            setPadding(dp(12), dp(14), dp(12), dp(14))
            setOnClickListener { action() }
        }

    private fun note(label: String) = UiKit.text(context, label, 13f, R.color.text_secondary).apply {
        setPadding(dp(8), dp(10), dp(8), dp(10))
    }

    private fun dp(value: Int) = UiKit.dp(context, value)
}
