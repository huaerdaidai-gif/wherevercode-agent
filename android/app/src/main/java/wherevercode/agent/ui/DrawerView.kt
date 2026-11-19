package wherevercode.agent.ui

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import wherevercode.agent.R

/**
 * 定版收敛抽屉组件 (P0-2)
 *
 * 依据 2026-11-17 定版五条 + 双端对齐标准：
 * 抽屉只留：
 * 1. 品牌头：WhereverCode + 关闭按钮
 * 2. 四父级菜单：对话、项目、频道、控制
 * 3. 分割线
 * 4. 最近会话区与会话搜索
 * 5. 分割线
 * 6. 底部固定账号（设置）与「＋ 新对话」
 * 7. 最底部「全部功能（N 项）」入口
 *
 * 旧入口（设备/Agent/任务队列与审批/Webhook/事件/用量）进「控制」页入口列表；
 * 其余进全部功能注册表（保证每个旧入口在 控制 或 全部功能 里有可见入口，功能只规范呈现、不砍）。
 */
class DrawerView(
    private val context: Context,
    private val controller: DrawerController,
    private val onEntrySelected: (registryId: String) -> Unit,
    private val onPrefsChanged: () -> Unit = {},
    private val activeTargetProvider: () -> String? = { null },
    private val userLabelProvider: () -> String = { "未登录" },
    private val themeLabelProvider: () -> String = { "浅色" },
    private val recentSessionsProvider: () -> List<SessionItem> = { emptyList() },
    private val onDeleteSession: (sessionId: String) -> Unit = {},
    private val onNewConversation: () -> Unit = {},
    private val onOpenFeatureCenter: () -> Unit = {},
) {
    data class SessionItem(val id: String, val title: String)

    data class RenderedRow(
        val id: String,
        val label: String,
        val indent: Int = 0,
        /** "parent" / "recent" / "bottom" / "feature_center" / "top" / "workspace" / "extensions" */
        val group: String,
        val badge: Int? = null,
        val todo: Boolean = false,
        val active: Boolean = false,
        val trailing: String? = null,
        val iconRes: Int? = null,
    )

    val root: ScrollView = ScrollView(context).apply {
        isFillViewport = true
    }

    private val content: LinearLayout = UiKit.vertical(context).apply {
        setPadding(dp(16), dp(12), dp(16), dp(16))
    }

    private var renderedRowList: List<RenderedRow> = emptyList()
    private var badges: DrawerBadges = DrawerBadges()
    private var searchQuery: String = ""
    private var searchInputField: EditText? = null
    private var searchBoxView: LinearLayout? = null
    private var sessionListContainer: LinearLayout? = null
    private var recentHeaderView: TextView? = null

    // refresh() 重建期间的插入游标：>=0 时新分节插到持久区（最近会话区）之前，
    // -1 表示追加到 content 末尾。
    private var contentInsertIndex: Int = -1

    private fun addContent(view: View) {
        if (contentInsertIndex >= 0) {
            content.addView(view, contentInsertIndex)
            contentInsertIndex++
        } else {
            content.addView(view)
        }
    }
    private val collapsedGroups: MutableSet<String> = mutableSetOf()

    // 必须声明在 init 之前：init 会调用 refresh()，refresh() 读本表；
    // 声明在 init 之后会导致初始化顺序未到、字段为 null 而 NPE。
    private val PARENT_MENUS = listOf(
        ParentMenuItem("nav_chat", "对话", DrawerController.ID_NEW_CONVERSATION, R.drawable.ic_chat),
        ParentMenuItem("nav_projects", "项目", DrawerController.ID_PROJECTS, R.drawable.ic_folder),
        ParentMenuItem("nav_channels", "频道", DrawerController.ID_TEAM_CHANNEL, R.drawable.ic_chat),
        ParentMenuItem("nav_control", "控制", DrawerController.ID_CONSOLE, R.drawable.ic_console),
    )

    init {
        root.addView(content)
        refresh()
    }

    fun isGroupCollapsed(group: String): Boolean = group in collapsedGroups

    fun setGroupCollapsed(group: String, collapsed: Boolean) {
        if (collapsed) collapsedGroups.add(group) else collapsedGroups.remove(group)
        refresh()
    }

    fun setBadges(newBadges: DrawerBadges) {
        badges = newBadges
        refresh()
    }

    fun currentBadges(): DrawerBadges = badges

    fun renderedRows(): List<RenderedRow> = renderedRowList

    fun renderedIds(): List<String> = renderedRowList.map { it.id }

    fun renderedLabels(): List<String> = renderedRowList.map { it.label }

    fun renderedGroupIds(group: String): List<String> =
        renderedRowList.filter { it.group == group }.map { it.id }

    fun renderedRowIsActive(id: String): Boolean =
        renderedRowList.firstOrNull { it.id == id }?.active == true

    fun renderedRowBadge(id: String): Int? =
        renderedRowList.firstOrNull { it.id == id }?.badge

    fun renderedRowTrailing(id: String): String? =
        renderedRowList.firstOrNull { it.id == id }?.trailing

    fun renderedRowIconRes(id: String): Int? =
        renderedRowList.firstOrNull { it.id == id }?.iconRes

    fun renderedRowIsTodo(id: String): Boolean =
        renderedRowList.firstOrNull { it.id == id }?.todo == true

    fun renderedRowIndent(id: String): Int =
        renderedRowList.firstOrNull { it.id == id }?.indent ?: 0

    fun iconResFor(entryId: String): Int? = when (entryId) {
        "nav_chat" -> R.drawable.ic_chat
        "nav_history" -> R.drawable.ic_search
        "nav_projects" -> R.drawable.ic_folder
        "nav_channels", "nav_team_channel" -> R.drawable.ic_chat
        "nav_control", "nav_console" -> R.drawable.ic_console
        "nav_team" -> R.drawable.ic_team
        "nav_devices" -> R.drawable.ic_device
        "nav_tools" -> R.drawable.ic_tools
        "nav_workbench", "settings" -> R.drawable.ic_settings
        "nav_theme" -> R.drawable.ic_theme
        "nav_running_tasks" -> R.drawable.ic_clock
        "nav_pending_approval" -> R.drawable.ic_approval
        "nav_results" -> R.drawable.ic_results
        "nav_help" -> R.drawable.ic_help
        "nav_mcp_plugins" -> R.drawable.ic_puzzle
        "nav_models_skills" -> R.drawable.ic_cube
        "nav_custom_modules" -> R.drawable.ic_grid
        "nav_feature_center" -> R.drawable.ic_dashboard
        else -> null
    }

    fun clickRowForTest(id: String): Boolean {
        val view = findRowView(id) ?: return false
        view.performClick()
        return true
    }

    private fun findRowView(id: String): View? {
        for (i in 0 until content.childCount) {
            val child = content.getChildAt(i)
            if (child.tag == id) return child
            if (child is LinearLayout) {
                for (j in 0 until child.childCount) {
                    val sub = child.getChildAt(j)
                    if (sub.tag == id) return sub
                }
            }
        }
        return null
    }

    fun refresh() {
        val rows = mutableListOf<RenderedRow>()

        // 最近会话区（标题 + 搜索框 + 会话列表容器）是持久区：建成一次、永不摘下。
        // 旧实现每次 refresh() 都以 content.removeAllViews() 开头，把搜索框连同
        // 输入框一起从窗口摘下、随后又 addView 重挂。而 refresh() 的调用点恰恰是
        // 抽屉打开（与 220ms 滑入动画同帧）、会话列表/角标的异步回主线程刷新
        // （可能落在动画中、甚至用户已经开始打字时）：输入框在动画容器的分数位移
        // 下被反复脱挂重绘，新旧位移的绘制叠在一起就是占位文字与正文的双影
        // （重影）；脱挂还会拆掉输入连接与焦点（开抽屉立刻打字被吞）。
        // 这里只摘非持久子视图、只重建其余分节，搜索框全程纹丝不动。
        ensureRecentSectionViews()
        val persistent = listOfNotNull(recentHeaderView, searchBoxView, sessionListContainer)
        for (i in content.childCount - 1 downTo 0) {
            val child = content.getChildAt(i)
            if (child !in persistent) content.removeViewAt(i)
        }
        contentInsertIndex = content.indexOfChild(recentHeaderView)
            .takeIf { it >= 0 } ?: content.childCount

        // 1. 品牌头：WhereverCode + 关闭按钮
        addBrandHeader()

        // 2. 四父级菜单：对话、项目、频道、控制
        addParentMenus(rows)

        addDivider()

        // 3. 中部最近会话与会话搜索（持久区已在位，只重画会话行）
        addRecentConversationsSection(rows)

        contentInsertIndex = -1

        addDivider()

        // 4. 底部固定账号（设置）与新对话
        addBottomAccountSection(rows)

        // 5. 抽屉最底部：全部功能（N 项）集中入口
        addFeatureCenterEntry(rows)

        renderedRowList = rows
    }

    private fun addBrandHeader() {
        val row = UiKit.horizontal(context).apply {
            setPadding(0, dp(4), 0, dp(12))
            gravity = Gravity.CENTER_VERTICAL
        }
        val brandColumn = UiKit.vertical(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val titleRow = UiKit.horizontal(context).apply {
            gravity = Gravity.BOTTOM
        }
        titleRow.addView(
            UiKit.text(context, "WhereverCode Agent", 18f, R.color.text_primary, bold = true)
        )
        val versionName = try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            "v${pInfo.versionName}"
        } catch (_: Exception) {
            "v5.0"
        }
        titleRow.addView(
            UiKit.text(context, " $versionName", 12f, R.color.text_secondary).apply {
                setPadding(dp(6), 0, 0, dp(2))
            }
        )
        brandColumn.addView(titleRow)
        row.addView(brandColumn)
        // 关闭抽屉按钮
        val closeBtn = UiKit.text(context, "✕", 16f, R.color.text_secondary, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            gravity = Gravity.CENTER
            background = UiKit.rounded(context, R.color.bg_card, 999)
            setOnClickListener { onEntrySelected("drawer_close") }
        }
        row.addView(closeBtn)
        addContent(row)
    }

    data class ParentMenuItem(
        val id: String,
        val label: String,
        val target: String,
        val iconRes: Int,
    )

    private fun addParentMenus(rows: MutableList<RenderedRow>) {
        val activeTarget = activeTargetProvider()
        PARENT_MENUS.forEach { menu ->
            val isActive = (menu.target == activeTarget ||
                (menu.id == "nav_chat" && (activeTarget == null || activeTarget == DrawerController.ID_NEW_CONVERSATION)) ||
                (menu.id == "nav_control" && activeTarget == DrawerController.ID_CONSOLE) ||
                (menu.id == "nav_channels" && activeTarget == DrawerController.ID_TEAM_CHANNEL) ||
                (menu.id == "nav_projects" && activeTarget == DrawerController.ID_PROJECTS))
            val badge = badges.badgeFor(menu.id)
            val trailing = if (isActive) null else "›"
            rows += RenderedRow(
                id = menu.id,
                label = menu.label,
                indent = 0,
                group = "parent",
                badge = badge,
                todo = false,
                active = isActive,
                trailing = trailing,
                iconRes = menu.iconRes,
            )

            val row = UiKit.horizontal(context).apply {
                tag = menu.id
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(46),
                ).apply {
                    topMargin = dp(2)
                    bottomMargin = dp(2)
                }
                setPadding(dp(12), 0, dp(12), 0)
                gravity = Gravity.CENTER_VERTICAL
                background = if (isActive) {
                    UiKit.rounded(context, R.color.accent_soft, 12)
                } else {
                    null
                }
                setOnClickListener { onEntrySelected(menu.id) }
            }

            row.addView(UiKit.icon(context, menu.iconRes, if (isActive) R.color.accent else R.color.text_secondary).apply {
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(12) }
            })
            row.addView(UiKit.text(
                context,
                menu.label,
                16f,
                R.color.text_primary,
                bold = isActive,
            ).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (badge != null) row.addView(badgeView(badge))
            if (trailing != null) row.addView(trailingView(trailing))
            addContent(row)
        }
    }

    /** 持久区三件套（标题、搜索框、会话列表容器）只建一次，之后只复用同一批实例。 */
    private fun ensureRecentSectionViews() {
        if (recentHeaderView == null) {
            recentHeaderView = UiKit.text(context, "最近会话", 12f, R.color.text_secondary, bold = true).apply {
                setPadding(dp(4), dp(6), dp(4), dp(4))
            }
        }
        if (searchBoxView == null) {
            searchBoxView = buildSearchBox()
        }
        if (sessionListContainer == null) {
            // 会话列表单独一个容器：打字时只重建这个容器里的行，
            // 搜索框与输入框纹丝不动。
            sessionListContainer = UiKit.vertical(context)
        }
    }

    private fun addRecentConversationsSection(rows: MutableList<RenderedRow>) {
        ensureRecentSectionViews()
        val header = recentHeaderView!!
        val searchBox = searchBoxView!!
        val container = sessionListContainer!!
        // 只在首次时把持久区三件套放进 content；之后 refresh() 只摘非持久
        // 子视图，三件套的挂载状态与位置保持不变，输入框永不脱挂、永不重挂。
        if (header.parent == null) addContent(header)
        if (searchBox.parent == null) addContent(searchBox)
        if (container.parent == null) addContent(container)
        renderSessionRows(rows)
    }

    private fun buildSearchBox(): LinearLayout {
        val searchBox = UiKit.horizontal(context).apply {
            background = UiKit.rounded(context, R.color.bg_card, 10, R.color.divider, 1)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            gravity = Gravity.CENTER_VERTICAL
            // 高度不写死：原先写死 38dp、上下内边距各 4dp，输入框可用高度只剩
            // 30dp 硬顶；字段自身又零内边距（见下），真机上字形下半被裁（200 实证）。
            // 改为随内容定高，框高由内边距 + 字段高度决定，外观高度基本不变。
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = dp(8)
            }
        }
        searchBox.addView(UiKit.icon(context, R.drawable.ic_search, R.color.text_secondary).apply {
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(8) }
        })
        val searchInput = EditText(context).apply {
            hint = "搜索会话与消息…"
            textSize = 13f
            setTextColor(UiKit.color(context, R.color.text_primary))
            setHintTextColor(UiKit.color(context, R.color.text_tertiary))
            background = null
            // 背景置空后，系统 EditText 默认背景附带的纵向内边距一并消失，
            // 字段零留白时字形上下界贴着字段内界、绘制时下半被裁；补回纵向内边距。
            setPadding(0, dp(3), 0, dp(3))
            isSingleLine = true
            // 软件绘制：避开抽屉滑动时硬件层缓存造成的文字双重绘制（重影）。
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchQuery = s?.toString().orEmpty()
                }
                override fun afterTextChanged(s: Editable?) {
                    // 只更新会话列表容器，绝不整抽屉 refresh()：
                    // 整刷会把正在输入的 EditText 脱挂重挂，吞字且重影。
                    updateSessionRowsOnly()
                }
            })
        }
        searchInputField = searchInput
        searchBox.addView(searchInput)
        return searchBox
    }

    /** 只重建会话列表容器（搜索过滤结果），不动搜索框与输入框。 */
    private fun updateSessionRowsOnly() {
        if (sessionListContainer == null) return
        val collector = mutableListOf<RenderedRow>()
        renderSessionRows(collector)
        val oldList = renderedRowList
        val start = oldList.indexOfFirst { it.group == "recent" }
        renderedRowList = if (start >= 0) {
            val end = oldList.indexOfLast { it.group == "recent" } + 1
            oldList.subList(0, start) + collector + oldList.subList(end, oldList.size)
        } else {
            val bottomIdx = oldList.indexOfFirst { it.group == "bottom" }
            if (bottomIdx >= 0) {
                oldList.subList(0, bottomIdx) + collector + oldList.subList(bottomIdx, oldList.size)
            } else {
                oldList + collector
            }
        }
    }

    private fun renderSessionRows(rows: MutableList<RenderedRow>) {
        val container = sessionListContainer ?: return
        container.removeAllViews()

        // 会话列表
        val allSessions = recentSessionsProvider()
        val filtered = if (searchQuery.isBlank()) {
            allSessions
        } else {
            allSessions.filter { it.title.contains(searchQuery, ignoreCase = true) }
        }

        if (filtered.isEmpty()) {
            val emptyNotice = UiKit.vertical(context).apply {
                setPadding(dp(8), dp(12), dp(8), dp(12))
            }
            emptyNotice.addView(
                UiKit.text(
                    context,
                    if (searchQuery.isEmpty()) "暂无历史会话" else "未找到匹配会话",
                    13f,
                    R.color.text_secondary,
                ),
            )
            emptyNotice.addView(
                UiKit.text(
                    context,
                    "点击下方「＋ 新对话」开始",
                    11f,
                    R.color.text_tertiary,
                ).apply { setPadding(0, dp(4), 0, 0) },
            )
            container.addView(emptyNotice)
        } else {
            filtered.forEach { session ->
                val rowId = "session_${session.id}"
                rows += RenderedRow(
                    id = rowId,
                    label = session.title,
                    indent = 0,
                    group = "recent",
                    iconRes = R.drawable.ic_chat,
                )
                val row = UiKit.horizontal(context).apply {
                    tag = rowId
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(40),
                    )
                    setPadding(dp(8), 0, dp(4), 0)
                    gravity = Gravity.CENTER_VERTICAL
                    background = UiKit.rounded(context, R.color.bg_card, 8)
                    setOnClickListener {
                        onEntrySelected("session:${session.id}")
                    }
                }
                row.addView(UiKit.icon(context, R.drawable.ic_chat, R.color.text_secondary).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(8) }
                })
                row.addView(UiKit.text(context, session.title, 13f, R.color.text_primary).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    maxLines = 1
                })
                val delBtn = UiKit.text(context, "🗑", 12f, R.color.home_danger).apply {
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    setOnClickListener { onDeleteSession(session.id) }
                }
                row.addView(delBtn)
                container.addView(row)
            }
        }
    }

    private fun addBottomAccountSection(rows: MutableList<RenderedRow>) {
        val userLabel = userLabelProvider()
        val isSettingsActive = (DrawerController.ID_SETTINGS == activeTargetProvider())
        rows += RenderedRow(
            id = DrawerController.ID_SETTINGS,
            label = userLabel,
            indent = 0,
            group = "bottom",
            badge = null,
            todo = false,
            active = isSettingsActive,
            trailing = null,
            iconRes = R.drawable.ic_settings,
        )

        val bottomRow = UiKit.horizontal(context).apply {
            setPadding(0, dp(8), 0, dp(8))
            gravity = Gravity.CENTER_VERTICAL
        }

        // 左侧：头像 + 设置入口
        val accountBtn = UiKit.horizontal(context).apply {
            tag = DrawerController.ID_SETTINGS
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { onEntrySelected(DrawerController.ID_SETTINGS) }
        }
        accountBtn.addView(
            UiKit.text(context, "W", 15f, R.color.white, bold = true).apply {
                background = UiKit.rounded(context, R.color.home_gradient_start, 999)
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                gravity = Gravity.CENTER
            },
        )
        val accountTextGroup = UiKit.vertical(context).apply {
            setPadding(dp(10), 0, 0, 0)
        }
        accountTextGroup.addView(UiKit.text(context, userLabel, 14f, R.color.text_primary, bold = true))
        accountTextGroup.addView(UiKit.text(context, "设置与配置", 11f, R.color.text_tertiary))
        accountBtn.addView(accountTextGroup)
        bottomRow.addView(accountBtn)

        // 右侧：「＋ 新对话」
        val newChatBtn = UiKit.horizontal(context).apply {
            tag = "nav_new_chat"
            background = UiKit.rounded(context, R.color.bg_card, 999, R.color.divider, 1)
            setPadding(dp(12), dp(8), dp(14), dp(8))
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener {
                onNewConversation()
                onEntrySelected(DrawerController.ID_NEW_CONVERSATION)
            }
        }
        newChatBtn.addView(UiKit.text(context, "＋", 13f, R.color.text_primary, bold = true).apply {
            setPadding(0, 0, dp(4), 0)
        })
        newChatBtn.addView(UiKit.text(context, "新对话", 13f, R.color.text_primary, bold = true))
        bottomRow.addView(newChatBtn)

        content.addView(bottomRow)
    }

    private fun addFeatureCenterEntry(rows: MutableList<RenderedRow>) {
        val totalCount = EntryRegistry.ENTRIES.size
        val label = "全部功能（${totalCount} 项）"
        rows += RenderedRow(
            id = "nav_feature_center",
            label = label,
            indent = 0,
            group = "feature_center",
            badge = null,
            todo = false,
            active = false,
            trailing = "›",
            iconRes = R.drawable.ic_dashboard,
        )

        val row = UiKit.horizontal(context).apply {
            tag = "nav_feature_center"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(42),
            ).apply {
                topMargin = dp(4)
            }
            background = UiKit.rounded(context, R.color.bg_card, 8, R.color.divider, 1)
            setPadding(dp(12), 0, dp(12), 0)
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener {
                onOpenFeatureCenter()
                onEntrySelected("nav_feature_center")
            }
        }
        row.addView(UiKit.icon(context, R.drawable.ic_dashboard, R.color.text_secondary).apply {
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(8) }
        })
        row.addView(UiKit.text(context, label, 13f, R.color.text_primary, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(UiKit.text(context, "›", 14f, R.color.text_tertiary))
        content.addView(row)
    }

    private fun addDivider() {
        addContent(
            View(context).apply {
                setBackgroundColor(UiKit.color(context, R.color.divider))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(1),
                ).apply {
                    topMargin = dp(6)
                    bottomMargin = dp(6)
                }
            },
        )
    }

    private fun badgeView(badge: Int): View =
        UiKit.text(context, "$badge", 11f, R.color.white, bold = true).apply {
            background = UiKit.rounded(context, R.color.accent, 999)
            setPadding(dp(6), dp(1), dp(6), dp(1))
            gravity = Gravity.CENTER
            minWidth = dp(18)
        }

    private fun trailingView(text: String): View =
        UiKit.text(context, text, 12f, R.color.text_tertiary).apply {
            setPadding(dp(6), 0, 0, 0)
        }

    private fun dp(v: Int): Int = UiKit.dp(context, v)
}
