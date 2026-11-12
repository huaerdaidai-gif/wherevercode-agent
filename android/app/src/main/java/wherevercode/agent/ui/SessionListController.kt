package wherevercode.agent.ui

import wherevercode.agent.R

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 会话列表（阶段 3，U5/批 3c）：聊天 Tab 的首屏。
 *
 * 分段（与 SessionLedger.listSessions 的分组约定一致）：
 * - 默认会话：当前默认对话（conversationId 为空时为新空时间线），
 *   行名按“<名>会话”走大脑显示名设置（默认“WhereverCode会话”）；
 * - 会话：具名会话（默认 GROUP / 按 Agent 的 PRIVATE / 按任务的 TASK）；
 * - 历史对话：引擎自动物化的 `chat:<id>` 会话（无名、仅按时间排）。
 * 只做展示与切换：数据由 MainActivity 从 SessionLedger 读，
 * 切换走 ChatViewModel.switchSession（各会话时间线分开保存）。
 *
 * V5 C2（批 3，第 1 步）：历史搜索框。
 * 搜索 EditText 放在固定头里（init 时建一次，永不重建），`render()` 只重建其下的
 * 列表段（`listContainer`），否则每次按键全量重建都会丢焦点与输入（勘查 R2-2）。
 * 搜索是纯 UI 侧过滤（[SessionListFilter]）：只搜会话名、类型或任务，
 * 不搜消息正文；默认会话行与“＋ 新对话”按钮不受过滤影响，始终显示。
 * 旋转重建时 query 丢失可接受（不进 onSaveInstanceState）。
 */
