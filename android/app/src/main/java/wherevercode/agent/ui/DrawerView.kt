package wherevercode.agent.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import wherevercode.agent.R

/**
 * Drawer View (V5 Phase B C2, batch 3, R2 visual rework).
 *
 * 视觉基准：v4.3-01（第 1 张，批 3 首批默认样式）+ V5-UI.md §三
 * 10 项映射。结构：品牌头（WhereverCode + V5）→ V5 一级入口 →
 * “工作空间”分组 → “扩展与资源”分组 → 底部用户行（头像 + 登录
 * 状态 + 设置齿轮）。会话可从顶栏与一级抽屉入口到达；
 * 原型图右半屏的版本文字是来源原型的版本号，不照抄；用户名是 maintainer
 * 本人，App 内无用户名可取，不写死，只显示登录状态。
 *
 * 视觉规矩（GPT 点名的原罪不再犯）：紧凑列表行（真实线形图标 +
 * 标题 + chevron/角标/“待开发”），行高 48dp；分组标题小灰字；颜色全部
 * 走主题 token（values/colors.xml 与 values-night/colors.xml），
 * 不写死；禁止整屏巨大圆角卡片——行不用 bg_card，当前行只用
 * accent_soft 底色高亮。行首图标一律是 drawable（[iconResFor]，
 * UiKit.icon + token tint 着色）；汉字字标只作注册表外自定义入口的
 * 回落（[glyphView]），默认 18 行里没有一行用它。
 *
 * 入口唯一事实来源是 [EntryRegistry]：顶栏/工作空间/扩展与资源
 * 三组按注册表顺序渲染；工作台行与底部齿轮是同一入口
 * （nav_workbench）的两种形态；“我的项目”是 nav_projects 的快捷
 * 方式（同一入口，不另注册）；“界面与主题”（nav_theme）在工作
 * 空间组内，接 cycleTheme()。已实现点按真可用；待开发行标
 * “待开发”，点按只把注册表 id 交给宿主（宿主只 toast 提示，
 * 不伪装已实现、不关抽屉）。
 *
 * 角标（[DrawerBadges]）：运行中任务数与待审批数都由宿主在打开
 * 抽屉时从真实数据源读（任务库 / 审批门），经 [setBadges] 传入；
 * 0 也显示“0”，只有读不到（未知）时不显示，绝不伪造数字。待审批
 * 行是待开发，徽标与行尾“待开发”标注共存。
 *
 * 旧扁平抽屉的定制（DrawerController）：隐藏与收藏保留（长按行
 * 可隐藏/收藏，隐藏后底部留“已隐藏”恢复入口；收藏的行在本组内
 * 置顶并标 ★）；嵌套过的子入口仍渲染在父行之下（缩进一级），不
 * 丢失。扁平列表的“排序（move）”与“更多”折叠不再适用分组结构，
 * 已退役：行顺序即注册表顺序；“更多”里的非基础入口在 App 内从
 * 未注册过（只在测试里出现），模型本身（DrawerController）不动。
 * 持久化（Room）与 C1 五项修复一字不碰。
 *
 * 路由不在这里：点按只回调 [onEntrySelected]（注册表 id），
 * MainActivity 决定打开什么。
 */
