package wherevercode.agent.core.team

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import wherevercode.agent.core.mcp.RemoteTarget
import wherevercode.agent.core.project.DispatchFolderSource
import wherevercode.agent.core.project.ProjectStore
import wherevercode.agent.core.project.folderFallbackReason
import wherevercode.agent.core.project.folderResolvedReason
import wherevercode.agent.core.task.EvalLogRecorder
import wherevercode.agent.core.task.EvalOutcome
import wherevercode.agent.core.task.RoomTaskStore
import wherevercode.agent.core.task.TaskEventKind
import wherevercode.agent.core.task.TaskRecord
import wherevercode.agent.core.task.TaskOrigins
import wherevercode.agent.core.task.TaskScheduler
import wherevercode.agent.core.task.TaskStates
import wherevercode.agent.core.task.TaskStepStore
import wherevercode.agent.core.task.TaskStore
import wherevercode.agent.ui.TurnTaskRecorder


/**
 * 阶段 B（V5 紧急开发）：远程任务泵——排队里指派给远程 Agent 的
 * 任务，由泵认领并交给 [RemoteTaskDispatcher] 发到远端执行。
 *
 * 与 [wherevercode.agent.core.task.LocalTaskRunner] 对称，但门禁不同：
 * - 本地执行器一次只做一个任务（本机大脑只有一个 TurnController，
 *   全局单任务）；远端泵是**一成员一次一任务**（[TaskScheduler]
 *   的成员门禁）：不同成员的任务可以并行——泵每轮为一个空闲成员
 *   启动一个任务，执行是异步的（`scope.launch`），泵不等它跑完，
 *   下一轮接着为别的空闲成员取活；同一成员手里已有在途任务时跳过
 *   他的排队任务，不抢、不重复认领。
 * - 只碰 queued、且 assignedAgentId 指向远程 Agent、且没有
 *   sessionId（轮次任务由轮次执行器自己认领，不由泵碰）的任务；
 * - 远程目标解析不到：任务不执行、留在 queued、needs_attention=true，
 *   并写一条可见错误（只写一次），不静默跳过、不伪造失败；
 * - 泵在后台循环跑；stop() 停止取新任务，不中断已在跑的一次执行
 *   （在途任务由 ControlPlane 的停止指令处理；执行任务是 scope
 *   的子任务，循环停了它们继续跑）。
 *
 * [isRemoteMember] 判定某成员 id 是否属于远程设备：默认只排除
 * 本地执行器（"local" 与 TurnTaskRecorder.LOCAL_AGENT_ID），
 * 其余已指派的成员一律视为远程；生产调用方应传 AgentStore 口径
 * （deviceId 非空才是远程），避免把本机 CLI 成员的任务误判。
 */
