package wherevercode.agent.core.team

import java.util.UUID
import wherevercode.agent.core.autonomy.AutonomyDecision
import wherevercode.agent.core.autonomy.AutonomyGate
import wherevercode.agent.core.mcp.RemoteTarget
import wherevercode.agent.core.task.RoomTaskStore
import wherevercode.agent.core.task.TaskScheduler
import wherevercode.agent.core.task.TaskStates
import wherevercode.agent.core.task.TaskStore

/**
 * 成员分派（V5 Phase B C3 第二阶段，成员框架）。
 *
 * 只做框架：分派的每一步都是诚实拒绝或本地状态写，不 spawn 真实
 * 进程、不调 Hand、不调 CLI。真实派发（Hand 把任务送到设备上的
 * Codex/CodeBuddy CLI）是后续批次的活，等 maintainer 安排。
 *
 * 分派门禁（按顺序，全过才认领）：
 * 1. 成员存在；
 * 2. 成员已连接（[TeamMember.canDispatch]；未连接一律拒绝，不伪装
 *    成功——文案就是“未连接（等待安排）”）；
 * 3. 任务存在且有所属项目，成员已获该项目授权（读绑定表）；
 * 4. 任务未被暂停（paused 是独立布尔字段，见 MemberTaskControl；
 *    已暂停的排队任务有可见的拒绝原因，不是静默跳过——老项目
 *    claim_next 只跳过、无可见状态的 bug 在此不带过去）；
 * 5. 成员手里没有在途任务（一成员一次一任务）；
 * 6. 任务是 queued（其余状态一律拒绝）。
 * 7. 自主度门（2026-11-11，maintainer 22:43 两种模式）：监督模式下派发
 *    需用户确认（拒绝原因 NEEDS_USER_CONFIRMATION，任务留队 queued，
 *    可见原因，不是静默跳过）；全权模式自动放行并记审计。
 * 通过后经 [TaskScheduler.claimTaskForMember] 一次认领：attempt+1、
 * 新 attemptId（uuid hex）、600 秒租约（lease_owner = 成员 id）。
 * 可认领态是 queued / requeued / rework（见 TaskStates.isClaimable）。
 */
enum class MemberDispatchRefusal {
    MEMBER_NOT_FOUND,
    MEMBER_NOT_CONNECTED,
    PROJECT_NOT_AUTHORIZED,
    TASK_NOT_FOUND,
    TASK_NOT_QUEUED,
    MEMBER_BUSY,
    TASK_PAUSED,
    /** 监督模式：派发需用户确认（自主度门；任务留队 queued，不是静默跳过）。 */
    NEEDS_USER_CONFIRMATION,
    /** 免费额度已耗尽：明确报错，不静默切付费（maintainer 07:20 规则）。 */
    QUOTA_EXHAUSTED,
}

data class MemberAssignment(
    val taskId: String,
    val memberId: String,
    val projectId: String,
    val attempt: Int,
    val attemptId: String,
    val leaseExpiresAt: Long,
    /** 阶段 B：远程成员的远端目标（设备 + 该设备上的 agent + 文件夹），本地成员为 null。 */
    val remoteTarget: RemoteTarget? = null,
)

sealed class MemberDispatchResult {
    data class Assigned(val assignment: MemberAssignment) : MemberDispatchResult()
    data class Refused(val reason: MemberDispatchRefusal, val detail: String) : MemberDispatchResult()
}

