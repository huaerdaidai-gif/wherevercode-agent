package wherevercode.agent.ui

import android.app.AlertDialog
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import wherevercode.agent.R

/** Navigation over the existing channel snapshot and Agent sessions; owns no message store. */
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

    init {
        root.setPadding(dp(16), dp(12), dp(16), dp(12))
        root.addView(UiKit.text(context, "频道", 22f, R.color.text_primary, bold = true))
        val tabs = UiKit.horizontal(context)
        listOf("频道" to false, "私聊" to true).forEach { (label, privateTab) ->
            tabs.addView(action(label, "channel_home_${if (privateTab) "private" else "channels"}") {
                privateChats = privateTab
                renderBody()
            })
        }
        root.addView(tabs)
        root.addView(EditText(context).apply {
            tag = "channel_home_search"
            hint = "搜索已配置频道或 Agent"
            setSingleLine(true)
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

    private fun renderBody() {
        body.removeAllViews()
        val current = snapshot
        if (privateChats) {
            body.addView(note("与你的 Agent 对话。用户之间的私聊暂不可用。"))
            val members = current?.members.orEmpty().filter {
                !it.isLocalApp && it.agentId != "app" && it.displayName.contains(query, ignoreCase = true)
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
        body.addView(note("已配置的频道"))
        if (current?.configured == true) {
            if (query.isEmpty() || "已配置频道".contains(query) || current.channelId.contains(query, true)) {
                body.addView(action("已配置频道 · ${current.unreadCount}${if (current.unreadCapped) "+" else ""} 条未读", "channel_open_existing", onOpenChannel))
                current.messages.lastOrNull()?.let { body.addView(note(it.text.take(120))) }
            } else body.addView(note("没有匹配的已配置频道。"))
            body.addView(note("完整频道列表暂不可用。"))
        } else {
            body.addView(note(current?.setupHint ?: "正在读取频道配置…"))
            body.addView(action("查看频道连接", "channel_open_existing", onOpenChannel))
        }
        body.addView(action("频道管理", "channel_manage") {
            AlertDialog.Builder(context).setTitle("频道管理暂不可用")
                .setMessage("创建、改名、删除、成员权限、免打扰、忽略、归档与清除尚不可用。现有频道和消息不会被修改。")
                .setPositiveButton("知道了", null).show()
        })
        body.addView(action("管理 Agent", "channel_manage_agents", onManageAgents))
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