class RemoteTaskPump(
    private val taskStore: TaskStore,
    private val scheduler: TaskScheduler,
    /** 由 [RemoteTaskDispatcher] 持有并使用；泵只为签名兼容而接收。 */
    @Suppress("UNUSED_PARAMETER") stepStore: TaskStepStore,
    private val dispatcher: RemoteTaskDispatcher,
    /** 由成员 agentId 找到它的远端目标；找不到返回 null（任务留待处理）。
     *
     * V5 跨设备开发（2026-11-12，A3）：解析器按 (任务, 成员) 取目标——
     * 任务的 projectId 决定派活文件夹（项目在该设备上的检出文件夹，
     * 无检出时回落 Agent 文件夹）；只按成员解析拿不到任务的项目，
     * 旧 `(agentId) -> RemoteTarget?` 签名已不够用。生产由
     * MainActivity 传入按任务解析的 lambda。
     */
    private val targetResolver: (task: TaskRecord, agentId: String) -> RemoteTarget?,
    private val isRemoteMember: (agentId: String) -> Boolean =
        { it != "local" && it != TurnTaskRecorder.LOCAL_AGENT_ID },
    private val pollIntervalMs: Long = 500,
    /**
     * P1 eval 日志（2026-11-11）：远端任务落终态时记一条。
     * 默认 null（老调用方与测试不动）；生产由 MainActivity 注入
     * EvalLogRecorder。记录失败绝不影响任务终态（内部 try/catch）。
     */
    private val evalRecorder: EvalLogRecorder? = null,
    /**
     * P2 流水线认领门（2026-11-11）：默认 null（老调用方与测试不动）；
     * 生产由 MainActivity 注入 `{ id -> orchestrator.canStartCoding(id) }`。
     * plan 未批准的流水线任务不认领编码。
     */
    private val pipelineGate: ((String) -> Boolean)? = null,
    /**
     * V5 跨设备开发（2026-11-12，A5）：回落事件写项目名时用的项目
     * 库；默认 null（老调用方与测试不动，无名时 reason 写“未绑定
     * 项目”或任务的 projectId）。生产由 MainActivity 注入。
     */
    private val projectStore: ProjectStore? = null,
    /**
     * V5 跨设备开发（2026-11-12，D7 新口径）：不可派判定——返回
     * 原因时任务不认领、不派活，留 queued + needs_attention + 原因
     * （[markNotDispatchable]）；返回 null 时正常走目标解析。默认
     * null（老调用方与测试不动，即不拦）。生产由 MainActivity 注入
     * 按任务查 [wherevercode.agent.core.project.dispatchBlockedReason]。
     */
    private val notDispatchableReason: ((task: TaskRecord, agentId: String) -> String?)? = null,
    /**
     * Agent 开关门禁（maintainer 2026-11-12：每个 Agent 都要有开/关，支持热插拔）。
     * 关掉的 Agent 不接派活：任务不认领、不派活，留 queued +
     * needs_attention + 可见原因（[markNotDispatchable]，与 D7 同款）。
     * 默认 `{ true }`（老调用方与测试不动，即不拦）。生产由 MainActivity
     * 注入现读 [wherevercode.agent.core.agent.AgentStore] 的 lambda——
     * 泵每轮都现查，开关翻完下一轮（500ms 内）即生效，不用重启 App；
     * 记录不存在的成员按启用处理（目标解析不到时自有 markUnroutable 兜底）。
     */
    private val isAgentEnabled: (agentId: String) -> Boolean = { true },
    /**
     * 2026-11-12 回复归属修复：Agent 存在判定——指派了不存在的
     * Agent 的排队任务，泵标 needs_attention + 原因（见
     * [markUnknownAgentTasks]），不再静默等。默认 null（老调用方
     * 与测试不动，即不判孤儿）。生产由 MainActivity 注入
     * `{ agentStore.getAgent(it) != null }`。
     */
    private val agentExists: ((String) -> Boolean)? = null,
) {
    private var running = false
    private var pumpJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * P1 eval 日志：远端执行落终态后记一条（RemoteTaskOutcome →
     * EvalOutcome 映射；失败自己吞，不影响泵循环）。
     * assignedViaFallback="manual"：路由日志里有该任务的决策且首选
     * 就是本 Agent 时，记录器会改记 "suggested"。
     */
    private fun recordEval(
        task: TaskRecord,
        outcome: RemoteTaskOutcome,
        startedAtMs: Long,
        deviceId: String,
    ) {
        val recorder = evalRecorder ?: return
        try {
            val fresh = taskStore.getTask(task.taskId) ?: task
            val evalOutcome = when (outcome) {
                is RemoteTaskOutcome.Completed -> EvalOutcome.COMPLETED
                is RemoteTaskOutcome.Failed -> EvalOutcome.FAILED
                is RemoteTaskOutcome.Cancelled -> EvalOutcome.CANCELLED
            }
            recorder.recordTerminal(
                fresh,
                evalOutcome,
                mode = "single",
                startedAtMs = startedAtMs,
                deviceId = deviceId,
                assignedViaFallback = "manual",
            )
        } catch (_: Exception) {
            // eval 记录失败不影响泵循环。
        }
    }

    fun start() {
        if (running) return
        running = true
        pumpJob = scope.launch {
            while (running) {
                try {
                    pumpOnce()
                } catch (_: Exception) {
                    // 单轮失败不杀泵；下一轮继续。
                }
                delay(pollIntervalMs)
            }
        }
    }

    fun stop() {
        running = false
        pumpJob?.cancel()
    }

    /**
     * 单轮取活：为一个空闲成员启动一个远程任务（认领后异步执行，
     * 不等它跑完），返回 true；本轮无活（无远程排队任务、或排队
     * 任务的成员全在忙）返回 false。
     * 测试入口；生产由 [start] 的循环调用。
     */
    suspend fun pumpOnce(): Boolean {
        for (next in findRemoteQueuedCandidates()) {
            val agentId = next.assignedAgentId ?: continue
            // 一成员一次一任务：该成员手里已有在途任务就跳过他，
            // 接着看下一个成员的排队任务，不挡别的成员。
            if (isAgentBusy(agentId)) continue
            // V5 跨设备开发（2026-11-12，D7 新口径）：git 家且目标设备
            // 上无 DEVICE 检出时不认领、不派活——代码不在设备上，派到
            // 空目录是假执行。任务留 queued + needs_attention + 原因，
            // 详情里看得到为什么没派；下一轮不再重复写。
            val blockedReason = try {
                notDispatchableReason?.invoke(next, agentId)
            } catch (_: Exception) {
                null
            }
            if (blockedReason != null) {
                markNotDispatchable(next, blockedReason)
                return true
            }
            // Agent 开关（2026-11-12）：关掉的 Agent 不接派活——留
            // queued + needs_attention + 可见原因，不认领、不派活。
            // 重新打开后下一轮泵即认领（热插拔，isAgentEnabled 每轮现查）。
            val agentEnabled = try {
                isAgentEnabled(agentId)
            } catch (_: Exception) {
                true
            }
            if (!agentEnabled) {
                markNotDispatchable(next, "该 Agent 已停用：$agentId，在 Agent 页重新启用后可派活。")
                return true
            }
            val target = targetResolver(next, agentId)
            if (target == null) {
                markUnroutable(next)
                return true
            }
            val attemptId = UUID.randomUUID().toString().replace("-", "")
            val claimed = try {
                scheduler.claimTaskForMember(next.taskId, agentId, attemptId)
            } catch (_: Exception) {
                null
            } ?: continue
            // V5 跨设备开发（2026-11-12，A5/D4）：派活文件夹如实记事件，
            // 任务详情“工作目录”行从事件反查。项目文件夹命中记
            // folder_resolved，回落记 folder_fallback；记录失败不影响
            // 认领与执行（事件丢了也不把已认领的任务打回去）。
            if (target.folderSource == DispatchFolderSource.AGENT_FALLBACK) {
                recordFolderFallback(claimed, target)
            } else {
                recordFolderResolved(claimed, target)
            }
            // 异步执行：泵不等这一次跑完，下一轮接着为别的空闲成员取活。
            // P1 eval：执行耗时从发往远端起算（不含排队等待），落终态时记一条。
            // 异步执行里记：泵不等跑完，eval 在这次执行落终态后才记。
            val startedAtMs = System.currentTimeMillis()
            scope.launch {
                try {
                    val outcome = dispatcher.execute(claimed, target)
                    recordEval(claimed, outcome, startedAtMs, target.peer.deviceId)
                } catch (_: Exception) {
                    // 执行抛错不杀泵；下一轮继续。
                }
            }
            return true
        }
        // 2026-11-12 回复归属修复：本轮没启动任务时，顺带标出指派
        // 了不存在 Agent 的排队任务（不再静默等，见下）；标了一个
        // 返回 true（本轮做了可见的事），没标的返回 false。
        if (markUnknownAgentTasks()) return true
        return false
    }

    /** 某成员手里是否有在途任务（assigned/working；review 不算）。
     * 2026-11-12 回复归属修复：成员比较大小写不敏感（与
     * [TaskScheduler.activeWorkCountForMember] 同口径），同一 Agent
     * 的 "Codex"/"codex" 两行不被当成两个成员。 */
    private fun isAgentBusy(agentId: String): Boolean {
        val tasks = try {
            taskStore.listTasks()
        } catch (_: Exception) {
            return true
        }
        return tasks.any {
            (it.memberId?.equals(agentId, ignoreCase = true) == true ||
                it.assignedAgentId?.equals(agentId, ignoreCase = true) == true) &&
                (it.state == "assigned" || it.state == "working")
        }
    }

    private fun findRemoteQueuedCandidates(): List<TaskRecord> {
        val queued = try {
            taskStore.listTasks().filter { TaskStates.isClaimable(it.state) }
        } catch (_: Exception) {
            return emptyList()
        }
        return queued.filter { task ->
            task.sessionId == null &&
                !TaskOrigins.isMcpDispatchTask(task.taskId) &&
                task.assignedAgentId != null &&
                isRemoteMember(task.assignedAgentId!!) &&
                // P2：plan 未批准的流水线任务不认领编码。
                (pipelineGate?.invoke(task.taskId) ?: true)
        }.sortedBy { it.createdAt }
    }

    /**
     * V5 跨设备开发（2026-11-12，A5/D7）：无检出链接回落 Agent 文件夹
     * 时记一条 folder_fallback 事件。项目名取项目库里的显示名，取不
     * 到时用任务的 projectId，任务未绑定时 reason 如实写“未绑定项目”。
     * git 家任务无设备文件夹时同样走回落、不拦下不派（D7），事件同款。
     */
    private fun recordFolderFallback(task: TaskRecord, target: RemoteTarget) {
        val projectName = try {
            task.projectId?.let { projectStore?.getProject(it)?.name } ?: task.projectId
        } catch (_: Exception) {
            task.projectId
        }
        val reason = folderFallbackReason(projectName, target.peer.deviceId, target.folder)
        try {
            taskStore.recordTaskEvent(task.taskId, TaskEventKind.FOLDER_FALLBACK, reason)
        } catch (_: Exception) {
            // 事件写失败不影响认领与执行。
        }
    }

    /**
     * V5 跨设备开发（2026-11-12，D4）：项目文件夹命中时记一条
     * folder_resolved 事件（详情“工作目录”行从事件反查的另一半）。
     */
    private fun recordFolderResolved(task: TaskRecord, target: RemoteTarget) {
        val projectName = try {
            task.projectId?.let { projectStore?.getProject(it)?.name } ?: task.projectId
        } catch (_: Exception) {
            task.projectId
        }
        val reason = folderResolvedReason(projectName, target.peer.deviceId, target.folder)
        try {
            taskStore.recordTaskEvent(task.taskId, TaskEventKind.FOLDER_RESOLVED, reason)
        } catch (_: Exception) {
            // 事件写失败不影响认领与执行。
        }
    }

    /**
     * V5 跨设备开发（2026-11-12，D7 新口径）：任务不可派——留 queued +
     * needs_attention + 原因（详情错误区与状态轨迹都看得到为什么没派），
     * 不认领、不派活、不伪造失败。原因已写过的不再重复写（避免泵每轮
     * 刷事件）；写失败不杀泵，下一轮再试。
     */
    private fun markNotDispatchable(task: TaskRecord, reason: String) {
        if (task.error?.contains(reason) == true) return
        val current = try {
            taskStore.getTask(task.taskId)
        } catch (_: Exception) {
            null
        } ?: return
        if (current.error?.contains(reason) == true) return
        try {
            taskStore.updateTask(
                task.taskId,
                mapOf(
                    "needs_attention" to true,
                    "error" to reason,
                ),
                current.version,
                current.goalVersion,
                current.authEpoch,
            )
        } catch (_: Exception) {
            return
        }
        try {
            taskStore.recordTaskEvent(task.taskId, TaskEventKind.NOT_DISPATCHABLE, reason)
        } catch (_: Exception) {
            // 事件写失败不影响留队标记本身。
        }
    }

    /**
     * 远程目标解析不到：留队 + needs_attention + 可见错误，只写一次。
     * 已带过该错误的不再重复写（避免泵每轮刷屏）。
     */
    private fun markUnroutable(task: TaskRecord) {
        if (task.error?.contains("远程目标不可达") == true) return
        val current = taskStore.getTask(task.taskId) ?: return
        try {
            taskStore.updateTask(
                task.taskId,
                mapOf(
                    "needs_attention" to true,
                    "error" to "远程目标不可达：${task.assignedAgentId} 未配置或未配对，请在设备管理里检查远端设备",
                ),
                current.version,
                current.goalVersion,
                current.authEpoch,
            )
        } catch (_: Exception) {
            // 标记失败不杀泵；下一轮再试。
        }
    }

    /**
     * 2026-11-12 回复归属修复：指派了不存在的 Agent 的排队任务。
     *
     * 根因：泵的候选过滤用 isRemoteMember（生产按 agentStore 查
     * 注册表），指派了一个没注册的 Agent 时任务连候选都进不了，
     * 静默排队、永不被认领，且无事件、无 error、无 needs_attention
     * （2026-11-12 真机：20106890 指派 "Codex"、注册是 "codex"，
     * 大小写不匹配时就是这副样子；getAgent 回落修好大小写后它不
     * 再是孤儿，但真不存在的 Agent 仍需被标出来）。
     * 每轮只标最老的一个（与 markUnroutable 同口径：已带过该错误
     * 的不再重复写）；本地执行器 id（"local"/"local-brain"）不是
     * 孤儿，归 LocalTaskRunner，不管。agentExists 未注入时不判
     * （老调用方与测试行为不变）；判定抛错时按“存在”处理，不误标。
     * 返回 true = 本轮标了一个，false = 无孤儿可标。
     */
    private fun markUnknownAgentTasks(): Boolean {
        val exists = agentExists ?: return false
        val orphans = try {
            taskStore.listTasks().filter { task ->
                TaskStates.isClaimable(task.state) &&
                    task.sessionId == null &&
                    task.assignedAgentId != null &&
                    task.assignedAgentId != "local" &&
                    task.assignedAgentId != TurnTaskRecorder.LOCAL_AGENT_ID
            }.sortedBy { it.createdAt }
        } catch (_: Exception) {
            return false
        }
        for (task in orphans) {
            val agentId = task.assignedAgentId ?: continue
            val known = try {
                exists(agentId)
            } catch (_: Exception) {
                true
            }
            if (known) continue
            val reason = "指派的 Agent 不存在：$agentId 未注册，请检查指派对象"
            if (task.error?.contains(reason) == true) continue
            val current = try {
                taskStore.getTask(task.taskId)
            } catch (_: Exception) {
                null
            } ?: continue
            if (current.error?.contains(reason) == true) continue
            try {
                taskStore.updateTask(
                    task.taskId,
                    mapOf(
                        "needs_attention" to true,
                        "error" to reason,
                    ),
                    current.version,
                    current.goalVersion,
                    current.authEpoch,
                )
            } catch (_: Exception) {
                return false
            }
            try {
                taskStore.recordTaskEvent(task.taskId, TaskEventKind.NOT_DISPATCHABLE, reason)
            } catch (_: Exception) {
                // 事件写失败不影响留队标记本身。
            }
            return true
        }
        return false
    }
}