class DrawerView(
    private val context: Context,
    private val controller: DrawerController,
    private val onEntrySelected: (registryId: String) -> Unit,
    private val onPrefsChanged: () -> Unit,
    /** 当前入口的 DrawerController id（MainActivity.currentEntry），用于高亮当前行。 */
    private val activeTargetProvider: () -> String? = { null },
    /** 底部用户行标签（MainActivity 读登录状态：“已登录”/“未登录”；App 内无用户名可取，不写死）。 */
    private val userLabelProvider: () -> String = { "未登录" },
    /** “界面与主题”行尾的当前主题名（“浅色”/“深色”/“跟随系统”）。 */
    private val themeLabelProvider: () -> String = { "浅色" },
) {
    val root: ScrollView = ScrollView(context).apply {
        isFillViewport = true
    }

    private val content: LinearLayout = UiKit.vertical(context).apply {
        setPadding(dp(12), dp(8), dp(12), dp(16))
    }

    /** 最后一次 [refresh] 渲染的行（测试口径）。 */
    private var renderedRowList: List<RenderedRow> = emptyList()

    data class RenderedRow(
        val id: String,
        val label: String,
        val indent: Int,
        /** "top" / "workspace" / "extensions" / "bottom"。 */
        val group: String,
        val badge: Int?,
        val todo: Boolean,
        val active: Boolean,
        /** 行尾文案：主题名 / “待开发” / chevron（角标行与当前行无行尾文案时为 null）。 */
        val trailing: String?,
        /** 行首真实 drawable 资源 id；底部用户行（头像原样保留）与注册表外回落行为 null。 */
        val iconRes: Int? = null,
    )

    private var badges: DrawerBadges = DrawerBadges()

    /** 折叠的分组（内存态；低频扩展默认收起，分组头点按切换）。 */
    // Keep low-frequency plugins/resources below the fold without forcing the
    // user to scroll past six secondary entries on every drawer open.
    private val collapsedGroups: MutableSet<String> = mutableSetOf("extensions")

    /** 某分组是否折叠（测试口径）。 */
    fun isGroupCollapsed(group: String): Boolean = group in collapsedGroups

    fun setGroupCollapsed(group: String, collapsed: Boolean) {
        if (collapsed) collapsedGroups.add(group) else collapsedGroups.remove(group)
        refresh()
    }

    /** 宿主传入真实角标后刷新（未知不显示，0 显示“0”）。 */
    fun setBadges(newBadges: DrawerBadges) {
        badges = newBadges
        refresh()
    }

    fun currentBadges(): DrawerBadges = badges

    init {
        root.addView(content)
        refresh()
    }

    /** 全部渲染行的快照（测试口径；不含分组头/分割线）。 */
    fun renderedRows(): List<RenderedRow> = renderedRowList.toList()

    /** 入口行的标签（测试口径；含 ★ 前缀与“我的项目”等覆盖标签）。 */
    fun renderedLabels(): List<String> = renderedRowList.map { it.label }

    /** 入口行的注册表 id（测试口径；nav_projects 与 nav_workbench 各出现两次：正行 + 快捷方式）。 */
    fun renderedIds(): List<String> = renderedRowList.map { it.id }

    /** 某分组内入口行的 id（测试口径）。 */
    fun renderedGroupIds(group: String): List<String> =
        renderedRowList.filter { it.group == group }.map { it.id }

    /** 一行的缩进，未渲染时 -1（测试口径）。 */
    fun renderedRowIndent(id: String): Int =
        renderedRowList.firstOrNull { it.id == id }?.indent ?: -1

    /** 一行是否标“待开发”（测试口径）。 */
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
     * 注册表入口 → 行首真实线形图标（R4：汉字大字假图标的替换表）。
     * 映射只按注册表 id，不碰 [EntryRegistry]（注册表的 iconGlyph 仍
     * 留作注册表外自定义入口的回落）。未知 id 返回 null，调用方回落
     * 到 [glyphView]，绝不崩。
     */
    fun iconResFor(entryId: String): Int? = when (entryId) {
        "nav_chat" -> R.drawable.ic_chat
        "nav_history" -> R.drawable.ic_search
        "nav_projects" -> R.drawable.ic_folder
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

    private fun findRowView(id: String): View? {
        for (i in 0 until content.childCount) {
            val child = content.getChildAt(i)
            if (child.tag == id) return child
        }
        return null
    }

    fun refresh() {
        val rows = mutableListOf<RenderedRow>()
        content.removeAllViews()
        addBrandHeader()
        var sectionShown = false
        // ── V5 默认一级入口（无分组头）──
        sectionShown = addGroupSection(
            title = null,
            groupKey = "top",
            planned = EntryRegistry.byGroup(EntryRegistry.Group.DRAWER_TOP).map { it to null },
            rows = rows,
            sectionShown = sectionShown,
        )
        // ── 工作空间分组 ──
        sectionShown = addWorkspaceGroup(rows, sectionShown)
        // ── 扩展与资源分组──
        sectionShown = addGroupSection(
            title = "扩展与资源",
            groupKey = "extensions",
            planned = EntryRegistry.byGroup(EntryRegistry.Group.DRAWER_EXTENSIONS).map { it to null },
            rows = rows,
            sectionShown = sectionShown,
        )
        // ── 底部用户行（内容不足一屏时钉在抽屉底边；工作台被隐藏时不出现）──
        if (!controller.isHidden(DrawerController.ID_SETTINGS)) {
            addSpacer()
            addDivider()
            addBottomRow(rows)
        }
        addHiddenSection()
        renderedRowList = rows
    }

    /** 注册表入口对应的抽屉控制器 id（DRAWER 类才有；ACTION/TODO/SUGGESTION 没有）。 */
    private fun EntryRegistry.Entry.controllerId(): String? =
        if (targetKind == EntryRegistry.TargetKind.DRAWER) target else null

    /**
     * 可见的计划行：去掉已隐藏与已被嵌套到别处的入口；收藏的在本组内
     * 置顶（收藏内部仍按注册表顺序）。
     */
    private fun visiblePlanned(
        planned: List<Pair<EntryRegistry.Entry, String?>>,
    ): List<Pair<EntryRegistry.Entry, String?>> {
        val visible = planned.filter { (entry, _) ->
            val id = entry.controllerId()
            id == null || (!controller.isHidden(id) && !controller.isNested(id))
        }
        val favorites = visible.filter { (entry, _) ->
            val id = entry.controllerId()
            id != null && controller.isFavorite(id)
        }
        return favorites + visible.filter { it !in favorites }
    }

    private fun addGroupSection(
        title: String?,
        groupKey: String,
        planned: List<Pair<EntryRegistry.Entry, String?>>,
        rows: MutableList<RenderedRow>,
        sectionShown: Boolean,
    ): Boolean {
        val entries = visiblePlanned(planned)
        if (entries.isEmpty()) return sectionShown
        if (sectionShown) addDivider()
        if (title != null) addGroupHeader(title, groupKey)
        if (!isGroupCollapsed(groupKey)) {
            entries.forEach { (entry, labelOverride) ->
                addEntryRow(entry, groupKey, indent = 0, rows, labelOverride)
            }
        }
        return true
    }

    /** 工作空间组展示主题等次级入口；项目、任务与设置在 V5 一级中。 */
    private fun addWorkspaceGroup(
        rows: MutableList<RenderedRow>,
        sectionShown: Boolean,
    ): Boolean {
        val planned = mutableListOf<Pair<EntryRegistry.Entry, String?>>()
        EntryRegistry.byGroup(EntryRegistry.Group.DRAWER_WORKSPACE).forEach { entry ->
            planned += entry to null
        }
        return addGroupSection("工作空间", "workspace", planned, rows, sectionShown)
    }

    private fun addBrandHeader() {
        val row = UiKit.horizontal(context).apply {
            setPadding(dp(8), dp(8), dp(8), dp(12))
        }
        row.addView(
            UiKit.text(context, "∞", 20f, R.color.home_gradient_start, bold = true).apply {
                layoutParams = LinearLayout.LayoutParams(dp(28), LinearLayout.LayoutParams.WRAP_CONTENT)
                gravity = Gravity.CENTER
            },
        )
        row.addView(UiKit.text(context, "WhereverCode", 17f, R.color.home_text, bold = true))
        row.addView(
            UiKit.text(context, "V5", 11f, R.color.text_tertiary).apply {
                setPadding(dp(6), dp(4), 0, 0)
            },
        )
        content.addView(row)
    }

    private fun addGroupHeader(title: String, groupKey: String) {
        val row = UiKit.horizontal(context).apply {
            setPadding(dp(8), dp(14), dp(8), dp(4))
            setOnClickListener {
                setGroupCollapsed(groupKey, !isGroupCollapsed(groupKey))
            }
        }
        row.addView(
            UiKit.text(context, title, 12f, R.color.text_secondary).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        row.addView(
            UiKit.text(
                context,
                if (isGroupCollapsed(groupKey)) "⌄" else "⌃",
                12f,
                R.color.text_tertiary,
            ),
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
                    bottomMargin = dp(8)
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

    private fun addEntryRow(
        entry: EntryRegistry.Entry,
        groupKey: String,
        indent: Int,
        rows: MutableList<RenderedRow>,
        labelOverride: String? = null,
    ) {
        val controllerId = entry.controllerId()
        val isFavorite = controllerId != null && controller.isFavorite(controllerId)
        val baseLabel = labelOverride ?: entry.title
        val display = if (isFavorite) "★ $baseLabel" else baseLabel
        val active = controllerId != null && controllerId == activeTargetProvider()
        val badge = badges.badgeFor(entry.id)
        val todo = entry.status == EntryRegistry.Status.TODO
        val iconRes = iconResFor(entry.id)
        val trailing: String? = when {
            entry.id == "nav_theme" -> themeLabelProvider()
            todo -> "待开发"
            badge != null || active -> null
            else -> "›"
        }
        rows += RenderedRow(entry.id, display, indent, groupKey, badge, todo, active, trailing, iconRes)
        val row = UiKit.horizontal(context).apply {
            tag = entry.id
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48),
            ).apply {
                marginStart = dp(16 * indent)
            }
            setPadding(dp(8), 0, dp(8), 0)
            if (active) background = UiKit.rounded(context, R.color.accent_soft, 8)
            setOnClickListener { onEntrySelected(entry.id) }
            if (controllerId != null) {
                setOnLongClickListener {
                    showCustomizeMenu(entry, controllerId)
                    true
                }
            }
        }
        row.addView(iconView(entry, active))
        row.addView(
            UiKit.text(
                context,
                display,
                15f,
                if (todo) R.color.text_secondary else R.color.text_primary,
                bold = active,
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                )
            },
        )
        if (badge != null) row.addView(badgeView(badge))
        trailing?.let { row.addView(trailingView(it)) }
        content.addView(row)
        // 嵌套过的子入口仍渲染在父行之下（缩进一级），不丢失。
        controllerId?.let { addChildren(it, groupKey, indent + 1, rows) }
    }

    private fun addChildren(
        parentControllerId: String,
        groupKey: String,
        indent: Int,
        rows: MutableList<RenderedRow>,
    ) {
        controller.childrenOf(parentControllerId).forEach { child ->
            val entry = EntryRegistry.ENTRIES.firstOrNull {
                it.targetKind == EntryRegistry.TargetKind.DRAWER && it.target == child.id
            } ?: EntryRegistry.Entry(
                id = child.id,
                title = child.label,
                group = EntryRegistry.Group.DRAWER_TOP,
                status = EntryRegistry.Status.IMPLEMENTED,
                targetKind = EntryRegistry.TargetKind.DRAWER,
                target = child.id,
                iconGlyph = "·",
                iconDrawable = null,
                parentId = null,
                todoMessage = null,
                note = "注册表外自定义入口（宿主按旧路由兜底）。",
            )
            addEntryRow(entry, groupKey, indent, rows)
        }
    }

    /**
     * 底部用户行：头像 + 登录状态 + 设置齿轮。工作台行与底部齿轮是
     * 同一入口（nav_workbench）的两种形态；调用方在工作台被隐藏时
     * 不调本方法（用底部“已隐藏”恢复）。
     */
    private fun addBottomRow(rows: MutableList<RenderedRow>) {
        val label = userLabelProvider()
        val active = DrawerController.ID_SETTINGS == activeTargetProvider()
        rows += RenderedRow("nav_workbench", label, 0, "bottom", null, false, active, null)
        val row = UiKit.horizontal(context).apply {
            tag = "nav_workbench"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(56),
            )
            setPadding(dp(8), 0, dp(8), 0)
            if (active) background = UiKit.rounded(context, R.color.accent_soft, 8)
            setOnClickListener { onEntrySelected("nav_workbench") }
        }
        row.addView(
            UiKit.text(context, "👤", 18f, R.color.accent).apply {
                background = UiKit.rounded(context, R.color.accent_soft, 999)
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                gravity = Gravity.CENTER
            },
        )
        row.addView(
            UiKit.text(context, label, 15f, R.color.text_primary).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                ).apply {
                    marginStart = dp(12)
                }
            },
        )
        row.addView(
            UiKit.text(context, "⚙", 18f, R.color.text_secondary),
        )
        content.addView(row)
    }

    /**
     * 行首图标：有映射时是 20dp 真实线形 drawable（UiKit.icon +
     * token tint：当前行 accent，其余 text_secondary），占 28dp 槽位
     * 与旧字标同宽，行高/密度不动；无映射（注册表外自定义入口）时
     * 回落到汉字字标 [glyphView]。
     */
    private fun iconView(entry: EntryRegistry.Entry, active: Boolean): View {
        val res = iconResFor(entry.id) ?: return glyphView(entry.iconGlyph, active)
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

    private fun badgeView(count: Int): TextView =
        UiKit.text(context, badgeLabel(count), 11f, R.color.accent, bold = true).apply {
            background = UiKit.rounded(context, R.color.accent_soft, 999)
            setPadding(dp(7), dp(2), dp(7), dp(2))
            gravity = Gravity.CENTER
            minWidth = dp(22)
        }

    private fun badgeLabel(count: Int): String = if (count > 99) "99+" else count.toString()

    private fun trailingView(text: String): TextView =
        // chevron 照 v4.3-01 与标题同级；“待开发”/主题名是小灰字说明。
        UiKit.text(context, text, if (text == "›") 16f else 12f, R.color.text_tertiary)

    private fun addHiddenSection() {
        val hidden = controller.toPrefs().hidden
        if (hidden.isEmpty()) return
        val labels = hidden.mapNotNull { controller.entry(it)?.label }
        val row = UiKit.text(context, "已隐藏（${hidden.size}）", 13f, R.color.text_primary, bold = true).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { showRestoreHiddenDialog(labels, hidden.toList()) }
        }
        content.addView(row)
    }

    private fun showRestoreHiddenDialog(labels: List<String>, ids: List<String>) {
        AlertDialog.Builder(context)
            .setTitle("已隐藏的入口")
            .setItems(labels.toTypedArray()) { _, which ->
                if (controller.show(ids[which])) {
                    refresh()
                    onPrefsChanged()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 长按菜单（R2）：只剩隐藏与收藏——扁平列表的排序/嵌套不适用
     * 分组结构，已退役；行顺序即注册表顺序。
     */
    private fun showCustomizeMenu(entry: EntryRegistry.Entry, controllerId: String) {
        val items = mutableListOf<String>()
        items += if (controller.isFavorite(controllerId)) "取消收藏" else "收藏"
        items += "隐藏"
        AlertDialog.Builder(context)
            .setTitle(entry.title)
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    "隐藏" -> if (controller.hide(controllerId)) changed()
                    "收藏", "取消收藏" -> if (controller.toggleFavorite(controllerId)) changed()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun changed() {
        refresh()
        onPrefsChanged()
    }

    private fun dp(value: Int): Int = UiKit.dp(context, value)
}
