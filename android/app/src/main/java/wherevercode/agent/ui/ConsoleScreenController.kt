package wherevercode.agent.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import wherevercode.agent.R
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.ui.ConsoleSnapshot.ConsoleModule
import wherevercode.agent.ui.ConsoleSnapshot.ConsoleSection
import wherevercode.agent.ui.ConsoleSnapshot.Snapshot

/**
 * V5 控制台（2026-11-11，控制台第一版）。
 *
 * 按需展开的专业工作台（V5-UI.md §三）：从抽屉“控制台”入口或聊天页
 * 控制行的控制台按钮进入，不是首页常驻仪表盘（V5-UI §七红线）。
 * 结构与 [ResultsScreenController] 同款：控制器 + `root` +
 * `render()`，`MainActivity` 只保留 `showEntry` 路由与数据组装
 * （`refreshConsole`）。固定头（标题 / 自由度选择 / 刷新）建一次；
 * `render()` 只重建其下的模块段。
 *
 * 控制台存在的意义（maintainer 2026-11-11 05:04，验收标准）：全局可见性——
 * 一个地方看全所有设备、所有 Agent、所有任务、进度、日志、状态；maintainer
 * 在控制台里一眼看到全局，不用再问 Muse 进度、不用再问屏幕上有什么。
 * 所以总览模块是“全局一眼看全”：计数、进行中任务、待审批、设备与
 * Agent 状态、最近事件都在总览里；下面各模块是钻进去看的完整列表。
 * 不是单个任务的详情页（详情已有任务详情页，点任务行进去）。
 *
 * 全部数据来自 [Snapshot]（MainActivity 在后台线程从现有 store 组装）；
 * 本控制器不读任何 store、不造数据。没有真实数据就明标“暂无……”，
 * 绝不造假、不写 mock。
 *
 * 自由度（PROJECT.md §3.2）：1 小白 / 2 普通 / 3 标准 / 4 进阶 / 5 高阶；
 * 等级只改变呈现与引导，不删除功能、数据。用户可单独隐藏/恢复任意
 * 模块（每节标题旁的“隐藏”与底部的“已隐藏的模块”）。5 档时执行日志
 * 模块称“Matrix 实时事件流”（仍是同一份真实事件流，只加自动刷新）。
 *
 * App 自己也是一个 Agent（maintainer 2026-11-11 04:56 纠正）：设备与 Agent
 * 模块首位是“本机 App”（本地大脑，自研核心引擎），任务数与最近活动
 * 按 leaseOwner == "local-brain" 的真实任务统计；其后才是成员 Agent。
 */
