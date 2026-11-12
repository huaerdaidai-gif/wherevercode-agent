package wherevercode.agent.ui

import wherevercode.agent.brain.ApprovalRecord
import wherevercode.agent.brain.EngineEvent
import wherevercode.agent.brain.PendingApproval
import wherevercode.agent.core.agent.AgentKind
import wherevercode.agent.core.agent.AgentRecord
import wherevercode.agent.core.task.TaskEvent
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.task.TaskStates
import wherevercode.agent.core.task.TaskStep
import wherevercode.agent.core.team.MemberConnectionState
import wherevercode.agent.core.transport.RemoteDevice

/**
 * V5 控制台快照（2026-11-11，控制台第一版）。
 *
 * 设计依据（只按新项目自己的文档，不按老项目）：
 * - PROJECT.md §7 工作台：对话、项目文件、代码、Diff、终端、任务时间线、
 *   审批、设备状态、Agent 团队、连接器中心、成果中心；模块可折叠、隐藏、
 *   排序、快捷入口与多级嵌套。Matrix 模式必须展示真实事件，不得播放
 *   伪装实时执行的装饰日志。
 * - PROJECT.md §3.2 自由度 5 档：等级只改变呈现与引导，不删除功能、
 *   数据，不自动授予执行权限，也不等于订阅档位。用户可单独开启/隐藏
 *   任意模块。
 * - PROJECT.md §2.1 基础版闭环：任务执行（真实状态更新、真实结果/失败
 *   说明）、基础任务控制（真实状态；停止/取消可传播到执行端）、基础
 *   设备页（真实状态）。
 * - PROJECT.md §8 安全：绝不虚报成功；区分已计划、已发送、已接收、
 *   执行中、已验证、失败、未知。
 * - V5-UI.md §三：唯一全局功能入口是可嵌套抽屉；控制台是按需展开的
 *   专业工作台，不是首页常驻仪表盘（V5-UI §七红线：首页没有大面积
 *   卡片控制台）。
 * - V5-UI.md §六模块化契约：控制台只读现有 store（TaskStore /
 *   TaskStepStore / eventLog / ApprovalBroker / RemoteDeviceStore /
 *   AgentStore / PairingTrustStore），不建第二套任务 store，不重写引擎。
 *
 * 控制台存在的意义（maintainer 2026-11-11 05:04，验收标准）：全局可见性——
 * 一个地方看全所有设备、所有 Agent、所有任务、进度、日志、状态；maintainer
 * 在控制台里一眼看到全局，不用再问 Muse 进度、不用再问屏幕上有什么。
 * 所以快照是全局的：全部任务、全部设备、全部 Agent、全局事件流、待
 * 审批、产物汇总，一次读全；不是单个任务的详情（详情已有任务详情页）。
 *
 * App 自己也是一个 Agent（maintainer 2026-11-11 04:56 纠正）：App（母体/
 * 总管）能聊天、能执行、能协作，和其他 Agent 一样，区别只是自研核心
 * 引擎。App 自己的执行在 TaskStore 里有真实归属：聊天轮次的任务由
 * [TurnTaskRecorder] 以 `LOCAL_AGENT_ID = "local-brain"` 认领
 * （[wherevercode.agent.core.task.TaskScheduler.claimTask] 把
 * lease_owner 写成该 id）。所以归属判定只读 leaseOwner /
 * assignedAgentId / memberId，不另造身份表；App 自身行由快照从真实
 * 任务数据派生，不在 AgentStore 里伪造一条记录。
 *
 * 诚实边界：
 * - 用量与成本不做：大脑不返回用量（聊天已显示“用量未知（大脑未返回
 *   用量）”），不编数字；EntryRegistry 的 feature_usage_cost 仍是 TODO。
 * - 行级 Diff 不做：任务产物只有写文件步骤的路径，没有 git patch
 *   输入（LineDiff 只接 WorkspaceEditSession 的改前改后文本）；产物
 *   模块只列真实路径、点开进项目页查看，不编 +N/−M。
 * - 终端、工作流、MCP 管理不在控制台里：App 没有真实终端与工作流
 *   引擎，MCP 是扩展注册表里的一类；控制台不为此造假入口，只在备注
 *   里写明去向。
 * - 设备“在线/离线”不猜：只显示已配对/未配对（配对记录真实存在）与
 *   地址；没有心跳数据就不写“在线”。
 */