class MemberDispatcher(
    private val taskStore: TaskStore,
    private val memberStore: TeamMemberStore,
    private val scheduler: TaskScheduler,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    /**
     * 阶段 B（V5 紧急开发）：由成员找到它的远端目标；不传时远程成员
     * 仍可认领，但 assignment.remoteTarget 为 null，执行体（泵）
     * 收到后按“目标不可达”留队并标 needs_attention，不静默跳过。
     */
    private val remoteTargetFor: ((TeamMember) -> RemoteTarget?)? = null,
    /**
     * 自主度门（2026-11-11）：null = 未接线，分派行为与原来完全一致；
     * 接上后监督模式的派发先等用户确认，全权模式自动放行并记审计。
     */
    private val autonomyGate: AutonomyGate? = null,
) {
    fun dispatch(taskId: String, memberId: String): MemberDispatchResult {
        val member = memberStore.getMember(memberId)
            ?: return MemberDispatchResult.Refused(
                MemberDispatchRefusal.MEMBER_NOT_FOUND,
                "成员不存在：$memberId",
            )
        if (!member.canDispatch()) {
            return MemberDispatchResult.Refused(
                MemberDispatchRefusal.MEMBER_NOT_CONNECTED,
                "成员 ${member.displayName} 未连接（等待安排）：真实连接等 maintainer 安排，分派已拒绝，未伪装成功。",
            )
        }
        val task = taskStore.getTask(taskId)
            ?: return MemberDispatchResult.Refused(
                MemberDispatchRefusal.TASK_NOT_FOUND,
                "任务不存在：$taskId",
            )
        if (member.quotaState == MemberQuotaState.FREE_EXHAUSTED) {
            return MemberDispatchResult.Refused(
                MemberDispatchRefusal.QUOTA_EXHAUSTED,
                "成员 ${member.displayName} 的免费额度已耗尽：明确报错，不静默切付费；额度数额以 maintainer 账号 Limits 页为准。",
            )
        }
        val projectId = task.projectId
        if (projectId == null || !memberStore.isAuthorized(memberId, projectId)) {
            return MemberDispatchResult.Refused(
                MemberDispatchRefusal.PROJECT_NOT_AUTHORIZED,
                "成员 $memberId 未获项目 ${projectId ?: "（任务无项目）"} 的授权，分派已拒绝。",
            )
        }
        if (memberStore.getControl(taskId)?.paused == true) {
            return MemberDispatchResult.Refused(
                MemberDispatchRefusal.TASK_PAUSED,
                "任务 $taskId 已暂停，分派已拒绝（暂停是可见状态，不是静默跳过）。",
            )
        }
        // 2026-11-12 回复归属修复：成员比较大小写不敏感（与
        // TaskScheduler.activeWorkCountForMember 同口径），同一
        // Agent 的 "Codex"/"codex" 两行不被当成两个成员。
        if (taskStore.listTasks().any {
                (it.memberId?.equals(memberId, ignoreCase = true) == true) &&
                    (it.state == "assigned" || it.state == "working")
            }
        ) {
            return MemberDispatchResult.Refused(
                MemberDispatchRefusal.MEMBER_BUSY,
                "成员 $memberId 手里已有在途任务，一次只认领一个。",
            )
        }
        if (!TaskStates.isClaimable(task.state)) {
            return MemberDispatchResult.Refused(
                MemberDispatchRefusal.TASK_NOT_QUEUED,
                "任务 $taskId 不可认领（当前 ${task.state}），不能认领。",
            )
        }
        // 自主度门（2026-11-11）：监督模式下派发需用户确认——任务留队
        // queued，可见拒绝原因，不是静默跳过；全权模式自动放行（门内记审计）。
        val gateDecision = autonomyGate?.dispatchDecision(taskId, memberId)
        if (gateDecision is AutonomyDecision.NeedsUser) {
            return MemberDispatchResult.Refused(
                MemberDispatchRefusal.NEEDS_USER_CONFIRMATION,
                "任务 $taskId 派发给 $memberId 需用户确认：${gateDecision.reason}",
            )
        }
        val now = nowSeconds()
        val attemptId = UUID.randomUUID().toString().replace("-", "")
        val claimed = scheduler.claimTaskForMember(taskId, memberId, attemptId, now)
            ?: return MemberDispatchResult.Refused(
                MemberDispatchRefusal.TASK_NOT_QUEUED,
                "任务 $taskId 认领失败（可能已被他人认领或已冻结）。",
            )
        return MemberDispatchResult.Assigned(
            MemberAssignment(
                taskId = taskId,
                memberId = memberId,
                projectId = projectId,
                attempt = claimed.attempt,
                attemptId = claimed.attemptId ?: attemptId,
                leaseExpiresAt = claimed.leaseExpiresAt
                    ?: (now + RoomTaskStore.LEASE_SECONDS),
                remoteTarget = remoteTargetFor?.invoke(member),
            ),
        )
    }

    /** 回收过期租约（RoomTaskStore：回队 queued，清 memberId/attemptId，attempt 保留）。 */
    fun releaseExpiredLeases(nowEpochSeconds: Long = nowSeconds()): List<String> =
        scheduler.releaseExpiredLeases(nowEpochSeconds)
}
