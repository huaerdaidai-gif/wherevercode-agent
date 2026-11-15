package wherevercode.agent.ui

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import wherevercode.agent.R

/**
 * Drawer View (V5 导航整改最终定稿，2026-11-15)。
 *
 * 参考 Claude 的抽屉滑动、主页面圆角、遮罩、选中状态、返回手势和
 * 底部固定操作区。结构：
 *
 * ┌─────────────────────────┐
 * │  ∞ WhereverCode  V5     │  品牌头
 * ├─────────────────────────┤
 * │  💬 对话                 │  一级入口（3 项）
 * │  📁 项目                 │
 * │  ⚙️ 控制                 │
 * ├─────────────────────────┤
 * │  最近会话                 │  分组标题
 * │  · 会话标题1    3分前    │  最近会话列表
 * │  · 会话标题2    1小时前  │
 * │  · 会话标题3    昨天     │
 * │  ...                     │
 * ├─────────────────────────┤
 * │ 👤 已登录          ＋    │  底部固定：头像→设置  ＋→新对话
 * └─────────────────────────┘
 *
 * 规矩：
 * - 不在普通界面显示 route_id、P0、待验收、扩展等开发字段。
 * - 未接通的执行能力显示真实状态，不用假数据模拟成功。
 * - 设置不再是一级菜单：点击底部账号头像进入独立设置页面。
 * - 工作台统一改名「项目」，控制台统一改名「控制」。
 * - 70 屏旗舰功能全部保留，通过业务导航逐层访问。
 * - 与 iOS 共享导航命名、信息架构和行为契约。
 */