object ConsoleSnapshot {

    /** 自由度档位：1 小白 / 2 普通 / 3 标准 / 4 进阶 / 5 高阶（PROJECT.md §3.2）。 */
    const val MIN_LEVEL: Int = 1
    const val MAX_LEVEL: Int = 5
    const val DEFAULT_LEVEL: Int = 3

    fun levelName(level: Int): String = when (level) {
        1 -> "小白"
        2 -> "普通"
        3 -> "标准"
        4 -> "进阶"
        5 -> "高阶"
        else -> "标准"
    }

    /** 任务的执行者归属：App 自身 / 某个成员 Agent / 未认领。 */
    sealed interface TaskExecutor {
        /** App 自身（本地大脑，leaseOwner == "local-brain"）。 */
        data object Self : TaskExecutor

        /** 成员 Agent（assignedAgentId / memberId / leaseOwner 指向的 id）。 */
        data class Member(val agentId: String) : TaskExecutor

        /** 未认领：没有执行者归属（queued 且无 lease）。 */
        data object Unclaimed : TaskExecutor
    }

    /**
     * 归属判定（只读 TaskRecord 的三个归属字段）：
     * - leaseOwner == "local-brain" ⇒ App 自身（聊天轮次任务，见
     *   TurnTaskRecorder.LOCAL_AGENT_ID）。
     * - assignedAgentId / memberId / leaseOwner 任一非空 ⇒ 该成员 Agent。
     * - 否则 ⇒ 未认领。
     */
    fun executorOf(task: TaskRecord): TaskExecutor {
        if (task.leaseOwner == TurnTaskRecorder.LOCAL_AGENT_ID) return TaskExecutor.Self
        val memberId = task.assignedAgentId ?: task.memberId ?: task.leaseOwner
        return if (memberId != null) TaskExecutor.Member(memberId) else TaskExecutor.Unclaimed
    }

    /** 执行者显示名：App 自身固定“本机 App”；成员用 Agent 记录名，未知 id 用 id 原样。 */
    fun executorLabel(executor: TaskExecutor, agentsById: Map<String, AgentRecord>): String =
        when (executor) {
            TaskExecutor.Self -> "本机 App"
            is TaskExecutor.Member -> agentsById[executor.agentId]?.displayName?.ifBlank { null }
                ?: executor.agentId
            TaskExecutor.Unclaimed -> "未认领"
        }

    /** 成员连接状态文案：与 TeamMember.displayConnection() 同口径（未连接一律诚实显示）。 */
    fun memberConnectionLabel(state: MemberConnectionState): String = when (state) {
        MemberConnectionState.NOT_CONNECTED -> "未连接（等待安排）"
        MemberConnectionState.CONNECTED -> "已连接"
        MemberConnectionState.UNAVAILABLE -> "不可用（连接已断开）"
    }

    /** Agent 种类文案。 */
    fun agentKindLabel(kind: AgentKind): String = when (kind) {
        AgentKind.CLOUD -> "云端"
        AgentKind.DEVICE -> "设备"
        AgentKind.LOCAL_MODEL -> "本机模型"
    }

    /** 控制台模块（PROJECT.md §7 工作台）。minLevel 是该模块首次出现的自由度档。 */
    enum class ConsoleModule(val title: String, val minLevel: Int) {
        /** 全局总览：计数 + 进行中任务 + 待审批 + 设备/Agent 状态 + 最近事件。1 档就有。 */
        OVERVIEW("总览", 1),

        /** 待审批：当前待决 + 审批历史。1 档就有（必要审批）。 */
        APPROVALS("待审批", 1),

        /** 产物：写文件步骤的真实路径汇总（复用 ResultsAggregation）。1 档就有（成果）。 */
        ARTIFACTS("产物", 1),

        /** 任务时间线：全部任务按状态分组，点按进任务详情。2 档。 */
        TASKS("任务", 2),

