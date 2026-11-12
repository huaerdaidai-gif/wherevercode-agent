package wherevercode.agent.ui

/**
 * V5 抽屉模型（Phase B C2，骨架）。
 *
 * 依据：V5-UI.md §三（唯一全局功能入口：左上可嵌套抽屉）。
 * 默认一级入口 10 项，顺序即 V5-UI.md §三所列顺序；允许隐藏、排序、
 * 收藏、嵌套；非基础能力折叠到“更多”，不默认铺开。
 *
 * 入口 id 用字符串：默认 10 项是固定常量，后续能力（例如 Matrix、
 * 工作流）以新 id 注册为非基础能力进“更多”，不用改模型。
 *
 * 纯 JVM 逻辑，不含 Android View，不接线进 MainActivity——接线是
 * C2-批 2 的事，且必须等 C1 首批落定后（C1 正在改 MainActivity，
 * 见实施计划 §C2 与 C1 的边界）。
 *
 * 本文件只写模型，不复用任何第三方代码；V5-UI.md §四的自由度
 * 5 档尚未实现（全树 grep 零命中），本模型不假装有自由度过滤——
 * “按自由度动态呈现”是 C5/Phase D 的事，首个 APK 只保留入口
 * （见 Phase 0 报告 §7.2 第 3 项）。
 */
class DrawerController {

    /** 单个抽屉入口。 */
    data class DrawerEntry(
        val id: String,
        val label: String,
        /** 是否基础能力：false 的入口默认折叠到“更多”，不占一级位置。 */
        val isBasic: Boolean = true,
        /** 嵌套子入口（空 = 叶子）。子入口同样可再嵌套。 */
        val children: List<DrawerEntry> = emptyList(),
    )

    /** 可持久化的用户定制（C2-批 2 接 SharedPreferences/Room 时用）。 */
    data class DrawerPrefs(
        val order: List<String> = DEFAULT_ORDER,
        val hidden: Set<String> = emptySet(),
        val favorites: Set<String> = emptySet(),
        /** 子入口 id → 父入口 id。 */
        val nestedUnder: Map<String, String> = emptyMap(),
        /** “更多”是否展开；默认折叠。 */
        val moreExpanded: Boolean = false,
    )

    companion object {
        // 默认一级入口 id（V5-UI.md §三，顺序即所列顺序，不得擅自调换）。
        const val ID_NEW_CONVERSATION = "new_conversation"
        const val ID_HISTORY_SEARCH = "history_search"
        const val ID_PROJECTS = "projects"
        const val ID_TASKS = "tasks"
        const val ID_AGENTS_TEAM = "agents_team"
        const val ID_DEVICES = "devices"
        const val ID_TOOLS_CONNECTIONS = "tools_connections"
        const val ID_RESULTS = "results"
        const val ID_HELP = "help"
        const val ID_SETTINGS = "settings"

        /**
         * 控制台入口 id（V5 控制台，2026-11-11）。
         * 不是默认一级入口：V5-UI.md §三的一级只有上面 10 项；控制台在
         * 抽屉“扩展与资源”分组（EntryRegistry nav_console），按需展开的
         * 专业工作台，不进 DEFAULT_ORDER、不占首页。
         */
        const val ID_CONSOLE = "console"

        /**
         * MCP / 插件入口 id（V5 MCP + 插件，2026-11-11）。
         * 与控制台同级：抽屉“扩展与资源”分组（EntryRegistry
         * nav_mcp_plugins），按需展开的扩展管理屏，不进 DEFAULT_ORDER。
         */
        const val ID_MCP_PLUGINS = "mcp_plugins"

        /**
         * 团队频道入口 id（P3，2026-11-11）。
         * 与控制台同级：抽屉“扩展与资源”分组（EntryRegistry
         * nav_team_channel），按需展开的有界团队聊天页，不进
         * DEFAULT_ORDER、不占首页。
         */
        const val ID_TEAM_CHANNEL = "team_channel"

        /** V5-UI.md §三默认一级入口顺序。 */
        val DEFAULT_ORDER: List<String> = listOf(
            ID_NEW_CONVERSATION,
            ID_HISTORY_SEARCH,
            ID_PROJECTS,
            ID_TASKS,
            ID_AGENTS_TEAM,
            ID_DEVICES,
            ID_TOOLS_CONNECTIONS,
            ID_RESULTS,
            ID_HELP,
            ID_SETTINGS,
        )

        private val DEFAULT_LABELS: Map<String, String> = mapOf(
            ID_NEW_CONVERSATION to "新对话",
            ID_HISTORY_SEARCH to "会话",
            ID_PROJECTS to "项目",
            ID_TASKS to "任务",
            ID_AGENTS_TEAM to "Agent 与团队",
            ID_DEVICES to "设备",
            ID_TOOLS_CONNECTIONS to "工具与连接",
            ID_RESULTS to "成果",
            ID_HELP to "帮助",
            ID_SETTINGS to "设置",
        )

        /** 默认 10 项入口（全为基础能力，全部平铺在一级）。 */
        fun defaultEntries(): List<DrawerEntry> =
            DEFAULT_ORDER.map { id ->
                DrawerEntry(id = id, label = DEFAULT_LABELS.getValue(id))
            }
    }

