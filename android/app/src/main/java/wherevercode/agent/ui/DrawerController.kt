package wherevercode.agent.ui

/**
 * V5 抽屉模型（导航整改最终定稿，2026-11-15）。
 *
 * 左侧抽屉一级菜单严格只有四项：对话、项目、频道、控制。
 * 抽屉中部显示最近会话列表与搜索。
 * 抽屉底部固定：左侧账号头像（点击进入完整设置），右侧 ＋ 新对话。
 * 设置不再是一级菜单。工作台统一改名「项目」，控制台统一改名「控制」。
 *
 * 旧的一级入口 id（new_conversation / history_search / tasks / agents_team
 * 等）保留为路由常量，但不再出现在 DEFAULT_ORDER 中——它们从三个主入口
 * 的子页面内逐层访问。70 屏旗舰功能全部保留，通过业务导航逐层访问。
 *
 * 纯 JVM 逻辑，不含 Android View。与 iOS 共享导航命名、信息架构和行为契约。
 */
class DrawerController {

    /** 单个抽屉入口。 */
    data class DrawerEntry(
        val id: String,
        val label: String,
        /** 是否基础能力：false 的入口默认折叠到"更多"，不占一级位置。 */
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
        /** "更多"是否展开；默认折叠。 */
        val moreExpanded: Boolean = false,
    )

    companion object {
        // ══════════════════════════════════════════════════════════════
        // V5 导航整改最终定稿：一级菜单严格只有三项
        // ══════════════════════════════════════════════════════════════

        /** 对话：自然语言 Agent 工作界面（参考 Claude/Codex）。 */
        const val ID_CHAT = "chat"

        /** 项目：真实项目列表（原 ID_PROJECTS，保留兼容）。 */
        const val ID_PROJECTS = "projects"

        /** 频道：频道 / 私聊双分区，真实消息与文件协作入口。 */
        const val ID_CHANNELS = "channels"

        /** 控制：真实平台状态及 Agent/设备/模型/连接器/调度/监控/事件/权限审计。 */
        const val ID_CONTROL = "control"

        // ══════════════════════════════════════════════════════════════
        // 旧入口 id（保留为路由常量，从三个主入口的子页面内逐层访问）
        // ══════════════════════════════════════════════════════════════

        /** 新对话动作（抽屉底部 ＋ 按钮触发，不再是一级入口）。 */
        const val ID_NEW_CONVERSATION = "new_conversation"

        /** 会话历史（从对话入口内访问）。 */
        const val ID_HISTORY_SEARCH = "history_search"

        /** 任务（从项目入口内访问）。 */
        const val ID_TASKS = "tasks"

        /** Agent 与团队（从控制入口内访问）。 */
        const val ID_AGENTS_TEAM = "agents_team"

        /** 设备（从控制入口内访问）。 */
        const val ID_DEVICES = "devices"

        /** 工具与连接（从控制入口内访问）。 */
        const val ID_TOOLS_CONNECTIONS = "tools_connections"

        /** 成果（从项目入口内访问）。 */
        const val ID_RESULTS = "results"

        /** 帮助（从设置页内访问）。 */
        const val ID_HELP = "help"

        /** 设置（抽屉底部账号头像点击进入，不再是一级入口）。 */
        const val ID_SETTINGS = "settings"

        /** 控制台（已改名「控制」，ID_CONTROL；保留旧 id 兼容旧路由）。 */
        const val ID_CONSOLE = "console"

        /** MCP / 插件（从控制入口内访问）。 */
        const val ID_MCP_PLUGINS = "mcp_plugins"

        /** 团队频道详情（从「频道」首页进入，不再占一级菜单位置）。 */
        const val ID_TEAM_CHANNEL = "team_channel"

        /** 一级菜单固定顺序：对话 → 项目 → 频道 → 控制。 */
        val DEFAULT_ORDER: List<String> = listOf(
            ID_CHAT,
            ID_PROJECTS,
            ID_CHANNELS,
            ID_CONTROL,
        )

        private val DEFAULT_LABELS: Map<String, String> = mapOf(
            ID_CHAT to "对话",
            ID_PROJECTS to "项目",
            ID_CHANNELS to "频道",
            ID_CONTROL to "控制",
            // 旧入口标签（保留兼容，不在一级显示）
            ID_NEW_CONVERSATION to "新对话",
            ID_HISTORY_SEARCH to "会话",
            ID_TASKS to "任务",
            ID_AGENTS_TEAM to "Agent 与团队",
            ID_DEVICES to "设备",
            ID_TOOLS_CONNECTIONS to "工具与连接",
            ID_RESULTS to "成果",
            ID_HELP to "帮助",
            ID_SETTINGS to "设置",
            ID_CONSOLE to "控制",
            ID_MCP_PLUGINS to "MCP / 插件",
            ID_TEAM_CHANNEL to "团队频道",
        )

        /** Known route ids retained when decoding older preference rows. */
        val KNOWN_ROUTE_IDS: Set<String> get() = DEFAULT_LABELS.keys

        /** 默认 4 项入口（对话、项目、频道、控制）。 */
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

    /** 恢复默认：4 项固定顺序（对话/项目/频道/控制）。 */
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