class DrawerView(
    private val context: Context,
    private val controller: DrawerController,
    private val onEntrySelected: (registryId: String) -> Unit,
    private val onPrefsChanged: () -> Unit,
    /** 当前入口的 DrawerController id（MainActivity.currentEntry），用于高亮当前行。 */
    private val activeTargetProvider: () -> String? = { null },
    /** 底部用户行标签（MainActivity 读登录状态："已登录"/"未登录"）。 */
    private val userLabelProvider: () -> String = { "未登录" },
    /** "界面与主题"行尾的当前主题名（"浅色"/"深色"/"跟随系统"）。 */
    private val themeLabelProvider: () -> String = { "浅色" },
    /** 最近会话列表数据提供者（返回 sessionId + label + 时间标签）。 */
    private val recentSessionsProvider: () -> List<RecentSession> = { emptyList() },
) {
    /** 最近会话数据项。 */
    data class RecentSession(
        val sessionId: String,
        val label: String,
        val timeLabel: String,
        val summary: String? = null,
    )

    /** 抽屉外壳：可滚动业务区 + 固定在底部的账号/新对话操作区。 */
    val root: LinearLayout = UiKit.vertical(context)

    private val scrollView: ScrollView = ScrollView(context).apply {
        isFillViewport = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f,
        )
    }

    private val content: LinearLayout = UiKit.vertical(context).apply {
        setPadding(dp(12), dp(8), dp(12), dp(0))
    }

    private val bottomContent: LinearLayout = UiKit.vertical(context).apply {
        setPadding(dp(12), 0, dp(12), dp(8))
    }

    private var searchQuery: String = ""

    /** 最后一次 [refresh] 渲染的行（测试口径）。 */
    private var renderedRowList: List<RenderedRow> = emptyList()

    data class RenderedRow(
        val id: String,
        val label: String,
        val indent: Int,
        /** "top" / "center" / "sessions" / "bottom"。 */
        val group: String,
        val badge: Int?,
        val todo: Boolean,
        val active: Boolean,
        /** 行尾文案。 */
        val trailing: String?,
        /** 行首真实 drawable 资源 id。 */
        val iconRes: Int? = null,
    )

    private var badges: DrawerBadges = DrawerBadges()

    /** 折叠的分组（内存态，默认全展开；分组头点按切换）。 */
    private val collapsedGroups: MutableSet<String> = mutableSetOf()

    /** 某分组是否折叠（测试口径）。 */
    fun isGroupCollapsed(group: String): Boolean = group in collapsedGroups

    fun setGroupCollapsed(group: String, collapsed: Boolean) {
        if (collapsed) collapsedGroups.add(group) else collapsedGroups.remove(group)
        refresh()
    }

    /** 宿主传入真实角标后刷新（未知不显示，0 显示"0"）。 */
    fun setBadges(newBadges: DrawerBadges) {
        badges = newBadges
        refresh()
    }

    fun currentBadges(): DrawerBadges = badges

    init {
        scrollView.addView(content)
        root.addView(scrollView)
        root.addView(bottomContent)
        refresh()
    }

    /** 全部渲染行的快照（测试口径；不含分组头/分割线）。 */
    fun renderedRows(): List<RenderedRow> = renderedRowList.toList()

    /** 入口行的标签（测试口径）。 */
    fun renderedLabels(): List<String> = renderedRowList.map { it.label }

    /** 入口行的注册表 id（测试口径）。 */
    fun renderedIds(): List<String> = renderedRowList.map { it.id }

    /** 某分组内入口行的 id（测试口径）。 */
    fun renderedGroupIds(group: String): List<String> =
        renderedRowList.filter { it.group == group }.map { it.id }

    /** 一行的缩进，未渲染时 -1（测试口径）。 */
    fun renderedRowIndent(id: String): Int =
        renderedRowList.firstOrNull { it.id == id }?.indent ?: -1

    /** 一行是否标"待开发"（测试口径）。 */
    fun renderedRowIsTodo(id: String): Boolean =
        renderedRowList.firstOrNull { it.id == id }?.todo ?: false

    /** 一行的角标（无角标时 null；测试口径）。 */
    fun renderedRowBadge(id: String): Int? =
        renderedRowList.firstOrNull { it.id == id }?.badge

    /** 一行是否高亮为当前入口（测试口径）。 */
    fun renderedRowIsActive(id: String): Boolean =
        renderedRowList.firstOrNull { it.id == id }?.active ?: false

    /** 一行的行尾文案（测试口径）。 */
    fun renderedRowTrailing(id: String): String? =
        renderedRowList.firstOrNull { it.id == id }?.trailing

    /** 一行行首的真实 drawable 资源 id（无 drawable 时 null；测试口径）。 */
    fun renderedRowIconRes(id: String): Int? =
        renderedRowList.firstOrNull { it.id == id }?.iconRes

    /**
     * 注册表入口 → 行首真实线形图标。
     * 映射只按注册表 id，不碰 EntryRegistry。
     */
    fun iconResFor(entryId: String): Int? = when (entryId) {
        "nav_chat" -> R.drawable.ic_chat
        "nav_projects" -> R.drawable.ic_folder
        "nav_channels" -> R.drawable.ic_chat
        "nav_control" -> R.drawable.ic_console
        "nav_history" -> R.drawable.ic_search
        "nav_team" -> R.drawable.ic_team
        "nav_devices" -> R.drawable.ic_device
        "nav_tools" -> R.drawable.ic_tools
        "nav_console" -> R.drawable.ic_console
        "nav_team_channel" -> R.drawable.ic_chat
        "nav_workbench" -> R.drawable.ic_settings
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

    /** 测试口径：按真实点按路径触发一行的 onEntrySelected。 */
    fun clickRowForTest(id: String): Boolean {
        val view = findRowView(id) ?: return false
        view.performClick()
        return true
    }

    private fun findRowView(id: String): View? = findRowView(root, id)

    private fun findRowView(parent: View, id: String): View? {
        if (parent.tag == id) return parent
        if (parent !is ViewGroup) return null
        for (i in 0 until parent.childCount) {
            findRowView(parent.getChildAt(i), id)?.let { return it }
        }
        return null
    }

    fun refresh() {
        val rows = mutableListOf<RenderedRow>()
        content.removeAllViews()
        addBrandHeader()
        // ── 一级入口（严格四项：对话、项目、频道、控制）──
        addPrimaryEntries(rows)
        // ── 全部功能：集中列出全部注册表入口（70 屏补漏）──
        addFeatureCenterEntry(rows)
        addSessionSearch()
        // ── 最近会话列表（支持关键词过滤）──
        addRecentSessionsSection(rows)
        // ── 底部固定：账号头像（左）+ ＋ 新对话（右）──
        addBottomBar(rows)
        renderedRowList = rows
    }

    /** 注册表入口对应的抽屉控制器 id（DRAWER 类才有）。 */
    private fun EntryRegistry.Entry.controllerId(): String? =
        if (targetKind == EntryRegistry.TargetKind.DRAWER) target else null

    /**
     * 一级入口：对话、项目、控制。
     * 参考 Claude 抽屉的紧凑列表行：真实线形图标 + 标题 + chevron/选中高亮。
     */
    private fun addPrimaryEntries(rows: MutableList<RenderedRow>) {
        val primaryEntries = listOf(
            Triple("nav_chat", "对话", DrawerController.ID_CHAT),
            Triple("nav_projects", "项目", DrawerController.ID_PROJECTS),
            Triple("nav_channels", "频道", DrawerController.ID_CHANNELS),
            Triple("nav_control", "控制", DrawerController.ID_CONTROL),
        )
        primaryEntries.forEach { (entryId, label, controllerId) ->
            val active = controllerId == activeTargetProvider()
            val iconRes = iconResFor(entryId)
            rows += RenderedRow(entryId, label, 0, "top", null, false, active, if (!active) "›" else null, iconRes)
            val row = UiKit.horizontal(context).apply {
                tag = entryId
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(52),
                )
                setPadding(dp(12), 0, dp(12), 0)
                if (active) background = UiKit.rounded(context, R.color.accent_soft, 10)
                setOnClickListener { onEntrySelected(entryId) }
            }
            row.addView(iconView(iconRes, active))
            row.addView(
                UiKit.text(
                    context,
                    label,
                    16f,
                    R.color.text_primary,
                    bold = active,
                ).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f,
                    )
                },
            )
            if (active) {
                row.addView(
                    UiKit.text(context, "", 14f, R.color.accent).apply {
                        background = UiKit.rounded(context, R.color.accent, 999)
                        layoutParams = LinearLayout.LayoutParams(dp(4), dp(20))
                    },
                )
            }
            content.addView(row)
        }
    }

    /**
     * 「全部功能」行（一级入口之下）：点按打开功能中心列表，集中列出
     * 全部注册表条目，保证每个功能都有可见入口（不靠隐藏手势）。
     * group 用 "center"，不进 "top"（一级入口严格四项不变）。
     */
    private fun addFeatureCenterEntry(rows: MutableList<RenderedRow>) {
        val entryId = "nav_feature_center"
        val iconRes = iconResFor(entryId)
        rows += RenderedRow(entryId, "全部功能", 0, "center", null, false, false, "›", iconRes)
        val row = UiKit.horizontal(context).apply {
            tag = entryId
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52),
            )
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { onEntrySelected(entryId) }
        }
        row.addView(iconView(iconRes, false))
        row.addView(
            UiKit.text(
                context,
                "全部功能",
                16f,
                R.color.text_primary,
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                )
            },
        )
        content.addView(row)
    }

    /** 搜索只过滤真实最近会话，不伪造远端搜索结果。 */
    private fun addSessionSearch() {
        val input = EditText(context).apply {
            hint = "搜索最近会话"
            setText(searchQuery)
            setSingleLine(true)
            textSize = 13f
            setTextColor(UiKit.color(context, R.color.text_primary))
            setHintTextColor(UiKit.color(context, R.color.text_tertiary))
            setBackgroundResource(R.drawable.bg_input)
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(40),
            ).apply { topMargin = dp(8); bottomMargin = dp(4) }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val next = s?.toString().orEmpty()
                    if (next == searchQuery) return
                    searchQuery = next
                    refresh()
                }
            })
        }
        content.addView(input)
    }

    /**
     * 最近会话列表：从宿主传入的真实会话数据渲染。
     * 无会话时显示空态提示；有会话时按时间排列，最多显示 8 条。
     */
    private fun addRecentSessionsSection(rows: MutableList<RenderedRow>) {
        val sessions = recentSessionsProvider()
            .filter { searchQuery.isBlank() || it.label.contains(searchQuery, ignoreCase = true) }
            .take(8)
        addDivider()
        // 分组标题
        val header = UiKit.horizontal(context).apply {
            setPadding(dp(8), dp(8), dp(8), dp(4))
            setOnClickListener {
                setGroupCollapsed("sessions", !isGroupCollapsed("sessions"))
            }
        }
        header.addView(
            UiKit.text(context, "最近会话", 13f, R.color.text_secondary).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                )
            },
        )
        header.addView(
            UiKit.text(
                context,
                if (isGroupCollapsed("sessions")) "⌄" else "⌃",
                12f,
                R.color.text_tertiary,
            ),
        )
        content.addView(header)

        if (!isGroupCollapsed("sessions")) {
            if (sessions.isEmpty()) {
                content.addView(
                    UiKit.text(
                        context,
                        "暂无会话记录",
                        13f,
                        R.color.text_tertiary,
                    ).apply {
                        setPadding(dp(20), dp(8), dp(20), dp(8))
                    },
                )
            } else {
                sessions.forEach { session ->
                    addSessionRow(session, rows)
                }
            }
        }
    }

    private fun addSessionRow(
        session: RecentSession,
        rows: MutableList<RenderedRow>,
    ) {
        val rowId = "session:${session.sessionId}"
        rows += RenderedRow(rowId, session.label, 0, "sessions", null, false, false, session.timeLabel, null)
        val row = UiKit.horizontal(context).apply {
            tag = rowId
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44),
            )
            setPadding(dp(20), 0, dp(12), 0)
            setOnClickListener { onEntrySelected(rowId) }
        }
        row.addView(
            UiKit.text(context, "·", 16f, R.color.text_tertiary).apply {
                layoutParams = LinearLayout.LayoutParams(dp(20), LinearLayout.LayoutParams.WRAP_CONTENT)
                gravity = Gravity.CENTER
            },
        )
        row.addView(
            UiKit.text(
                context,
                session.label.take(28),
                14f,
                R.color.text_primary,
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                )
                maxLines = 1
            },
        )
        row.addView(
            UiKit.text(context, session.timeLabel, 11f, R.color.text_tertiary),
        )
        content.addView(row)
    }

    private fun addBrandHeader() {
        val row = UiKit.horizontal(context).apply {
            setPadding(dp(8), dp(12), dp(8), dp(16))
        }
        row.addView(
            UiKit.text(context, "∞", 22f, R.color.home_gradient_start, bold = true).apply {
                layoutParams = LinearLayout.LayoutParams(dp(28), LinearLayout.LayoutParams.WRAP_CONTENT)
                gravity = Gravity.CENTER
            },
        )
        row.addView(UiKit.text(context, "WhereverCode", 18f, R.color.home_text, bold = true))
        row.addView(
            UiKit.text(context, "V5", 12f, R.color.text_tertiary).apply {
                setPadding(dp(6), dp(6), 0, 0)
            },
        )
        content.addView(row)
    }

    private fun addDivider() {
        content.addView(
            View(context).apply {
                setBackgroundColor(UiKit.color(context, R.color.divider))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(1),
                ).apply {
                    topMargin = dp(8)
                    bottomMargin = dp(4)
                    marginStart = dp(4)
                    marginEnd = dp(4)
                }
            },
        )
    }

    private fun addSpacer() {
        content.addView(
            View(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                )
            },
        )
    }

    /**
     * 底部固定操作区：左侧账号头像（点击进入完整设置），右侧 ＋ 新对话。
     * 参考 Claude 抽屉底部固定操作区的设计。
     */
    private fun addBottomBar(rows: MutableList<RenderedRow>) {
        bottomContent.removeAllViews()
        bottomContent.addView(
            View(context).apply {
                setBackgroundColor(UiKit.color(context, R.color.divider))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(1),
                ).apply { bottomMargin = dp(4) }
            },
        )
        val bottomBar = UiKit.horizontal(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(56),
            )
            setPadding(dp(12), dp(8), dp(12), dp(12))
            gravity = Gravity.CENTER_VERTICAL
        }

        // 左侧：账号头像 + 登录状态 → 点击进入设置
        val active = DrawerController.ID_SETTINGS == activeTargetProvider()
        rows += RenderedRow("nav_workbench", userLabelProvider(), 0, "bottom", null, false, active, null)
        val avatarRow = UiKit.horizontal(context).apply {
            tag = "nav_workbench"
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            )
            gravity = Gravity.CENTER_VERTICAL
            if (active) background = UiKit.rounded(context, R.color.accent_soft, 8)
            setOnClickListener { onEntrySelected("nav_workbench") }
        }
        avatarRow.addView(
            UiKit.text(context, "👤", 20f, R.color.accent).apply {
                background = UiKit.rounded(context, R.color.accent_soft, 999)
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                gravity = Gravity.CENTER
            },
        )
        avatarRow.addView(
            UiKit.text(context, userLabelProvider(), 14f, R.color.text_primary).apply {
                setPadding(dp(10), 0, 0, 0)
            },
        )
        bottomBar.addView(avatarRow)

        // 右侧：＋ 新对话按钮
        val newChatButton = UiKit.text(context, "＋", 22f, R.color.home_text).apply {
            tag = "nav_new_chat"
            background = UiKit.rounded(context, R.color.accent_soft, 999)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            gravity = Gravity.CENTER
            setOnClickListener { onEntrySelected("nav_new_chat") }
        }
        bottomBar.addView(newChatButton)

        bottomContent.addView(bottomBar)
    }

    /**
     * 行首图标：有映射时是 20dp 真实线形 drawable（UiKit.icon +
     * token tint：当前行 accent，其余 text_secondary），占 28dp 槽位；
     * 无映射时回落到汉字字标 [glyphView]。
     */
    private fun iconView(iconRes: Int?, active: Boolean): View {
        val res = iconRes ?: return glyphView("·", active)
        return UiKit.icon(
            context,
            res,
            20,
            if (active) R.color.accent else R.color.text_secondary,
        ).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(24))
        }
    }

    private fun glyphView(glyph: String, active: Boolean): TextView =
        UiKit.text(
            context,
            glyph,
            16f,
            if (active) R.color.accent else R.color.text_secondary,
        ).apply {
            layoutParams = LinearLayout.LayoutParams(dp(28), LinearLayout.LayoutParams.WRAP_CONTENT)
            gravity = Gravity.CENTER
        }

    private fun dp(value: Int): Int = UiKit.dp(context, value)
}