    /** 全部已知入口（含后续加入的非基础能力）。 */
    private val allEntries = LinkedHashMap<String, DrawerEntry>()

    /** 一级自定义顺序（仅含顶级基础入口，不含被嵌套的子入口）。 */
    private var order: MutableList<String> = DEFAULT_ORDER.toMutableList()

    private var hidden: MutableSet<String> = mutableSetOf()
    private var favorites: MutableSet<String> = mutableSetOf()

    /** 子入口 id → 父入口 id。 */
    private var nestedUnder: MutableMap<String, String> = mutableMapOf()

    /** “更多”是否展开；默认折叠。 */
    var moreExpanded: Boolean = false
        private set

    init {
        defaultEntries().forEach { allEntries[it.id] = it }
    }

    /**
     * 注册一个入口。基础能力进一级末尾，非基础能力默认进“更多”，
     * 不占一级位置。重复 id 拒绝注册。
     */
    fun addCustomEntry(entry: DrawerEntry) {
        require(entry.id !in allEntries) { "duplicate drawer entry: ${entry.id}" }
        allEntries[entry.id] = entry
        if (entry.isBasic) order.add(entry.id)
    }

    fun entry(id: String): DrawerEntry? = allEntries[id]

    fun isHidden(id: String): Boolean = id in hidden

    fun isFavorite(id: String): Boolean = id in favorites

    /** 是否被嵌套在另一入口之下（嵌套入口不出现在一级列表里）。 */
    fun isNested(id: String): Boolean = id in nestedUnder

    /**
     * 一级可见入口：未隐藏、未被嵌套的基础能力，按自定义顺序排列，
     * 收藏的入口置顶（收藏内部仍按当前自定义顺序）。
     */
    fun visibleTopLevel(): List<DrawerEntry> {
        val top = order.filter { id ->
            val entry = allEntries.getValue(id)
            entry.isBasic && id !in hidden && id !in nestedUnder
        }
        // 收藏的入口置顶，收藏内部仍按当前自定义顺序。
        val pinned = top.filter { it in favorites }
        val rest = top.filter { it !in favorites }
        return (pinned + rest).map { allEntries.getValue(it) }
    }

    /** 折叠在“更多”里的入口：未隐藏、未被嵌套的非基础能力。 */
    fun moreEntries(): List<DrawerEntry> =
        allEntries.values.filter { entry ->
            !entry.isBasic && entry.id !in hidden && entry.id !in nestedUnder
        }

    /** 某入口的直接子入口（未隐藏的才列出；父入口被隐藏时返回空）。 */
    fun childrenOf(parentId: String): List<DrawerEntry> {
        if (parentId in hidden) return emptyList()
        return nestedUnder.entries
            .filter { it.value == parentId && it.key !in hidden }
            .map { allEntries.getValue(it.key) }
    }