class ConsoleScreenController(
    private val context: Context,
    private val onOpenTask: (String) -> Unit,
    private val onOpenFile: (String) -> Unit,
    private val onOpenChat: () -> Unit,
    private val onOpenDevices: () -> Unit,
    private val onOpenAgents: () -> Unit,
    private val onRefresh: () -> Unit,
) {
    val root: ScrollView = ScrollView(context).apply {
        visibility = View.GONE
        isFillViewport = true
    }

    private val content: LinearLayout = UiKit.vertical(context).apply {
        setPadding(dp(16), dp(8), dp(16), dp(20))
    }

    /** 模块段容器：每次 render() 重建。固定头不在其中。 */
    private val modulesContainer: LinearLayout = UiKit.vertical(context)

    /** 各模块标题行的视图（供 scrollToSection 定位）。 */
    private val moduleHeaders: MutableMap<ConsoleModule, View> = mutableMapOf()

    private val prefs = context.getSharedPreferences("console_prefs", Context.MODE_PRIVATE)

    /** 自由度档位（SharedPreferences 持久化，默认 3 标准）。 */
    var freedomLevel: Int = prefs.getInt(KEY_LEVEL, ConsoleSnapshot.DEFAULT_LEVEL)
        .coerceIn(ConsoleSnapshot.MIN_LEVEL, ConsoleSnapshot.MAX_LEVEL)
        private set

    /** 用户单独隐藏的模块（SharedPreferences 持久化，逗号分隔）。 */
    var hiddenModules: Set<ConsoleModule> = readHidden()
        private set

    /** 内存内折叠的模块（不持久化；旋转重建后回到默认展开）。 */
    private val collapsedModules: MutableSet<ConsoleModule> = mutableSetOf()

    /** 2026-11-11（事件密度）：执行日志分组的展开状态（刷新后不丢）。 */
    private val expandedLogGroupKeys = mutableSetOf<String>()
    private var showAllLogGroups = false

    /** 任务模块的执行者筛选：null = 全部；"local-brain" = 本机 App；其余 = 成员 id。 */
    var agentFilter: String? = null
        private set

    private var snapshot: Snapshot? = null
    private var loading = false
    private var loadError = false

    private val levelChips: List<TextView> = (ConsoleSnapshot.MIN_LEVEL..ConsoleSnapshot.MAX_LEVEL)
        .map { level -> levelChip(level) }

    init {
        root.addView(content)
        content.addView(
            UiKit.text(context, "控制台", 20f, R.color.text_primary, bold = true).apply {
                setPadding(0, dp(4), 0, dp(2))
            },
        )
        content.addView(
            UiKit.text(
                context,
                "全局总览：所有设备、所有 Agent、所有任务、进度、日志、状态都在这里，" +
                    "一眼看全，不用再问进度。",
                11f,
                R.color.text_tertiary,
            ).apply { setPadding(0, 0, 0, dp(8)) },
        )
        val levelRow = UiKit.horizontal(context).apply { setPadding(0, dp(2), 0, dp(6)) }
        levelRow.addView(
            UiKit.text(context, "自由度", 12f, R.color.text_primary, bold = true).apply {
                setPadding(0, 0, dp(8), 0)
            },
        )
        levelChips.forEach { levelRow.addView(it) }
        content.addView(levelRow)
        val refreshRow = UiKit.horizontal(context).apply { setPadding(0, 0, 0, dp(10)) }
        refreshRow.addView(
            UiKit.text(context, "刷新", 12f, R.color.accent, bold = true).apply {
                setBackgroundResource(R.drawable.bg_chip_accent)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener { onRefresh() }
            },
        )
        refreshRow.addView(
            UiKit.text(context, "数据全部来自本机真实记录；没有数据就明说暂无，不编数字。", 10f, R.color.text_tertiary).apply {
                setPadding(dp(10), 0, 0, 0)
            },
        )
        content.addView(refreshRow)
        content.addView(modulesContainer)
        refreshLevelChips()
    }

    /** 大脑未建好时：只说暂不可读，不伪造空总览。 */
    fun renderLoading() {
        loading = true
        loadError = false
        snapshot = null
        modulesContainer.removeAllViews()
        modulesContainer.addView(note("大脑加载中，控制台暂不可读。"))
    }

    /** 读任务库失败时：明说失败，不渲染空总览。 */
    fun renderError() {
        loading = false
        loadError = true
        snapshot = null
        modulesContainer.removeAllViews()
        modulesContainer.addView(note("读取持久任务库失败；详见运行事件/日志。"))
    }

    fun render(snapshot: Snapshot) {
        loading = false
        loadError = false
        this.snapshot = snapshot
        renderModules()
    }

    /** 滚动到指定模块（抽屉深链：待审批 → 审批模块；运行日志 → 日志模块）。 */
    fun scrollToSection(section: ConsoleSection) {
        val target = moduleHeaders[section.module] ?: return
        root.post { root.scrollTo(0, target.top) }
    }

    fun setLevel(level: Int) {
        val clamped = level.coerceIn(ConsoleSnapshot.MIN_LEVEL, ConsoleSnapshot.MAX_LEVEL)
        if (clamped == freedomLevel) return
        freedomLevel = clamped
        prefs.edit().putInt(KEY_LEVEL, clamped).apply()
        refreshLevelChips()
        renderModules()
    }

    fun hideModule(module: ConsoleModule) {
        if (module in hiddenModules) return
        hiddenModules = hiddenModules + module
        persistHidden()
        renderModules()
    }

    fun showModule(module: ConsoleModule) {
        if (module !in hiddenModules) return
        hiddenModules = hiddenModules - module
        persistHidden()
        renderModules()
    }

    fun setAgentFilter(agentId: String?) {
        if (agentFilter == agentId) return
        agentFilter = agentId
        renderModules()
    }

    private fun renderModules() {
        val current = snapshot
        modulesContainer.removeAllViews()
        moduleHeaders.clear()
        if (current == null) {
            // 大脑未建好 / 读库失败时保持提示，不渲染空模块。
            if (loading) modulesContainer.addView(note("大脑加载中，控制台暂不可读。"))
            if (loadError) modulesContainer.addView(note("读取持久任务库失败；详见运行事件/日志。"))
            return
        }
        val visible = ConsoleSnapshot.visibleModules(freedomLevel, hiddenModules)
        visible.forEach { module ->
            val section = moduleSection(module, current)
            modulesContainer.addView(section)
        }
        val hidden = ConsoleModule.values().filter { it in hiddenModules }
        if (hidden.isNotEmpty()) {
            val row = UiKit.horizontal(context).apply { setPadding(0, dp(6), 0, 0) }
            row.addView(
                UiKit.text(context, "已隐藏的模块：", 11f, R.color.text_tertiary).apply {
                    setPadding(0, 0, dp(6), 0)
                },
            )
            hidden.forEach { module ->
                row.addView(
                    UiKit.text(context, module.title, 11f, R.color.accent).apply {
                        setBackgroundResource(R.drawable.bg_chip)
                        setPadding(dp(9), dp(5), dp(9), dp(5))
                        setOnClickListener { showModule(module) }
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { rightMargin = dp(6) }
                    },
                )
            }
            modulesContainer.addView(row)
        }
    }

    private fun moduleSection(module: ConsoleModule, snapshot: Snapshot): LinearLayout {
        val section = UiKit.vertical(context).apply {
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }
        val header = UiKit.horizontal(context)
        val title = if (module == ConsoleModule.LOGS && freedomLevel >= ConsoleSnapshot.MAX_LEVEL) {
            "执行日志 · Matrix 实时事件流"
        } else {
            module.title
        }
        header.addView(
            UiKit.text(context, title, 15f, R.color.text_primary, bold = true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(
            UiKit.text(context, if (module in collapsedModules) "展开" else "折叠", 11f, R.color.accent).apply {
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener {
                    if (module in collapsedModules) collapsedModules.remove(module)
                    else collapsedModules.add(module)
                    renderModules()
                }
            },
        )
        header.addView(
            UiKit.text(context, "隐藏", 11f, R.color.text_tertiary).apply {
                setPadding(dp(8), dp(4), 0, dp(4))
                setOnClickListener { hideModule(module) }
            },
        )
        section.addView(header)
        moduleHeaders[module] = header
        if (module !in collapsedModules) {
            when (module) {
                ConsoleModule.OVERVIEW -> renderOverview(section, snapshot)
                ConsoleModule.APPROVALS -> renderApprovals(section, snapshot)
                ConsoleModule.ARTIFACTS -> renderArtifacts(section, snapshot)
                ConsoleModule.TASKS -> renderTasks(section, snapshot)
                ConsoleModule.DEVICES_AGENTS -> renderDevicesAgents(section, snapshot)
                ConsoleModule.LOGS -> renderLogs(section, snapshot)
            }
        }
        return section
    }

    // ── 总览：全局一眼看全 ──────────────────────────────────────────

    private fun renderOverview(section: LinearLayout, snapshot: Snapshot) {
        section.addView(
            UiKit.text(
                context,
                "任务：进行中 ${snapshot.activeTasks.size} · 等待 ${snapshot.queuedTasks.size} · " +
                    "已完成 ${snapshot.completedCount} · 失败 ${snapshot.failedCount} · " +
                    "已停止 ${snapshot.cancelledCount}",
                12f,
                R.color.text_primary,
            ).apply { setPadding(0, dp(8), 0, 0) },
        )
        section.addView(
            UiKit.text(
                context,
                "设备 ${snapshot.devices.size} 台 · Agent ${snapshot.agents.size} 个（含本机 App） · " +
                    "待审批 ${if (snapshot.pendingApproval != null) 1 else 0} 个 · " +
                    "产物文件 ${snapshot.results.distinctArtifactCount} 个",
                12f,
                R.color.text_primary,
            ).apply { setPadding(0, dp(4), 0, 0) },
        )
        section.addView(sectionLabel("进行中任务"))
        if (snapshot.activeTasks.isEmpty()) {
            section.addView(noteText("暂无进行中任务。"))
        } else {
            snapshot.activeTasks.take(5).forEach { task ->
                section.addView(taskRow(task, snapshot))
            }
            if (snapshot.activeTasks.size > 5) {
                section.addView(
                    UiKit.text(context, "还有 ${snapshot.activeTasks.size - 5} 个进行中任务，见任务模块。", 11f, R.color.text_tertiary),
                )
            }
        }
        section.addView(sectionLabel("待审批"))
        val pending = snapshot.pendingApproval
        if (pending == null) {
            section.addView(noteText("暂无待审批。"))
        } else {
            section.addView(pendingApprovalRow(pending))
        }
        section.addView(sectionLabel("设备与 Agent 状态"))
        if (snapshot.devices.isEmpty() && snapshot.agents.size <= 1) {
            section.addView(noteText("暂无远程设备；本机 App 在运行。"))
        }
        snapshot.devices.forEach { device ->
            section.addView(
                UiKit.text(
                    context,
                    "▸ ${device.displayName} · ${device.host}:${device.port} · " +
                        if (device.paired) "已配对" else "未配对",
                    12f,
                    R.color.text_secondary,
                ).apply { setPadding(0, dp(2), 0, 0) },
            )
        }
        snapshot.agents.forEach { agent ->
            section.addView(
                UiKit.text(
                    context,
                    "▸ ${agent.displayName} · ${agent.statusLabel} · ${agent.taskCount} 个任务",
                    12f,
                    R.color.text_secondary,
                ).apply { setPadding(0, dp(2), 0, 0) },
            )
        }
        section.addView(sectionLabel("最近事件"))
        if (snapshot.events.isEmpty()) {
            section.addView(noteText("暂无运行事件。"))
        } else {
            snapshot.events.take(3).forEach { event ->
                section.addView(taskEventRow(context, event))
            }
        }
        section.addView(
            UiKit.text(
                context,
                "更新：${UiKit.shortTime(snapshot.generatedAtEpochSeconds)}",
                10f,
                R.color.text_tertiary,
            ).apply { setPadding(0, dp(8), 0, 0) },
        )
    }

    // ── 待审批 ──────────────────────────────────────────────────────

    private fun renderApprovals(section: LinearLayout, snapshot: Snapshot) {
        val pending = snapshot.pendingApproval
        if (pending == null) {
            section.addView(noteText("暂无待审批。审批由 App 在执行工具前发起，超时自动拒绝。"))
        } else {
            section.addView(pendingApprovalRow(pending))
            section.addView(
                UiKit.text(context, "去对话处理", 12f, R.color.accent, bold = true).apply {
                    setBackgroundResource(R.drawable.bg_chip_accent)
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    setOnClickListener { onOpenChat() }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(8) }
                },
            )
            section.addView(
                UiKit.text(
                    context,
                    "审批只在对话里的审批弹窗决定；控制台只展示，不另开一条审批路径，避免重复决定。",
                    10f,
                    R.color.text_tertiary,
                ).apply { setPadding(0, dp(6), 0, 0) },
            )
        }
        section.addView(sectionLabel("审批历史"))
        if (snapshot.approvalHistory.isEmpty()) {
            section.addView(noteText("暂无审批记录。"))
        } else {
            snapshot.approvalHistory.forEach { record ->
                val line = buildString {
                    append("${UiKit.shortTime(record.atEpochSeconds)} · ${record.tool}")
                    record.target?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                    append(" · ${approvalOutcomeLabel(record.outcome, record.cached)}")
                }
                section.addView(
                    UiKit.text(context, line, 11f, R.color.text_secondary).apply {
                        setBackgroundResource(R.drawable.bg_system_note)
                        setPadding(dp(10), dp(8), dp(10), dp(8))
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { bottomMargin = dp(5) }
                    },
                )
            }
        }
    }

    private fun pendingApprovalRow(pending: wherevercode.agent.brain.PendingApproval): LinearLayout {
        val row = UiKit.vertical(context).apply {
            setBackgroundResource(R.drawable.bg_system_note)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        }
        val remainingSeconds = ((pending.deadlineEpochMillis - System.currentTimeMillis()) / 1000)
            .coerceAtLeast(0)
        val line = buildString {
            append("等待审批：${pending.tool}")
            pending.target?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
            append(" · 剩余 ${remainingSeconds} 秒，超时自动拒绝")
        }
        row.addView(UiKit.text(context, line, 12f, R.color.text_primary, bold = true))
        return row
    }

    // ── 产物 ────────────────────────────────────────────────────────

    private fun renderArtifacts(section: LinearLayout, snapshot: Snapshot) {
        val results = snapshot.results
        section.addView(
            UiKit.text(
                context,
                "任务：${results.taskCount} 个 · 有产物任务：${results.tasksWithArtifacts} 个 · " +
                    "产物文件（去重）：${results.distinctArtifactCount} 个",
                12f,
                R.color.text_primary,
            ).apply { setPadding(0, dp(8), 0, 0) },
        )
        val empty = ResultsAggregation.emptyMessage(results.totalTaskCount, results.tasksWithArtifacts)
        if (empty != null) {
            section.addView(noteText(empty))
            return
        }
        results.tasks.forEach { taskResults ->
            if (taskResults.artifactPaths.isEmpty()) return@forEach
            section.addView(
                UiKit.text(
                    context,
                    taskResults.task.title.ifBlank { taskResults.task.taskId },
                    12f,
                    R.color.text_primary,
                    bold = true,
                ).apply { setPadding(0, dp(8), 0, dp(2)) },
            )
            taskResults.artifactPaths.forEach { path ->
                section.addView(artifactRow(path))
            }
        }
        section.addView(
            noteText(
                "只列写文件步骤的路径；行级 Diff（+N/−M）待接改动记录后显示。" +
                    "未校验文件是否仍存在，点开后由项目页如实报错；" +
                    "run_command 产物与任务结果文本不在此列。",
            ),
        )
    }

    private fun artifactRow(path: String): TextView =
        UiKit.text(context, "▸ $path", 12f, R.color.accent, mono = true).apply {
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(10), dp(9), dp(10), dp(9))
            setOnClickListener { onOpenFile(path) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(5) }
        }

    // ── 任务时间线 ──────────────────────────────────────────────────

    private fun renderTasks(section: LinearLayout, snapshot: Snapshot) {
        val filterRow = UiKit.horizontal(context).apply { setPadding(0, dp(8), 0, dp(8)) }
        filterRow.addView(filterChip("全部", agentFilter == null) { setAgentFilter(null) })
        snapshot.agents.forEach { agent ->
            filterRow.addView(
                filterChip(agent.displayName, agentFilter == agent.id) { setAgentFilter(agent.id) },
            )
        }
        section.addView(filterRow)
        val tasks = when (val filter = agentFilter) {
            null -> snapshot.tasks
            else -> snapshot.tasks.filter { task ->
                when (val executor = ConsoleSnapshot.executorOf(task)) {
                    is ConsoleSnapshot.TaskExecutor.Self -> filter == TurnTaskRecorder.LOCAL_AGENT_ID
                    is ConsoleSnapshot.TaskExecutor.Member -> executor.agentId == filter
                    ConsoleSnapshot.TaskExecutor.Unclaimed -> false
                }
            }
        }
        if (tasks.isEmpty()) {
            section.addView(
                noteText(
                    if (agentFilter == null) "暂时没有任务。对话任务与分派任务会在有记录时显示在这里。"
                    else "该执行者暂无任务。",
                ),
            )
            return
        }
        val active = tasks.filter { it.state in ConsoleSnapshot.Snapshot.ACTIVE_STATES }
        val recent = tasks.filter { it.state !in ConsoleSnapshot.Snapshot.ACTIVE_STATES }
        if (active.isNotEmpty()) {
            section.addView(sectionLabel("进行中（${active.size}）"))
            active.forEach { task -> section.addView(taskRow(task, snapshot)) }
        }
        if (recent.isNotEmpty()) {
            section.addView(sectionLabel("最近（${recent.size}）"))
            recent.take(8).forEach { task -> section.addView(taskRow(task, snapshot)) }
            if (recent.size > 8) {
                section.addView(
                    UiKit.text(context, "还有 ${recent.size - 8} 个历史任务，见任务页。", 11f, R.color.text_tertiary),
                )
            }
        }
    }

    private fun taskRow(task: TaskRecord, snapshot: Snapshot): LinearLayout {
        val card = UiKit.vertical(context).apply {
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(6) }
            setOnClickListener { onOpenTask(task.taskId) }
        }
        val top = UiKit.horizontal(context)
        top.addView(
            UiKit.text(
                context,
                task.title.ifBlank { "未命名任务" },
                13f,
                R.color.text_primary,
                bold = true,
            ),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        top.addView(stateChip(task.state))
        card.addView(top)
        val agentsById = snapshot.agents.associateBy { it.id }
        val executorName = when (val executor = ConsoleSnapshot.executorOf(task)) {
            ConsoleSnapshot.TaskExecutor.Self -> "本机 App"
            is ConsoleSnapshot.TaskExecutor.Member -> agentsById[executor.agentId]?.displayName
                ?: executor.agentId
            ConsoleSnapshot.TaskExecutor.Unclaimed -> "未认领"
        }
        card.addView(
            UiKit.text(
                context,
                "$executorName · ${taskStateLabel(task.state)} · 更新：${UiKit.shortTime(task.updatedAt)}",
                10f,
                R.color.text_tertiary,
            ).apply { setPadding(0, dp(5), 0, 0) },
        )
        task.error?.takeIf { it.isNotBlank() }?.let { error ->
            card.addView(
                UiKit.text(context, error, 11f, R.color.text_secondary).apply {
                    setPadding(0, dp(4), 0, 0)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
            )
        }
        return card
    }

    private fun stateChip(state: String): TextView =
        UiKit.text(context, taskStateLabel(state), 10f, R.color.text_secondary).apply {
            setBackgroundResource(R.drawable.bg_chip)
            setPadding(dp(9), dp(4), dp(9), dp(4))
        }

    // ── 设备与 Agent ────────────────────────────────────────────────

    private fun renderDevicesAgents(section: LinearLayout, snapshot: Snapshot) {
        section.addView(sectionLabel("Agent"))
        snapshot.agents.forEach { agent ->
            val row = UiKit.vertical(context).apply {
                setBackgroundResource(
                    if (agent.isSelf) R.drawable.bg_chip_accent else R.drawable.bg_card,
                )
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(6) }
                setOnClickListener {
                    // 点 Agent 行 = 只看该执行者的任务；任务模块在 2 档才出现，
                    // 档位不够时自动提到 2 档，否则筛选后用户看不到任务模块。
                    if (freedomLevel < 2) setLevel(2)
                    if (agent.isSelf) setAgentFilter(TurnTaskRecorder.LOCAL_AGENT_ID)
                    else setAgentFilter(agent.id)
                    scrollToSection(ConsoleSection.TASKS)
                }
            }
            row.addView(
                UiKit.text(
                    context,
                    (if (agent.isSelf) "本机 App" else agent.displayName) +
                        if (agent.isSelf) " · 本地大脑" else "",
                    13f,
                    R.color.text_primary,
                    bold = true,
                ),
            )
            row.addView(
                UiKit.text(context, agent.statusLabel, 11f, R.color.text_secondary).apply {
                    setPadding(0, dp(4), 0, 0)
                },
            )
            row.addView(
                UiKit.text(context, agent.detail, 11f, R.color.text_tertiary).apply {
                    setPadding(0, dp(2), 0, 0)
                },
            )
            row.addView(
                UiKit.text(
                    context,
                    "任务 ${agent.taskCount} 个" +
                        (agent.lastActivityEpochSeconds?.let { " · 最近 ${UiKit.shortTime(it)}" } ?: ""),
                    11f,
                    R.color.text_secondary,
                ).apply { setPadding(0, dp(4), 0, 0) },
            )
            if (agent.isSelf) {
                row.addView(
                    UiKit.text(
                        context,
                        "点按只看本机 App 自己的任务（任务模块自动筛选）。",
                        10f,
                        R.color.text_tertiary,
                    ).apply { setPadding(0, dp(4), 0, 0) },
                )
            }
            section.addView(row)
        }
        section.addView(
            UiKit.text(context, "去 Agent 页", 12f, R.color.accent, bold = true).apply {
                setBackgroundResource(R.drawable.bg_chip_accent)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener { onOpenAgents() }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(4) }
            },
        )
        section.addView(sectionLabel("设备"))
        if (snapshot.devices.isEmpty()) {
            section.addView(noteText("暂无远程设备。加设备后，设备与它上面的 Agent 会出现在这里。"))
        } else {
            snapshot.devices.forEach { device ->
                val row = UiKit.vertical(context).apply {
                    setBackgroundResource(R.drawable.bg_card)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { bottomMargin = dp(6) }
                    setOnClickListener { onOpenDevices() }
                }
                row.addView(
                    UiKit.text(context, device.displayName, 13f, R.color.text_primary, bold = true),
                )
                row.addView(
                    UiKit.text(
                        context,
                        "${device.host}:${device.port} · " +
                            if (device.paired) "已配对" else "未配对" +
                            " · ${device.agentCount} 个 Agent",
                        11f,
                        R.color.text_secondary,
                    ).apply { setPadding(0, dp(4), 0, 0) },
                )
                section.addView(row)
            }
        }
        section.addView(
            noteText("设备“在线/离线”没有心跳数据就不猜；只显示已配对/未配对与地址。"),
        )
    }

    // ── 执行日志 ────────────────────────────────────────────────────

    private fun renderLogs(section: LinearLayout, snapshot: Snapshot) {
        section.addView(
            UiKit.text(
                context,
                if (freedomLevel >= ConsoleSnapshot.MAX_LEVEL) {
                    "Matrix 实时事件流：只展示真实运行事件，不播放装饰日志；控制台打开时每 5 秒刷新。"
                } else {
                    "全局运行事件流：只展示真实运行事件，不播放装饰日志；控制台打开时每 5 秒刷新。"
                },
                10f,
                R.color.text_tertiary,
            ).apply { setPadding(0, dp(8), 0, dp(4)) },
        )
        if (snapshot.events.isEmpty()) {
            section.addView(noteText("暂无运行事件。"))
        } else {
            // 2026-11-11（事件密度）：执行日志与任务屏同款分组折叠
            // （会话 + 10 分钟簇，默认只展最新 3 组；无实质内容事件
            // 不单独占行）。总览里的“最近事件”只取 3 条，本就有限，
            // 不分组。展开状态记在字段里，刷新后不丢。
            val logContainer = UiKit.vertical(context)
            section.addView(logContainer)
            EventGroupViews.render(
                context = context,
                container = logContainer,
                groups = EventLogGrouping.groupEvents(snapshot.events),
                expandedKeys = expandedLogGroupKeys,
                showAll = showAllLogGroups,
                onStateChanged = { renderModules() },
                onToggleShowAll = {
                    if (showAllLogGroups) {
                        showAllLogGroups = false
                        expandedLogGroupKeys.clear()
                    } else {
                        showAllLogGroups = true
                    }
                    renderModules()
                },
            )
        }
        // P0：任务状态轨迹（审计）——持久的任务转移记录（Room，重启
        // 仍在），不是动态 feed；控制台既是动态也是审计轨迹。
        section.addView(sectionLabel("任务状态轨迹（审计）"))
        section.addView(
            UiKit.text(
                context,
                "每次任务状态转移、每次租约过期回队/入死信都落盘一条；只增不改，重启仍在。",
                10f,
                R.color.text_tertiary,
            ).apply { setPadding(0, dp(4), 0, dp(4)) },
        )
        if (snapshot.taskEvents.isEmpty()) {
            section.addView(noteText("暂无任务状态事件：旧任务在事件日志落地前创建，或尚无转移。"))
        } else {
            snapshot.taskEvents.forEach { event ->
                section.addView(taskStateEventRow(context, event))
            }
        }
    }

    // ── 小组件 ──────────────────────────────────────────────────────

    private fun levelChip(level: Int): TextView =
        UiKit.text(
            context,
            "$level ${ConsoleSnapshot.levelName(level)}",
            12f,
            R.color.text_primary,
            bold = true,
        ).apply {
            setBackgroundResource(R.drawable.bg_chip)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { setLevel(level) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { rightMargin = dp(6) }
        }

    private fun filterChip(label: String, selected: Boolean, onClick: () -> Unit): TextView =
        UiKit.text(context, label, 11f, R.color.text_primary, bold = selected).apply {
            setBackgroundResource(
                if (selected) R.drawable.bg_chip_accent else R.drawable.bg_chip,
            )
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { rightMargin = dp(6) }
        }

    private fun sectionLabel(text: String): TextView =
        UiKit.text(context, text, 12f, R.color.text_primary, bold = true).apply {
            setPadding(0, dp(10), 0, dp(4))
        }

    private fun noteText(text: String): TextView =
        UiKit.text(context, text, 11f, R.color.text_tertiary).apply {
            setBackgroundResource(R.drawable.bg_system_note)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8); bottomMargin = dp(4) }
        }

    private fun note(text: String): TextView = noteText(text)

    private fun refreshLevelChips() {
        levelChips.forEachIndexed { index, chip ->
            val level = index + ConsoleSnapshot.MIN_LEVEL
            chip.setBackgroundResource(
                if (level == freedomLevel) R.drawable.bg_chip_accent else R.drawable.bg_chip,
            )
        }
    }

    private fun readHidden(): Set<ConsoleModule> {
        val raw = prefs.getString(KEY_HIDDEN, "") ?: ""
        if (raw.isBlank()) return emptySet()
        return raw.split(",").mapNotNull { name ->
            runCatching { ConsoleModule.valueOf(name) }.getOrNull()
        }.toSet()
    }

    private fun persistHidden() {
        prefs.edit().putString(KEY_HIDDEN, hiddenModules.joinToString(",") { it.name }).apply()
    }

    private fun dp(value: Int): Int = UiKit.dp(context, value)

    companion object {
        private const val KEY_LEVEL = "freedom_level"
        private const val KEY_HIDDEN = "hidden_modules"
    }
}
