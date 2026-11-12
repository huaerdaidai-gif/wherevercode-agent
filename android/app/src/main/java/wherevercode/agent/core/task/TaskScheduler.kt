package wherevercode.agent.core.task

import wherevercode.agent.AgentDiscovery

/**
 * TaskScheduler, stage-2 single-task version (audit section 4.5).
 *
 * Rules carried over from the old queue:
 * - The LOCAL path ([claimTask]) claims ONE coding task at a time,
 *   globally: the phone's own brain runs one turn at a time over the
 *   one process-wide TurnController, so two local turns must never
 *   run together.
 * - The MEMBER path ([claimTaskForMember]) claims one task at a time
 *   PER MEMBER: different members run on their own devices/CLIs and
 *   do not share the phone's TurnController, so member A working
 *   must not block member B's claim (task decomposition needs this:
 *   subtasks for different members run in parallel). The per-member
 *   busy check is the same predicate [MemberDispatcher] enforces
 *   (gate 5); the freeze scan stays global (any frozen task still
 *   blocks member claims, as before).
 * - A task in `review` does NOT count as work in hand.
 * - Match by capability / device / authorization (three dimensions);
 *   stage 2 matches on AgentDiscovery's installed agents + an explicit
 *   agent allow-list per task, and stage 6 extends the match.
 *
 * The scheduler references `AgentDiscovery` (package
 * `wherevercode.agent`) one-way only: the discovery class
 * never references the scheduler back.
 */
data class TaskRequirement(
    /** Agent ids the task may run on; empty = any installed agent. */
    val agentIds: List<String> = emptyList(),
    /** Human-readable capability labels the task needs (informational in stage 2). */
    val capabilities: List<String> = emptyList(),
    val deviceId: String? = null,
)

/**
 * Port the scheduler consults for control-plane freeze state (M2).
 * Lives in core/task so core/task never imports core/control (plan
 * §1.3: control -> task only); implemented by ControlStore.
 * `controlStatus` returns the ControlStatus name ("RUNNING" / "PAUSING"
 * / "PAUSED" / "STOPPING") or null when the task has no control row.
 */
interface DispatchFreezePort {
    fun isDispatchFrozen(taskId: String): Boolean
    fun controlStatus(taskId: String): String?
}