    /** 隐藏入口；隐藏父入口时其子入口随之不可见。返回是否真的改了状态。 */
    fun hide(id: String): Boolean {
        if (id !in allEntries || id in hidden) return false
        hidden.add(id)
        return true
    }

    fun show(id: String): Boolean {
        if (id !in allEntries || id !in hidden) return false
        hidden.remove(id)
        return true
    }

    /** 收藏/取消收藏；收藏只影响一级排序，不改变隐藏与嵌套。 */
    fun toggleFavorite(id: String): Boolean {
        if (id !in allEntries) return false
        if (id in favorites) favorites.remove(id) else favorites.add(id)
        return true
    }

    /**
     * 移动一级入口到新位置（越界下标会被夹到合法范围）。
     * 被嵌套的入口不参与一级排序，移动返回 false。
     */
    fun move(id: String, newIndex: Int): Boolean {
        if (id !in allEntries || id in nestedUnder || id !in order) return false
        order.remove(id)
        val clamped = newIndex.coerceIn(0, order.size)
        order.add(clamped, id)
        return true
    }

    /**
     * 把 [childId] 嵌套到 [parentId] 之下。
     * 拒绝：任一 id 未知、自己嵌自己、成环（父入口是子入口的后代）。
     */
    fun nest(childId: String, parentId: String): Boolean {
        if (childId !in allEntries || parentId !in allEntries) return false
        if (childId == parentId) return false
        if (isDescendantOf(parentId, childId)) return false
        nestedUnder[childId] = parentId
        order.remove(childId)
        return true
    }

    /** 取消嵌套，子入口回到一级末尾。 */
    fun unnest(childId: String): Boolean {
        if (childId !in nestedUnder) return false
        nestedUnder.remove(childId)
        if (childId !in order) order.add(childId)
        return true
    }

    fun setMoreExpanded(expanded: Boolean) {
        moreExpanded = expanded
    }

    /** 当前定制快照（供 C2-批 2 持久化）。 */
    fun toPrefs(): DrawerPrefs = DrawerPrefs(
        order = order.toList(),
        hidden = hidden.toSet(),
        favorites = favorites.toSet(),
        nestedUnder = nestedUnder.toMap(),
        moreExpanded = moreExpanded,
    )

    /** 从持久化快照恢复；快照里的未知 id 会被丢弃，不抛错。 */
    fun applyPrefs(prefs: DrawerPrefs) {
        val known = allEntries.keys
        // 先恢复嵌套：补一级入口时要跳过已嵌套的入口，否则嵌套子项
        // 会被当成“快照没提到”又补回一级，往返后 order 不一致。
        nestedUnder = prefs.nestedUnder
            .filterKeys { it in known }
            .filterValues { it in known }
            .filter { it.key != it.value }
            .toMutableMap()
        order = prefs.order.filter { it in known }.toMutableList()
        // 快照没提到的已知入口补到一级末尾（默认顺序），防止丢入口。
        DEFAULT_ORDER.filter { it !in order && it !in nestedUnder }.forEach { order.add(it) }
        hidden = prefs.hidden.intersect(known).toMutableSet()
        favorites = prefs.favorites.intersect(known).toMutableSet()
        moreExpanded = prefs.moreExpanded
    }

    /** 恢复默认：10 项默认顺序、无隐藏、无收藏、无嵌套、“更多”折叠。 */
    fun reset() {
        order = DEFAULT_ORDER.toMutableList()
        hidden.clear()
        favorites.clear()
        nestedUnder.clear()
        moreExpanded = false
    }

    /** [id] 是否为 [ancestorId] 的后代（沿 nestedUnder 链向上查）。 */
    private fun isDescendantOf(id: String, ancestorId: String): Boolean {
        var current = nestedUnder[id]
        while (current != null) {
            if (current == ancestorId) return true
            current = nestedUnder[current]
        }
        return false
    }
}