        /** 设备与 Agent：App 自身 + 全部成员 Agent + 全部远程设备。3 档。 */
        DEVICES_AGENTS("设备与 Agent", 3),

        /** 执行日志：全局事件流（真实 EngineEvent）；5 档时称 Matrix 实时事件流。4 档。 */
        LOGS("执行日志", 4),
    }

    /** 按自由度与用户隐藏集算出可见模块（顺序固定为 enum 声明顺序）。 */
    fun visibleModules(level: Int, hidden: Set<ConsoleModule>): List<ConsoleModule> =
        ConsoleModule.values().filter { it.minLevel <= level && it !in hidden }

    /** 抽屉深链的目标模块（nav_pending_approval → 待审批；feature_run_logs → 执行日志）。 */
    enum class ConsoleSection(val module: ConsoleModule) {
        OVERVIEW(ConsoleModule.OVERVIEW),
        TASKS(ConsoleModule.TASKS),
        LOGS(ConsoleModule.LOGS),
        ARTIFACTS(ConsoleModule.ARTIFACTS),
        APPROVALS(ConsoleModule.APPROVALS),
        DEVICES_AGENTS(ConsoleModule.DEVICES_AGENTS),
    }

    /** 一行 Agent（成员 Agent 或 App 自身），只由真实数据派生。 */
    data class ConsoleAgentRow(
        val id: String,
        val displayName: String,
        /** true = App 自身（本机 App，本地大脑，自研核心引擎）；false = 成员 Agent。 */
        val isSelf: Boolean,
        /** 状态行：成员用连接/登录/测试摘要；App 自身固定“在线（本 App 正在运行）”。 */
        val statusLabel: String,
        /** 补充说明：成员是 kind/设备/文件夹；App 自身是“自研核心引擎”。 */
        val detail: String,
        /** 该执行者的任务数（按 executorOf 归属统计）。 */
        val taskCount: Int,
        /** 该执行者最近任务的 updatedAt（epoch 秒），无任务时为 null。 */
        val lastActivityEpochSeconds: Long?,
    )

    /** 一行设备（只读 RemoteDeviceStore + 配对记录）。 */
    data class ConsoleDeviceRow(
        val deviceId: String,
        val displayName: String,
        val host: String,
        val port: Int,
        /** 有配对记录 = 已配对；无 = 未配对（不猜在线/离线）。 */
        val paired: Boolean,
        /** 该设备上的 Agent 数（按 AgentRecord.deviceId 归属统计）。 */
        val agentCount: Int,
    )

    /** 全局快照：一次读全，供控制台渲染。全部字段来自现有 store，不编数据。 */
    data class Snapshot(
        val tasks: List<TaskRecord>,
        val stepsByTask: Map<String, List<TaskStep>>,
        /** 全局事件流：最新在前，最多 40 条（与任务页 refreshEvents 同口径）。 */
        val events: List<EngineEvent>,
        val pendingApproval: PendingApproval?,
        /** 审批历史：最新在前，最多 20 条。 */
        val approvalHistory: List<ApprovalRecord>,
        val devices: List<ConsoleDeviceRow>,
        /** Agent 行：App 自身在首位，其后是成员 Agent（按显示名排序）。 */
        val agents: List<ConsoleAgentRow>,
        val results: ResultsSummary,
        val generatedAtEpochSeconds: Long,
        /**
         * P0：任务事件审计轨迹（全局最新在前，最多 40 条，与 events
         * 同口径）。events 是大脑轮次的动态 feed（内存）；taskEvents
         * 是任务状态转移的持久审计（Room，重启仍在）。LOGS 模块两
         * 段都展示：上面是动态、下面是审计。
         */
        val taskEvents: List<TaskEvent> = emptyList(),
    ) {
        /** 进行中（assigned/working/blocked/review）：在手的工作。 */
        val activeTasks: List<TaskRecord>
            get() = tasks.filter { it.state in ACTIVE_STATES }

        /** 等待执行（可认领态：queued / requeued / rework）。 */
        val queuedTasks: List<TaskRecord>
            get() = tasks.filter { TaskStates.isClaimable(it.state) }

        val completedCount: Int get() = tasks.count { it.state == "completed" }
        val failedCount: Int get() = tasks.count { it.state == "failed" }
        val cancelledCount: Int get() = tasks.count { it.state == "cancelled" }

        /** 待审核（review）：看板 In Review 列。 */
        val inReviewCount: Int get() = tasks.count { it.state == "review" }

        /** 已阻塞（blocked）：看板 Blocked 列。 */
        val blockedCount: Int get() = tasks.count { it.state == "blocked" }

        /** App 自身的任务（leaseOwner == "local-brain"）。 */
        val selfTasks: List<TaskRecord>
            get() = tasks.filter { executorOf(it) is TaskExecutor.Self }

        companion object {
            val ACTIVE_STATES: Set<String> = setOf("assigned", "working", "blocked", "review")
        }
    }