class TaskScheduler(
    private val taskStore: TaskStore,
    private val discovery: AgentDiscovery,
    /** M2: null = no control plane wired (pre-M2 behaviour). */
    private val freezePort: DispatchFreezePort? = null,
) {
    private val lock = Any()

    /** Work in hand: assigned or working. Review does NOT count. */
    fun activeWorkCount(): Int =
        taskStore.listTasks().count { it.state == "assigned" || it.state == "working" }

    /**
     * Work in hand for ONE member (memberId or assignedAgentId match):
     * assigned or working. Review does NOT count. This is the
     * per-member predicate [MemberDispatcher] enforces (gate 5);
     * [claimTaskForMember] uses it so different members can hold
     * tasks at the same time.
     *
     * 2026-11-12 回复归属修复：成员比较大小写不敏感——同一 Agent
     * 的任务行可能一处写 "Codex"（派活方传显示名）、一处写
     * "codex"（注册 id），精确比较会把它们当成两个成员、同时认领。
     */
    fun activeWorkCountForMember(memberId: String): Int =
        taskStore.listTasks().count {
            (it.memberId?.equals(memberId, ignoreCase = true) == true ||
                it.assignedAgentId?.equals(memberId, ignoreCase = true) == true) &&
                (it.state == "assigned" || it.state == "working")
        }

    /**
     * M2: frozen dispatch blocks new claims. The freeze check runs
     * inside the same [lock] as [claimTask] so a freeze cannot race a
     * claim that already passed the check (a claim that still slips
     * through is caught again by ExecutionFence, which refuses writes
     * for frozen tasks).
     */
    fun canClaim(): Boolean = synchronized(lock) {
        if (activeWorkCount() != 0) return false
        val port = freezePort ?: return true
        taskStore.listTasks().none { port.isDispatchFrozen(it.taskId) }
    }

    /**
     * Member-path claim gate: only THIS member must be idle (one task
     * at a time per member); other members' work does not block it.
     * The freeze scan stays global, exactly as in [canClaim]: any
     * frozen task still blocks member claims.
     */
    fun canClaimForMember(memberId: String): Boolean = synchronized(lock) {
        if (activeWorkCountForMember(memberId) != 0) return false
        val port = freezePort ?: return true
        taskStore.listTasks().none { port.isDispatchFrozen(it.taskId) }
    }

    /**
     * Claim a queued task for [agentId]. Returns null when another task
     * is already in hand (single-task rule), the task is not claimable
     * (queued / requeued / rework — see [TaskStates.isClaimable]), the
     * task is frozen, or its control status is not RUNNING (M2: only
     * RUNNING tasks are claimable). The claim takes a 600s lease (see
     * RoomTaskStore.LEASE_SECONDS) and snapshots the task's current
     * counters as the expected versions for later writes.
     *
     * P0: the claim bumps attempt+1 and mints a fresh attemptId (uuid
     * hex), exactly like [claimTaskForMember] always did — the plain
     * path used to skip this, so the local/remote runners had no
     * attempt fencing. The runners capture this attemptId and refuse
     * to write once the task has been re-queued and re-claimed under a
     * new attempt.
     */
    fun claimTask(taskId: String, agentId: String): TaskRecord? = synchronized(lock) {
        if (!canClaim()) return null
        val task = taskStore.getTask(taskId) ?: return null
        if (!TaskStates.isClaimable(task.state)) return null
        // M2: frozen tasks and non-RUNNING control states are not claimable.
        val port = freezePort
        if (port != null) {
            if (port.isDispatchFrozen(taskId)) return null
            val status = port.controlStatus(taskId)
            if (status != null && status != "RUNNING") return null
        }
        val now = System.currentTimeMillis() / 1000
        val attemptId = java.util.UUID.randomUUID().toString().replace("-", "")
        return try {
            taskStore.updateTask(
                taskId,
                mapOf(
                    "state" to "assigned",
                    "assigned_agent_id" to agentId,
                    "attempt" to (task.attempt + 1),
                    "attempt_id" to attemptId,
                    "lease_owner" to agentId,
                    "lease_expires_at" to (now + RoomTaskStore.LEASE_SECONDS),
                ),
                task.version,
                task.goalVersion,
                task.authEpoch,
            )
        } catch (_: Exception) {
            null
        }
    }

    fun startTask(taskId: String): TaskRecord? {
        val task = taskStore.getTask(taskId) ?: return null
        if (task.state != "assigned") return null
        return try {
            taskStore.updateTask(taskId, mapOf("state" to "working"), task.version, task.goalVersion, task.authEpoch)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * P0：审查打回返工。review → rework（reason 写入 error 栏，可见），
     * 返工任务回认领池（rework 可认领，见 [TaskStates.isClaimable]），
     * 下次认领走 assigned → working。P2 审查流水线的打回入口；本分支
     * 只给 API + 单测，不接 UI 按钮。非 review 态一律返回 null。
     */
    fun requestRework(taskId: String, reason: String? = null): TaskRecord? {
        val task = taskStore.getTask(taskId) ?: return null
        if (task.state != "review") return null
        val changes = mutableMapOf<String, Any?>("state" to "rework")
        reason?.takeIf { it.isNotBlank() }?.let { changes["error"] = it.take(4000) }
        return try {
            taskStore.updateTask(taskId, changes, task.version, task.goalVersion, task.authEpoch)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * C3 第二阶段（成员框架）：把一个 queued 任务认领给指定团队成员。
     * 门禁是按成员的（[canClaimForMember]）：该成员手里没有在途任务
     * 即可认领，其他成员的在途任务不挡它——不同成员可同时各持一个
     * 任务（任务拆分的子任务并行执行就靠这条；本地路径 [claimTask]
     * 仍是全局一次一任务，本机大脑只有一个 TurnController）。
     * 冻结检查与控制态（须为 RUNNING）与 [claimTask] 同款。
     * 一次 updateTask 原子写完：state assigned +
     * assigned_agent_id/member_id + attempt+1 + attempt_id（新 uuid）
     * + 600 秒租约（lease_owner = 成员 id）。attempt 绑定沿用老项目
     * ExecutionHostTaskStore 的认领语义：每次认领 attempt+1、新
     * attempt_id，旧 attempt 的回传/事件按 attemptId 拒绝。
     * 可认领态是 queued / requeued / rework（见 [TaskStates.isClaimable]）。
     */
    fun claimTaskForMember(
        taskId: String,
        memberId: String,
        attemptId: String,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
    ): TaskRecord? = synchronized(lock) {
        if (!canClaimForMember(memberId)) return null
        val task = taskStore.getTask(taskId) ?: return null
        if (!TaskStates.isClaimable(task.state)) return null
        val port = freezePort
        if (port != null) {
            if (port.isDispatchFrozen(taskId)) return null
            val status = port.controlStatus(taskId)
            if (status != null && status != "RUNNING") return null
        }
        return try {
            taskStore.updateTask(
                taskId,
                mapOf(
                    "state" to "assigned",
                    "assigned_agent_id" to memberId,
                    "member_id" to memberId,
                    "attempt" to (task.attempt + 1),
                    "attempt_id" to attemptId,
                    "lease_owner" to memberId,
                    "lease_expires_at" to (nowEpochSeconds + RoomTaskStore.LEASE_SECONDS),
                ),
                task.version,
                task.goalVersion,
                task.authEpoch,
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Pick an available agent for [requirement]: installed, `--version`
     * runnable, and (when the task names agents) on the allow-list.
     * Login state stays UNKNOWN until a real run proves it (the discovery
     * honesty rule); the scheduler never claims a login it has not seen.
     */
    fun matchAgent(requirement: TaskRequirement): AgentDiscovery.AgentReport? {
        val available = discovery.availableAgents().filter { it.installed }
        val filtered = if (requirement.agentIds.isEmpty()) {
            available
        } else {
            available.filter { it.agentId in requirement.agentIds }
        }
        // Prefer an agent whose --version actually ran.
        return filtered.firstOrNull { it.version != null } ?: filtered.firstOrNull()
    }

    /** Reclaim expired leases; returns the task ids returned to requeued (or failed, dead letter). */
    fun releaseExpiredLeases(nowEpochSeconds: Long = System.currentTimeMillis() / 1000): List<String> =
        taskStore.expireLeases(nowEpochSeconds)
}