class SessionListController(
    private val context: Context,
    private val onOpenDefault: () -> Unit,
    private val onOpenSession: (sessionId: String, label: String) -> Unit,
    private val onNewConversation: () -> Unit,
    /**
     * V5 C3 chat-bugfix：删除入口。之前列表只有打开，没有任何删除
     * 控件——删除不是“点了没反应”，是根本没有入口。
     */
    private val onDeleteSession: (sessionId: String, label: String) -> Unit = { _, _ -> },
    private val onDeleteDefault: () -> Unit = {},
) {
    /**
     * 默认会话行名（按“<名>会话”走大脑显示名设置，默认“WhereverCode会话”）。
     * 宿主（MainActivity）在大脑改名后更新它并重渲染；renderList 每次都读
     * 当前值。固定头的说明行不写会话名（只写“默认会话”），改名后不 stale。
     */
    var defaultSessionLabel: String = BrainDisplayName.sessionLabel(null)
    val root: ScrollView = ScrollView(context).apply {
        visibility = View.GONE
        isFillViewport = true
        // 2026-11-11：与 TaskDetailController 同款——根视图不透明背景，
        // 浮层盖在其他屏之上时底层内容不再透出来。R.color.bg 与窗口
        // 底色一致，夜间自动跟随。
        setBackgroundResource(R.color.bg)
    }

    private val content: LinearLayout = UiKit.vertical(context).apply {
        setPadding(dp(16), dp(8), dp(16), dp(20))
    }

    /** 只重建的列表段：默认会话行 + 具名段 + 历史段 + 空态。搜索框不在其中。 */
    private val listContainer: LinearLayout = UiKit.vertical(context)

    /**
     * 搜索输入框（固定头的一部分，建一次、永不重建）。
     * 公开供测试驱动（输入 query 后断言焦点/文本仍在）；生产代码不从外部改它。
     */
    val searchField: EditText = EditText(context).apply {
        hint = "搜索会话名、类型或任务…"
        inputType = InputType.TYPE_CLASS_TEXT
        setSingleLine(true)
        setTextSize(13f)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setBackgroundResource(R.drawable.bg_input)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(4) }
    }

    private var query: String = ""
    private var defaultSubtitle: String = ""
    private var allItems: List<SessionListItem> = emptyList()
    private var loadError: String? = null

    init {
        root.addView(content)
        // 固定头：标题、说明、搜索框、边界说明、“＋ 新对话”——只建一次。
        content.addView(
            UiKit.text(context, "会话", 20f, R.color.text_primary, bold = true).apply {
                setPadding(0, dp(4), 0, dp(4))
            },
        )
        content.addView(
            UiKit.text(context, "默认会话、按 Agent 与按任务的独立会话；切换不丢各会话历史。", 11f, R.color.text_tertiary).apply {
                setPadding(0, 0, 0, dp(8))
            },
        )
        content.addView(searchField)
        content.addView(
            UiKit.text(context, "只搜会话名、类型或任务，不搜消息正文。", 10f, R.color.text_tertiary).apply {
                setPadding(0, 0, 0, dp(8))
            },
        )
        val newChat = UiKit.text(context, "＋ 新对话", 12f, R.color.accent, bold = true).apply {
            setBackgroundResource(R.drawable.bg_chip_accent)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            setOnClickListener { onNewConversation() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(12) }
        }
        content.addView(newChat)
        content.addView(listContainer)
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?,
                start: Int,
                count: Int,
                after: Int,
            ) = Unit

            override fun onTextChanged(
                s: CharSequence?,
                start: Int,
                before: Int,
                count: Int,
            ) = Unit

            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                // 只重建列表段：搜索框本身不动，焦点与输入不丢。
                renderList()
            }
        })
    }

    data class SessionListItem(
        val sessionId: String,
        val name: String,
        val kindLabel: String,
        val subtitle: String,
        val isHistory: Boolean,
    )

    /** 当前搜索 query（测试用；旋转重建后丢失，不持久化）。 */
    fun currentQuery(): String = query

    fun render(
        defaultSubtitle: String,
        sessions: List<SessionListItem>,
    ) {
        this.defaultSubtitle = defaultSubtitle
        this.allItems = sessions
        this.loadError = null
        renderList()
    }

    fun renderError(message: String) {
        loadError = message
        allItems = emptyList()
        renderList()
    }

    private fun renderList() {
        listContainer.removeAllViews()
        listContainer.addView(sectionTitle(defaultSessionLabel))
        listContainer.addView(
            sessionRow(
                defaultSessionLabel, "默认", defaultSubtitle,
                onClick = { onOpenDefault() },
                onDelete = { onDeleteDefault() },
            ),
        )

        loadError?.let { error ->
            listContainer.addView(
                UiKit.text(context, error, 11f, R.color.danger).apply {
                    setBackgroundResource(R.drawable.bg_system_note)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                },
            )
            return
        }

        val filtered = SessionListFilter.filter(allItems, query)
        val named = filtered.filter { !it.isHistory }
        val history = filtered.filter { it.isHistory }
        if (named.isNotEmpty()) {
            listContainer.addView(sectionTitle("会话（${named.size}）"))
            named.forEach { item ->
                listContainer.addView(
                    sessionRow(
                        item.name, item.kindLabel, item.subtitle,
                        onClick = { onOpenSession(item.sessionId, item.name) },
                        onDelete = { onDeleteSession(item.sessionId, item.name) },
                    ),
                )
            }
        }
        if (history.isNotEmpty()) {
            listContainer.addView(sectionTitle("历史对话（${history.size}）"))
            listContainer.addView(
                UiKit.text(context, "按最近更新时间排列；空会话和暂时无法读取的记录会明确标注。", 10f, R.color.text_tertiary).apply {
                    setPadding(0, 0, 0, dp(6))
                },
            )
            history.forEach { item ->
                listContainer.addView(
                    sessionRow(
                        item.name, item.kindLabel, item.subtitle,
                        onClick = { onOpenSession(item.sessionId, item.name) },
                        onDelete = { onDeleteSession(item.sessionId, item.name) },
                    ),
                )
            }
        }
        if (named.isEmpty() && history.isEmpty()) {
            if (query.isNotBlank()) {
                listContainer.addView(
                    UiKit.text(context, "没有匹配“${query.trim()}”的会话。", 11f, R.color.text_tertiary).apply {
                        setBackgroundResource(R.drawable.bg_system_note)
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                    },
                )
            } else {
                listContainer.addView(
                    UiKit.text(context, "还没有其他会话：与某个 Agent 对话或指派任务后，这里会出现它的独立会话。", 11f, R.color.text_tertiary).apply {
                        setBackgroundResource(R.drawable.bg_system_note)
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                    },
                )
            }
        }
    }

    private fun sectionTitle(text: String): TextView =
        UiKit.text(context, text, 13f, R.color.text_primary, bold = true).apply {
            setPadding(0, dp(10), 0, dp(6))
        }

    private fun sessionRow(
        name: String,
        kindLabel: String,
        subtitle: String,
        onClick: () -> Unit,
        onDelete: (() -> Unit)? = null,
    ): LinearLayout {
        val row = UiKit.vertical(context).apply {
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
            setOnClickListener { onClick() }
        }
        val top = UiKit.horizontal(context)
        top.addView(
            UiKit.text(context, name, 14f, R.color.text_primary, bold = true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        top.addView(
            UiKit.text(context, kindLabel, 10f, R.color.text_secondary).apply {
                setBackgroundResource(R.drawable.bg_chip)
                setPadding(dp(9), dp(4), dp(9), dp(4))
            },
        )
        // 删除入口在行内：子 View 自带点击监听会消费事件，不会冒泡
        // 触发整行的打开回调。
        if (onDelete != null) {
            top.addView(
                UiKit.text(context, "删除", 11f, R.color.danger).apply {
                    setPadding(dp(10), dp(4), dp(2), dp(4))
                    setOnClickListener { onDelete() }
                },
            )
        }
        row.addView(top)
        row.addView(
            UiKit.text(context, subtitle, 11f, R.color.text_tertiary).apply {
                setPadding(0, dp(5), 0, 0)
            },
        )
        return row
    }

    private fun dp(value: Int): Int = UiKit.dp(context, value)
}