    /**
     * 由原始 store 读数拼快照（MainActivity 在后台线程调；纯拼装，不读
     * 任何 store）。App 自身行排首位：它不是 AgentStore 里的一条记录，
     * 是从真实任务数据派生的（任务数与最近活动都按 leaseOwner 统计）。
     */
    fun build(
        tasks: List<TaskRecord>,
        stepsByTask: Map<String, List<TaskStep>>,
        events: List<EngineEvent>,
        pendingApproval: PendingApproval?,
        approvalHistory: List<ApprovalRecord>,
        devices: List<RemoteDevice>,
        pairedDeviceIds: Set<String>,
        agents: List<AgentRecord>,
        generatedAtEpochSeconds: Long,
        taskEvents: List<TaskEvent> = emptyList(),
    ): Snapshot {
        val sortedTasks = tasks.sortedByDescending { it.updatedAt }
        val selfTasks = sortedTasks.filter { executorOf(it) is TaskExecutor.Self }
        val selfRow = ConsoleAgentRow(
            id = TurnTaskRecorder.LOCAL_AGENT_ID,
            displayName = "本机 App",
            isSelf = true,
            statusLabel = "在线（本 App 正在运行）",
            detail = "本地大脑 · 自研核心引擎",
            taskCount = selfTasks.size,
            lastActivityEpochSeconds = selfTasks.maxOfOrNull { it.updatedAt },
        )
        val memberRows = agents.map { agent ->
            val mine = sortedTasks.filter { task ->
                when (val executor = executorOf(task)) {
                    is TaskExecutor.Member -> executor.agentId == agent.agentId
                    else -> false
                }
            }
            val status = buildString {
                append(memberConnectionLabel(agent.memberConnectionState))
                agent.lastTestSummary?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
            }
            val detail = buildString {
                append(agentKindLabel(agent.kind))
                agent.deviceId?.let { append(" · 设备 $it") }
                agent.deviceFolder?.let { append(" · $it") }
            }
            ConsoleAgentRow(
                id = agent.agentId,
                displayName = agent.displayName.ifBlank { agent.agentId },
                isSelf = false,
                statusLabel = status,
                detail = detail.ifBlank { agentKindLabel(agent.kind) },
                taskCount = mine.size,
                lastActivityEpochSeconds = mine.maxOfOrNull { it.updatedAt },
            )
        }.sortedBy { it.displayName }
        val deviceRows = devices.map { device ->
            ConsoleDeviceRow(
                deviceId = device.deviceId,
                displayName = device.displayName.ifBlank { device.deviceId },
                host = device.host,
                port = device.port,
                paired = device.deviceId in pairedDeviceIds,
                agentCount = agents.count { it.deviceId == device.deviceId },
            )
        }.sortedBy { it.displayName }
        return Snapshot(
            tasks = sortedTasks,
            stepsByTask = stepsByTask,
            events = events,
            pendingApproval = pendingApproval,
            approvalHistory = approvalHistory.takeLast(20).reversed(),
            devices = deviceRows,
            agents = listOf(selfRow) + memberRows,
            results = ResultsAggregation.aggregate(sortedTasks, stepsByTask),
            generatedAtEpochSeconds = generatedAtEpochSeconds,
            taskEvents = taskEvents,
        )
    }
}
